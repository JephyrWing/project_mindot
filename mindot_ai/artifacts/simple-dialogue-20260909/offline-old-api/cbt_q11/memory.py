"""Explicit serializer and successful-head transaction, not an external DB transaction."""
from copy import deepcopy
from dataclasses import dataclass, fields
import json
from langchain_core.messages import AIMessage, HumanMessage
from .contracts import Domain, QuestionAnswer, CbtTurnResponse
from .diagnostics import canonical, sha


def state_data(state):
    """Only JSON primitives cross the saver boundary; no arbitrary dataclass pickle."""
    result={}
    special={'coverage','history','sources','atoms','trusted_controls','fulfilled_controls'}
    for field in fields(state):
        if field.name in special:
            continue
        value=getattr(state,field.name)
        result[field.name]=sorted(value) if isinstance(value,set) else deepcopy(value)
    result['coverage']={d.value:c.dump() for d,c in state.coverage.items()}
    result['history']=[q.model_dump(by_alias=True,mode='json') for q in state.history]
    result['sources']={k:{'source_id':v.source_id,'revision':v.revision,
        'item':v.item.model_dump(by_alias=True,mode='json')} for k,v in state.sources.items()}
    result['atoms']=[{'ref_id':a.ref_id,'source_key':a.source_key,'start':a.start,
        'length':a.length,'domain':a.domain.value if a.domain is not None else None,'kind':a.kind,'role':a.role,
        'goal_id':a.goal_id,'analysis_revision':a.analysis_revision,'origin_event_id':a.origin_event_id,
        'evidence_kind':a.evidence_kind,'gap_binding':deepcopy(a.gap_binding),'analysis_id':a.analysis_id} for a in state.atoms]
    result['trusted_controls']={k:sorted(v) for k,v in state.trusted_controls.items()}
    result['fulfilled_controls']=[list(x) for x in sorted(state.fulfilled_controls)]
    return result


def restore_state(data):
    from .state import AcceptedState, Coverage, Source, EvidenceAtom
    if isinstance(data,str):
        data=json.loads(data)
    data=deepcopy(data)
    result=AcceptedState()
    for key,value in data.items():
        if key in {'coverage','history','sources','atoms','trusted_controls','fulfilled_controls'}:
            continue
        if not hasattr(result,key):
            raise ValueError('unknown_memory_field')
        setattr(result,key,set(value) if isinstance(getattr(result,key),set) else value)
    result.coverage={Domain(k):Coverage(v['status'],v['sourceQuestionCodes']) for k,v in data['coverage'].items()}
    result.history=[QuestionAnswer.model_validate(q) for q in data['history']]
    result.sources={k:Source(v['source_id'],v['revision'],QuestionAnswer.model_validate(v['item'])) for k,v in data['sources'].items()}
    result.atoms=tuple(EvidenceAtom(**{**a,'domain':Domain(a['domain']) if a.get('domain') is not None else None}) for a in data['atoms'])
    result.trusted_controls={k:set(v) for k,v in data['trusted_controls'].items()}
    result.fulfilled_controls={tuple(v) for v in data['fulfilled_controls']}
    return result


@dataclass(frozen=True)
class CommitBundle:
    thread_id: str | None
    checkpoint_id: str | None
    accepted_json: str
    response_json: str
    logical_fingerprint: str
    attempt_id: str
    turn_revision: int
    generation: int
    success_cache_json: str

    def response(self,request_id):
        response=CbtTurnResponse.model_validate_json(self.response_json)
        return response.model_copy(deep=True,update={'request_id':request_id})


def receive_ledger(runtime,request):
    """Retain genuine USER input even when semantic processing later fails."""
    rows=[]
    for field in ('situation','automatic_thought'):
        text=getattr(request.record,field)
        if text:
            rows.append((f'record:{request.record.record_id}:{field}',text,'USER_RECORD'))
    for q in getattr(request,'question_answers',[]):
        rows.append((f'session:{request.session_id}:record:{request.record.record_id}:answer:{q.question_code}',q.answer,'USER_ANSWER'))
    for address,text,kind in rows:
        key=address+'@'+sha(text)
        runtime.source_ledger.setdefault(key,{'id':'USER_'+sha(key),'address':address,
            'revision':sha(text),'text':text,'kind':kind})


def incoming_messages(request,draft,ledger):
    """Stable IDs mean reducer replace-on-edit, not repeatedly appending full history."""
    messages=[]
    for entry in ledger.values():
        if entry['kind']=='USER_RECORD':
            messages.append(HumanMessage(content=entry['text'],id=entry['id'],additional_kwargs={
                'sourceAddress':entry['address'],'sourceRevision':entry['revision'],'kind':entry['kind']}))
    for q in draft.history:
        qid='QUESTION_'+sha(str(request.session_id)+':'+q.question_code)
        messages.append(AIMessage(content=q.question,id=qid,additional_kwargs={'publicQuestionCode':q.question_code,'renderedBy':'PUBLIC_HISTORY'}))
        key=f'session:{request.session_id}:record:{request.record.record_id}:answer:{q.question_code}@'+sha(q.answer)
        entry=ledger[key]
        messages.append(HumanMessage(content=q.answer,id=entry['id'],additional_kwargs={
            'sourceAddress':entry['address'],'sourceRevision':entry['revision'],'publicQuestionCode':q.question_code}))
    return messages


def context_conversation(messages,table):
    """Data view of selected dialogue, not a second raw transcript copy.

    USER text occurs only in readable sources. Internal historical Agent/tool
    arguments stay paired in saver memory, but are not repeated in model input.
    """
    by_question={s.get('questionCode'):s for s in table if s['kind']=='USER_ANSWER'}
    by_field={s.get('field'):s for s in table if s['kind']=='USER_RECORD'}
    result=[]
    for message in messages:
        meta=message.additional_kwargs
        if isinstance(message,HumanMessage):
            field=meta.get('sourceAddress','').rsplit(':',1)[-1]
            field={'automatic_thought':'automaticThought','situation':'situation'}.get(field)
            source=by_question.get(meta.get('publicQuestionCode')) or by_field.get(field)
            if source and source['text']==message.content:
                result.append({'role':'USER','sourceId':source['sourceId']})
            # Historical replaced revisions remain in memory, never presented as current facts.
        elif isinstance(message,AIMessage) and not message.tool_calls:
            code=meta.get('publicQuestionCode')
            source=by_question.get(code)
            if source:
                result.append({'role':'ASSISTANT','questionAtSourceId':source['sourceId']})
    return result
