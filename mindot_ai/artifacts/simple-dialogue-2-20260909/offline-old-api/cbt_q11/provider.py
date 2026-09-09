"""Request-scoped raw boundary before ChatOpenAI 1.4.1 converts tool arguments."""
from dataclasses import dataclass, replace
import json
import asyncio
from types import SimpleNamespace
from langchain_openai import ChatOpenAI
from . import llm
from .contracts import CompletionTechnicalError, MODEL
from copy import deepcopy

def select_review_inputs(messages):
    """Source-local identity hints only; canonical views/state are not changed.

    A source's own question identity is not the target of any inferred request.
    Raw source text stays in its original single location, including archives.
    """
    result=deepcopy(messages)
    for message in result:
        if message.get('role')!='user' or not isinstance(message.get('content'),str):
            continue
        try:
            data=json.loads(message['content'],object_pairs_hook=llm.unique_object)
        except json.JSONDecodeError:
            continue
        if not isinstance(data,dict) or data.get('phase')!='SELECT':
            continue
        sources=data.get('sources'); slots=data.get('reviewSlots'); pending=data.get('pendingInteractions')
        requests=data.get('presentationRequests')
        if (not isinstance(sources,list) or not isinstance(slots,dict)
                or not isinstance(pending,dict) or not isinstance(requests,dict)):
            raise CompletionTechnicalError('select_review_input_binding')
        by_source={}
        for source in sources:
            if (not isinstance(source,dict) or not isinstance(source.get('sourceId'),str)
                    or source['sourceId'] in by_source or 'reviewSlot' in source
                    or 'sourceQuestionPendingId' in source or 'reviewSignalReservations' in source):
                raise CompletionTechnicalError('select_review_input_binding')
            by_source[source['sourceId']]=source
        source_slots={}
        for slot,binding in slots.items():
            source=by_source.get(binding.get('sourceId')) if isinstance(binding,dict) else None
            if (source is None or source.get('kind')!='USER_ANSWER'
                    or source['sourceId'] in source_slots
                    or any(source.get(key)!=binding.get(key) or source.get(key) is None
                           for key in ('sourceKey','revision'))):
                raise CompletionTechnicalError('select_review_input_binding')
            source_slots[source['sourceId']]=slot
        question_pending={}
        for pending_id,item in pending.items():
            if (not isinstance(item,dict) or item.get('pendingId')!=pending_id
                    or not isinstance(item.get('questionCode'),str)
                    or item['questionCode'] in question_pending):
                raise CompletionTechnicalError('select_review_input_binding')
            question_pending[item['questionCode']]=pending_id
        for source in sources:
            source['reviewSlot']=source_slots.get(source['sourceId'])
            source['sourceQuestionPendingId']=(question_pending.get(source.get('questionCode'))
                if source.get('kind')=='USER_ANSWER' else None)
            source['reviewSignalReservations']={}
        active={}
        for alias,entry in requests.items():
            if not isinstance(entry,dict):
                raise CompletionTechnicalError('select_review_input_binding')
            if entry.get('state')=='ACTIVE':
                if not isinstance(entry.get('requestId'),str) or alias!='p:'+entry['requestId']:
                    raise CompletionTechnicalError('select_review_input_binding')
                active[alias]=entry
                continue
            slot=entry.get('slot'); index=entry.get('signalIndex')
            binding=slots.get(slot) if isinstance(slot,str) else None
            source=by_source.get(binding.get('sourceId')) if isinstance(binding,dict) else None
            if (entry.get('state')!='RESERVED_NOT_AUTHORIZED' or source is None
                    or type(index) is not int or index not in (0,1)
                    or alias!=slot+':signal_'+str(index)
                    or entry.get('sourceKey')!=binding.get('sourceKey')):
                raise CompletionTechnicalError('select_review_input_binding')
            source['reviewSignalReservations'][alias]=entry
        # Only existing ACTIVE acts remain a global request catalog. A reserved
        # address is kept beside its source, never materialized as an act here.
        data['presentationRequests']=active
        from .diagnostics import canonical
        message['content']=canonical(data)
    return result


def agent_wire(messages,tools,phase='SELECT',*,codec=None):
    # Pinned langchain-openai converter, also used by ChatOpenAI's actual
    # _get_request_payload. Generic core conversion retains a Tool name/empty
    # AI string that the SDK converter removes; it is not the same wire.
    from langchain_core.messages import AIMessage
    from langchain_openai.chat_models.base import _convert_message_to_dict, _convert_from_v1_to_chat_completions
    converted=[]
    for message in messages:
        value=_convert_message_to_dict(_convert_from_v1_to_chat_completions(message) if isinstance(message,AIMessage) else message)
        original=message.additional_kwargs.get('tool_calls',[])
        if isinstance(message,AIMessage) and original:
            by_id={call['id']:call for call in original}
            for call in value.get('tool_calls',[]):
                raw=by_id.get(call['id'])
                if not raw or raw['function']['name']!=call['function']['name'] or json.loads(
                        raw['function']['arguments'],object_pairs_hook=llm.unique_object)!=json.loads(call['function']['arguments']):
                    raise CompletionTechnicalError('wire_original_tool_arguments_mismatch')
                call['function']['arguments']=raw['function']['arguments']
        converted.append(value)
    if phase=='SELECT':
        converted=select_review_inputs(converted)
    codec=codec or llm.wire_codec()
    encoded_tools=codec.encode_tools(tools)
    converted=codec.encode_messages(converted)
    return {'model':MODEL,'temperature':0.0,'messages':converted,'tools':encoded_tools,
        'tool_choice':'required','parallel_tool_calls':False,'stream':False,'max_completion_tokens':llm.PHASES[phase][1]}


@dataclass(frozen=True)
class ToolSelection:
    name: str
    call_id: str
    arguments_json: str
    schema_issue: str | None = None
    decoded_arguments: dict | None = None

    def arguments(self):
        if self.decoded_arguments is not None:
            return deepcopy(self.decoded_arguments)
        return json.loads(self.arguments_json,object_pairs_hook=llm.unique_object)


def parse_tool_response(raw,tools):
    try:
        choices=raw['choices']
        if len(choices)!=1:
            raise CompletionTechnicalError('agent_choice_count')
        choice=choices[0]; message=choice['message']
        if message.get('refusal'):
            raise CompletionTechnicalError('agent_refusal')
        if choice.get('finish_reason')!='tool_calls':
            raise CompletionTechnicalError('agent_finish_'+str(choice.get('finish_reason')))
        calls=message.get('tool_calls',[])
        if len(calls)!=1:
            raise CompletionTechnicalError('agent_tool_count')
        call=calls[0]; function=call['function']
        names={t['function']['name'] for t in tools}
        if function['name'] not in names:
            raise CompletionTechnicalError('agent_unknown_tool')
        if call.get('type')!='function' or not isinstance(call.get('id'),str) or not call['id']:
            raise CompletionTechnicalError('agent_tool_identity')
        arguments=function['arguments']
        if not isinstance(arguments,str):
            raise CompletionTechnicalError('agent_arguments_not_raw_string')
        try:
            parsed=json.loads(arguments,object_pairs_hook=llm.unique_object)
        except ValueError as exc:
            raise CompletionTechnicalError('agent_duplicate_json_key' if 'duplicate_json_key' in str(exc) else 'agent_arguments_parse') from None
        schema=next(t['function']['parameters'] for t in tools if t['function']['name']==function['name'])
        issue=None
        try:
            llm.validate_schema(parsed,schema)
        except ValueError:
            if function['name']!='present_pending_question' or 'requestIds' not in parsed.get('presentation',{}):
                raise
            # Resolve shared definitions first, then relax only the requestIds binding.
            def relax(node,request=False):
                if isinstance(node,dict):
                    if '$ref' in node:
                        return relax(schema['$defs'][node['$ref'].split('/')[-1]],request)
                    return {k:relax(v,request or k=='requestIds') for k,v in node.items()
                            if k!='$defs' and not (request and k=='enum')}
                if isinstance(node,list):
                    return [relax(v,request) for v in node]
                return node
            llm.validate_schema(parsed,relax(schema))
            issue='requestIds_binding_only'
        return ToolSelection(function['name'],call['id'],arguments,issue)
    except (KeyError,TypeError,IndexError,ValueError) as exc:
        raise CompletionTechnicalError('agent_tool_schema_or_envelope_'+type(exc).__name__) from None


class CapturedRaw:
    def __init__(self,raw,owner,ticket):
        self.raw=raw; self.owner=owner; self.ticket=ticket
        self.headers=getattr(raw,'headers',{})
        self.http_response=getattr(raw,'http_response',None)
        self.parsed=None
    def parse(self):
        if self.parsed is None:
            try:
                self.parsed=self.raw.parse()  # LegacyAPIResponse.parse is synchronous in this version.
            except BaseException as exc:
                self.owner.budget.failed(self.ticket,exc)
                raise
            value=self.parsed.model_dump(mode='json') if hasattr(self.parsed,'model_dump') else self.parsed
            self.owner.budget.received(self.ticket,value)
            # Raw content (including arguments string) is saved before duplicate/schema checks.
            self.owner.selection=parse_tool_response(value,self.owner.tools)
        return self.parsed


class CaptureCompletions:
    def __init__(self,endpoint,budget,tools,phase,expected):
        self.endpoint=endpoint; self.budget=budget; self.tools=tools; self.selection=None
        self.phase=phase
        self.expected=deepcopy(expected)
        self.with_raw_response=SimpleNamespace(create=self.create_raw)
    async def create_raw(self,**kwargs):
        if 'response_format' in kwargs or kwargs.get('stream') or kwargs.get('parallel_tool_calls') is not False:
            raise CompletionTechnicalError('agent_provider_mode')
        # Restore only the previously received exact JSON encoding. IDs, tool
        # name, parsed arguments, messages and envelope must otherwise match.
        for actual,wanted in zip(kwargs.get('messages',[]),self.expected['messages']):
            if actual.get('role')=='assistant' and actual.get('tool_calls'):
                for call,original in zip(actual['tool_calls'],wanted.get('tool_calls',[])):
                    if (call.get('id')!=original.get('id') or call['function']['name']!=original['function']['name'] or
                            json.loads(call['function']['arguments'],object_pairs_hook=llm.unique_object)!=json.loads(original['function']['arguments'],object_pairs_hook=llm.unique_object)):
                        raise CompletionTechnicalError('actual_tool_arguments_changed')
                    call['function']['arguments']=original['function']['arguments']
        from .diagnostics import canonical,sha
        expected_json=canonical(self.expected)
        actual_json=canonical(kwargs)
        # Python equality equates 0 with 0.0 (and False); wire equality does not.
        if actual_json!=expected_json:
            raise CompletionTechnicalError('admission_actual_wire_mismatch')
        self.budget.diagnostics.emit('provider_wire_binding',phase=self.phase,
            admittedWireSha256=sha(expected_json),actualWireSha256=sha(actual_json),equal=True)
        ticket=self.budget.admit('agent',kwargs,self.phase)
        try:
            raw=await asyncio.wait_for(self.endpoint.with_raw_response.create(**kwargs),llm.remaining_timeout())
            return CapturedRaw(raw,self,ticket)
        except BaseException as exc:
            self.budget.failed(ticket,exc)
            raise


class NoSync:
    def create(self,**kwargs):
        raise CompletionTechnicalError('synchronous_provider_not_supported')


async def choose(messages,tools,budget,injected=None,*,phase='SELECT'):
    # Injection is the genuine async completions resource interface, NOT an LLM bypass.
    endpoint=injected if injected is not None else llm.client().chat.completions
    expected=agent_wire(messages,tools,phase,codec=budget.wire_codec)
    capture=CaptureCompletions(endpoint,budget,expected['tools'],phase,expected)
    model=ChatOpenAI(model=MODEL,temperature=0,max_retries=0,timeout=llm.remaining_timeout(),
        max_completion_tokens=llm.PHASES[phase][1],streaming=False,use_responses_api=False,
        client=NoSync(),async_client=capture,api_key='offline-fixture' if injected is not None else None,
        cache=False)
    from langchain_openai.chat_models.base import _convert_dict_to_message
    bound=model.bind_tools(expected['tools'],tool_choice='required',parallel_tool_calls=False,strict=True)
    message=await bound.ainvoke([_convert_dict_to_message(value) for value in expected['messages']],config={'callbacks':[]})
    if message.invalid_tool_calls:
        raise CompletionTechnicalError('agent_invalid_tool_calls')
    selected=capture.selection
    if selected is None or len(message.tool_calls)!=1:
        raise CompletionTechnicalError('agent_raw_capture_missing')
    converted=message.tool_calls[0]
    if (converted['id'],converted['name'],converted['args'])!=(selected.call_id,selected.name,selected.arguments()):
        raise CompletionTechnicalError('agent_converted_tool_mismatch')
    original_schema=next(t['function']['parameters'] for t in tools if t['function']['name']==selected.name)
    decoded=budget.wire_codec.decode_arguments(selected.arguments(),original_schema)
    selected=replace(selected,decoded_arguments=decoded)
    return selected,message
