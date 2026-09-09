"""Session ownership and one atomic response/checkpoint/cache publication."""
import asyncio
import json
from copy import deepcopy
from time import perf_counter
from uuid import uuid4
from langchain_core.messages import HumanMessage,AIMessage
from langsmith.run_helpers import tracing_context
from cbt_q11.state import SessionRegistry, check_history
from cbt_q11.memory import CommitBundle
from cbt_q11.contracts import CbtTurnResponse,CbtAgentIdempotencyError,CompletionTechnicalError
from cbt_q11.diagnostics import Diagnostics,canonical,sha
from cbt_q11.safety import detector
from . import state as st
from .graph import compile_graph,safety_response
from .provider import Provider,Budget

registry=SessionRegistry(600)

async def moderate(provider,values,injected,diagnostics):
    budget=provider.budget
    st.require(budget.moderations==0 and budget.guard(),'moderation_budget_or_cancel')
    wire=dict(model='omni-moderation-latest',input=values)
    if budget.aggregate: budget.aggregate.moderation(wire)
    diagnostics.emit('moderation_input',providerRequest=wire)
    budget.moderations+=1; diagnostics.count('moderation_invocation')
    endpoint=injected if injected is not None else provider.client
    try:
        raw=await asyncio.wait_for(endpoint.moderations.create(**wire),15)
        raw=raw.model_dump(mode='json') if hasattr(raw,'model_dump') else raw
    except asyncio.CancelledError:
        diagnostics.emit('moderation_failure',error='CancelledError',usage=None); raise
    except Exception as exc:
        diagnostics.emit('moderation_unavailable',error=type(exc).__name__,usage=None)
        return dict(available=False,positive=None)
    diagnostics.emit('moderation_response',raw=raw); diagnostics.count('moderation_response')
    keys=['self-harm','self-harm/intent','self-harm/instructions','violence','harassment/threatening','illicit/violent']
    try:
        st.require(len(raw['results'])==len(values),'moderation_result_count')
        return dict(available=True,positive=any(r['categories'].get(k,False) for r in raw['results'] for k in keys),
            categoriesBySource=[{k:r['categories'].get(k,False) for k in keys} for r in raw['results']])
    except (KeyError,TypeError,CompletionTechnicalError): return dict(available=False,positive=None)

def raw_messages(state):
    messages=[]
    for s in state['sources'].values():
        messages.append(HumanMessage(content=s['text'],id='RAW_'+s['sourceId'],additional_kwargs={'sourceId':s['sourceId']}))
    for q in state['questions'].values():
        messages.append(AIMessage(content=q['question'],id='PUBLIC_'+q['questionCode']))
    return messages

async def generate(request,*,agent_model=None,assessor_model=None,writer_model=None,moderation_client=None,
                   registry=registry,diagnostics=None):
    request=type(request).model_validate(request.model_dump(by_alias=True,mode='json'))
    with tracing_context(enabled=False):
        return await asyncio.wait_for(_generate(request,agent_model,assessor_model,writer_model,moderation_client,registry,diagnostics),180)

async def _generate(request,agent,assessor,writer,moderation_client,registry,diagnostics):
    runtime,_=await registry.get_or_create(request.session_id)
    task=asyncio.current_task(); runtime.active_tasks.add(task)
    try:
        async with runtime.lock:
            st.require(not runtime.closed,'session_was_closed')
            exact=sha(canonical(request.model_dump(by_alias=True,mode='json')))
            rid=str(request.request_id)
            if rid in runtime.request_fingerprints and runtime.request_fingerprints[rid]!=exact:
                raise CbtAgentIdempotencyError('requestId_payload_conflict')
            runtime.request_fingerprints[rid]=exact
            if runtime.record_key not in (None,request.record.record_id):
                raise CbtAgentIdempotencyError('session_record_binding')
            d=diagnostics or Diagnostics(); runtime.diagnostics=d
            if rid in runtime.failures: raise CompletionTechnicalError(runtime.failures[rid])
            prior_bundle=runtime.bundle
            cache=json.loads(prior_bundle.success_cache_json) if prior_bundle else {}
            if rid in cache:
                d.emit('success_replay',newModelResult=False)
                return CbtTurnResponse.model_validate(cache[rid])
            logical=request.model_dump(by_alias=True,mode='json'); logical.pop('requestId')
            for q in logical.get('questionAnswers',[]):
                for field in ('askedAt','answeredAt','semanticRouteType'): q.pop(field,None)
            fingerprint=sha(canonical(logical))
            if prior_bundle and prior_bundle.logical_fingerprint==fingerprint:
                d.emit('logical_success_replay',newModelResult=False)
                return prior_bundle.response(request.request_id)
            previous=json.loads(prior_bundle.accepted_json) if prior_bundle else None
            if previous and previous.get('revision')!=st.REVISION:
                previous=st.migrate_legacy(previous,request)
            # Record received text before reconciliation, including rejected histories.
            raw_rows=[('record:'+k,v) for k,v in request.record.model_dump(by_alias=True,mode='json').items()
                      if k in ('situation','automaticThought') and isinstance(v,str)]
            raw_rows += [('answer:'+q.question_code,q.answer) for q in getattr(request,'question_answers',[]) if q.answer is not None]
            for address,text in raw_rows:
                runtime.source_ledger.setdefault('RECEIVED:'+address+'@'+sha(text),dict(address=address,text=text,revision=sha(text)))
            # Separate raw ledger survives a failed semantic transaction.
            received=st.receive(previous,request)
            # Retain earlier failed-attempt revisions in graph memory without
            # treating them as the current public history or accepted facts.
            current=deepcopy(received['current'])
            keys={s['key'] for s in received['sources'].values()}
            for archived in runtime.source_ledger.values():
                if archived.get('key') and archived['key'] not in keys:
                    st.add_source(received,archived['address'],archived['text'],archived['kind'],
                        **{k:archived[k] for k in ('field','questionCode') if k in archived})
                    keys.add(archived['key'])
            received['current']=current
            for identity,source in received['sources'].items(): runtime.source_ledger.setdefault(source['key'],deepcopy(source))
            runtime.record_key=request.record.record_id
            history=list(getattr(request,'question_answers',[]))
            pending=previous['questions'].get(previous['pendingQuestion']) if previous and previous['pendingQuestion'] else None
            check_history(history,runtime.authoritative_history,pending)
            generation=runtime.generation
            budget=Budget(d,lambda:not runtime.closed and runtime.generation==generation and not task.cancelling()
                          and not d.counters.get('audit_sink_failure'))
            provider=None
            try:
                provider=Provider(budget,agent,writer,assessor)
                received['restoration']='CHECKPOINT' if previous else 'COLD_PUBLIC_ONLY'
                # Existing narrow emergency recognizer is preserved, never expanded.
                latest=[received['sources'][h['answerSourceId']] for h in received['history'][-1:] if h['answerSourceId']]
                if not received['history']: latest=[received['sources'][i] for k,i in received['record'].items() if k.endswith('SourceId') and i]
                emergency=next(((s,detector(s['text'])) for s in latest if detector(s['text'])),None)
                thread=prior_bundle.thread_id if prior_bundle else 'SD:'+str(request.session_id)+':'+uuid4().hex
                runtime.owned_threads.add(thread)
                config={'configurable':{'thread_id':thread},'callbacks':[],'recursion_limit':8}
                if prior_bundle and prior_bundle.checkpoint_id: config['configurable']['checkpoint_id']=prior_bundle.checkpoint_id
                if emergency:
                    s,hit=emergency
                    response=safety_response(request,received,dict(action='STOP',episodeId=None,
                        trigger=dict(sourceId=s['sourceId'],quote=hit.primary_trigger['exactExcerpt'],occurrence=None),
                        sourceIds=[s['sourceId']],reason='existing emergency detector',reasonCode=hit.reason,urgency='IMMEDIATE'))
                    # Emergency still goes through the same saver and atomic bundle.
                    from langgraph.graph import StateGraph,START,END
                    from .graph import GraphState
                    g=StateGraph(GraphState)
                    g.add_node('emergency',lambda value:dict(completed=True));g.add_edge(START,'emergency');g.add_edge('emergency',END)
                    graph=g.compile(checkpointer=runtime.saver)
                    initial=dict(accepted=received,messages=raw_messages(received),response=response.model_dump(by_alias=True,mode='json'),completed=False,selection=None,candidate=None)
                else:
                    mod=await moderate(provider,[s['text'] for s in received['sources'].values()],moderation_client,d)
                    graph=compile_graph(runtime.saver,request,provider,d,mod)
                    initial=dict(accepted=received,messages=raw_messages(received),response=None,completed=False,selection=None,candidate=None)
                if prior_bundle and prior_bundle.checkpoint_id and previous['restoration']!='LEGACY_MIGRATED':
                    saved=await graph.aget_state(config)
                    saved_accepted=saved.values.get('accepted')
                    mismatch=[k for k in previous if (saved_accepted or {}).get(k)!=previous[k]]
                    st.require(saved.values.get('completed') and not mismatch,'committed_checkpoint_mismatch:'+','.join(mismatch))
                final=await graph.ainvoke(initial,config)
                head=await graph.aget_state({'configurable':{'thread_id':thread}})
                st.require(final['completed'] and not head.next and head.values['accepted']==final['accepted'],'graph_not_complete')
                response=CbtTurnResponse.model_validate(final['response'])
                cache[rid]=final['response']
                bundle=CommitBundle(thread,head.config['configurable']['checkpoint_id'],canonical(final['accepted']),
                    canonical(final['response']),fingerprint,rid,1+(prior_bundle.turn_revision if prior_bundle else 0),generation,canonical(cache))
                d.emit('request_precommit',accepted=final['accepted'],response=final['response'],checkpointId=bundle.checkpoint_id)
                st.require(budget.guard() and runtime.bundle is prior_bundle and perf_counter()<budget.deadline,'cancelled_or_stale_before_publish')
                runtime.bundle=bundle
                runtime.authoritative_history=deepcopy(history)
                registry.touch(request.session_id,runtime)
                d.emit('commit',checkpointId=bundle.checkpoint_id,turnRevision=bundle.turn_revision)
                return response
            except BaseException as exc:
                runtime.failures[rid]=type(exc).__name__+':'+str(exc)
                d.emit('request_failure',error=type(exc).__name__,reason=str(exc),committed=False)
                raise
            finally:
                if provider: await provider.close()
    finally:
        runtime.active_tasks.discard(task)

async def close(session_id,*,registry=registry):
    await registry.remove(session_id)
