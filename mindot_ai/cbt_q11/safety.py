"""Safety meaning and urgency are distinct from provider availability."""
import asyncio
from dataclasses import dataclass, asdict, replace
import re
from time import perf_counter
from .contracts import CompletionTechnicalError
from .llm import client, remaining_timeout, token_count, budget

@dataclass(frozen=True)
class SafetyDecision:
    action: str
    reason: str | None = None
    level: str = 'NONE'
    evidence: tuple = ()
    unresolved: str | None = None
    primary_trigger: dict | None = None
    clarification_goal: str | None = None
    episode_id: str | None = None
    concern: str = 'SELF_HARM'
    def dump(self):
        return asdict(self)
def latest_user(request):
    history=getattr(request,'question_answers',[])
    return history[-1].answer if history else '\n'.join(x for x in (request.record.situation,request.record.automatic_thought) if x)
def detector(text):
    for clause in re.split(r'[.!?\n]|하지만|그런데',text or ''):
        if re.search(r'아니|않|없|예전|과거|어제|가정|만약|라면|라고|라는|인용|소설|영화|수업|친구|동료가|그가|그녀|["“”]',clause):
            continue
        if not re.search(r'지금|당장',clause):
            continue
        for reason,pattern in [
            ('IMMEDIATE_DANGER',r'(?:약을|약물).*(?:많이\s*삼켰|한꺼번에\s*먹었)|피가\s*멈추지'),
            ('SUICIDE',r'(?:옥상|난간).*(?:뛰어내리려|뛰어내리고)|목을\s*매고\s*있'),
            ('SELF_HARM',r'(?:칼|면도날).*(?:손목|팔).*(?:긋고\s*있|베고\s*있)'),
            ('HARM_TO_OTHERS',r'(?:칼|총).*(?:찌르고\s*있|쏘려)')]:
            if re.search(pattern,clause):
                return SafetyDecision('STOP',reason,'CRISIS',primary_trigger={'start':text.index(clause),
                    'length':len(clause),'exactExcerpt':clause})
    return None

def detect_request(request):
    from .diagnostics import sha
    from .state import source_revision
    history=getattr(request,'question_answers',[])
    if history:
        latest=history[-1]
        candidates=[(f'SESSION_{request.session_id}_RECORD_{request.record.record_id}_ANSWER_'+latest.question_code,
                     latest.answer,source_revision(latest),latest.question_code)]
    else:
        candidates=[(f'RECORD_{request.record.record_id}_'+name,text,sha(text)[:20],None)
            for name,text in [('situation',request.record.situation),('automaticThought',request.record.automatic_thought)] if text]
    for address,text,revision,code in candidates:
        hit=detector(text)
        if hit:
            return replace(hit,primary_trigger={**hit.primary_trigger,'address':address,'revision':revision,
                'sourceKey':address+'@'+revision if code else None,'questionCode':code})
    return None
async def moderate(text,diagnostics,injected=None):
    budget().admit_moderation({'model':'omni-moderation-latest','input':text})
    diagnostics.emit('moderation_invocation',attempt=1,model='omni-moderation-latest',
        input=text,inputCharacters=sum(map(len,text)) if isinstance(text,list) else len(text),
        inputTokens=token_count(text),scope='ALL_READABLE_USER_SOURCES',cost='separate_from_generic_usage')
    start=perf_counter()
    try:
        endpoint=injected if injected is not None else client()
        response=await asyncio.wait_for(endpoint.moderations.create(model='omni-moderation-latest',input=text),remaining_timeout(15))
    except asyncio.CancelledError:
        diagnostics.emit('moderation_no_response',error='CancelledError',providerExecution='UNKNOWN',billing='UNKNOWN')
        raise
    except Exception as exc:
        diagnostics.emit('moderation_unavailable',error=type(exc).__name__,httpStatus=getattr(exc,'status_code',None),
            providerExecution='UNKNOWN',billing='UNKNOWN')
        return {'available':False,'positive':None}
    raw=response.model_dump(mode='json') if hasattr(response,'model_dump') else response
    diagnostics.count('moderation_response')
    diagnostics.emit('moderation_response',raw=raw,latencyMs=(perf_counter()-start)*1000)
    try:
        items=raw['results']; related=['self-harm','self-harm/intent','self-harm/instructions','violence','harassment/threatening','illicit/violent']
        if len(items)!=(len(text) if isinstance(text,list) else 1):
            raise ValueError('moderation_result_count')
        return {'available':True,'scope':'ALL_READABLE_USER_SOURCES','positive':any(item['categories'].get(k,False) for item in items for k in related),
                'categoriesBySource':[{k:item['categories'].get(k,False) for k in related} for item in items]}
    except (KeyError,IndexError,TypeError,ValueError):
        return {'available':False,'positive':None}

class SafetyConflict(CompletionTechnicalError):
    """Only this allowlisted conflict may request SAFETY_RECHECK."""
    pass


def migrate(state):
    from .diagnostics import canonical, sha
    if state.episodes:
        return
    prior=[*state.safety_episodes,*([state.pending_safety] if state.pending_safety else [])]
    for old in prior:
        primary=old.get('primaryTrigger')
        if not primary:
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_episode_origin')
        concern=old.get('concern','SELF_HARM')
        identity=old.get('episodeId') or 'EP_'+sha(canonical([primary,concern]))[:24]
        status='RESOLVED' if old.get('resolved') is True else 'ACTIVE'
        state.episodes[identity]={**old,'episodeId':identity,'primaryTrigger':primary,'concern':concern,
            'status':status,'clarificationUsed':bool(old.get('clarificationUsed'))}
        if status=='ACTIVE':
            state.focus_episode_id=identity
    sync_focus(state)


def sync_focus(state):
    active=[e for e in state.episodes.values() if e['status']=='ACTIVE']
    focused=state.episodes.get(state.focus_episode_id)
    if not focused or focused['status']!='ACTIVE':
        focused=active[-1] if active else None
        state.focus_episode_id=focused['episodeId'] if focused else None
    state.pending_safety=focused.copy() if focused else None
    state.safety_clarification_count=int(bool(focused and focused['clarificationUsed']))


def active_episodes(state):
    return [e for e in state.episodes.values() if e['status']=='ACTIVE']


def episode_bindings(episode):
    """The current callable's exact safety context, not every audit receipt."""
    return [episode['primaryTrigger'],*episode.get('originAliases',[]),
        *episode.get('resolutionContext',[]),*episode.get('resolutionEvidence',[]),
        *episode.get('reopenContext',[])]


def review_catalog_required(state):
    from .state import answers_to_review
    return bool(answers_to_review(state))


def catalog(state,table):
    """An action ID is offered only alongside every referenced safety source."""
    from copy import deepcopy
    from .views import pointers
    from .diagnostics import canonical, sha
    readable={(s['address'],s['revision']) for s in table}
    actionable=[]; omitted=[]
    require_resolved=review_catalog_required(state)
    for episode in state.episodes.values():
        bindings=episode_bindings(episode)
        missing=[p for p in bindings if (p['address'],p['revision']) not in readable]
        if missing:
            if episode['status']=='ACTIVE' or require_resolved and episode['status']=='RESOLVED':
                raise CompletionTechnicalError('active_safety_context_not_readable')
            omitted.append({'kind':'SAFETY_EPISODE','id':episode['episodeId'],
                'reason':'HISTORICAL_SOURCE_NOT_SELECTED'})
            continue
        view={k:deepcopy(v) for k,v in episode.items() if k not in ('resolutionHistory','reopenHistory')}
        view.setdefault('initialStatus','UNKNOWN_LEGACY')
        for key in ('resolutionHistory','reopenHistory'):
            view[key+'Count']=len(episode.get(key,[]))
            view[key+'Sha256']=sha(canonical(episode.get(key,[])))
        actionable.append(pointers(view))
    return {'knownEpisodeIds':list(state.episodes),'actionableEpisodes':actionable,
        'omittedCatalogItems':omitted}


def resolution_evidence(state,origin,contexts,episode=None):
    from .validity import validate_pointer
    evidence=[]
    order={item.question_code:i for i,item in enumerate(state.history)}
    reopened_after=max((order.get(p.get('questionCode'),-1) for p in (episode or {}).get('reopenContext',[])),default=-1)
    for pointer in contexts:
        if (pointer['address'],pointer['revision'])==(origin['address'],origin['revision']) and (
                origin['start']<=pointer['start'] and pointer['start']+pointer['length']<=origin['start']+origin['length']):
            continue
        try:
            validate_pointer(state,pointer,'SAFETY_RESOLUTION')
        except ValueError:
            continue  # Historical context is readable but cannot authorize clearance.
        if episode and episode.get('resolutionStatus')=='REVIEW_REQUIRED' and reopened_after>=0 and (
                order.get(pointer.get('questionCode'),-1)<=reopened_after):
            continue  # Reopening requires a genuinely subsequent USER clearance.
        evidence.append(pointer)
    if not evidence:
        raise CompletionTechnicalError('safety_clearance_requires_current_valid_resolution')
    return evidence


def refresh_resolutions(state):
    """Editing/retracting clearance reopens the SAME episode and consumed budget."""
    from .validity import validate_pointer
    for episode in state.episodes.values():
        # A safety-only reopen invalidates the clearance meaning, even while
        # its exact historical span has not yet been reviewed as a correction.
        # Only apply_review with a newly validated resolution can clear it.
        if episode.get('resolutionStatus')=='REVIEW_REQUIRED':
            episode['status']='ACTIVE'
            continue
        if episode['status']!='RESOLVED':
            continue
        evidence=episode.get('resolutionEvidence')
        if evidence is None:
            try:
                evidence=resolution_evidence(state,episode['primaryTrigger'],episode.get('resolutionContext',[]))
                episode['resolutionEvidence']=evidence
            except (ValueError,CompletionTechnicalError):
                evidence=[]
        current=bool(evidence)
        for pointer in evidence:
            try:
                validate_pointer(state,pointer,'SAFETY_RESOLUTION')
            except ValueError:
                current=False
        if not current:
            archive_resolution(episode)
            episode['status']='ACTIVE'
            episode['resolutionStatus']='REVIEW_REQUIRED'
    sync_focus(state)


def contexts_for(origin,pointers,table):
    from .state import resolve_pointer
    from .diagnostics import canonical
    contexts=[resolve_pointer(p,table) for p in pointers]
    if not contexts or len({canonical(p) for p in contexts})!=len(contexts):
        raise CompletionTechnicalError('invalid_safety_context')
    if all(p['address']==origin['address'] and p['revision']==origin['revision'] and
           origin['start']<=p['start'] and p['start']+p['length']<=origin['start']+origin['length'] for p in contexts):
        raise SafetyConflict('ORIGIN_ONLY_CLEARANCE')
    return contexts


def evaluate_review(review,state,table,episode_slots,moderation):
    from .state import resolve_pointer
    from .provider_schemas import safety_review
    from .llm import validate_schema
    try:
        validate_schema(review,safety_review([s['sourceId'] for s in table],list(episode_slots)))
    except ValueError as exc:
        raise CompletionTechnicalError('safety_review_schema:'+str(exc)) from None
    expected={e['episodeId'] for e in active_episodes(state)}
    if {binding['episodeId'] for binding in episode_slots.values()}!=expected:
        raise CompletionTechnicalError('active_episode_binding_mismatch')
    resolutions=[]; conflicts=[]
    for key,binding in episode_slots.items():
        episode=state.episodes[binding['episodeId']]
        from .views import pointers
        if pointers(episode['primaryTrigger'])!=pointers(binding['origin']):
            raise CompletionTechnicalError('stale_episode_origin')
        supplied=review['episodes'][key]
        try:
            contexts=contexts_for(episode['primaryTrigger'],supplied['contextReferences'],table)
        except SafetyConflict as exc:
            conflicts.append(str(exc))
            contexts=[resolve_pointer(p,table) for p in supplied['contextReferences']]
        evidence=[] if conflicts else resolution_evidence(state,episode['primaryTrigger'],contexts,episode)
        resolutions.append({'episodeId':episode['episodeId'],'clearance':supplied['clearance'],
            'contextReferences':contexts,'resolutionEvidence':evidence})
    current=review['current']
    if current['basis']=='CONTEXT_CLEARED':
        origin=resolve_pointer(current['primaryTrigger'],table)
        if state.history and origin['sourceKey']!=state.current_sources[state.history[-1].question_code]:
            raise CompletionTechnicalError('current_clearance_requires_current_source')
        if not state.history and not any(s['kind']=='USER_RECORD' and s['address']==origin['address'] and s['revision']==origin['revision'] for s in table):
            raise CompletionTechnicalError('current_clearance_requires_current_record')
        try:
            contexts=contexts_for(origin,current['contextReferences'],table)
        except SafetyConflict as exc:
            conflicts.append(str(exc))
            contexts=[resolve_pointer(p,table) for p in current['contextReferences']]
        if not conflicts:
            resolution_evidence(state,origin,contexts)
    elif moderation.get('available'):
        current_code=state.history[-1].question_code if state.history else None
        categories=moderation.get('categoriesBySource',[])
        indices=[i for i,s in enumerate(table) if (s['kind']=='USER_ANSWER' and s['questionCode']==current_code)
            or (current_code is None and s['kind']=='USER_RECORD')]
        if any(i<len(categories) and any(categories[i].get(k) for k in (
                'self-harm','self-harm/intent','self-harm/instructions','violence','harassment/threatening','illicit/violent')) for i in indices):
            conflicts.append('MODERATION_CURRENT_SIGNAL_CONFLICT')
    if conflicts:
        failure=SafetyConflict('|'.join(dict.fromkeys(conflicts)))
        raise failure
    return {'resolutions':resolutions}


def apply_review(state,result):
    from copy import deepcopy
    for resolution in result['resolutions']:
        episode=state.episodes[resolution['episodeId']]
        archive_resolution(episode)
        episode.update(status='RESOLVED',clearance=resolution['clearance'],
            resolutionContext=deepcopy(resolution['contextReferences']),
            resolutionEvidence=deepcopy(resolution['resolutionEvidence']),resolutionStatus='CURRENT')
    sync_focus(state)


def register_episode(state,primary,concern):
    from uuid import uuid4
    from .validity import overlaps
    for episode in state.episodes.values():
        origins=[episode['primaryTrigger'],*episode.get('originAliases',[])]
        if episode['concern']==concern and any(old['address']==primary['address'] and old['revision']==primary['revision'] and
                overlaps(old['start'],old['length'],primary['start'],primary['length']) for old in origins):
            raise CompletionTechnicalError('overlapping_episode_requires_existing_id')
    identity='EP_'+uuid4().hex
    episode={'episodeId':identity,'primaryTrigger':primary,'concern':concern,'status':'ACTIVE',
        'initialStatus':'ACTIVE','clarificationUsed':False,'unresolved':None}
    state.episodes[identity]=episode
    return episode


def archive_resolution(episode):
    """Preserve the old accepted clearance as history, never as a new proof."""
    from copy import deepcopy
    from .diagnostics import canonical, sha
    if not episode.get('resolutionEvidence') and not episode.get('resolutionContext'):
        return
    value={k:deepcopy(episode.get(k)) for k in ('clearance','resolutionContext','resolutionEvidence')}
    value['resolutionId']='SRES_'+sha(canonical(value))[:24]
    history=episode.setdefault('resolutionHistory',[])
    if not any(old['resolutionId']==value['resolutionId'] for old in history):
        history.append(value)


def reopen_episode(state,episode,contexts):
    from copy import deepcopy
    from .validity import validate_pointer
    from .diagnostics import canonical, sha
    latest=state.current_sources.get(state.history[-1].question_code) if state.history else None
    current=[p for p in contexts if p.get('sourceKey')==latest and latest is not None]
    if not current:
        raise CompletionTechnicalError('resolved_episode_reopen_requires_current_user_context')
    for pointer in current:
        try:
            # Current/exact/nonwithdrawn is required; the Agent decides whether
            # this is uncertainty/retraction. It is not accepted CBT extraction.
            validate_pointer(state,pointer,'SAFETY_RESOLUTION')
        except ValueError as exc:
            raise CompletionTechnicalError('invalid_episode_reopen_context:'+str(exc)) from None
    archive_resolution(episode)
    row={'sourceReferences':deepcopy(list(contexts)),'previousStatus':episode['status'],
        'previousResolutionStatus':episode.get('resolutionStatus'),
        'sourceReviewAccepted':False}
    row['reopenId']='SREOPEN_'+sha(canonical([episode['episodeId'],current]))[:24]
    history=episode.setdefault('reopenHistory',[])
    if not any(old['reopenId']==row['reopenId'] for old in history):
        history.append(row)
    episode.update(status='ACTIVE',resolutionStatus='REVIEW_REQUIRED',reopenContext=deepcopy(list(contexts)))


def resolve_directive(raw,state,table):
    from .state import resolve_pointer
    origin=raw['origin']
    contexts=tuple(resolve_pointer(p,table) for p in raw['contextReferences'])
    if 'existingEpisodeId' in origin:
        episode=state.episodes.get(origin['existingEpisodeId'])
        if not episode or episode['concern']!=raw['concern']:
            raise CompletionTechnicalError('unknown_or_mismatched_episode')
        # Reopening a previously resolved actual episode never grants a new clarification.
        if episode['status']=='RESOLVED':
            reopen_episode(state,episode,contexts)
        else:
            episode['status']='ACTIVE'
    else:
        primary=resolve_pointer(origin['newTrigger'],table)
        from .validity import validate_pointer
        try:
            validate_pointer(state,primary,'SAFETY_ORIGIN')
        except ValueError as exc:
            raise CompletionTechnicalError('invalid_safety_origin:'+str(exc)) from None
        episode=register_episode(state,primary,raw['concern'])
    state.focus_episode_id=episode['episodeId']
    if raw['type']=='STOP':
        episode['level']='CRISIS' if raw['urgency']=='IMMEDIATE' else 'REVIEW'
        return SafetyDecision('STOP','SELF_HARM' if raw['concern']=='SELF_HARM' else 'HARM_TO_OTHERS',
            episode['level'],contexts,primary_trigger=episode['primaryTrigger'],episode_id=episode['episodeId'],concern=raw['concern'])
    repeated=episode['clarificationUsed']
    return SafetyDecision('STOP' if repeated else 'CLARIFY','AMBIGUOUS_SAFETY_SIGNAL','REVIEW',contexts,
        raw['clarificationGoal'],episode['primaryTrigger'],raw['clarificationGoal'],episode['episodeId'],raw['concern'])


def register_emergency(state,decision):
    from .validity import overlaps
    concern='HARM_TO_OTHERS' if decision.reason=='HARM_TO_OTHERS' else 'SELF_HARM'
    primary=decision.primary_trigger
    found=next((e for e in state.episodes.values() if e['concern']==concern and
        any(p['address']==primary['address'] and p['revision']==primary['revision'] and
            overlaps(p['start'],p['length'],primary['start'],primary['length'])
            for p in [e['primaryTrigger'],*e.get('originAliases',[])])),None)
    episode=found or register_episode(state,primary,concern)
    episode.update(status='ACTIVE',level=decision.level)
    state.focus_episode_id=episode['episodeId']
    return replace(decision,episode_id=episode['episodeId'],concern=concern)

CLARIFICATION_QUESTIONS={
    'subject':'말씀하신 위험과 관련된 내용은 본인에 관한 이야기인가요?',
    'currentness':'그 내용은 지금도 해당하는 상태인가요?',
    'intent':'지금 자신이나 다른 사람을 실제로 해칠 의도나 계획이 있나요?',
    'immediacy':'지금 당장 자신이나 다른 사람을 해칠 행동을 하고 있거나 바로 하려는 상태인가요?',
}
