"""One chronological half-open interval reducer shared by every evidence consumer."""
from copy import deepcopy
from dataclasses import replace
from uuid import uuid4
from .contracts import CompletionTechnicalError, ReviewRequired, ANALYSIS_CONTRACT_REVISION
from .diagnostics import canonical, sha

def binding(value):
    return {'address':value['address'],'revision':value['revision']}

def address_key(value):
    return value['address']+'@'+value['revision']

def interval(value):
    return (value['start'],value['start']+value['length'])

def union(ranges,added):
    merged=[]
    for left,right in sorted([*ranges,*added]):
        if left>=right:
            continue
        if merged and left<=merged[-1][1]:
            merged[-1]=(merged[-1][0],max(right,merged[-1][1]))
        else:
            merged.append((left,right))
    return merged

def subtract(ranges,removed):
    result=list(ranges)
    for start,end in removed:
        pieces=[]
        for left,right in result:
            if right<=start or end<=left:
                pieces.append((left,right))
            else:
                if left<start: pieces.append((left,start))
                if end<right: pieces.append((end,right))
        result=pieces
    return result

def intersect(ranges,selected):
    return union([],[(max(a,c),min(b,d)) for a,b in ranges for c,d in selected if max(a,c)<min(b,d)])

def overlaps_range(selected,ranges):
    a,b=selected
    return any(a<d and c<b for c,d in ranges)

def overlaps(start,length,other_start,other_length):
    return overlaps_range((start,start+length),[(other_start,other_start+other_length)])

def contained(selected,ranges):
    a,b=selected
    return any(c<=a and b<=d for c,d in ranges)

def event_id(source,start,length,action,target):
    return sha(canonical([source.source_id,source.revision,start,length,action,
        target['address'],target['revision'],*interval(target)]))[:24]

def event_order(state,event):
    source=state.sources.get(event['correctionSourceKey'])
    codes={q.question_code:i for i,q in enumerate(state.history)}
    if source is None or source.item.question_code not in codes:
        raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_correction_origin')
    return tuple(event.get('canonicalOrder') or (codes[source.item.question_code],event['start'],event['id']))

def source_current_events(state):
    current=set(state.current_sources.values())
    return sorted([e for e in state.retraction_history if e['correctionSourceKey'] in current],
        key=lambda e:event_order(state,e))

def event_masks(events):
    masks={}
    for event in events:
        key=address_key(event['target']); selected=[interval(event['target'])]
        masks[key]=(union(masks.get(key,[]),selected) if event['action']=='RETRACT' else
                    subtract(masks.get(key,[]),selected))
    return masks

def active_events(state,events):
    """Acyclic correction-of-correction validity, decided latest source first.

    Events in a source cannot target that source, so each group is evaluated
    against only already decided later sources. Partial clause withdrawal
    disables the whole act; only full restoration can reactivate it.
    """
    active=[]; groups={}
    for event in events:
        groups.setdefault(event_order(state,event)[0],[]).append(event)
    for index in sorted(groups,reverse=True):
        later_masks=event_masks(sorted(active,key=lambda e:event_order(state,e)))
        for event in groups[index]:
            if not overlaps_range((event['start'],event['start']+event['length']),
                    later_masks.get(event['correctionSourceKey'],[])):
                active.append(event)
    return sorted(active,key=lambda e:event_order(state,e))

def current_events(state):
    return active_events(state,source_current_events(state))

def exclusion_kinds(state):
    result={}
    for projection in state.review_projections.values():
        key=projection['sourceKey']; source=state.sources.get(key)
        if (source is None or state.current_sources.get(source.item.question_code)!=key or
                state.review_revisions.get(source.item.question_code)!=key or
                projection['analysisContractRevision']!=ANALYSIS_CONTRACT_REVISION):
            continue
        result[key]={name:union([],[(p['start'],p['start']+p['length']) for p in projection.get(name,[])])
                     for name in ('safetyOnlyRanges','controlRanges')}
    return result

def exclusions(state):
    result={}
    for key,spans in exclusion_kinds(state).items():
        result[key]=union(spans['safetyOnlyRanges'],spans['controlRanges'])
    return result

def canonical_event(state,source,start,length,action,target,prior_id=None):
    """Explicit same-act correspondence preserves the first accepted occurrence."""
    if type(start) is not int or type(length) is not int or start<0 or length<=0 or start+length>len(source.text):
        raise ValueError('invalid_correction_occurrence')
    candidates=[e for e in state.retraction_history if e['correctionSourceKey']==source.key]
    same_target=lambda e: (e['action']==action and binding(e['target'])==binding(target) and
                            interval(e['target'])==interval(target))
    exact=[e for e in candidates if same_target(e) and (e['start'],e['length'])==(start,length)]
    if prior_id is not None:
        event=next((e for e in candidates if e['id']==prior_id or prior_id in e.get('legacyIds',[])),None)
        if event is None or not same_target(event) or not overlaps(start,length,event['start'],event['length']):
            raise ValueError('prior_correction_binding_mismatch')
        competing=[e for e in candidates if e['id']!=event['id'] and
                   overlaps(start,length,e['start'],e['length'])]
        if competing:
            raise ValueError('prior_correction_occurrence_ambiguous')
        return deepcopy(event)
    if len(exact)==1:
        return deepcopy(exact[0])
    if exact or any(same_target(e) and overlaps(start,length,e['start'],e['length']) for e in candidates):
        raise ValueError('prior_correction_id_required')
    identity=event_id(source,start,length,action,target)
    event={'id':identity,'action':action,'target':deepcopy(target),'correctionSourceKey':source.key,
        'start':start,'length':length,'exactCorrectionExcerpt':source.text[start:start+length],
        'acceptanceEvidence':None,'acceptanceStatus':'NEW_PENDING_ACCEPTANCE'}
    event['canonicalOrder']=list(event_order(state,event))
    return event

def acceptance_evidence(state,event,atoms,before,excluded):
    key=address_key(event['target']); selected=[interval(event['target'])]
    after=union(before,selected) if event['action']=='RETRACT' else subtract(before,selected)
    affected=[]
    if event['action']=='RETRACT':
        for atom in atoms:
            r=(atom.start,atom.start+atom.length)
            source=state.sources.get(atom.source_key)
            if (source and source.key==key and overlaps_range(r,selected) and
                    not overlaps_range(r,before+excluded.get(key,[])) and
                    state.review_revisions.get(source.item.question_code)==source.key):
                affected.append({'start':atom.start,'length':atom.length,'refId':atom.ref_id,
                    'sourceKey':source.key,'reviewRevision':state.review_revisions.get(source.item.question_code),
                    'analysisContractRevision':atom.analysis_revision,'kind':atom.kind,
                    'domain':atom.domain.value if atom.domain is not None else None,
                    'role':atom.role,'goalId':atom.goal_id,'analysisId':atom.analysis_id,
                    'evidenceKind':atom.evidence_kind,'gapBinding':deepcopy(atom.gap_binding)})
    # Preserve each independently accepted atom boundary, never its bounding hull.
    residual=[piece for value in affected for piece in subtract([interval(value)],after+excluded.get(key,[]))]
    restored=intersect(before,selected) if event['action']=='REAFFIRM' else []
    return {'status':'ACCEPTED','projectionRevision':state.projection_revision,
        'sourceReviewRevision':state.review_revisions.get(state.sources[event['correctionSourceKey']].item.question_code),
        'analysisContractRevision':ANALYSIS_CONTRACT_REVISION,'canonicalOrder':list(event_order(state,event)),
        'affectedAtoms':affected,'allowedResidualRanges':[list(r) for r in residual],
        'restoredAnswerBindings':deepcopy(replacement_gap_bindings(state,event)) if restored else [],
        'restoredRanges':[list(r) for r in restored],'beforeRetractions':[list(r) for r in before],
        'afterRetractions':[list(r) for r in after]}

def _replacement_atoms(state,event,make_replacement,excluded):
    replacement=event.get('replacementContributions'); evidence=event.get('acceptanceEvidence')
    target=event['target']; key=address_key(target); target_source=state.sources.get(key)
    if replacement is None or event.get('replacementAnalysisRevision')!=ANALYSIS_CONTRACT_REVISION:
        raise ValueError('replacement_review_incomplete')
    if evidence is None or evidence.get('status')!='ACCEPTED':
        raise ValueError('correction_acceptance_evidence_unrecoverable')
    if target_source is None and replacement!=[]:
        raise ValueError('record_replacement_must_be_empty')
    allowed=evidence['allowedResidualRanges'] if event['action']=='RETRACT' else evidence['restoredRanges']
    result=[]
    for item in replacement:
        atom=make_replacement(target_source,item,event); r=(atom.start,atom.start+atom.length)
        if not contained(r,allowed):
            raise ValueError('replacement_outside_affected_or_restored_range')
        if overlaps_range(r,excluded.get(key,[])):
            raise ValueError('replacement_overlaps_active_exclusion')
        result.append(atom)
    return result

def new_analysis_id():
    return 'AN_'+uuid4().hex

def analysis_pair(atom):
    return {'analysisId':atom.analysis_id,'refId':atom.ref_id}

def pair_key(pair):
    return (pair.get('analysisId'),pair['refId'])

def proof_pairs(state,proof):
    """Resolve legacy refs only against actual retained atoms, without rewriting proof."""
    by_ref={atom.ref_id:atom for atom in state.atoms}
    result=[]
    for value in proof.get('affectedAtoms',[]):
        identity=value.get('analysisId')
        if identity is None and value['refId'] in by_ref:
            identity=by_ref[value['refId']].analysis_id
        if identity is not None:
            result.append({'analysisId':identity,'refId':value['refId']})
    return result

def replacement_gap_bindings(state,event):
    """Only a target's accepted proof/actual stored ref can confer GAP purpose."""
    proof=event.get('acceptanceEvidence') or {}; proofs=[proof]
    if event['action']=='REAFFIRM':
        proofs.extend(prior.get('acceptanceEvidence') or {} for prior in state.retraction_history
            if prior['action']=='RETRACT' and address_key(prior['target'])==address_key(event['target']) and
            event_order(state,prior)<event_order(state,event) and
            overlaps_range(interval(prior['target']),[interval(event['target'])]))
    by_ref={a.ref_id:a for a in state.atoms}; values={}
    for saved in proofs:
        for binding in saved.get('restoredAnswerBindings',[]): values[canonical(binding)]=binding
        for value in saved.get('affectedAtoms',[]):
            binding=value.get('gapBinding')
            if binding is None and value.get('refId') in by_ref:
                binding=by_ref[value['refId']].gap_binding
            if binding: values[canonical(binding)]=binding
    return list(values.values())

def same_lane(left,right):
    return (left.evidence_kind,left.domain,left.goal_id)==(right.evidence_kind,right.domain,right.goal_id)

def goal_dependencies(state,source_key,outputs=()):
    source=state.sources.get(source_key)
    plans=list(state.prior_questions)+([state.pending_question] if state.pending_question else [])
    question=next((q for q in plans if source and q['questionCode']==source.item.question_code),{})
    ids={a.goal_id for a in outputs if a.goal_id}
    if question.get('goalId'): ids.add(question['goalId'])
    fields=('goalId','scope','move','targetDomain','targetSourceBinding','contextSourceBindings','relatedGoalId')
    return {identity:{field:deepcopy(state.goals[identity].get(field)) for field in fields}
            for identity in sorted(ids) if identity in state.goals}

def dependencies_current(state,node,atom):
    if atom.goal_id:
        saved=node.get('goalDependencies',{}).get(atom.goal_id)
        actual=state.goals.get(atom.goal_id)
        if saved and (actual is None or any(actual.get(k)!=v for k,v in saved.items())):
            return False
    if atom.evidence_kind=='GAP':
        binding=atom.gap_binding; gap=state.gap_registry.get((binding or {}).get('gapId'))
        if not gap or any(binding.get(k)!=gap.get(k) for k in ('rootQuestionCode','goalId','thoughtRevision')):
            return False
        if gap['thoughtRevision']!=state.thought_revision:
            return False
    return True

def event_frontier(state,source_key,events=None):
    relevant={source_key}; selected=[]; changed=True
    pool=state.retraction_history if events is None else events
    while changed:
        changed=False
        for event in pool:
            if address_key(event['target']) in relevant and event['id'] not in {e['id'] for e in selected}:
                selected.append(event); relevant.add(event['correctionSourceKey']); changed=True
    active={e['id'] for e in (current_events(state) if events is None else active_events(state,events))}
    return {e['id']:{'active':e['id'] in active,
        'replacementAnalysisId':e.get('currentReplacementAnalysisId')} for e in selected}

def semantic_frontier(state,source_key,events=None,*,chronological=False):
    """The exact analysis frontier read by a new full source review."""
    current,_,_,_,projection,_=project_semantics(state,events,chronological=chronological)
    return {'events':event_frontier(state,source_key,events if chronological else None),
        'analyses':[row['analysisId'] for row in projection
                    if row.get('sourceKey')==source_key and row.get('applicability')=='APPLICABLE'],
        'outputPairs':[analysis_pair(a) for a in current if a.source_key==source_key],
        'goalDependencies':goal_dependencies(state,source_key),
        'answerBinding':deepcopy(next((p.get('gapAnswerBinding') for p in state.review_projections.values()
                                      if p.get('sourceKey')==source_key),None))}

def register_analysis(state,identity,source_key,kind,outputs,*,predecessor=None,frontier=None,event=None,gap_binding=None):
    if identity in state.semantic_analyses or predecessor==identity:
        raise ValueError('semantic_analysis_identity_or_cycle')
    if predecessor is not None and predecessor not in state.semantic_analyses:
        raise ValueError('semantic_predecessor_missing')
    seen={identity}; previous=predecessor
    while previous:
        if previous in seen: raise ValueError('semantic_predecessor_cycle')
        seen.add(previous); prior=state.semantic_analyses.get(previous)
        if prior is None: raise ValueError('semantic_predecessor_missing')
        previous=prior.get('semanticPredecessor')
    node={'analysisId':identity,'sourceKey':source_key,'analysisKind':kind,
        'semanticPredecessor':predecessor,'eventDependency':event['id'] if event else None,
        'reviewFrontier':deepcopy(frontier or {'events':{},'analyses':[],'outputPairs':[]}),
        'replacedPairs':deepcopy((frontier or {}).get('outputPairs',[])) if kind=='BASE' else None,
        'outputRefs':[a.ref_id for a in outputs],'gapAnswerBinding':deepcopy(gap_binding),
        'goalDependencies':goal_dependencies(state,source_key,outputs),'outputLineages':{},
        'analysisContractRevision':ANALYSIS_CONTRACT_REVISION}
    state.semantic_analyses[identity]=node
    state.atoms=tuple(state.atoms)+tuple(outputs)
    if kind=='BASE':
        bind_lineages(state,node,node['replacedPairs'])
    return node

def bind_lineages(state,node,replaced):
    """Persist exact semantic edges; range is used only to bind that edge once.

    Full review replaces its complete read frontier, but a new cross-domain
    output does not inherit unrelated overlapping atoms' correction lineage.
    A replacement may reclassify an affected atom, so its lane may change.
    """
    by_ref={a.ref_id:a for a in state.atoms}
    node['replacedPairs']=deepcopy(replaced)
    for ref in node['outputRefs']:
        output=by_ref[ref]
        overlapping=[p for p in [*replaced,*node.get('lineageRoots',[])] if p['refId'] in by_ref and
            overlaps(output.start,output.length,by_ref[p['refId']].start,by_ref[p['refId']].length)]
        matching=[p for p in overlapping if same_lane(output,by_ref[p['refId']])]
        # A full review may legitimately change domain. Prefer its own
        # semantic lane when present (protecting independent cross-domain
        # evidence); otherwise preserve the explicit reclassification edge.
        parents=(matching or overlapping) if node['analysisKind']=='BASE' else overlapping
        node['outputLineages'][ref]=deepcopy(parents)

def ancestors(state,atom,seen=None):
    pair=analysis_pair(atom); identity=pair_key(pair); seen=set() if seen is None else set(seen)
    if identity in seen: raise CompletionTechnicalError('semantic_predecessor_cycle')
    seen.add(identity); result={identity}; node=state.semantic_analyses.get(atom.analysis_id,{})
    by_ref={a.ref_id:a for a in state.atoms}
    for parent in node.get('outputLineages',{}).get(atom.ref_id,[]):
        key=pair_key(parent); result.add(key); previous=by_ref.get(parent['refId'])
        if previous is not None and previous.analysis_id==parent.get('analysisId'):
            result.update(ancestors(state,previous,seen))
    return result

def pair_dependency_status(state,pair,active_ids,seen=None):
    """Validate a retained semantic dependency, not its current visibility.

    Superseded outputs remain valid historical predecessors. An inactive
    parent correction does not; a missing retained node/ref is different.
    """
    identity=pair.get('analysisId'); node=state.semantic_analyses.get(identity)
    atom=next((a for a in state.atoms if a.ref_id==pair['refId']),None)
    if node is None or atom is None or atom.analysis_id!=identity or pair['refId'] not in node['outputRefs']:
        return 'MISSING'
    seen=set() if seen is None else set(seen)
    if identity in seen: raise CompletionTechnicalError('semantic_predecessor_cycle')
    seen.add(identity)
    if node['analysisKind']=='BASE':
        return 'VALID' if all((event_id in active_ids)==value['active'] for event_id,value in
            node['reviewFrontier']['events'].items()) else 'INACTIVE'
    if node['analysisKind']!='REPLACEMENT': return 'MISSING'
    event_id=node.get('eventDependency')
    if not any(e['id']==event_id for e in state.retraction_history): return 'MISSING'
    if event_id not in active_ids: return 'INACTIVE'
    return replacement_dependency_status(state,node,active_ids,seen)

def replacement_dependency_status(state,node,active_ids,seen=None):
    pairs=node.get('replacedPairs')
    if pairs is None: return 'MISSING'
    statuses=[pair_dependency_status(state,pair,active_ids,seen) for pair in pairs]
    if 'MISSING' in statuses: return 'MISSING'
    return 'INACTIVE' if 'INACTIVE' in statuses else 'VALID'

def replacement_scope(state,event,node,current,outputs,restored):
    """Find only valid successors of immutable first-proof/replacement edges."""
    proof=event.get('acceptanceEvidence') or {}
    seeds={pair_key(p) for p in proof_pairs(state,proof)}
    previous=state.semantic_analyses.get(node.get('semanticPredecessor'),{})
    seeds.update(pair_key(p) for p in (previous.get('replacedPairs') or []))
    seeds.update((previous.get('analysisId'),ref) for ref in previous.get('outputRefs',[]))
    # A restored clause is related to the actual RETRACT(s) that first removed
    # it; never to an arbitrary independent overlapping current atom.
    if event['action']=='REAFFIRM':
        for prior in state.retraction_history:
            if (prior['action']=='RETRACT' and address_key(prior['target'])==address_key(event['target']) and
                    event_order(state,prior)<event_order(state,event) and
                    overlaps_range(interval(prior['target']),proof.get('restoredRanges',[]))):
                seeds.update(pair_key(p) for p in proof_pairs(state,prior.get('acceptanceEvidence') or {}))
    # Only a newly accepted review may recover an actual valid predecessor
    # through retained causal edges. Never infer a replacement from overlap or
    # silently repair a previously accepted node when its dependency changes.
    active_ids={e['id'] for e in current_events(state)}; by_ref={a.ref_id:a for a in state.atoms}
    recovered=set()
    for identity,ref in seeds:
        if pair_dependency_status(state,{'analysisId':identity,'refId':ref},active_ids)!='INACTIVE': continue
        atom=by_ref.get(ref)
        if atom is not None:
            recovered.update(pair for pair in ancestors(state,atom) if
                pair_dependency_status(state,{'analysisId':pair[0],'refId':pair[1]},active_ids)=='VALID')
    seeds.update(recovered)
    scope=[]
    for atom in current:
        if atom.source_key!=address_key(event['target']) or not (ancestors(state,atom)&seeds): continue
        if event['action']=='RETRACT' or overlaps_range((atom.start,atom.start+atom.length),restored) or any(
                overlaps(atom.start,atom.length,out.start,out.length) for out in outputs):
            scope.append(analysis_pair(atom))
    # Stable serialization only; these pairs are a set, never priority order.
    node.setdefault('lineageRoots',[{'analysisId':identity,'refId':ref} for identity,ref in sorted(seeds,key=canonical)])
    return scope

def _valid_base(state,identity,active_ids):
    seen=set()
    while identity:
        if identity in seen: raise CompletionTechnicalError('semantic_predecessor_cycle')
        seen.add(identity); node=state.semantic_analyses.get(identity)
        if node is None: return None
        if node['analysisKind']!='BASE': raise CompletionTechnicalError('semantic_base_predecessor_kind')
        if all((key in active_ids)==value['active'] for key,value in node['reviewFrontier']['events'].items()):
            return node
        identity=node['semanticPredecessor']
    return None

def _reason(reasons,key,reason):
    if key:
        values=reasons.setdefault(key,[])
        if reason not in values: values.append(reason)

def project_semantics(state,events=None,*,initialize_edges=False,chronological=False):
    """Single causal reducer: raw restoration never undoes a semantic edge."""
    events=source_current_events(state) if events is None else events
    dependency_active_ids={e['id'] for e in current_events(state)}
    # Immutable first acceptance/initial edges read their canonical prefix.
    # New interpretations and final projection read the full current DAG;
    # later stored clause withdrawals cannot revive an invalid predecessor.
    active=(active_events(state,events) if chronological else
            [event for event in events if event['id'] in dependency_active_ids])
    active_ids={event['id'] for event in active}
    if chronological: dependency_active_ids=active_ids
    excluded=exclusions(state); masks={}; timeline=[]; projection=[]; reasons={}
    by_ref={a.ref_id:a for a in state.atoms}; current=[]; bases={}
    for key,identity in state.current_base_analyses.items():
        node=_valid_base(state,identity,dependency_active_ids)
        if node is None:
            if key in state.current_sources.values(): _reason(reasons,key,'SEMANTIC_PREDECESSOR_REVIEW_REQUIRED')
            continue
        if any(ref not in by_ref for ref in node['outputRefs']):
            _reason(reasons,key,'SEMANTIC_OUTPUT_REVIEW_REQUIRED'); continue
        bases[key]=node
        if node.get('migrationStatus')=='UNKNOWN_REVIEW_FRONTIER':
            _reason(reasons,key,'SEMANTIC_LEGACY_FRONTIER_REVIEW_REQUIRED')
            continue
        current.extend(by_ref[ref] for ref in node['outputRefs'] if dependencies_current(state,node,by_ref[ref]))
        projection.append({'analysisId':node['analysisId'],'sourceKey':key,'applicability':'APPLICABLE',
            'outputRefs':list(node['outputRefs']),'replacedPairs':deepcopy(node['replacedPairs'])})
    # Canonical bundle JSON may reorder a mapping's keys on restore. Only base
    # projection display order is canonicalized; atom/causal event order stays intact.
    projection.sort(key=lambda row: row['sourceKey'])
    for event in active:
        key=address_key(event['target']); selected=[interval(event['target'])]; before=list(masks.get(key,[]))
        after=union(before,selected) if event['action']=='RETRACT' else subtract(before,selected)
        restored=intersect(before,selected) if event['action']=='REAFFIRM' else []
        masks[key]=after; proof=event.get('acceptanceEvidence') or {}
        identity=event.get('currentReplacementAnalysisId'); node=state.semantic_analyses.get(identity)
        applicability='APPLICABLE'
        frontier=bases.get(key,{}).get('reviewFrontier',{})
        read_event=frontier.get('events',{}).get(event['id'],{})
        covered=node is not None and (identity in frontier.get('analyses',[]) or
            (read_event.get('active') and read_event.get('replacementAnalysisId')==identity))
        if not proof:
            applicability='NEEDS_REVIEW'
            _reason(reasons,event['correctionSourceKey'],'SEMANTIC_FIRST_ACCEPTANCE_REVIEW_REQUIRED')
            _reason(reasons,event['target'].get('sourceKey'),'SEMANTIC_TARGET_DEPENDENCY_REVIEW_REQUIRED')
        elif event['action']=='REAFFIRM' and not restored:
            applicability='NOT_APPLICABLE'
        elif covered:
            applicability='SUPERSEDED_BY_FULL_REVIEW'
        elif not proof or node is None or event.get('replacementContributions') is None:
            applicability='NEEDS_REVIEW'
            _reason(reasons,event['correctionSourceKey'],'SEMANTIC_REPLACEMENT_REVIEW_REQUIRED')
            _reason(reasons,event['target'].get('sourceKey'),'SEMANTIC_TARGET_DEPENDENCY_REVIEW_REQUIRED')
        if applicability=='APPLICABLE':
            if len(proof_pairs(state,proof))!=len(proof.get('affectedAtoms',[])):
                applicability='NEEDS_REVIEW'
                _reason(reasons,event['correctionSourceKey'],'SEMANTIC_FIRST_OUTPUT_IDENTITY_REVIEW_REQUIRED')
                _reason(reasons,event['target'].get('sourceKey'),'SEMANTIC_TARGET_DEPENDENCY_REVIEW_REQUIRED')
        if applicability=='APPLICABLE':
            outputs=[]
            for ref in node['outputRefs']:
                atom=by_ref.get(ref)
                if atom is None:
                    applicability='NEEDS_REVIEW'; _reason(reasons,event['correctionSourceKey'],'SEMANTIC_OUTPUT_REVIEW_REQUIRED'); break
                r=(atom.start,atom.start+atom.length)
                allowed=proof.get('allowedResidualRanges',[]) if event['action']=='RETRACT' else intersect(proof.get('restoredRanges',[]),restored)
                if contained(r,allowed) and not overlaps_range(r,after+excluded.get(key,[])) and dependencies_current(state,node,atom):
                    outputs.append(atom)
            if applicability=='APPLICABLE':
                if initialize_edges and node['replacedPairs'] is None:
                    bind_lineages(state,node,replacement_scope(state,event,node,current,outputs,restored))
                dependency=replacement_dependency_status(state,node,dependency_active_ids)
                if dependency=='MISSING':
                    applicability='NEEDS_REVIEW'
                    _reason(reasons,event['correctionSourceKey'],'SEMANTIC_PREDECESSOR_OUTPUT_REVIEW_REQUIRED')
                    _reason(reasons,event['target'].get('sourceKey'),'SEMANTIC_TARGET_DEPENDENCY_REVIEW_REQUIRED')
                elif dependency=='INACTIVE':
                    applicability='NOT_APPLICABLE'
            if applicability=='APPLICABLE':
                seeds={pair_key(p) for p in (node['replacedPairs'] or [])}
                # An edge survives raw restoration, and follows only explicit
                # valid successors. Cross-domain overlap alone is not an edge.
                replaced_ids={a.ref_id for a in current if a.source_key==key and ancestors(state,a)&seeds}
                current=[a for a in current if a.ref_id not in replaced_ids]+outputs
        if (initialize_edges and applicability=='NOT_APPLICABLE' and node is not None and
                node['replacedPairs'] is None):
            # Delta-zero REAFFIRM is a completed stored interpretation, not a
            # missing edge. Its current restored scope is empty; retained
            # lineage roots allow a later clause restoration without a retry.
            bind_lineages(state,node,replacement_scope(state,event,node,current,[],restored))
        if identity:
            projection.append({'analysisId':identity,'sourceKey':key,'eventId':event['id'],
                'applicability':applicability,'outputRefs':list(node['outputRefs']) if node else [],
                'replacedPairs':deepcopy(node.get('replacedPairs')) if node else None})
        timeline.append({'eventId':event['id'],'address':event['target']['address'],'revision':event['target']['revision'],
            'before':[list(r) for r in before],'after':[list(r) for r in after],
            'affectedRanges':[list(interval(a)) for a in proof.get('affectedAtoms',[])],
            'restoredRanges':[list(r) for r in restored],'status':'ACTIVE',
            'replacementStatus':applicability,'analysisId':identity})
    for event in state.retraction_history:
        if event['id'] not in active_ids:
            timeline.append({'eventId':event['id'],'address':event['target']['address'],'revision':event['target']['revision'],
                'before':[],'after':[],'affectedRanges':[],'restoredRanges':[],
                'status':'INACTIVE_CORRECTION_SOURCE','replacementStatus':'NOT_APPLICABLE',
                'analysisId':event.get('currentReplacementAnalysisId')})
    current=[a for a in current if not overlaps_range((a.start,a.start+a.length),
        masks.get(a.source_key,[])+excluded.get(a.source_key,[]))]
    return current,masks,excluded,timeline,projection,reasons

def reduce(state,base_atoms=(),make_replacement=None,existing_replacements=()):
    # Legacy arguments remain compatible for callers; authoritative semantic
    # output comes only from accepted analysis nodes, not an appended atom bag.
    if make_replacement is not None:
        raise CompletionTechnicalError('semantic_review_requires_analysis_registration')
    values=project_semantics(state)
    return values[:4]

def refresh_semantics(state):
    atoms,masks,excluded,timeline,projection,reasons=project_semantics(state)
    for key in list(state.review_required_reasons):
        state.review_required_reasons[key]=[r for r in state.review_required_reasons[key] if not r.startswith('SEMANTIC_')]
        if not state.review_required_reasons[key]: state.review_required_reasons.pop(key)
    for key,values in reasons.items():
        for reason in values: _reason(state.review_required_reasons,key,reason)
    state.semantic_projection=projection
    return atoms,masks,excluded,timeline

def migrate_analyses(state):
    """Adopt actually stored outputs; never reconstruct a lost first proof."""
    if state.semantic_analyses:
        state.bounded_fix_projection_revision=2
        return
    originals=list(state.atoms); revised=[]
    for key in {a.source_key for a in originals if a.origin_event_id is None} | set(state.review_revisions.values()):
        rows=[a for a in originals if a.source_key==key and a.origin_event_id is None]
        identity='AN_LEGACY_BASE_'+sha(canonical([key,[a.ref_id for a in rows]]))[:24]
        outputs=[replace(a,analysis_id=identity) for a in rows]
        node={'analysisId':identity,'sourceKey':key,'analysisKind':'BASE','semanticPredecessor':None,
            'eventDependency':None,'reviewFrontier':{'events':{},'analyses':[],'outputPairs':[]},
            'replacedPairs':[],'outputRefs':[a.ref_id for a in outputs],
            'gapAnswerBinding':None,'analysisContractRevision':ANALYSIS_CONTRACT_REVISION,'migrationStatus':'STORED_OUTPUTS'}
        state.semantic_analyses[identity]=node; state.current_base_analyses[key]=identity; revised.extend(outputs)
    for event in state.retraction_history:
        rows=[a for a in originals if a.origin_event_id==event['id']]
        if not event.get('acceptanceEvidence') or event.get('replacementContributions') is None:
            revised.extend(rows); continue
        # Every declared stored output must still exist. [] is explicitly a
        # complete analysis, whereas vanished outputs cannot become [].
        if len(rows)!=len(event['replacementContributions']):
            revised.extend(rows); continue
        identity='AN_LEGACY_REPLACEMENT_'+sha(canonical([event['id'],[a.ref_id for a in rows]]))[:24]
        outputs=[replace(a,analysis_id=identity) for a in rows]
        state.semantic_analyses[identity]={'analysisId':identity,'sourceKey':address_key(event['target']),
            'analysisKind':'REPLACEMENT','semanticPredecessor':None,'eventDependency':event['id'],
            'reviewFrontier':{'events':{},'analyses':[],'outputPairs':[]},
            'replacedPairs':[{'analysisId':a.get('analysisId'),'refId':a['refId']} for a in event['acceptanceEvidence'].get('affectedAtoms',[])],
            'outputRefs':[a.ref_id for a in outputs],'gapAnswerBinding':None,
            'analysisContractRevision':ANALYSIS_CONTRACT_REVISION,'migrationStatus':'STORED_OUTPUTS'}
        event['currentReplacementAnalysisId']=identity; revised.extend(outputs)
    state.atoms=tuple(revised); state.bounded_fix_projection_revision=2
    # A pre-v2 proof's refId can bind only to an actually retained atom. Lost
    # first outputs are not rebuilt from today's atom partition or raw span.
    for event in source_current_events(state):
        proof=event.get('acceptanceEvidence') or {}
        node=state.semantic_analyses.get(event.get('currentReplacementAnalysisId'))
        if node is None: continue
        pairs=proof_pairs(state,proof)
        if len(pairs)!=len(proof.get('affectedAtoms',[])):
            base=state.semantic_analyses.get(state.current_base_analyses.get(address_key(event['target'])))
            if base: base['migrationStatus']='UNKNOWN_REVIEW_FRONTIER'
            continue
        node['outputLineages']={}; node['replacedPairs']=None
    project_semantics(state,initialize_edges=True)

def source_validity(state,table):
    _,tombstones,excluded,_=reduce(state,[])
    purposes=exclusion_kinds(state)
    current=set(state.current_sources.values())
    return {s['sourceId']:{'address':s['address'],'revision':s['revision'],
        'currentRevision':s['kind'] in ('USER_RECORD','USER_ANSWER'),
        'reviewed':s['kind']=='USER_RECORD' or (s.get('sourceKey') in current and
            state.review_revisions.get(s.get('questionCode'))==s.get('sourceKey')),
        'activeRetractions':[list(r) for r in tombstones.get(address_key(s),[])],
        'controlRanges':[list(r) for r in purposes.get(address_key(s),{}).get('controlRanges',[])],
        'safetyOnlyRanges':[list(r) for r in purposes.get(address_key(s),{}).get('safetyOnlyRanges',[])],
        'pureExclusions':[list(r) for r in excluded.get(address_key(s),[])],
        'validRanges':[list(r) for r in subtract([(0,len(s['text']))],
            tombstones.get(address_key(s),[])+excluded.get(address_key(s),[]))]} for s in table}

def valid_span(state,address,revision,start,length,purpose='CBT_EVIDENCE'):
    _,tombstones,excluded,_=reduce(state,[])
    key=address+'@'+revision
    masks=tombstones.get(key,[])
    if purpose=='CBT_EVIDENCE':
        masks=masks+excluded.get(key,[])
    elif purpose in ('SAFETY_ORIGIN','HISTORICAL_CONTEXT'):
        masks=[]
    elif purpose not in ('REQUEST_CONTROL','RESUME','SAFETY_RESOLUTION','ASSESSMENT_TARGET'):
        raise ValueError('unknown_provenance_purpose')
    return type(start) is int and type(length) is int and start>=0 and length>0 and not overlaps_range((start,start+length),masks)

def validate_pointer(state,pointer,purpose,latest=False):
    """Exact provenance first, currentness and admissibility by purpose second."""
    key=address_key(pointer); source=state.sources.get(key); record=state.record_sources.get(key)
    overlay=next((o.get('source') for o in state.safety_overlays.values()
                  if o.get('source') and address_key(o['source'])==key),None)
    text=source.text if source else record.get('text') if record else overlay.get('text') if overlay else None
    start,length=pointer.get('start'),pointer.get('length')
    if (text is None or type(start) is not int or type(length) is not int or start<0 or length<=0 or
            start+length>len(text) or pointer.get('exactExcerpt',text[start:start+length])!=text[start:start+length]):
        raise ValueError('invalid_exact_provenance')
    current_source=source is not None and state.current_sources.get(source.item.question_code)==key
    current_record=record is not None and state.current_record_sources.get(record.get('field'))==key
    if source and (pointer.get('sourceKey',source.key)!=source.key or
            pointer.get('questionCode',source.item.question_code)!=source.item.question_code):
        raise ValueError('provenance_source_binding_mismatch')
    if purpose not in ('SAFETY_ORIGIN','HISTORICAL_CONTEXT') and not (current_source or current_record):
        raise ValueError('provenance_requires_current_revision')
    if purpose in ('REQUEST_CONTROL','RESUME'):
        if not current_source:
            raise ValueError('control_requires_user_answer')
        from .signals import eligible_control
        if not eligible_control(text,text[start:start+length],start):
            raise ValueError('control_quoted_or_negated')
    if latest or purpose=='RESUME':
        latest_key=state.current_sources.get(state.history[-1].question_code) if state.history else None
        if key!=latest_key:
            raise ValueError('control_requires_latest_user_answer')
    if purpose=='RESUME' and not state.stop_guidance_pending:
        raise ValueError('resume_without_stop')
    if purpose=='CBT_EVIDENCE' and (not current_source or
            state.review_revisions.get(source.item.question_code)!=key):
        raise ValueError('evidence_requires_current_review')
    if purpose=='ASSESSMENT_TARGET' and (not current_record or record.get('field')!='automaticThought'):
        raise ValueError('assessment_requires_original_thought')
    if not valid_span(state,pointer['address'],pointer['revision'],start,length,purpose):
        raise ValueError('provenance_overlaps_active_retraction_or_exclusion')
    return pointer

def migrate_events(state):
    """Old raw events survive; old derived replacement validation does not become approval."""
    events={}
    for raw in state.retraction_history:
        event=deepcopy(raw); source=state.sources.get(event.get('correctionSourceKey'))
        target=event.get('target',{})
        if source is None or not all(k in target for k in ('address','revision','start','length')):
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_correction_range')
        target_source=state.sources.get(address_key(target))
        record_source=state.record_sources.get(address_key(target))
        target_text=target_source.text if target_source else record_source.get('text') if record_source else None
        if (target_text is None or event.get('action') not in ('RETRACT','REAFFIRM') or
            any(type(v) is not int for v in (target['start'],target['length'],event.get('start'),event.get('length'))) or
            target['start']<0 or target['length']<=0 or target['start']+target['length']>len(target_text) or
            event['start']<0 or event['length']<=0 or event['start']+event['length']>len(source.text)):
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_correction_range')
        if target.get('exactExcerpt',target_text[target['start']:target['start']+target['length']])!=target_text[target['start']:target['start']+target['length']]:
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_correction_excerpt')
        if target_source and target.get('sourceKey')!=target_source.key:
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_correction_address')
        if any(type(r.get('start')) is not int or type(r.get('length')) is not int or r['start']<0 or
                r['length']<=0 or r['start']+r['length']>len(target_text) for r in event.get('affectedRanges',[])):
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_legacy_affected_range')
        if target_source and event_order(state,{'correctionSourceKey':target_source.key,
                'start':target['start'],'id':'target'})[0]>=event_order(state,event)[0]:
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_correction_cycle')
        computed=event_id(source,event['start'],event['length'],event['action'],target)
        old_id=event.get('id')
        # IDs with known first acceptance stay canonical. Legacy mapping is
        # explicit and never overwrites the evidence or invents new occurrence.
        event['id']=old_id or computed
        event.setdefault('canonicalOrder',list(event_order(state,event)))
        # An already canonical current event is not a legacy migration. Do
        # not alter its immutable receipt merely to materialize an empty field.
        if computed!=event['id'] and computed not in event.get('legacyIds',[]):
            event.setdefault('legacyIds',[]).append(computed)
        if event.get('acceptanceEvidence') is None:
            archived_proof=event.get('firstAcceptanceEvidence')
            if archived_proof and archived_proof.get('status')=='ACCEPTED':
                event['acceptanceEvidence']=deepcopy(archived_proof)
                event['acceptanceStatus']='ACCEPTED'
            else:
                # Current atom partitions and a later refresh timeline are not
                # evidence of the original acceptance. Preserve this dependency.
                event['acceptanceEvidence']=None
                event['acceptanceStatus']='NEEDS_REVIEW_LEGACY_ACCEPTANCE'
                issue={'kind':'CORRECTION_ACCEPTANCE_EVIDENCE_UNRECOVERABLE','eventId':event['id'],
                    'sourceKey':event['correctionSourceKey'],'target':binding(target)}
                if issue not in state.migration_issues:
                    state.migration_issues.append(issue)
        if 'replacementContributions' not in event:
            if event['action']=='RETRACT' and event.get('affectedRanges') is not None:
                event['replacementContributions']=deepcopy(event.get('residuals'))
                event['migrationStatus']='LEGACY_RANGE_BOUND_REVIEW_REQUIRED'
            else:
                event['replacementContributions']=None
                event['migrationStatus']='REPLACEMENT_REVIEW_REQUIRED'
            event['replacementAnalysisRevision']=None
            event['legacyResiduals']=event.pop('residuals',None)
        events[event['id']]=event
    state.retraction_history=sorted(events.values(),key=lambda e:event_order(state,e))
