"""Committed gap consumption, answer projection and assessment attempts are separate."""
from copy import deepcopy
from .contracts import ANALYSIS_CONTRACT_REVISION, CompletionTechnicalError, ReviewRequired, Move
from .diagnostics import canonical, sha

WAIT_MESSAGE='앞서 확인하던 내용에 대한 답변은 아직 없는 상태로 남겨둘게요. 답변 내용을 적거나, 이 질문은 건너뛰겠다고 알려주세요.'
GAP_KINDS={'GAP_CONTENT','GAP_NONE'}


def plans(state):
    values={q['questionCode']:q for q in state.prior_questions}
    if state.pending_question:
        values[state.pending_question['questionCode']]=state.pending_question
    return values


def root_for_question(state,code):
    """Only actual committed parent/root bindings confer gap answer lineage."""
    catalog=plans(state); seen=set()
    roots={g['rootQuestionCode'] for g in state.gap_registry.values()}
    while code and code not in seen:
        if code in roots:
            return code
        seen.add(code); question=catalog.get(code)
        if not question:
            return None
        root=question.get('rootGapQuestionCode')
        if root:
            return root if root in roots else None
        code=question.get('parentQuestionCode')
    return None


def answer_target(state,question_code,gap=None):
    """Readable lineage is not an answer purpose: CONTROL/SAFETY never qualify."""
    question=plans(state).get(question_code)
    if not question: return None
    root=root_for_question(state,question_code)
    matched=gap or next((g for g in state.gap_registry.values() if g['rootQuestionCode']==root),None)
    if not matched or matched['rootQuestionCode']!=root or question.get('goalId')!=matched['goalId']:
        return None
    if question_code==root and question.get('kind')=='B': return matched
    if (question.get('kind') in ('E','X') and question.get('scope')=='CBT' and
            question.get('move')==Move.FACT_CERTAINTY_CHECK.value and
            question.get('thoughtRevision',matched['thoughtRevision'])==matched['thoughtRevision']):
        return matched
    return None


def compile_answer(state,source,gap_answer,targets):
    from .state import locate
    if gap_answer is None: return None
    target=targets.get(gap_answer['targetPendingId'])
    gap=answer_target(state,target['questionCode']) if target else None
    if not gap:
        raise ValueError('gap_answer_requires_committed_answer_target')
    if state.current_sources.get(source.item.question_code)!=source.key:
        raise ValueError('gap_answer_requires_current_user_revision')
    question=plans(state).get(source.item.question_code)
    if not question or root_for_question(state,source.item.question_code)!=gap['rootQuestionCode']:
        raise ValueError('gap_answer_actual_question_lineage')
    if question.get('thoughtRevision',gap['thoughtRevision'])!=gap['thoughtRevision']:
        raise ValueError('gap_answer_thought_revision_mismatch')
    start,length=locate(source.text,gap_answer['span'])
    return {'gapId':gap['gapId'],'rootQuestionCode':gap['rootQuestionCode'],'goalId':gap['goalId'],
        'thoughtRevision':gap['thoughtRevision'],'targetQuestionCode':target['questionCode'],
        'questionCode':source.item.question_code,'sourceKey':source.key,
        'address':source.source_id,'revision':source.revision,'start':start,'length':length}


def validate_contribution(state,source,item,start,length,binding,event=None):
    from .validity import contained
    if item['kind'] not in GAP_KINDS or binding is None:
        raise ValueError('gap_contribution_requires_explicit_answer_binding')
    gap=state.gap_registry.get(binding.get('gapId'))
    if not gap or any(binding.get(k)!=gap[k] for k in ('rootQuestionCode','goalId','thoughtRevision')):
        raise ValueError('gap_contribution_identity_mismatch')
    if item['goalId']!=gap['goalId'] or binding['sourceKey']!=source.key or (
            binding['address'],binding['revision'])!=(source.source_id,source.revision):
        raise ValueError('gap_contribution_source_or_goal_mismatch')
    if not answer_target(state,binding['targetQuestionCode'],gap):
        raise ValueError('gap_contribution_target_not_answer_purpose')
    if not contained((start,start+length),[(binding['start'],binding['start']+binding['length'])]):
        raise ValueError('gap_contribution_outside_answer_span')
    # Replacement callers inherit this exact target binding from accepted semantic
    # output/first proof. They must not borrow the correction source's gapAnswer.
    return deepcopy(binding)


def gap_answer_bindings(state):
    rows=[]
    for code,key in state.current_sources.items():
        projection=state.review_projections.get(code,{})
        if projection.get('sourceKey')!=key: continue
        raw=projection.get('review',state.reviews.get(code,{}))
        binding=projection.get('gapAnswerBinding')
        if binding is not None or 'gapAnswer' in raw or any(r.startswith('GAP_') for r in state.review_required_reasons.get(key,[])):
            rows.append({'sourceKey':key,'questionCode':code,'binding':deepcopy(binding),
                'fieldState':projection.get('gapAnswerFieldState') or ('EXPLICIT_NULL' if 'gapAnswer' in raw and raw['gapAnswer'] is None else
                    'BOUND' if binding else 'UNKNOWN_LEGACY_BINDING')})
    return rows


def migrate(state):
    # Usage is historical acceptance, not a review-derived or candidate flag.
    for revision in state.gap_revisions:
        state.gap_usage.setdefault(revision,{'thoughtRevision':revision,'used':True,
            'bindingStatus':'UNKNOWN_LEGACY_BINDING'})
    pending=state.pending_fact_boundary
    if not pending:
        return
    root=pending.get('rootQuestionCode') or pending.get('questionCode')
    existing=next((g for g in state.gap_registry.values() if g['rootQuestionCode']==root),None)
    if existing:
        return
    question=plans(state).get(root)
    if not question or question.get('kind')!='B':
        issue={'kind':'GAP_ROOT','questionCode':root,'status':'UNKNOWN_LEGACY_BINDING'}
        if issue not in state.migration_issues:
            state.migration_issues.append(issue)
        pending['resolution']='NEEDS_RECOVERY'
        return
    revision=pending.get('thoughtRevision')
    if not revision:
        pending['resolution']='NEEDS_RECOVERY'
        return
    identity=pending.get('gapId') or 'GAP_'+sha(canonical([root,question.get('goalId'),revision]))[:24]
    value={'gapId':identity,'rootQuestionCode':root,'goalId':question.get('goalId'),
        'thoughtRevision':revision,'question':question['question'],
        'acceptedCandidate':deepcopy(pending.get('acceptedCandidate')),
        'bindingStatus':'BOUND' if pending.get('acceptedCandidate') else 'LEGACY_COMMITTED_QUESTION',
        'answerProjection':None,'resolution':'AWAITING_ANSWER'}
    state.gap_registry[identity]=value
    if revision in state.gap_revisions or state.fact_boundary_question_used:
        state.gap_revisions.add(revision)
        state.gap_usage.setdefault(revision,{'thoughtRevision':revision,'used':True,
            'gapId':identity,'rootQuestionCode':root,'bindingStatus':value['bindingStatus']})
    question.setdefault('rootGapQuestionCode',root)


def accept_gap(state,candidate,question_code,goal_id):
    if state.thought_revision in state.gap_usage or state.thought_revision in state.gap_revisions:
        raise CompletionTechnicalError('gap_budget_already_committed')
    question=plans(state).get(question_code)
    if not question or question.get('kind')!='B' or question.get('goalId')!=goal_id:
        raise CompletionTechnicalError('gap_requires_actual_root_plan')
    if candidate['thoughtRevision']!=state.thought_revision or candidate['result']['type']!='FACT_BOUNDARY_REQUIRED':
        raise CompletionTechnicalError('gap_candidate_binding')
    identity='GAP_'+sha(canonical([question_code,goal_id,state.thought_revision,candidate['hash']]))[:24]
    value={'gapId':identity,'rootQuestionCode':question_code,'goalId':goal_id,
        'thoughtRevision':state.thought_revision,'question':question['question'],
        'acceptedCandidate':deepcopy(candidate),'bindingStatus':'BOUND',
        'answerProjection':None,'resolution':'AWAITING_ANSWER'}
    state.gap_registry[identity]=value
    state.gap_usage[state.thought_revision]={'thoughtRevision':state.thought_revision,'used':True,
        'gapId':identity,'rootQuestionCode':question_code,'candidateHash':candidate['hash'],'bindingStatus':'BOUND'}
    state.gap_revisions.add(state.thought_revision); state.fact_boundary_question_used=True
    question['rootGapQuestionCode']=question_code
    state.pending_fact_boundary=deepcopy(value)
    record_attempt(state,candidate,'ACCEPTED_GAP')


def _dependency(state,source_key):
    source=state.sources[source_key]; code=source.item.question_code
    # Follow all correction dependencies leading to this answer, including
    # correction-of-correction source revisions. Unrelated sources are excluded.
    affected={source_key}; events=[]; changed=True
    while changed:
        changed=False
        for event in state.retraction_history:
            target=event['target']; key=target.get('sourceKey') or target['address']+'@'+target['revision']
            if key in affected and event['id'] not in {e['id'] for e in events}:
                events.append(event); affected.add(event['correctionSourceKey']); changed=True
    return {'sourceKey':source_key,'reviewRevision':state.review_revisions.get(code),
        'analysisContractRevision':state.analysis_contract_revision,
        'reviewDigest':sha(canonical(state.review_projections.get(code))),
        'semanticAnalyses':sorted({a.analysis_id for a in state.atoms if a.source_key==source_key and a.analysis_id}),
        'corrections':[{'id':e['id'],'originCurrent':e['correctionSourceKey'] in state.current_sources.values(),
            'currentProjection':next((p for p in state.correction_projection if p.get('id',p.get('eventId'))==e['id']),None),
            'replacementAnalysisRevision':e.get('replacementAnalysisRevision'),
            'replacementContributions':deepcopy(e.get('replacementContributions'))} for e in events]}


def _input_path(state,code,gap):
    if answer_target(state,code,gap): return True
    question=plans(state).get(code,{})
    return (question.get('controlPurpose')=='GAP_ANSWER_WAIT' and
        root_for_question(state,code)==gap['rootQuestionCode'] and
        question.get('thoughtRevision')==gap['thoughtRevision'])


def _binding_matches(state,binding,gap,source_key):
    source=state.sources.get(source_key)
    question=plans(state).get(source.item.question_code,{}) if source else {}
    return bool(source and binding and binding.get('sourceKey')==source_key and
        binding.get('questionCode')==source.item.question_code and
        (binding.get('address'),binding.get('revision'))==(source.source_id,source.revision) and
        type(binding.get('start')) is int and type(binding.get('length')) is int and
        binding['start']>=0 and binding['length']>0 and binding['start']+binding['length']<=len(source.text) and
        root_for_question(state,source.item.question_code)==gap['rootQuestionCode'] and
        question.get('thoughtRevision',gap['thoughtRevision'])==gap['thoughtRevision'] and
        all(binding.get(k)==gap[k] for k in ('gapId','rootQuestionCode','goalId','thoughtRevision')) and
        answer_target(state,binding.get('targetQuestionCode'),gap))


def _reason(state,key,reason):
    values=state.review_required_reasons.setdefault(key,[])
    if reason not in values: values.append(reason)


def prepare_review_reasons(state):
    """Field-local scheduling before SELECT; accepted CBT coverage is not invalidated."""
    for key in list(state.review_required_reasons):
        kept=[r for r in state.review_required_reasons[key] if not r.startswith('GAP_')]
        if kept: state.review_required_reasons[key]=kept
        else: state.review_required_reasons.pop(key)
    for gap in state.gap_registry.values():
        if gap['thoughtRevision']!=state.thought_revision:
            gap['reviewDependencySourceKeys']=[]
            continue
        bound_keys=set()
        for code,key in state.current_sources.items():
            projection=state.review_projections.get(code,{})
            raw=projection.get('review',state.reviews.get(code,{}))
            if 'gapAnswer' not in raw and projection.get('sourceKey')==key and projection.get('gapAnswerBinding') is None:
                bindings=[]
                explicit_kinds={item['kind'] for item in raw.get('contributions',[]) if item.get('goalId')==gap['goalId']}
                for atom in state.atoms:
                    if atom.source_key==key and atom.kind in GAP_KINDS & explicit_kinds and _binding_matches(state,atom.gap_binding,gap,key):
                        if atom.gap_binding not in bindings: bindings.append(atom.gap_binding)
                if len(bindings)==1:
                    projection['gapAnswerBinding']=deepcopy(bindings[0])
                    projection['gapAnswerFieldState']='RECOVERED_EXPLICIT_BINDING'
            historical_binding=projection.get('gapAnswerBinding')
            binding=projection.get('gapAnswerBinding') if projection.get('sourceKey')==key else None
            if _binding_matches(state,binding,gap,key): bound_keys.add(key)
            if not (_input_path(state,code,gap) or historical_binding and historical_binding.get('gapId')==gap['gapId']): continue
            bound_keys.add(key)
            if historical_binding: bound_keys.add(historical_binding['sourceKey'])
            if 'gapAnswer' not in raw and _binding_matches(state,binding,gap,key):
                explicit_meaning=any(a.source_key==key and a.kind in GAP_KINDS and a.gap_binding==binding for a in state.atoms)
                explicit_skip=any(c['sourceKey']==key and c['type']=='SKIP' and binding['start']<=c['start'] and
                    c['start']+c['length']<=binding['start']+binding['length'] for c in state.controls)
                if explicit_meaning or explicit_skip:
                    projection['gapAnswerFieldState']='RECOVERED_EXPLICIT_BINDING'
            if state.review_revisions.get(code)!=key:
                _reason(state,key,'GAP_SOURCE_REVIEW_REQUIRED')
            elif 'gapAnswer' not in raw and projection.get('gapAnswerFieldState')!='RECOVERED_EXPLICIT_BINDING':
                _reason(state,key,'GAP_BINDING_UNKNOWN_LEGACY_BINDING')
            elif raw.get('gapAnswer') is not None and binding is None:
                _reason(state,key,'GAP_BINDING_COMPILE_REQUIRED')
        # A current correction dependency under local review takes precedence
        # over an older answer; unrelated control lineage does not.
        affected=set(bound_keys)
        changed=True
        while changed:
            changed=False
            for event in state.retraction_history:
                target=event['target']; key=target.get('sourceKey') or target['address']+'@'+target['revision']
                if key not in affected: continue
                origin=state.sources.get(event['correctionSourceKey'])
                if not origin: continue
                current_key=state.current_sources.get(origin.item.question_code)
                if current_key and current_key not in affected:
                    affected.add(current_key); changed=True
                if current_key and state.review_revisions.get(origin.item.question_code)!=current_key:
                    _reason(state,current_key,'GAP_RELATED_CORRECTION_REVIEW_REQUIRED')
        gap['reviewDependencySourceKeys']=sorted(affected)
    return deepcopy(state.review_required_reasons)


def refresh(state):
    from .state import supplemental_atoms
    from .requests import active
    from .validity import validate_pointer
    state.fact_boundary_question_used=state.thought_revision in state.gap_usage or state.thought_revision in state.gap_revisions
    active_requests=active(state); current_supplemental=supplemental_atoms(state)
    prepare_review_reasons(state)
    for gap in state.gap_registry.values():
        root=gap['rootQuestionCode']
        gap['answerProjection']=None; gap['resolution']='ANSWER_WAIT'; candidates=[]
        for answer in state.history:
            if answer.answer is None: continue
            key=state.current_sources.get(answer.question_code)
            if not key:
                continue
            source=state.sources[key]
            if state.review_revisions.get(answer.question_code)!=key: continue
            refs=[]; bindings=[]; skips=[]
            for atom in current_supplemental:
                binding=atom.gap_binding
                if atom.source_key!=key or not _binding_matches(state,binding,gap,key): continue
                pointer={'address':source.source_id,'revision':source.revision,'sourceKey':key,
                    'start':atom.start,'length':atom.length,'exactExcerpt':source.text[atom.start:atom.start+atom.length]}
                try:
                    validate_pointer(state,pointer,'CBT_EVIDENCE')
                except ValueError:
                    continue
                refs.append({'refId':atom.ref_id,'analysisId':atom.analysis_id,'kind':atom.kind,'role':atom.role})
                if binding not in bindings: bindings.append(deepcopy(binding))
            stored=state.review_projections.get(answer.question_code,{})
            binding=stored.get('gapAnswerBinding') if stored.get('sourceKey')==key else None
            if _binding_matches(state,binding,gap,key):
                for control in state.controls:
                    if control['sourceKey']!=key or control['type']!='SKIP': continue
                    if not (binding['start']<=control['start'] and control['start']+control['length']<=binding['start']+binding['length']):
                        continue
                    pointer={'address':source.source_id,'revision':source.revision,'sourceKey':key,
                        'start':control['start'],'length':control['length'],
                        'exactExcerpt':source.text[control['start']:control['start']+control['length']]}
                    try:
                        validate_pointer(state,pointer,'REQUEST_CONTROL')
                    except ValueError:
                        continue
                    skips.append({'controlId':control['id'],'source':pointer,'binding':deepcopy(binding)})
                    if binding not in bindings: bindings.append(deepcopy(binding))
            if not refs and not skips: continue
            dependency={**_dependency(state,key),'bindings':bindings,'supplementalRefs':refs,'validSkips':skips}
            projection={'sourceKey':key,'address':source.source_id,'revision':source.revision,
                'questionCode':answer.question_code,'rootQuestionCode':root,
                'dependency':dependency,'dependencyHash':sha(canonical(dependency)),
                'reviewRevision':state.review_revisions.get(answer.question_code),
                'bindings':bindings,'supplementalReferenceIds':[r['refId'] for r in refs],
                'validSkips':skips,'status':'REVIEWED'}
            candidates.append(projection)
        # Only current explicit valid answers compete, by actual USER chronology.
        # Invalid latest answers fall back to the preceding valid one.
        if candidates: gap['answerProjection']=candidates[-1]
        gap['validAnswerSourceKeys']=[p['sourceKey'] for p in candidates]
        related_requests=[r for r in active_requests if root_for_question(state,
            state.sources[r['sourceKey']].item.question_code)==root]
        pending={key:deepcopy(state.review_required_reasons[key]) for key in gap.get('reviewDependencySourceKeys',[])
            if key in state.review_required_reasons}
        gap['pendingReviewReasons']=pending
        if pending:
            gap['resolution']='REVIEW_PENDING'
        elif related_requests:
            gap['resolution']='PRESENTATION_PENDING'
        elif gap['answerProjection']:
            gap['resolution']='REVIEWED'
    current=[g for g in state.gap_registry.values() if g['thoughtRevision']==state.thought_revision]
    if current:
        state.pending_fact_boundary=deepcopy(current[-1])
    elif state.pending_fact_boundary and state.pending_fact_boundary.get('resolution')!='NEEDS_RECOVERY':
        state.pending_fact_boundary=None


refresh_projection=refresh


def unresolved(state):
    pending=state.pending_fact_boundary
    return bool(pending and pending.get('resolution')!='REVIEWED')


def wait_plan(state,target_pending_id,diagnostics):
    from . import planner,requests
    refresh(state)
    target=requests.question_targets(state).get(target_pending_id)
    gap=answer_target(state,target['questionCode']) if target else None
    if not gap or gap['thoughtRevision']!=state.thought_revision or not state.fact_boundary_question_used:
        raise CompletionTechnicalError('gap_wait_requires_current_consumed_answer_target')
    if gap['resolution']=='REVIEW_PENDING':
        keys=list(gap.get('pendingReviewReasons',{}))
        raise ReviewRequired('gap_wait_requires_local_review',keys)
    if gap['resolution']!='ANSWER_WAIT':
        raise CompletionTechnicalError('gap_wait_requires_answer_wait')
    plan=planner.bind(planner.build(Move.USER_DIRECTION,WAIT_MESSAGE,(),state,diagnostics,
        source='GAP_ANSWER_WAIT',target=target['questionCode']),scope='CONTROL',goal_id=gap['goalId'],
        pending_id=target_pending_id,control_purpose='GAP_ANSWER_WAIT',existing=WAIT_MESSAGE,
        target_binding=target.get('targetSourceBinding'),context_bindings=target.get('contextSourceBindings',[]))
    return plan.model_copy(update={'root_control_id':gap.get('waitRootControlId'),
        'root_gap_question_code':gap['rootQuestionCode'],'thought_revision':gap['thoughtRevision'],
        'parent_question_code':state.history[-1].question_code if state.history else None})


def commit_wait(state,plan,question_code,parent_code):
    gap=next((g for g in state.gap_registry.values() if g['rootQuestionCode']==plan.root_gap_question_code and
        g['thoughtRevision']==plan.thought_revision and g['goalId']==plan.goal_id),None)
    if gap is None or gap['resolution']!='ANSWER_WAIT':
        raise CompletionTechnicalError('gap_wait_stale_binding')
    root=gap.setdefault('waitRootControlId','GWAIT_'+sha(canonical([gap['gapId'],question_code]))[:24])
    if plan.root_control_id not in (None,root):
        raise CompletionTechnicalError('gap_wait_root_mismatch')
    rows=gap.setdefault('waitResponses',[])
    row={'questionCode':question_code,'question':WAIT_MESSAGE,'parentQuestionCode':parent_code,
        'rootControlId':root,'rootGapQuestionCode':gap['rootQuestionCode'],'goalId':gap['goalId'],
        'thoughtRevision':gap['thoughtRevision']}
    if not any(q['questionCode']==question_code for q in rows): rows.append(row)
    return root


def candidate_dependency(state):
    pending=state.pending_fact_boundary
    return {'thoughtRevision':state.thought_revision,'gapUsed':state.fact_boundary_question_used,
        'gapId':pending.get('gapId') if pending else None,
        'answerDependencyHash':(pending.get('answerProjection') or {}).get('dependencyHash') if pending else None,
        'answerStatus':pending.get('resolution') if pending else None,
        'gapUsage':deepcopy(state.gap_usage.get(state.thought_revision)),
        'validAnswerSourceKeys':list(pending.get('validAnswerSourceKeys',[])) if pending else [],
        'reviewRequiredReasons':deepcopy(state.review_required_reasons),
        'semanticProjection':deepcopy(state.semantic_projection),
        'activeCorrections':deepcopy(state.correction_projection),
        'requestDispositions':{key:deepcopy(value.get('kindStates',{})) for key,value in state.request_registry.items()},
        'assessmentTarget':deepcopy(state.assessment_target)}


def record_attempt(state,candidate,status,reason=None):
    row={'candidateId':candidate['candidateId'],'candidateHash':candidate['hash'],
        'thoughtRevision':candidate['thoughtRevision'],'status':status,'reason':reason,
        'result':deepcopy(candidate['result']),
        'gapAnswerDependency':deepcopy(candidate.get('gapAnswerDependency',candidate_dependency(state)))}
    if not any(x.get('candidateId')==row['candidateId'] and x.get('status')==status for x in state.assessment_attempts):
        state.assessment_attempts.append(row)
