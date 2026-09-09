"""Atomic source projections and chronological, range-bound replacement review."""
from copy import deepcopy
from .contracts import Domain, CompletionTechnicalError, ReviewRequired, ANALYSIS_CONTRACT_REVISION
from .diagnostics import canonical, sha
from .llm import validate_schema
from .provider_schemas import review_schema
from .state import EvidenceAtom, locate, resolve_pointer, refresh
from . import validity, requests, gaps

def atom(state,source,item,excluded=(),event=None,*,analysis_id=None,gap_binding=None):
    a,n=locate(source.text,item['span'])
    if validity.overlaps_range((a,a+n),excluded):
        raise ValueError('pure_safety_or_control_span')
    goal_id=item.get('goalId')
    is_gap=item['kind'] in ('GAP_CONTENT','GAP_NONE')
    if is_gap:
        if gap_binding is None and event:
            candidates=[value for value in validity.replacement_gap_bindings(state,event) if value.get('goalId')==goal_id]
            bindings={canonical(b):b for b in candidates if validity.contained((a,a+n),[validity.interval(b)])}
            if len(bindings)!=1: raise ValueError('gap_replacement_binding_unrecoverable')
            gap_binding=next(iter(bindings.values()))
        gap_binding=gaps.validate_contribution(state,source,item,a,n,gap_binding,event=event)
    elif goal_id:
        goal=state.goals.get(goal_id)
        if not goal or (goal.get('targetDomain') and goal['targetDomain']!=item['domain']):
            raise ValueError('unknown_or_mismatched_none_goal')
    origin=event['id'] if event else None
    identity=[source.key,a,n,item.get('domain'),item['kind'],item.get('role'),goal_id,ANALYSIS_CONTRACT_REVISION,origin,analysis_id]
    return EvidenceAtom('A_'+sha(canonical(identity))[:24].upper(),source.key,a,n,
        None if is_gap else Domain(item['domain']),item['kind'],item.get('role'),goal_id,
        ANALYSIS_CONTRACT_REVISION,origin,'GAP' if is_gap else 'CBT',deepcopy(gap_binding),analysis_id)

def accept(state,raw,diagnostics,*,slots,table):
    # Safety context is never an exclusion. Only the normal review's explicit pure
    # spans and control spans participate; failed batches cannot leak any state.
    draft=deepcopy(state); targets=requests.question_targets(draft)
    validity.migrate_analyses(draft)
    frontiers={draft.current_sources[code]:validity.semantic_frontier(draft,draft.current_sources[code]) for code in slots.values()}
    schema=review_schema(slots,[s['sourceId'] for s in table],list(draft.goals),
        list(targets),list(draft.request_registry),[e['id'] for e in validity.source_current_events(draft)])
    try:
        validate_schema(raw,schema)
    except ValueError as exc:
        raise CompletionTechnicalError('review_schema_'+str(exc)) from None
    if set(raw)!=set(slots):
        raise CompletionTechnicalError('review_fixed_slots')
    order={q.question_code:i for i,q in enumerate(draft.history)}
    events={e['id']:deepcopy(e) for e in draft.retraction_history}
    for slot,code in slots.items():
        key=draft.current_sources[code]; source=draft.sources[key]; entry=raw[slot]
        try:
            if 'limit' in entry: raise ValueError('OUTPUT_CAPACITY')
            controls=[requests.signal_control(draft,source,item,i,targets) for i,item in enumerate(entry['signals'])]
            if len({(c['id'],c['type']) for c in controls})!=len(controls):
                raise ValueError('duplicate_signal')
            pure=[{'start':a,'length':n} for a,n in (locate(source.text,p) for p in entry['safetyOnlySpans'])]
            control_ranges=[{'start':c['start'],'length':c['length']} for c in controls]
            control_ranges.extend({'start':r['start'],'length':r['length']} for r in draft.resume_receipts if r.get('sourceKey')==key)
            # Committed target-resolution clauses stay control on later reanalysis.
            control_ranges.extend({'start':r['targetResolution']['source']['start'],
                'length':r['targetResolution']['source']['length']} for r in draft.request_registry.values()
                if r.get('targetResolution') and r['targetResolution']['source'].get('sourceKey')==key)
            control_ranges.extend({'start':u['source']['start'],'length':u['source']['length']}
                for u in draft.request_updates if u.get('source',{}).get('sourceKey')==key)
            for confirmation in draft.assessment_target_confirmations.values():
                proofs=list(confirmation.get('recordChangeProofs',[]))
                if confirmation.get('recordChangeProof'):
                    proofs.append(confirmation['recordChangeProof'])
                for proof in proofs:
                    if proof.get('sourceKey')==key:
                        # The committed act remains a control clause on this
                        # exact USER revision, even when a later correction
                        # withdraws/reaffirms it. Current intent is projected
                        # separately by assessment_target, never as CBT facts.
                        value={'start':proof['start'],'length':proof['length']}
                        if value not in control_ranges: control_ranges.append(value)
            reviewed_events=set()
            gap_binding=gaps.compile_answer(draft,source,entry['gapAnswer'],targets)
            analysis_id=validity.new_analysis_id()
            for correction in entry['corrections']:
                a,n=locate(source.text,correction['correctionSpan'])
                from .signals import eligible_control
                if not eligible_control(source.text,source.text[a:a+n],a):
                    raise ValueError('correction_quoted_or_negated')
                target=resolve_pointer(correction['target'],table); target_key=target.get('sourceKey')
                if target_key and order[draft.sources[target_key].item.question_code]>=order[code]:
                    raise ValueError('correction_requires_earlier_source')
                if target_key is None and validity.address_key(target) not in draft.record_sources:
                    raise ValueError('correction_target_not_clinical_user_source')
                event=validity.canonical_event(draft,source,a,n,correction['action'],target,
                    correction['priorCorrectionId'])
                event.update(replacementContributions=deepcopy(correction['replacementContributions']),
                    replacementAnalysisRevision=ANALYSIS_CONTRACT_REVISION,
                    currentCorrectionSpan={'start':a,'length':n},
                    currentInterpretationRevision=ANALYSIS_CONTRACT_REVISION,
                    pendingReplacementAnalysisId=validity.new_analysis_id())
                events[event['id']]=event; reviewed_events.add(event['id'])
                control_ranges.extend([{'start':a,'length':n},{'start':event['start'],'length':event['length']}])
            for event in events.values():
                if event['correctionSourceKey']==key and event['id'] not in reviewed_events:
                    event['replacementContributions']=None
                    event['replacementAnalysisRevision']=None
                    # Historical nodes remain, but an omitted current
                    # interpretation cannot be mistaken for the old frontier.
                    event['currentReplacementAnalysisId']=None
            excluded=[validity.interval(p) for p in pure+control_ranges]
            atoms=[]
            for item in entry['contributions']:
                value=atom(draft,source,item,excluded,analysis_id=analysis_id,gap_binding=gap_binding)
                if any((x.start,x.length,x.domain)==(value.start,value.length,value.domain) and
                        (x.kind,x.role)!=(value.kind,value.role) for x in atoms):
                    raise ValueError('same_span_domain_contradiction')
                if value not in atoms: atoms.append(value)
            draft.controls=[c for c in draft.controls if c['sourceKey']!=key]+controls
            validity.register_analysis(draft,analysis_id,key,'BASE',atoms,
                predecessor=draft.current_base_analyses.get(key),frontier=frontiers[key],gap_binding=gap_binding)
            draft.current_base_analyses[key]=analysis_id
            draft.reviews[code]=deepcopy(entry); draft.review_revisions[code]=key
            draft.review_projections[code]={'sourceKey':key,'analysisContractRevision':ANALYSIS_CONTRACT_REVISION,
                'review':deepcopy(entry),'safetyOnlyRanges':pure,'controlRanges':control_ranges,
                'analysisId':analysis_id,'gapAnswerBinding':deepcopy(gap_binding)}
            draft.review_required_reasons.pop(key,None)
            draft.trusted_controls[code]={c['type'] for c in controls}
            diagnostics.emit('answer_validation',slot=slot,sourceQuestionCode=code,status='VALID',
                validSignals=controls,draftContributions=[a.ref_id for a in atoms])
        except (ValueError,KeyError,TypeError) as exc:
            diagnostics.emit('answer_validation',slot=slot,sourceQuestionCode=code,status='INVALID',reason=str(exc))
            raise CompletionTechnicalError('answer_review_batch_rejected:'+str(exc)) from None
    draft.retraction_history=list(events.values())
    prefix=[]
    for event in validity.source_current_events(draft):
        first_acceptance=False
        analysis_id=event.pop('pendingReplacementAnalysisId',None)
        if analysis_id:
            first_acceptance=(event.get('acceptanceStatus')=='NEW_PENDING_ACCEPTANCE' and event.get('acceptanceEvidence') is None)
            prior_atoms,masks,excluded,*_=validity.project_semantics(draft,prefix,chronological=first_acceptance)
            if first_acceptance:
                event['acceptanceEvidence']=validity.acceptance_evidence(draft,event,prior_atoms,
                    masks.get(validity.address_key(event['target']),[]),excluded)
                event['acceptanceStatus']='ACCEPTED'
            previous=event.get('currentReplacementAnalysisId')
            if event.get('replacementContributions') is None:
                event['currentReplacementAnalysisId']=None
            else:
                try:
                    outputs=validity._replacement_atoms(draft,event,
                        lambda source,item,origin:atom(draft,source,item,event=origin,analysis_id=analysis_id),excluded)
                except (ValueError,KeyError,TypeError) as exc:
                    raise ReviewRequired(str(exc),[k for k in (event['correctionSourceKey'],event['target'].get('sourceKey')) if k]) from None
                validity.register_analysis(draft,analysis_id,validity.address_key(event['target']),
                    'REPLACEMENT',outputs,predecessor=previous,
                    frontier=validity.semantic_frontier(draft,validity.address_key(event['target']),prefix,
                        chronological=first_acceptance),event=event)
                event['currentReplacementAnalysisId']=analysis_id
        prefix.append(event)
        validity.project_semantics(draft,prefix,initialize_edges=True,chronological=first_acceptance)
    _,_,_,_,_,missing=validity.project_semantics(draft,initialize_edges=True)
    if missing:
        raise ReviewRequired('semantic_analysis_dependency_missing',list(missing))
    refresh(draft)
    state.__dict__.update(deepcopy(draft.__dict__))
    diagnostics.emit('batch_validated',scope='DRAFT_ONLY',reviewedCount=len(slots),
        analysisContractRevision=ANALYSIS_CONTRACT_REVISION)
