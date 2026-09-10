"""Lossless raw memory and one source/event validity boundary.

No content extraction, coverage requirements, or semantic keyword routing.
All semantic events are proposed by the Agent; offsets and dependencies are
validated here and published only with the successful response.
"""
from copy import deepcopy
from cbt_q11.contracts import CbtAgentIdempotencyError, CompletionTechnicalError
from cbt_q11.diagnostics import canonical, sha

REVISION = 'simple-dialogue-1'

def empty():
    return dict(revision=REVISION, sources={}, current={}, questions={}, history=[],
                goals={}, requests={}, corrections=[], events=[], gap=None,
                safety={}, stop=None, terminal=None, rejectedCodes=[],
                pendingQuestion=None, legacySnapshot=None, restoration='COLD_PUBLIC_ONLY')

def require(condition, reason):
    if not condition:
        raise CompletionTechnicalError(reason)

def add_source(state, address, text, kind, **metadata):
    key = address + '@' + sha(text)
    found = next((s for s in state['sources'].values() if s['key'] == key), None)
    if found is None:
        identity = 'S' + str(len(state['sources']) + 1)
        found = dict(sourceId=identity, ordinal=len(state['sources'])+1, key=key, address=address, text=text,
                     revision=sha(text), kind=kind, **metadata)
        state['sources'][identity] = found
    state['current'][address] = found['sourceId']
    return found['sourceId']

def receive(state, request):
    state = deepcopy(state) if state else empty()
    require(state['revision'] == REVISION, 'checkpoint_revision_requires_migration')
    sid, rid = request.session_id, request.record.record_id
    if state.get('identity') not in (None, [sid, rid]):
        raise CbtAgentIdempotencyError('session_record_binding')
    state['identity'] = [sid, rid]
    record = request.record.model_dump(by_alias=True, mode='json')
    for field in ('situation', 'automaticThought'):
        value = record.pop(field)
        record[field + 'SourceId'] = add_source(state, f'record:{rid}:{field}', value,
                                                'USER_RECORD', field=field) if value else None
    state['record'] = record
    history = []
    for q in getattr(request, 'question_answers', []):
        raw = q.model_dump(by_alias=True, mode='json')
        old = state['questions'].get(q.question_code)
        if old and any(old[k] != raw[k] for k in ('question', 'questionPurpose')):
            raise CbtAgentIdempotencyError('question_identity_conflict')
        state['questions'].setdefault(q.question_code, {k:raw[k] for k in
            ('questionCode', 'question', 'questionPurpose', 'semanticRouteType')})
        source = add_source(state, f'session:{sid}:record:{rid}:answer:{q.question_code}',
            q.answer, 'USER_ANSWER', questionCode=q.question_code) if q.answer is not None else None
        history.append(dict(questionCode=q.question_code, answerSourceId=source,
                            askedAt=raw['askedAt'], answeredAt=raw['answeredAt']))
    state['history'] = history
    state['rejectedCodes'] = list(dict.fromkeys(state['rejectedCodes'] + [x.code.value
        for x in getattr(request, 'before_distortions', []) if x.review_status.value == 'REJECTED']))
    refresh(state)
    return state

def source(state, identity, *, current=True):
    value = state['sources'].get(identity)
    require(value is not None, 'unknown_source')
    require(not current or state['current'].get(value['address']) == identity, 'archived_source')
    return value

def span(state, identity, quote, occurrence=None, *, valid=True, current=True):
    value = source(state, identity, current=current)
    require(isinstance(quote, str) and bool(quote.strip()), 'empty_quote')
    positions = [i for i in range(len(value['text'])) if value['text'].startswith(quote, i)]
    require(bool(positions), 'quote_not_exact')
    if occurrence is None:
        require(len(positions) == 1, 'ambiguous_quote_occurrence')
        occurrence = 0
    require(type(occurrence) is int and 0 <= occurrence < len(positions), 'invalid_occurrence')
    start = positions[occurrence]
    pointer = dict(sourceId=identity, start=start, end=start + len(quote))
    if valid:
        require(valid_pointer(state, pointer), 'withdrawn_quote')
    return pointer

def valid_pointer(state, pointer):
    s = state['sources'].get(pointer['sourceId'])
    if not s or state['current'].get(s['address']) != s['sourceId']:
        return False
    return not any(a < pointer['end'] and pointer['start'] < b
                   for a, b in state.get('withdrawn', {}).get(s['sourceId'], []))

def source_valid(state, identity):
    s = source(state, identity)
    intervals = state.get('withdrawn', {}).get(identity, [])
    return sum(b-a for a,b in intervals) < len(s['text'])

def references(state, identities):
    require(len(identities) == len(set(identities)), 'duplicate_source_reference')
    for identity in identities:
        require(source_valid(state, identity), 'fully_withdrawn_source')

def edit_intervals(intervals, start, end, retract):
    if retract:
        intervals = sorted(intervals + [[start, end]])
        out = []
        for a,b in intervals:
            if out and a <= out[-1][1]: out[-1][1] = max(out[-1][1], b)
            else: out.append([a,b])
        return out
    out = []
    for a,b in intervals:
        if b <= start or a >= end: out.append([a,b])
        else:
            if a < start: out.append([a,start])
            if b > end: out.append([end,b])
    return out

def refresh(state):
    # A correction is a past-to-future event. Later correction of its instruction
    # disables its force. Recompute in reverse source chronology without inventing
    # replacement facts; keep irreversible derived receipts separately.
    def masks(events):
        result={}
        for index,c in sorted(events):
            t=c['target']
            result[t['sourceId']]=edit_intervals(result.get(t['sourceId'],[]),t['start'],t['end'],c['operation']=='RETRACT')
        return result
    groups={}; active=[]
    for index,c in enumerate(state['corrections']):
        s=state['sources'][c['instruction']['sourceId']]
        if state['current'].get(s['address'])==s['sourceId']:
            groups.setdefault(s['ordinal'],[]).append((index,c))
    for ordinal in sorted(groups,reverse=True):
        later=masks(active)
        for index,c in groups[ordinal]:
            p=c['instruction']
            if not any(a<p['end'] and p['start']<b for a,b in later.get(p['sourceId'],[])):
                active.append((index,c))
    withdrawn=masks(active)
    state['withdrawn'] = withdrawn
    for goal in state['goals'].values():
        if goal.get('dependency') and not valid_pointer(state, goal['dependency']):
            goal.update(status='AWAITING_ANSWER', invalidated=True)
    for req in state['requests'].values():
        if not valid_pointer(state, req['origin']):
            req.update(status='INVALIDATED', invalidated=True)
    gap=state['gap']
    if gap and gap.get('answerDependency') and not valid_pointer(state, gap['answerDependency']):
        gap.update(answerState='AWAITING_ANSWER', invalidated=True)
    for episode in state['safety'].values():
        if episode.get('resolution') and not valid_pointer(state, episode['resolution']):
            episode.update(status='ACTIVE', resolutionInvalidated=True)
    if state['terminal']:
        pointers=state['terminal'].get('pointers', [])
        if any(not valid_pointer(state,p) for p in pointers):
            state['terminal']['invalidated']=True

def question(state, code):
    require(code in state['questions'], 'unknown_target_question')
    return state['questions'][code]

def whole(state, identity):
    s=source(state, identity)
    return dict(sourceId=identity, start=0, end=len(s['text']))

def new_request(state, event):
    p=span(state,event['sourceId'],event['quote'])
    target=event['targetQuestionCode']
    if target is not None: question(state,target)
    for req in state['requests'].values():
        if req['origin']==p and req['kind']==event['kind'] and req['targetQuestionCode']==target:
            return req
    identity='R'+str(len(state['requests'])+1)
    req=dict(requestId=identity, origin=p, kind=event['kind'], targetQuestionCode=target, status='PENDING', deliveries=[])
    state['requests'][identity]=req
    return req

def updates(state, raw):
    for event in (raw or {}).get('events', []):
        kind=event['type']
        sid=event['sourceId']
        source(state,sid)
        if kind=='CORRECTION':
            instruction=span(state,sid,event['instructionQuote'])
            target_source=source(state,event['targetSourceId'],current=False)
            require(state['sources'][sid]['ordinal']>target_source['ordinal'], 'correction_must_follow_target')
            target=span(state,target_source['sourceId'],event['targetQuote'],event['occurrence'],valid=False,current=False) if event['targetQuote'] is not None else dict(sourceId=target_source['sourceId'],start=0,end=len(target_source['text']))
            require(event['targetQuote'] is not None or event['occurrence'] is None,'whole_correction_occurrence')
            c=dict(operation=event['operation'],instruction=instruction,target=target)
            if c not in state['corrections']: state['corrections'].append(c)
        elif kind=='GOAL':
            code=event['questionCode']; q=question(state,code)
            goal=state['goals'].setdefault(code,dict(questionCode=code,status='OPEN'))
            goal.update(status='OPEN' if event['status']=='REOPEN' else event['status'],
                        dependency=whole(state,sid),note=event['note'],invalidated=False)
            gap=state['gap']
            if gap and (code==gap['questionCode'] or q.get('gapId')==gap['gapId']):
                require(source(state,sid).get('questionCode') in state['questions'], 'gap_answer_source_kind')
                actual=question(state,source(state,sid)['questionCode'])
                require(actual.get('gapId')==gap['gapId'] or actual['questionCode']==gap['questionCode'], 'gap_answer_target_mismatch')
                gap.update(answerState='AWAITING_ANSWER' if event['status']=='REOPEN' else 'ANSWERED',
                           disposition=event['status'],answerDependency=whole(state,sid),invalidated=False)
        elif kind=='REQUEST': new_request(state,event)
        elif kind=='REQUEST_UPDATE':
            req=state['requests'].get(event['requestId'])
            require(req is not None and req['status']=='PENDING','request_not_pending')
            if event['operation']=='CANCEL': req['status']='CANCELLED'
            else:
                if event['targetQuestionCode'] is not None: question(state,event['targetQuestionCode'])
                req['targetQuestionCode']=event['targetQuestionCode']
            req.setdefault('changes',[]).append(deepcopy(event))
        elif kind=='RESUME':
            target=state['stop'] if event['scope']=='DIALOGUE' else state['safety'].get(event['targetId'])
            require(target is not None and target['id']==event['targetId'] and target['status']=='ACTIVE','resume_target_not_active')
            target.update(status='RESOLVED',resolution=whole(state,sid),reason=event['reason'])
        state['events'].append(deepcopy(event))
        refresh(state)

def view(state, remaining=3):
    sources=[]
    for s in sorted(state['sources'].values(),key=lambda x:x['ordinal']):
        sources.append({k:v for k,v in s.items() if k not in ('key','address')} | {
            'current':state['current'].get(s['address'])==s['sourceId'],
            'withdrawnRanges':state.get('withdrawn',{}).get(s['sourceId'],[])})
    conversation=[dict(**state['questions'][h['questionCode']],answerSourceId=h['answerSourceId']) for h in state['history']]
    if state['pendingQuestion'] and (not conversation or conversation[-1]['questionCode']!=state['pendingQuestion']):
        conversation.append(dict(**state['questions'][state['pendingQuestion']],answerSourceId=None))
    result=dict(record=deepcopy(state['record']),sources=sources,conversation=conversation,
        dialogue=dict(currentQuestionCode=state['pendingQuestion'],goals=deepcopy(list(state['goals'].values())),
            requests=deepcopy(list(state['requests'].values())),corrections=deepcopy(state['corrections']),
            rejectedCodes=state['rejectedCodes'],restoration=state['restoration']),
        budget=dict(generationRemaining=remaining,gapUsed=state['gap'] is not None))
    for k in ('gap','safety','stop'):
        if state[k]: result[k]=deepcopy(state[k])
    return result

def migrate_legacy(data,request):
    """Translate actual stored receipts; preserve all unknown fields verbatim.

    This does not run old semantic policies or manufacture SELECT reviews.
    Missing old receipt bindings remain explicit migration blockers, not fresh
    gap/safety allowances or invented historical approvals.
    """
    state=empty(); state['legacySnapshot']=deepcopy(data)
    state['restoration']='LEGACY_MIGRATED'; aliases={}
    for key,s in data.get('record_sources',{}).items():
        sid=add_source(state,'record:'+str(request.record.record_id)+':'+s['field'],s['text'],'USER_RECORD',field=s['field'])
        aliases[key]=sid
    old_sources=data.get('sources',{})
    for key,s in old_sources.items():
        item=s['item']; code=item['questionCode']
        sid=add_source(state,f'session:{request.session_id}:record:{request.record.record_id}:answer:{code}',
                       item['answer'],'USER_ANSWER',questionCode=code)
        aliases[key]=sid
    for key in list(data.get('current_sources',{}).values())+list(data.get('current_record_sources',{}).values()):
        if key in aliases:
            s=state['sources'][aliases[key]];state['current'][s['address']]=s['sourceId']
    for q in data.get('prior_questions',[])+([data['pending_question']] if data.get('pending_question') else []):
        state['questions'][q['questionCode']]=deepcopy(q)
    for q in data.get('history',[]):
        state['questions'].setdefault(q['questionCode'],{k:q[k] for k in ('questionCode','question','questionPurpose','semanticRouteType')})
    state['pendingQuestion']=(data.get('pending_question') or {}).get('questionCode')
    def pointer(p):
        if not p: return None
        key=p.get('sourceKey') or p['address']+'@'+p['revision']
        require(key in aliases,'legacy_pointer_source_missing')
        return dict(sourceId=aliases[key],start=p['start'],end=p['start']+p['length'])
    for e in data.get('retraction_history',[]):
        require(e['correctionSourceKey'] in aliases,'legacy_correction_origin_missing')
        state['corrections'].append(dict(operation=e['action'],
            instruction=dict(sourceId=aliases[e['correctionSourceKey']],start=e['start'],end=e['start']+e['length']),target=pointer(e['target'])))
    for g in data.get('goals',{}).values():
        for code in g.get('questionCodes',[]):
            state['goals'][code]=dict(questionCode=code,status=g.get('status','OPEN'),focus=g.get('focus'),legacyReceipt=deepcopy(g))
    from cbt_q11.requests import pending_id
    pending={pending_id(code):code for code in state['questions']}
    for key,r in data.get('request_registry',{}).items():
        target=r.get('resolvedTargetPendingId') or r.get('initialTargetPendingId')
        for kind in r.get('requestedKinds',[]):
            identity='R'+str(len(state['requests'])+1)
            receipts=[x for x in data.get('presentation_receipts',[]) if x.get('requestId')==key and kind in x.get('fulfilledRequestKinds',[])]
            cancelled=any(x.get('requestId')==key and x.get('action')=='CANCEL' and kind in x.get('kinds',[]) for x in data.get('request_updates',[]))
            state['requests'][identity]=dict(requestId=identity,origin=pointer(r['source']),kind=kind.removeprefix('REQUEST_'),
                targetQuestionCode=pending.get(target),status='FULFILLED' if receipts else ('CANCELLED' if cancelled else 'PENDING'),deliveries=deepcopy(receipts),legacyReceipt=deepcopy(r))
    gap=data.get('pending_fact_boundary')
    if not gap and data.get('gap_registry'): gap=list(data['gap_registry'].values())[-1]
    if gap:
        projection=gap.get('answerProjection')
        bindings=(projection or {}).get('bindings',[])
        state['gap']=dict(gapId=gap.get('gapId','LEGACY_GAP'),questionCode=gap.get('rootQuestionCode') or gap.get('questionCode'),
            question=gap.get('question'),used=True,answerState='ANSWERED' if projection else 'AWAITING_ANSWER',
            answerDependency=pointer(bindings[0]) if bindings else None,legacyReceipt=deepcopy(gap))
        for q in state['questions'].values():
            if q.get('rootGapQuestionCode')==state['gap']['questionCode'] or q['questionCode']==state['gap']['questionCode']:
                q['gapId']=state['gap']['gapId']
    elif data.get('fact_boundary_question_used') or data.get('gap_usage'):
        state['gap']=dict(gapId='LEGACY_GAP',questionCode=None,answerState='UNKNOWN',used=True,legacyUsage=deepcopy(data.get('gap_usage')))
    for eid,e in data.get('episodes',{}).items():
        resolution=e.get('resolutionEvidence') or []
        state['safety'][eid]=dict(id=eid,status=e['status'],trigger=pointer(e['primaryTrigger']),
            clarificationUsed=e.get('clarificationUsed',False),history=[],legacyReceipt=deepcopy(e))
        if isinstance(resolution,list) and resolution:
            state['safety'][eid]['resolution']=pointer(resolution[0])
    if data.get('stop_guidance_pending'): state['stop']=dict(id='LEGACY_STOP',status='ACTIVE')
    state['rejectedCodes']=list(data.get('rejected_codes',[]))
    refresh(state)
    return state
