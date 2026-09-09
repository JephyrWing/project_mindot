"""Presentation acts: source != target, label != identity, delivery != interpretation."""
from copy import deepcopy
from .contracts import CompletionTechnicalError
from .diagnostics import canonical, sha
from . import validity

KINDS={'REQUEST_EXAMPLE','REQUEST_EXPLANATION'}
TARGET_QUESTION='어느 질문에 대한 예시나 설명이 필요한지 짚어 주실 수 있을까요?'
TARGET_WAIT='어느 질문인지 정해지면 알려주세요. 예시나 설명이 더 필요 없으시면 그렇게 말씀해 주세요.'

def pending_id(code):
    return 'P_'+sha(code)[:24]

def all_questions(state):
    rows={q['questionCode']:q for q in state.prior_questions}
    if state.pending_question:
        rows[state.pending_question['questionCode']]=state.pending_question
    return rows

def presentation_question_binding(state,question,seen=None):
    """Strict ordinary-history/committed-help lineage, separate from CBT binding."""
    from .state import public_history_question_binding,is_safety_question
    seen=set() if seen is None else seen
    code=question.get('questionCode')
    if (not code or code in seen or not question.get('questionPurpose') or 'semanticRouteType' not in question or
            question.get('scope')!='CBT' or
            question.get('sourceBindingStatus')!='UNKNOWN_LEGACY_BINDING' or
            question.get('targetSourceBinding') is not None or question.get('contextSourceBindings') or
            question.get('move')=='FACT_CERTAINTY_CHECK' or question.get('controlPurpose') or
            question.get('episodeId') or question.get('rootGapQuestionCode') or is_safety_question(code)):
        return None
    seen.add(code)
    history=next((item for item in state.history if item.question_code==code),None)
    if history is not None and (history.question!=question.get('question') or
            history.question_purpose.value!=question.get('questionPurpose') or
            (history.semantic_route_type is not None and history.semantic_route_type.value!=question.get('semanticRouteType'))):
        return None
    origin=question.get('publicHistoryQuestionBinding')
    if origin is not None:
        if (history is None or question.get('kind')!='Q' or question.get('planSource')!='HISTORY' or
                question.get('targetQuestionCode')!=code or not isinstance(origin,dict) or
                type(origin.get('sessionId')) is not int or origin.get('recordId')!=state.record_id or
                code.startswith(('AGQ_','AGE_','AGX_','AGB_','AGS_','AGC_','AGU_')) or
                (len(code)>1 and code[0]=='R' and code[1].isdigit())):
            return None
        source=state.sources.get(state.current_sources.get(code))
        expected=public_history_question_binding(origin['sessionId'],state.record_id,history)
        # Spring can omit a previously stored route. Preserve its exact known
        # value; an explicitly different current route was rejected above.
        expected['semanticRouteType']=question.get('semanticRouteType')
        if (origin!=expected or source is None or source.source_id!=expected['historySourceAddress'] or
                source.item.question!=history.question or source.item.question_purpose!=history.question_purpose or
                (source.item.semantic_route_type is not None and
                 source.item.semantic_route_type.value!=expected['semanticRouteType'])):
            return None
        return deepcopy(expected)
    # This exception does not recover lost opaque plans: an actual stored E/X
    # plan must link to the exact question-only binding used by its Writer.
    prior=question.get('presentationQuestionBinding')
    parent=all_questions(state).get(question.get('targetQuestionCode'))
    if (not isinstance(prior,dict) or question.get('kind') not in ('E','X') or
            not code.startswith('AG'+question['kind']+'_') or not parent or not question.get('semanticRouteType') or
            question.get('goalId')!=parent.get('goalId') or
            question.get('presentationTargetPendingId')!=pending_id(parent['questionCode']) or
            (history is None and question!=state.pending_question)):
        return None
    actual=presentation_question_binding(state,parent,seen)
    if actual is None or prior!=actual:
        return None
    return {**actual,'kind':'COMMITTED_PRESENTATION_QUESTION_ONLY','questionCode':code,
        'questionSha256':sha(question['question']),'questionPurpose':question['questionPurpose'],
        'semanticRouteType':question['semanticRouteType'],'targetQuestionCode':parent['questionCode']}

def question_targets(state):
    from .goals import scope_of
    result={}
    for q in all_questions(state).values():
        scope=scope_of(q); episode=state.episodes.get(q.get('episodeId'))
        root=q.get('rootControlId')
        control_open=(not root or any(g.get('rootControlId')==root and g.get('state')!='CLOSED'
            for g in state.request_clarifications.values()) or
            state.assessment_target.get('rootControlId')==root and state.assessment_target.get('status')!='VALID' or
            q.get('controlPurpose')=='GAP_ANSWER_WAIT' and bool(state.pending_fact_boundary) and
            state.pending_fact_boundary.get('rootQuestionCode')==q.get('rootGapQuestionCode') and
            state.pending_fact_boundary.get('resolution')!='REVIEWED')
        executable=((scope!='CONTROL' or control_open) and
            (scope!='SAFETY' or bool(episode and episode['status']=='ACTIVE' and episode.get('clarificationUsed'))) and
            (scope!='STOP' or state.stop_guidance_pending))
        question_binding=presentation_question_binding(state,q)
        if q.get('sourceBindingStatus')=='UNKNOWN_LEGACY_BINDING' and question_binding is None:
            executable=False
        identity=pending_id(q['questionCode'])
        result[identity]={'pendingId':identity,'questionCode':q['questionCode'],'question':q['question'],
            'scope':scope,'goalId':q.get('goalId'),'episodeId':q.get('episodeId'),'move':q['move'],
            'focus':q['focus'],'targetDomain':q.get('targetDomain'),'executable':executable,
            'targetSourceBinding':deepcopy(q.get('targetSourceBinding')),
            'contextSourceBindings':deepcopy(q.get('contextSourceBindings',[])),
            'sourceBindingStatus':q.get('sourceBindingStatus','BOUND'),
            'presentationQuestionBinding':question_binding,
            'controlPurpose':q.get('controlPurpose'),'rootControlId':root,
            'clarificationId':q.get('clarificationId'),'clarificationIds':list(q.get('clarificationIds',[])),
            'controlStage':q.get('controlStage'),'parentQuestionCode':q.get('parentQuestionCode'),
            'rootGapQuestionCode':q.get('rootGapQuestionCode')}
    return result

def target_binding(target):
    return {k:target.get(k) for k in ('pendingId','questionCode','goalId','scope','episodeId','rootControlId','rootGapQuestionCode')}

def identity(source,target):
    return 'REQ_'+sha(canonical([source['address'],source['revision'],source['start'],source['length'],target]))[:24]

def source_matches(a,b):
    return validity.binding(a)==validity.binding(b) and validity.overlaps_range(validity.interval(a),[validity.interval(b)])

def compatible(request,target):
    fixed=request.get('resolvedTargetPendingId') or request.get('initialTargetPendingId')
    return fixed is None or target is None or fixed==target

def target_id(request):
    return request.get('resolvedTargetPendingId') or request.get('initialTargetPendingId')

def fulfilled(state,request_id,kind):
    return any(r.get('requestId')==request_id and kind in r.get('fulfilledRequestKinds',[]) for r in state.presentation_receipts)

def cancelled(state,request_id,kind):
    return any(u['action']=='CANCEL' and u['requestId']==request_id and kind in u['kinds'] for u in state.request_updates)

def disposition(state,request_id,kind):
    if fulfilled(state,request_id,kind): return 'FULFILLED'
    if cancelled(state,request_id,kind): return 'CANCELLED'
    return 'OUTSTANDING'

def record_kind(state,request,kind):
    kinds=request.setdefault('requestedKinds',[])
    has_disposition=any(r.get('requestId')==request['requestId'] for r in state.presentation_receipts) or any(
        u['requestId']==request['requestId'] for u in state.request_updates if u['action']=='CANCEL')
    # A reinterpretation cannot manufacture a new, uncancelled kind of an old act.
    if kind not in kinds and not has_disposition: kinds.append(kind)

def register(state,source,target,*,prior=None,targets=None):
    targets=question_targets(state) if targets is None else targets
    if target is not None and target not in targets:
        raise CompletionTechnicalError('request_unknown_target')
    if any(r.get('bindingStatus')=='UNKNOWN_LEGACY_BINDING' and r.get('source') and
            source_matches(r['source'],source) for r in state.request_registry.values()):
        raise CompletionTechnicalError('REQUEST_BINDING_UNRECOVERABLE_legacy_delivery')
    if prior is not None:
        request=state.request_registry.get(prior)
        if not request or request.get('bindingStatus')=='UNKNOWN_LEGACY_BINDING':
            raise CompletionTechnicalError('REQUEST_BINDING_UNRECOVERABLE_prior')
        if not source_matches(source,request['source']) or not compatible(request,target):
            raise CompletionTechnicalError('prior_request_source_span_or_target_mismatch')
        return request
    key=identity(source,target)
    if key in state.request_registry:
        return state.request_registry[key]
    for request in state.request_registry.values():
        original=request.get('source')
        if original and validity.binding(original)==validity.binding(source):
            if source_matches(original,source) and compatible(request,target):
                raise CompletionTechnicalError('overlapping_act_requires_priorRequestId')
    request={'requestId':key,'source':deepcopy(source),'initialTargetPendingId':target,
        'resolvedTargetPendingId':None,'targetBinding':target_binding(targets[target]) if target else None,
        'targetResolution':None,'bindingStatus':'BOUND' if target else 'UNRESOLVED','requestedKinds':[]}
    state.request_registry[key]=request
    return request

def signal_control(state,source,item,index,targets):
    from .state import locate,control_id
    from .signals import eligible_control
    a,n=locate(source.text,item['span'])
    if not eligible_control(source.text,item['span']['exactExcerpt'],a):
        raise ValueError('signal_not_user_control')
    code=source.item.question_code
    if item['type'] not in KINDS:
        return {'id':control_id(source.key,a,n,item['type'],code),'sourceKey':source.key,'questionCode':code,
            'start':a,'length':n,'type':item['type'],'target':code,
            'fulfilled':(source.key,item['type'],code) in state.fulfilled_controls,'signalIndex':index}
    pointer={'address':source.source_id,'revision':source.revision,'sourceKey':source.key,'questionCode':code,
        'start':a,'length':n,'exactExcerpt':source.text[a:a+n]}
    request=register(state,pointer,item['targetPendingId'],prior=item['priorRequestId'],targets=targets)
    record_kind(state,request,item['type'])
    original=request['source']; target=target_id(request)
    return {'id':request['requestId'],'requestId':request['requestId'],'sourceKey':source.key,'questionCode':code,
        'start':original['start'],'length':original['length'],'type':item['type'],
        'targetPendingId':target,'target':targets[target]['questionCode'] if target else None,
        'fulfilled':fulfilled(state,request['requestId'],item['type']),
        'disposition':disposition(state,request['requestId'],item['type']),'signalIndex':index}

def active(state):
    current=set(state.current_sources.values()); result=[]
    for request in state.request_registry.values():
        source=request.get('source') or {}; key=request['requestId']
        if source.get('sourceKey') not in current or request.get('bindingStatus')=='UNKNOWN_LEGACY_BINDING':
            continue
        if not validity.valid_span(state,source['address'],source['revision'],source['start'],source['length'],purpose='REQUEST_CONTROL'):
            continue
        for kind in request.get('requestedKinds',[]):
            if disposition(state,key,kind)!='OUTSTANDING': continue
            result.append({'id':key,'requestId':key,'sourceKey':source['sourceKey'],'questionCode':source['questionCode'],
                'start':source['start'],'length':source['length'],'type':kind,'targetPendingId':target_id(request),
                'fulfilled':False,'disposition':'OUTSTANDING'})
    return result

def ordered(state,controls=None):
    order={q.question_code:i for i,q in enumerate(state.history)}
    return sorted(active(state) if controls is None else controls,
        key=lambda c:(order.get(c['questionCode'],-1),c['start'],c['requestId'],c['type']))

def aliases(state,data):
    live={c['requestId'] for c in active(state)}; result={}
    for name,entry in data['presentationRequests'].items():
        if entry['state']=='ACTIVE':
            if entry['requestId'] in live: result[name]=entry['requestId']
        else:
            binding=data['reviewSlots'][entry['slot']]
            controls=[c for c in state.controls if c['sourceKey']==binding['sourceKey'] and
                c.get('signalIndex')==entry['signalIndex'] and c['type'] in KINDS and c['requestId'] in live]
            if len(controls)>1: raise CompletionTechnicalError('ambiguous_signal_alias')
            if controls: result[name]=controls[0]['requestId']
    return result

def clarification(state,ids):
    if not ids or len(ids)!=len(set(ids)):
        raise CompletionTechnicalError('request_target_distinct_ids')
    live=ordered(state); chosen=[c for c in live if c['requestId'] in ids]
    if {c['requestId'] for c in chosen}!=set(ids) or any(c['targetPendingId'] is not None for c in chosen):
        raise CompletionTechnicalError('request_target_requires_unresolved_requests')
    if len({c['sourceKey'] for c in chosen})!=1:
        raise CompletionTechnicalError('request_target_requires_same_source')
    if any(c['targetPendingId'] is not None for c in live):
        raise CompletionTechnicalError('request_target_cannot_bypass_definite_request')
    for old in state.request_clarifications.values():
        if set(old['requestIds']) & set(ids) and old['state']!='CLOSED':
            raise CompletionTechnicalError('request_target_clarification_already_open')
    return chosen

def proof(state,pointer,table):
    from .state import resolve_pointer,active_atoms
    from .signals import eligible_control
    try:
        resolved=resolve_pointer(pointer,table)
        validity.validate_pointer(state,resolved,'REQUEST_CONTROL',latest=True)
    except ValueError as exc:
        raise CompletionTechnicalError(str(exc)) from None
    raw=state.sources.get(resolved.get('sourceKey'))
    if raw is None or not eligible_control(raw.text,resolved['exactExcerpt'],resolved['start']):
        raise CompletionTechnicalError('request_update_not_user_control')
    if any(c['sourceKey']==resolved['sourceKey'] and c['type'] in ('SKIP','UNCLEAR','REQUEST_STOP') and
            validity.overlaps_range((c['start'],c['start']+c['length']),[validity.interval(resolved)]) for c in state.controls):
        raise CompletionTechnicalError('request_update_conflicts_with_nondecision_signal')
    if any(a.source_key==resolved['sourceKey'] and validity.overlaps_range((a.start,a.start+a.length),
            [validity.interval(resolved)]) for a in active_atoms(state)):
        raise CompletionTechnicalError('request_control_overlaps_contribution')
    return resolved

def canonical_id(state,value,aliases=None):
    if value in state.request_registry: return value
    key=(aliases or {}).get(value)
    if key is None and value.startswith('p:'): key=value[2:]
    if key not in state.request_registry:
        raise CompletionTechnicalError('request_update_unknown_request')
    return key

def linked_answer(state,group,source):
    question=all_questions(state).get(source['questionCode'])
    if not question: return False
    return (question['questionCode'] in group['questionCodes'] and
        (question.get('rootControlId')==group['rootControlId'] or
         group['clarificationId'] in question.get('clarificationIds',[])))

def apply_updates(state,updates,table,aliases=None):
    """Validate the entire batch first; only the enclosing request draft is changed."""
    if not isinstance(updates,list) or len(updates)>4:
        raise CompletionTechnicalError('request_updates_capacity')
    targets=question_targets(state); staged=[]; per_request={}
    for update in updates:
        key=canonical_id(state,update['requestId'],aliases); request=state.request_registry[key]
        action=update['action']; fingerprint=canonical({**update,'requestId':key})
        prior=per_request.setdefault(key,[])
        if any(p['action']!=action or (action=='RESOLVE_TARGET' and p['targetPendingId']!=update['targetPendingId']) for p in prior):
            raise CompletionTechnicalError('request_updates_conflict')
        prior.append(update)
        existing=next((u for u in state.request_updates if u.get('inputDigest')==sha(fingerprint)),None)
        if existing:
            staged.append(None); continue
        source=proof(state,update['source'],table)
        original=request.get('source') or {}
        order={q.question_code:i for i,q in enumerate(state.history)}
        if original.get('sourceKey') not in state.current_sources.values() or order.get(source['questionCode'],-1)<order.get(original.get('questionCode'),0):
            raise CompletionTechnicalError('request_update_source_not_current_or_newer')
        value={'action':action,'requestId':key,'source':source,'inputDigest':sha(fingerprint)}
        if action=='RESOLVE_TARGET':
            target=update['targetPendingId']; group=state.request_clarifications.get(update['clarificationId'])
            if request.get('initialTargetPendingId') is not None:
                raise CompletionTechnicalError('resolution_cannot_change_fixed_target')
            if request.get('resolvedTargetPendingId'):
                if target==request['resolvedTargetPendingId'] and any(u['action']=='RESOLVE_TARGET' and u['requestId']==key and
                        u['clarificationId']==update['clarificationId'] and u['targetPendingId']==target for u in state.request_updates):
                    staged.append(None); continue
                raise CompletionTechnicalError('resolution_cannot_change_fixed_target')
            if not group or group['state']=='CLOSED' or key not in group['remainingRequestIds'] or not linked_answer(state,group,source):
                raise CompletionTechnicalError('target_resolution_question_request_binding')
            if target not in targets or not targets[target]['executable']:
                raise CompletionTechnicalError('resolution_target_not_executable')
            if order.get(original['questionCode'],-1)>=order.get(source['questionCode'],-1):
                raise CompletionTechnicalError('target_resolution_not_newer')
            value.update(targetPendingId=target,clarificationId=group['clarificationId'],targetBinding=target_binding(targets[target]))
        elif action=='CANCEL':
            kinds=update['kinds']
            if not kinds or len(kinds)!=len(set(kinds)) or not set(kinds)<=set(request.get('requestedKinds',[])):
                raise CompletionTechnicalError('cancel_requires_actual_requested_kinds')
            if any(fulfilled(state,key,kind) for kind in kinds):
                raise CompletionTechnicalError('delivery_cannot_be_relabelled_cancel')
            if all(cancelled(state,key,kind) for kind in kinds):
                staged.append(None); continue
            value['kinds']=sorted(kinds)
        else:
            raise CompletionTechnicalError('unknown_request_update')
        value['updateId']='RUP_'+sha(canonical(value))[:24]; staged.append(value)
    for value in staged:
        if value is None or any(u['updateId']==value['updateId'] for u in state.request_updates): continue
        state.request_updates.append(value)
        if value['action']=='RESOLVE_TARGET':
            state.request_registry[value['requestId']].update(resolvedTargetPendingId=value['targetPendingId'],
                targetBinding=deepcopy(value['targetBinding']),targetResolution=deepcopy(value),bindingStatus='BOUND')
        source=value['source']; projection=state.review_projections.get(source['questionCode'])
        if projection and projection['sourceKey']==source['sourceKey']:
            span={'start':source['start'],'length':source['length']}
            if span not in projection['controlRanges']: projection['controlRanges'].append(span)
    refresh_projection(state)

def refresh_projection(state):
    for request in state.request_registry.values():
        key=request['requestId']; source=request.get('source') or {}
        interpreted={c['type'] for c in state.controls if c.get('requestId')==key and c['type'] in KINDS}
        disposed=any(r.get('requestId')==key for r in state.presentation_receipts) or any(
            u['requestId']==key and u['action']=='CANCEL' for u in state.request_updates)
        if interpreted and not disposed and state.review_revisions.get(source.get('questionCode'))==source.get('sourceKey'):
            request['requestedKinds']=sorted(interpreted)
        request['kindStates']={kind:disposition(state,request['requestId'],kind) for kind in request.get('requestedKinds',[])}
        request['remainingKinds']=[k for k,v in request['kindStates'].items() if v=='OUTSTANDING']
    outstanding={r['requestId'] for r in active(state)}
    latest=state.history[-1].question_code if state.history else None
    suspended=state.stop_guidance_pending or any(e['status']=='ACTIVE' for e in state.episodes.values())
    for group in state.request_clarifications.values():
        if group['state']=='CLOSED': continue
        group['remainingRequestIds']=[key for key in group['requestIds'] if key in outstanding]
        if not group['remainingRequestIds']: group['state']='CLOSED'
        elif suspended: group['state']='SUSPENDED'
        elif any(target_id(state.request_registry[key]) or key not in outstanding for key in group['requestIds']):
            group['state']='ANSWERED_PARTIAL'
        elif latest in group['questionCodes']: group['state']='AWAITING_DECISION'
        else: group['state']='ASKED'
        group['latestAnswerSourceKey']=state.current_sources.get(latest) if latest in group['questionCodes'] else group.get('latestAnswerSourceKey')
    for code,old in state.request_target_questions.items():
        group=state.request_clarifications.get(old.get('clarificationId'))
        if group: old.update(status='RESOLVED' if group['state']=='CLOSED' else group['state'],state=group['state'],
            remainingRequestIds=list(group['remainingRequestIds']))
    for control in state.controls:
        if control['type'] in KINDS and control.get('requestId'):
            control['disposition']=disposition(state,control['requestId'],control['type'])
            control['fulfilled']=control['disposition']=='FULFILLED'

def control_plan(arguments,state,data,aliases,diagnostics):
    from . import planner
    from .contracts import Move
    ids=[canonical_id(state,key,aliases) for key in arguments['requestIds']]
    if not ids or len(ids)>2 or len(ids)!=len(set(ids)):
        raise CompletionTechnicalError('request_target_distinct_ids')
    stage=arguments['stage']; group=None
    if stage=='ASK_TARGET': clarification(state,ids)
    elif stage=='WAIT_FOR_DECISION':
        candidates=[g for g in state.request_clarifications.values() if g['state']!='CLOSED' and set(ids)<=set(g['remainingRequestIds'])]
        if len(candidates)!=1 or any(target_id(state.request_registry[key]) is not None for key in ids):
            raise CompletionTechnicalError('request_target_wait_requires_open_unresolved_group')
        if any(c['targetPendingId'] is not None for c in active(state)):
            raise CompletionTechnicalError('request_target_cannot_bypass_definite_request')
        group=candidates[0]
        latest=state.history[-1].question_code if state.history else None
        if latest not in group['questionCodes']:
            raise CompletionTechnicalError('request_target_wait_requires_actual_descendant_answer')
    else: raise CompletionTechnicalError('unknown_request_target_stage')
    message=TARGET_QUESTION if stage=='ASK_TARGET' else TARGET_WAIT
    plan=planner.bind(planner.build(Move.USER_DIRECTION,message,(),state,diagnostics,source='REQUEST_TARGET',requests=ids),
        scope='CONTROL',control_purpose='REQUEST_TARGET',existing=message)
    return plan.model_copy(update={'control_stage':stage,'root_control_id':group['rootControlId'] if group else None,
        'clarification_id':group['clarificationId'] if group else None})

def deliver(state,ids,target,mode,response_code,attempt_id,requested_kind=None):
    targets=question_targets(state); binding=targets[target]
    for key in dict.fromkeys(ids):
        request=state.request_registry[key]
        kinds={requested_kind} if requested_kind else {c['type'] for c in active(state) if c['requestId']==key and c['type']=='REQUEST_'+mode}
        if not kinds or None in kinds: raise CompletionTechnicalError('delivery_without_actual_request_kind')
        fulfilled_kinds=sorted(kind for kind in KINDS if not cancelled(state,key,kind)) if (
            binding['scope']=='SAFETY' and mode=='EXPLANATION') else ['REQUEST_'+mode]
        if any(disposition(state,key,kind)!='OUTSTANDING' for kind in kinds):
            raise CompletionTechnicalError('request_already_delivered')
        receipt={'requestId':key,'source':deepcopy(request['source']),'target':target_binding(binding),
            'requestedMode':sorted(kinds),'performedMode':mode,'fulfilledRequestKinds':fulfilled_kinds,
            'questionCode':response_code,'attemptId':str(attempt_id),'bindingStatus':'BOUND'}
        receipt['receiptId']='RCPT_'+sha(canonical([str(attempt_id),key,target,mode,response_code]))[:24]
        state.presentation_receipts.append(receipt)
    refresh_projection(state)

def migrate(state,diagnostics):
    targets=question_targets(state); questions=all_questions(state); migrated=[]
    for old in state.presentation_receipts:
        if old.get('bindingStatus') in ('BOUND','UNKNOWN_LEGACY_BINDING'):
            migrated.append(old); continue
        response=questions.get(old.get('questionCode'))
        target=(response.get('presentationTargetPendingId') or pending_id(response['targetQuestionCode'])) if response else None
        source=deepcopy(old.get('source'))
        control=next((c for c in state.controls if c['id']==old.get('controlId')),None)
        if source is None and control:
            raw=state.sources.get(control['sourceKey'])
            if raw:
                source={'address':raw.source_id,'revision':raw.revision,'sourceKey':raw.key,
                    'questionCode':raw.item.question_code,'start':control['start'],'length':control['length'],
                    'exactExcerpt':raw.text[control['start']:control['start']+control['length']]}
        if source and target in targets:
            key=identity(source,target)
            state.request_registry.setdefault(key,{'requestId':key,'source':source,'initialTargetPendingId':target,
                'resolvedTargetPendingId':None,'targetBinding':target_binding(targets[target]),
                'targetResolution':None,'bindingStatus':'BOUND'})
            mode=old['performedMode']; kind=old.get('requestedMode','REQUEST_'+mode)
            scope=targets[target]['scope']
            migrated.append({**old,'requestId':key,'source':source,'target':target_binding(targets[target]),
                'requestedMode':[kind] if isinstance(kind,str) else kind,'performedMode':mode,
                'fulfilledRequestKinds':sorted(KINDS) if scope=='SAFETY' and mode=='EXPLANATION' else ['REQUEST_'+mode],
                'bindingStatus':'BOUND','receiptId':old.get('receiptId') or 'RCPT_LEGACY_'+sha(canonical(old))[:24]})
        else:
            key='UNKNOWN_'+sha(canonical(old))[:24]
            if source is None and old.get('sourceKey') in state.sources:
                raw=state.sources[old['sourceKey']]
                source={'address':raw.source_id,'revision':raw.revision,'sourceKey':raw.key,
                    'questionCode':raw.item.question_code,'start':0,'length':len(raw.text)}
            unknown={**old,'requestId':key,'source':source,'bindingStatus':'UNKNOWN_LEGACY_BINDING'}
            state.request_registry[key]=unknown; migrated.append(unknown)
            diagnostics.emit('legacy_receipt_binding',status='UNKNOWN_LEGACY_BINDING',requestId=key)
    # Legacy fulfilled tuples without an actual response receipt are not erased or invented.
    for source_key,kind,code in state.fulfilled_controls:
        if kind not in KINDS or source_key not in state.sources:
            continue
        if any(r.get('source',{}).get('sourceKey')==source_key and kind in r.get('fulfilledRequestKinds',[]) for r in migrated if r.get('source')):
            continue
        # Older general controls can be recovered only with one genuine stored
        # response plan and one exact request span, not merely a fulfilled flag.
        controls=[c for c in state.controls if c['sourceKey']==source_key and c['type']==kind and c['target']==code]
        responses=[q for q in questions.values() if q.get('kind')==('E' if kind=='REQUEST_EXAMPLE' else 'X') and
            q.get('targetQuestionCode')==code and q['questionCode']!=code]
        target=pending_id(code)
        if len(controls)==1 and len(responses)==1 and target in targets:
            source=state.sources[source_key]; control=controls[0]
            pointer={'address':source.source_id,'revision':source.revision,'sourceKey':source_key,
                'questionCode':source.item.question_code,'start':control['start'],'length':control['length']}
            key=identity(pointer,target); mode='EXAMPLE' if kind=='REQUEST_EXAMPLE' else 'EXPLANATION'
            state.request_registry.setdefault(key,{'requestId':key,'source':pointer,'initialTargetPendingId':target,
                'resolvedTargetPendingId':None,'targetBinding':target_binding(targets[target]),
                'targetResolution':None,'bindingStatus':'BOUND'})
            migrated.append({'requestId':key,'source':pointer,'target':target_binding(targets[target]),
                'requestedMode':[kind],'performedMode':mode,'fulfilledRequestKinds':[kind],
                'questionCode':responses[0]['questionCode'],'bindingStatus':'BOUND',
                'receiptId':'RCPT_LEGACY_'+sha(canonical([key,responses[0]['questionCode']]))[:24]})
            continue
        source=state.sources[source_key]; key='UNKNOWN_'+sha(canonical([source_key,kind,code]))[:24]
        pointer={'address':source.source_id,'revision':source.revision,'sourceKey':source_key,
            'questionCode':code,'start':0,'length':len(source.text)}
        row={'requestId':key,'source':pointer,'bindingStatus':'UNKNOWN_LEGACY_BINDING','legacyFulfilledKind':kind}
        state.request_registry.setdefault(key,row)
        if not any(r.get('requestId')==key for r in migrated): migrated.append(row)
    state.presentation_receipts=migrated
    # Recover kinds from actual old controls and receipts, never from the current pending question.
    for request in state.request_registry.values():
        if 'requestedKinds' not in request:
            kinds={c['type'] for c in state.controls if c.get('requestId',c.get('id'))==request['requestId'] and c['type'] in KINDS}
            for receipt in migrated:
                if receipt.get('requestId')==request['requestId']:
                    raw=receipt.get('requestedMode',[])
                    kinds.update([raw] if isinstance(raw,str) else raw)
            request['requestedKinds']=sorted(kinds & KINDS)
    for code,old in state.request_target_questions.items():
        question=questions.get(code)
        if question is None:
            old['migrationStatus']='UNKNOWN_LEGACY_BINDING'; continue
        cid=old.get('clarificationId') or 'CLAR_'+sha(code)[:24]
        root=old.get('rootControlId') or 'CTRL_'+sha(code)[:24]
        old.update(clarificationId=cid,rootControlId=root)
        group=state.request_clarifications.setdefault(cid,{'clarificationId':cid,'rootControlId':root,
            'questionCode':code,'questionCodes':[code],'requestIds':list(old['requestIds']),
            'remainingRequestIds':list(old['requestIds']),'state':'CLOSED' if old.get('status')=='RESOLVED' else 'ASKED'})
        question.update(scope='CONTROL',controlPurpose='REQUEST_TARGET',rootControlId=root,clarificationId=cid)
        question.setdefault('clarificationIds',[cid]); question.setdefault('controlStage','ASK_TARGET')
        # Stored targetResolution is immutable history, not a newly accepted proof.
        for key in group['requestIds']:
            request=state.request_registry.get(key,{})
            resolution=request.get('targetResolution')
            if resolution and request.get('resolvedTargetPendingId') and not any(u['action']=='RESOLVE_TARGET' and u['requestId']==key for u in state.request_updates):
                value={'action':'RESOLVE_TARGET','requestId':key,'targetPendingId':request['resolvedTargetPendingId'],
                    'clarificationId':cid,'source':deepcopy(resolution['source']),'migrationStatus':'EXACT_STORED_RESOLUTION'}
                value['updateId']='RUP_LEGACY_'+sha(canonical(value))[:24]; state.request_updates.append(value)
        for receipt in state.presentation_receipts:
            response=questions.get(receipt.get('questionCode'))
            if receipt.get('requestId') not in group['requestIds'] or receipt.get('bindingStatus')!='BOUND' or response is None:
                continue
            if response.get('rootControlId') not in (None,root):
                group['migrationStatus']='UNKNOWN_LEGACY_BINDING'; continue
            response['rootControlId']=root
            response.setdefault('clarificationIds',[])
            if cid not in response['clarificationIds']: response['clarificationIds'].append(cid)
            if response['questionCode'] not in group['questionCodes']: group['questionCodes'].append(response['questionCode'])
    refresh_projection(state)

def safety_request(state,pointer,target,kind,table):
    # SAFETY has no normal reviews. Reuse only a unique same-act binding;
    # never bind a request to the question that happened to receive its text.
    matches=[r for r in state.request_registry.values() if r.get('source') and
        validity.binding(r['source'])==validity.binding(pointer) and
        validity.interval(r['source'])==validity.interval(pointer) and compatible(r,target)]
    if len(matches)>1: raise CompletionTechnicalError('ambiguous_safety_request_identity')
    request=register(state,pointer,target,
        prior=matches[0]['requestId'] if matches else None)
    # The occurrence source may be an older still-current outstanding act; only
    # requestUpdates' resolution/cancellation proof must be the latest answer.
    validity.validate_pointer(state,pointer,'REQUEST_CONTROL')
    record_kind(state,request,kind)
    if disposition(state,request['requestId'],kind)!='OUTSTANDING' or kind not in request.get('requestedKinds',[]):
        raise CompletionTechnicalError('request_already_delivered')
    return request

def clarification_ids(state):
    latest=state.history[-1].question_code if state.history else None
    # Suspension is not loss of lineage: the same normal action may explicitly resume,
    # and the SAFETY-only presentation contract also allows independently proved updates.
    return [key for key,g in state.request_clarifications.items() if g['state']!='CLOSED' and
        latest in g['questionCodes'] and any(target_id(state.request_registry[r]) is None for r in g['remainingRequestIds'])]
