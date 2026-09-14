"""Revision-checked hydration/delta, no model calls during RESTORE."""
import asyncio
from copy import deepcopy
from datetime import datetime,timezone
from langsmith.run_helpers import tracing_context
from .contracts import Start,Turn,Result,ProtocolError
from .diagnostics import Diagnostics,canonical,sha
from .safety import detector
from .state import Registry,require
from .provider import Provider,Budget
from .graph import execute,SAFETY
registry=Registry(600)

async def start(request:Start,*,registry=registry,**injected):
    runtime=registry.acquire(request.sessionId)
    require(not runtime.lock.locked(),'IN_PROGRESS')
    async with runtime.lock:
        require(not runtime.closed,'RESYNC_REQUIRED')
        snap=request.model_dump(mode='json')
        if request.mode=='RESTORE':
            # Preserve a newer successful cache until Spring explicitly catches up;
            # authoritative restore may discard its visible text, never replay it as conversation.
            runtime.snapshot=deepcopy(snap);runtime.pending_question_purpose=None;registry.touch(runtime)
            return Result(sessionId=request.sessionId,inputRevision=request.revision,revision=request.revision,
                outcome='RESTORED',phase=request.phase,currentProposal=request.currentProposal)
        runtime.pending_question_purpose=None
        job=request.pendingJob
        return await generate(runtime,snap,job.model_dump(),registry,injected)

async def turn(request:Turn,*,registry=registry,**injected):
    runtime=registry.acquire(request.sessionId)
    require(not runtime.lock.locked(),'IN_PROGRESS')
    async with runtime.lock:
        require(not runtime.closed and runtime.snapshot,'RESYNC_REQUIRED')
        snap=deepcopy(runtime.snapshot);job=dict(requestId=request.requestId,attemptNo=request.attemptNo,
            inputRevision=request.inputRevision,userMessageNumber=request.userMessage.messageNumber)
        key=request.requestId;fingerprint=sha(canonical(dict(inputRevision=request.inputRevision,userMessage=request.userMessage.model_dump(mode='json'))))
        require(runtime.fingerprints.get(key) in (None,fingerprint),'REQUEST_CONFLICT')
        if key in runtime.successes:return Result.model_validate({**runtime.successes[key], 'attemptNo': job['attemptNo']})
        user=request.userMessage.model_dump(mode='json')
        if snap['revision']==request.baseRevision:
            require(user['messageNumber']==len(snap['messages'])+1,'RESYNC_REQUIRED')
            snap['messages'].append(user);snap['revision']=request.inputRevision;snap['pendingJob']=job
        else:
            pending=snap.get('pendingJob') or {}
            require(snap['revision']==request.inputRevision and pending.get('requestId')==key and pending.get('userMessageNumber')==user['messageNumber']
                and snap['messages'] and snap['messages'][-1]==user,'RESYNC_REQUIRED')
            snap['pendingJob']=job
        runtime.fingerprints[key]=fingerprint
        return await generate(runtime,snap,job,registry,injected)

async def generate(runtime,snap,job,registry,injected):
    key=job['requestId'];attempt=job['attemptNo'];ticket=(key,attempt)
    fingerprint=sha(canonical(dict(record=snap['record'],inputRevision=snap['revision']))) if snap['mode']=='NEW' else runtime.fingerprints.get(key)
    if snap['mode']=='NEW':
        require(runtime.fingerprints.get(key) in (None,fingerprint),'REQUEST_CONFLICT');runtime.fingerprints[key]=fingerprint
    if key in runtime.successes:return Result.model_validate({**runtime.successes[key], 'attemptNo': job['attemptNo']})
    if ticket in runtime.failures:raise ProtocolError(runtime.failures[ticket])
    runtime.snapshot=deepcopy(snap) # Preserve input even if generation fails.
    d=injected.get('diagnostics') or Diagnostics();task=asyncio.current_task()
    budget=Budget(d,lambda:not runtime.closed and not task.cancelling())
    provider=Provider(budget,injected.get('agent_model'),injected.get('assessor_model'))
    async def run():
        latest=[m['content'] for m in snap['messages'][-1:] if m['role']=='USER'] or [x for x in (snap['record']['situation'],snap['record']['automaticThought']) if x]
        if any(detector(t) for t in latest):return dict(outcome='SAFETY_STOP',phase='DIALOGUE',text=SAFETY,currentProposal=None,issue=None)
        endpoint=injected.get('moderation_client') or provider.client
        wire=dict(model='omni-moderation-latest',input=latest)
        if budget.aggregate:budget.aggregate.moderation(wire)
        budget.moderations+=1;d.emit('moderation_input',providerRequest=wire)
        try:
            m=await asyncio.wait_for(endpoint.moderations.create(**wire),15)
            raw=m.model_dump(mode='json') if hasattr(m,'model_dump') else m
            d.emit('moderation_response',raw=raw)
            moderation=dict(available=True,flagged=any(r.get('flagged',False) for r in raw.get('results',[])))
        except Exception as exc:
            d.emit('moderation_unavailable',error=type(exc).__name__);moderation=dict(available=False,flagged=None)
        # Advisory moderation does not mechanically classify quotations as current risk.
        model_snapshot=deepcopy(snap);model_snapshot['moderation']=moderation
        model_snapshot['pendingQuestionPurpose']=runtime.pending_question_purpose
        return await execute(model_snapshot,provider,d)
    try:
        with tracing_context(enabled=False):value=await asyncio.wait_for(run(),180)
        require(budget.guard(),'CANCELLED')
        question_purpose=value.pop('_questionPurpose',None)
        message=dict(messageNumber=len(snap['messages'])+1,role='ASSISTANT',content=value.pop('text'),createdAt=datetime.now(timezone.utc).isoformat())
        result=Result(sessionId=snap['sessionId'],requestId=key,attemptNo=attempt,inputRevision=snap['revision'],revision=snap['revision']+1,
            assistantMessage=message,**value)
        updated=deepcopy(snap);updated['messages'].append(message);updated.update(revision=result.revision,phase=result.phase,
            currentProposal=result.currentProposal,pendingJob=None,mode='RESTORE')
        runtime.snapshot=updated;runtime.pending_question_purpose=question_purpose
        runtime.successes[key]=result.model_dump(mode='json');registry.touch(runtime)
        d.emit('commit',response=runtime.successes[key],snapshot=updated)
        return result
    except BaseException as exc:
        runtime.failures[ticket]='GENERATION_FAILED';d.emit('request_failure',error=type(exc).__name__,committed=False)
        raise
    finally:await provider.close()

async def close(session_id,*,registry=registry):registry.remove(session_id)
