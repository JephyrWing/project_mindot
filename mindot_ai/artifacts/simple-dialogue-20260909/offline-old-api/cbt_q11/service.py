"""Request ownership -> actual Agent graph -> explicit END head -> atomic bundle publish."""
import asyncio
from contextlib import asynccontextmanager
from copy import deepcopy
from dataclasses import dataclass
from time import perf_counter
from uuid import uuid4
import os
import json
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph.message import add_messages
from langsmith.run_helpers import tracing_context
from . import safety,policy,gaps
from .agent_graph import TurnContext, compile_graph, execute
from .capacity import agent_admission
from .contracts import CbtAgentIdempotencyError, CbtTurnResponse, CompletionTechnicalError, ReviewRequired
from .diagnostics import Diagnostics,canonical,sha
from .llm import DEADLINE, REQUEST_BUDGET, Admission, remaining_timeout
from .memory import CommitBundle, state_data, restore_state, receive_ledger, incoming_messages
from .prompts import AGENT_SYSTEM_PROMPT
from .state import AcceptedState, SessionRegistry, Source, reconstruct, check_history, source_revision, refresh

TURN_TIMEOUT_SECONDS=180.0
registry=SessionRegistry(float(os.getenv('CBT_AGENT_SESSION_TTL_SECONDS','600')))

@dataclass(frozen=True)
class CachedFailure:
    error_type: str
    message: str
    def raise_error(self):
        cls=CbtAgentIdempotencyError if self.error_type=='CbtAgentIdempotencyError' else CompletionTechnicalError
        raise cls(self.message)

@asynccontextmanager
async def bounded_lock(lock):
    await asyncio.wait_for(lock.acquire(),remaining_timeout(TURN_TIMEOUT_SECONDS))
    try:
        yield
    finally:
        lock.release()

def logical_input(request):
    value=request.model_dump(by_alias=True,mode='json'); value.pop('requestId')
    for item in value.get('questionAnswers',[]):
        for key in ('askedAt','answeredAt','semanticRouteType'):
            item.pop(key,None)
    return value

def emergency_draft(request,prior):
    """Reconcile only verified identities; no clinical extraction before guidance."""
    draft=deepcopy(prior) if prior else AcceptedState()
    history=list(getattr(request,'question_answers',[]))
    known={q['questionCode']:q for q in draft.prior_questions}
    if draft.pending_question:
        known[draft.pending_question['questionCode']]=draft.pending_question
    for item in history:
        if item.question_code not in known:
            raise CbtAgentIdempotencyError('emergency_history_plan_unverified')
        if (known[item.question_code]['question'],item.question_code)!=(item.question,item.question_code):
            raise CbtAgentIdempotencyError('emergency_history_question_identity')
    draft.history=deepcopy(history)
    draft.prior_questions=[deepcopy(q) for q in known.values()]
    draft.pending_question=None
    draft.pending_assessment=None
    draft.record_id=request.record.record_id
    draft.thought_revision=sha(request.record.automatic_thought)[:20]
    record_revision=sha(canonical([request.record.record_id,request.record.situation,request.record.automatic_thought]))
    if draft.record_revision and draft.record_revision!=record_revision:
        draft.review_revisions={}; draft.reviews={}; draft.review_projections={}
    draft.record_revision=record_revision
    draft.current_record_sources={}
    for name,text in [('automaticThought',request.record.automatic_thought),('situation',request.record.situation)]:
        if text:
            address=f'RECORD_{request.record.record_id}_{name}'; revision=sha(text)[:20]
            draft.record_sources.setdefault(address+'@'+revision,{'address':address,'revision':revision,'field':name,'text':text})
            draft.current_record_sources[name]=address+'@'+revision
    for item in draft.history:
        source=Source(f'SESSION_{request.session_id}_RECORD_{request.record.record_id}_ANSWER_'+item.question_code,
                      source_revision(item),item)
        if draft.current_sources.get(item.question_code)!=source.key:
            draft.review_revisions.pop(item.question_code,None)
            draft.reviews.pop(item.question_code,None)
        draft.sources.setdefault(source.key,source)
        draft.current_sources[item.question_code]=source.key
    gaps.migrate(draft)
    refresh(draft)
    return draft


def publish_emergency_overlay(request,runtime,prior,hit,logical,diagnostics,generation,task,reason):
    """Atomic safety receipt only. An invalid history never becomes a CBT head."""
    from dataclasses import replace
    from .rendering import safety_response
    original=deepcopy(hit.primary_trigger)
    history=getattr(request,'question_answers',[])
    text=history[-1].answer if history else (request.record.automatic_thought
        if original['address'].endswith('_automaticThought') else request.record.situation)
    source_address='SAFETY_RECEIPT_'+sha(canonical([str(request.session_id),str(request.request_id),original]))[:24]
    source={'address':source_address,'revision':sha(text)[:20],'sourceKey':source_address+'@'+sha(text)[:20],
        'kind':'UNADOPTED_SAFETY_RECEIPT','text':text,'unadoptedHistory':True}
    trigger={**original,'address':source['address'],'revision':source['revision'],
        'sourceKey':source['sourceKey'],'questionCode':None}
    scratch=AcceptedState()
    question_identity={'questionCode':history[-1].question_code,'question':history[-1].question,
        'questionPurpose':history[-1].question_purpose.value} if history else None
    # Same received source/occurrence is the same risk even on an explicit new
    # attempt. Its episode identity/clarification consumption never resets.
    previous=next((r for r in runtime.safety_receipts.values() if r['originalBinding']==original and
        r.get('originalQuestionIdentity')==question_identity and r['source']['text']==text),None)
    if previous:
        source=deepcopy(previous['source']); trigger=deepcopy(previous['episode']['primaryTrigger'])
        scratch.episodes[previous['episode']['episodeId']]=deepcopy(previous['episode'])
    response=safety_response(request,scratch,replace(hit,primary_trigger=trigger),diagnostics)
    episode=deepcopy(scratch.episodes[scratch.focus_episode_id])
    receipt={'receiptId':source_address,'attemptId':str(request.request_id),'source':source,
        'originalBinding':original,'episode':episode,'response':response.model_dump(by_alias=True,mode='json'),
        'originalQuestionIdentity':question_identity,
        'logicalFingerprint':logical,'recoveryRequired':True,'unadoptedHistory':True,'reason':reason}
    remaining_timeout(TURN_TIMEOUT_SECONDS)
    if runtime.closed or runtime.generation!=generation or task.cancelling():
        raise CompletionTechnicalError('cancelled_or_stale_before_safety_overlay')
    # This block has no await. Invalidate any older generation without replacing
    # bundle/checkpoint/accepted state/authoritative history or clinical cache.
    runtime.safety_receipts[receipt['receiptId']]=receipt
    runtime.safety_success_cache[str(request.request_id)]=deepcopy(receipt)
    runtime.safety_overlay_revision+=1; runtime.generation+=1
    runtime.requires_recovery=True
    diagnostics.emit('emergency_overlay_published',receiptId=receipt['receiptId'],
        clinicalHeadUnchanged=True,historyAccepted=False,recoveryRequired=True,generation=runtime.generation)
    return response


def reconcile_overlays(draft,runtime,request):
    """Readable safety history is not approval as current CBT evidence."""
    for identity,receipt in runtime.safety_receipts.items():
        first=identity not in draft.safety_overlays
        overlay=deepcopy(draft.safety_overlays.get(identity,receipt))
        episode=deepcopy(receipt['episode'])
        original=receipt['originalBinding']
        original_question=receipt.get('originalQuestionIdentity')
        actual=next((s for s in draft.sources.values() if s.source_id==original['address'] and
            s.revision==original['revision'] and draft.current_sources.get(s.item.question_code)==s.key and
            original_question=={'questionCode':s.item.question_code,'question':s.item.question,
                'questionPurpose':s.item.question_purpose.value}),None)
        record=draft.record_sources.get(original['address']+'@'+original['revision'])
        current_records={f'RECORD_{request.record.record_id}_{name}':sha(value)[:20]
            for name,value in [('automaticThought',request.record.automatic_thought),('situation',request.record.situation)] if value}
        current_record=record and current_records.get(original['address'])==original['revision']
        if actual or current_record:
            origin=deepcopy(original)
            origin['sourceKey']=actual.key if actual else None
            episode['originAliases']=[deepcopy(receipt['episode']['primaryTrigger'])]
            episode['primaryTrigger']=origin
            overlay.update(unadoptedHistory=False,recoveryRequired=False,adopted=True,adoptedSource=origin)
            overlay['source'].update(kind='ARCHIVED_SAFETY_RECEIPT',unadoptedHistory=False)
        elif overlay.get('adoptedSource'):
            # Adoption is historical identity, not a currentness claim. A later
            # USER edit archives the adopted origin; it does not undo the mapping
            # or lose its alias/consumption while constructing the next request.
            episode['primaryTrigger']=deepcopy(overlay['adoptedSource'])
            episode['originAliases']=[deepcopy(receipt['episode']['primaryTrigger'])]
        draft.safety_overlays[identity]=overlay
        if first or episode['episodeId'] not in draft.episodes:
            draft.episodes[episode['episodeId']]=episode
        elif not overlay['unadoptedHistory']:
            # Adoption preserves the already committed status and consumed count.
            draft.episodes[episode['episodeId']]['primaryTrigger']=episode['primaryTrigger']
            draft.episodes[episode['episodeId']]['originAliases']=episode['originAliases']
    safety.sync_focus(draft)

async def generate(request,**kwargs):
    # Capture caller-owned DTO before any await; no mutable caller payload enters a model.
    request=type(request).model_validate(request.model_dump(by_alias=True,mode='json'))
    token=DEADLINE.set(perf_counter()+TURN_TIMEOUT_SECONDS)
    try:
        with tracing_context(enabled=False):
            return await asyncio.wait_for(_generate(request,**kwargs),TURN_TIMEOUT_SECONDS)
    finally:
        DEADLINE.reset(token)

async def _generate(request,*,agent_model=None,assessor_model=None,writer_model=None,moderation_client=None,
                    registry=registry,diagnostics=None):
    runtime,_=await registry.get_or_create(request.session_id)
    request_id=str(request.request_id)
    exact=sha(canonical(request.model_dump(by_alias=True,mode='json')))
    logical=sha(canonical([type(request).__name__,logical_input(request)]))
    async with bounded_lock(runtime.lock):
        if runtime.closed:
            raise CompletionTechnicalError('session_was_closed')
        if runtime.record_key not in (None,request.record.record_id):
            raise CbtAgentIdempotencyError('session_record_binding')
        old=runtime.request_fingerprints.get(request_id)
        if old and old!=exact:
            raise CbtAgentIdempotencyError('requestId_payload_conflict')
        runtime.request_fingerprints[request_id]=exact
        d=diagnostics or Diagnostics(); runtime.diagnostics=d
        if request_id in runtime.failures:
            runtime.failures[request_id].raise_error()
        overlay_cache=runtime.safety_success_cache.get(request_id)
        if overlay_cache:
            d.emit('emergency_overlay_replay',originalAttempt=overlay_cache['attemptId'],
                currentAttempt=request_id,newModelResult=False,acceptedHeadUnchanged=True)
            return CbtTurnResponse.model_validate(overlay_cache['response']).model_copy(deep=True,update={'request_id':request.request_id})
        cached=json.loads(runtime.bundle.success_cache_json) if runtime.bundle else {}
        cache_key=runtime.success_aliases.get(request_id,request_id)
        if cache_key in cached:
            d.emit('attempt_success_replay',originalAttempt=cache_key,currentAttempt=request_id,
                newModelResult=False,acceptedHeadUnchanged=True)
            return CbtTurnResponse.model_validate(cached[cache_key]).model_copy(deep=True,update={'request_id':request.request_id})
        if runtime.bundle and runtime.bundle.logical_fingerprint==logical and runtime.bundle.generation==runtime.generation:
            d.emit('success_replay',originalAttempt=runtime.bundle.attempt_id,currentAttempt=request_id,newModelResult=False)
            registry.touch(request.session_id,runtime)
            runtime.success_aliases[request_id]=runtime.bundle.attempt_id
            return runtime.bundle.response(request.request_id)
        prior=runtime.state if runtime.bundle else None
        hit=safety.detect_request(request)
        # Receipt of real USER text is durable only in this process, separate from semantic commit.
        receive_ledger(runtime,request)
        runtime.record_key=request.record.record_id
        history=list(getattr(request,'question_answers',[]))
        if runtime.requires_recovery and not history and not hit:
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_expired_history_missing')
        history_error=None
        try:
            check_history(history,runtime.authoritative_history,prior.pending_question if prior else None)
        except CbtAgentIdempotencyError as exc:
            if not hit:
                raise
            history_error=str(exc)
        task=asyncio.current_task(); runtime.active_tasks.add(task)
        generation=runtime.generation; bundle=runtime.bundle
        thread=bundle.thread_id if bundle and bundle.checkpoint_id else f'CBT:{request.session_id}:{request.record.record_id}:{uuid4().hex}'
        parent=bundle.checkpoint_id if bundle else None
        runtime.owned_threads.add(thread)
        config={'configurable':{'thread_id':thread,'checkpoint_ns':''},'recursion_limit':12,'callbacks':[]}
        if parent:
            config['configurable']['checkpoint_id']=parent
        budget=Admission(d,guard=lambda:not runtime.closed and runtime.generation==generation and not task.cancelling())
        budget_token=REQUEST_BUDGET.set(budget)
        try:
            if hit:
                same=next((r for r in runtime.safety_success_cache.values() if r['logicalFingerprint']==logical),None)
                if same:
                    runtime.safety_success_cache[request_id]=deepcopy(same)
                    d.emit('emergency_overlay_replay',originalAttempt=same['attemptId'],currentAttempt=request_id,
                        newModelResult=False,acceptedHeadUnchanged=True)
                    return CbtTurnResponse.model_validate(same['response']).model_copy(deep=True,update={'request_id':request.request_id})
                try:
                    if history_error:
                        raise CbtAgentIdempotencyError(history_error)
                    draft=emergency_draft(request,prior)
                except (CompletionTechnicalError,CbtAgentIdempotencyError,ValueError,KeyError) as exc:
                    return publish_emergency_overlay(request,runtime,prior,hit,logical,d,generation,task,str(exc))
            else:
                draft=reconstruct(request,prior,d)
            reconcile_overlays(draft,runtime,request)
            for receipt in runtime.assessment_attempt_receipts.values():
                if receipt not in draft.assessment_attempts:
                    draft.assessment_attempts.append(deepcopy(receipt))
            safety.migrate(draft)
            for code,key in draft.current_sources.items():
                if key in runtime.review_hints and not runtime.review_hints[key].lower().startswith('gap_'):
                    draft.review_revisions.pop(code,None)
            refresh(draft)
            graph=compile_graph(runtime.saver)
            memory=[]
            if parent:
                previous=await graph.aget_state(config)
                if previous.next or not previous.values.get('completed') or previous.config['configurable']['checkpoint_id']!=parent:
                    raise CompletionTechnicalError('committed_head_unavailable')
                if state_data(restore_state(previous.values['accepted']))!=state_data(prior):
                    raise CompletionTechnicalError('committed_state_head_mismatch')
                memory=previous.values.get('messages',[])
            incoming=incoming_messages(request,draft,runtime.source_ledger)
            message_view=add_messages(memory,incoming)
            if hit:
                data={}; schemas=[]; aliases={}
            else:
                data,schemas,aliases=policy.prepare(request,draft,message_view,{'available':False,'scope':'NOT_YET_MODERATED'})
                # Field-local gap migration reasons/accepted analysis bindings
                # were scheduled before SELECT. Attempt diagnostics must not
                # overwrite them or invalidate unrelated accepted CBT coverage.
                data['reviewAttemptHints']={k:v for k,v in runtime.review_hints.items()
                    if k in {s.get('sourceKey') for s in data['sources']}}
                # Reserve worst shaped moderation metadata BEFORE a provider call.
                predicted={**data,'moderation':{'available':True,'positive':True,'scope':'ALL_READABLE_USER_SOURCES',
                    'categoriesBySource':[{'self-harm':True,'self-harm/intent':True,'self-harm/instructions':True,
                        'violence':True,'harassment/threatening':True,'illicit/violent':True} for _ in data['sources']]}}
                agent_admission([SystemMessage(content=AGENT_SYSTEM_PROMPT),HumanMessage(content=canonical(predicted))],schemas,data['sources'],d)
                moderation=await safety.moderate([s['text'] for s in data['sources']],d,moderation_client)
                data={**data,'moderation':moderation}
                data['viewId']='V_'+sha(canonical({k:v for k,v in data.items() if k!='viewId'}))
            context=TurnContext(request,data,schemas,aliases,d,budget,agent_model,assessor_model,writer_model,hit)
            initial={'messages':incoming,'accepted':state_data(draft),'attempt_id':request_id,
                'baseline_accepted':state_data(draft),'origin_selection':None,'phase':'SELECT','phase_payload':None,
                'parent_checkpoint':parent,'selection':None,'response':None,'completed':False}
            head,final=await execute(graph,initial,config,context)
            response=CbtTurnResponse.model_validate(final['response'])
            if response.request_id!=request.request_id:
                raise CompletionTechnicalError('response_attempt_mismatch')
            cached[request_id]=final['response']
            new_bundle=CommitBundle(thread,head['checkpoint_id'],canonical(final['accepted']),
                canonical(final['response']),logical,request_id,1+(bundle.turn_revision if bundle else 0),generation,
                canonical(cached))
            remaining_timeout(TURN_TIMEOUT_SECONDS)
            if runtime.closed or runtime.generation!=generation or runtime.bundle is not bundle or task.cancelling():
                raise CompletionTechnicalError('cancelled_or_stale_before_publish')
            d.emit('request_precommit',checkpointId=head['checkpoint_id'],parentCheckpoint=parent,attemptId=request_id)
            if runtime.closed or runtime.generation!=generation or runtime.bundle is not bundle or task.cancelling():
                raise CompletionTechnicalError('cancelled_at_publish')
            # Single pointer replacement, no await: head + accepted state + clinical cache + revision.
            runtime.bundle=new_bundle
            runtime.authoritative_history=deepcopy(history); runtime.record_key=request.record.record_id
            reviewed=set(final['accepted']['review_revisions'].values())
            runtime.review_hints={k:v for k,v in runtime.review_hints.items() if k not in reviewed}
            runtime.requires_recovery=any(x.get('unadoptedHistory') and
                final['accepted']['episodes'].get(x['episode']['episodeId'],{}).get('status')=='ACTIVE'
                for x in final['accepted'].get('safety_overlays',{}).values())
            registry.touch(request.session_id,runtime)
            d.emit('request_complete',checkpointId=head['checkpoint_id'],stateCommitted=True,
                response=final['response'],counters=d.counters.copy(),callerDatabaseCommitKnown=False)
            return response
        except BaseException as exc:
            if hit and isinstance(exc,(CompletionTechnicalError,CbtAgentIdempotencyError,ValueError,KeyError)):
                # A broken clinical checkpoint must not suppress deterministic
                # emergency guidance. Guarded receipt publish still cannot accept
                # that checkpoint or overwrite the existing clinical head.
                return publish_emergency_overlay(request,runtime,prior,hit,logical,d,generation,task,
                    'emergency_clinical_reconciliation:'+str(exc))
            if isinstance(exc,ReviewRequired):
                runtime.review_hints.update({key:str(exc) for key in exc.source_keys})
            message=(str(exc) if isinstance(exc,(CompletionTechnicalError,CbtAgentIdempotencyError))
                     else 'cancelled_attempt' if isinstance(exc,asyncio.CancelledError) else type(exc).__name__)
            if not runtime.closed:
                runtime.failures[request_id]=CachedFailure(type(exc).__name__,message)
            d.emit('request_aborted',error=type(exc).__name__,reason=message,acceptedHeadUnchanged=True,
                attemptedThread=thread,committedParent=parent,providerLedger=budget.ledger)
            # Failed shared-thread checkpoints stay diagnostic branches, never selected as parent.
            # An isolated bootstrap has no successful ancestor and may be safely removed now.
            if parent is None:
                try:
                    await runtime.saver.adelete_thread(thread)
                    runtime.owned_threads.discard(thread)
                except Exception:
                    d.count('deferred_saver_cleanup')
            raise
        finally:
            for event in d.events:
                if event.get('event')=='assessment_rejected':
                    row={**deepcopy(event),'status':'REJECTED','attemptId':request_id,
                        'thoughtRevision':sha(request.record.automatic_thought)[:20]}
                    runtime.assessment_attempt_receipts.setdefault(request_id,row)
            REQUEST_BUDGET.reset(budget_token)
            runtime.active_tasks.discard(task)
            registry.touch(request.session_id,runtime)

async def close(session_id,*,registry=registry):
    await registry.remove(session_id)
