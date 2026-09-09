"""Actual ChatOpenAI/SDK resource boundary, raw receipts and bounded dispatch."""
import asyncio
import json
import math
from contextvars import ContextVar
from pathlib import Path
from time import perf_counter
from types import SimpleNamespace
from langchain_openai import ChatOpenAI
from openai import AsyncOpenAI
from cbt_q11.contracts import CompletionTechnicalError
from cbt_q11.diagnostics import canonical, sha
from cbt_q11.llm import token_count, parse, unique_object, validate_schema
from . import schema

MODEL='gpt-4o-mini'
INPUT_TOKEN_LIMIT=48000
REQUEST_BYTE_LIMIT=196608
PHASES={'SELECT':8192,'WRITER':650,'ASSESSOR':1800,'ASSESSMENT_REVIEW':1200,'WRITER_REPAIR':650}
PROMPTS={k:(Path(__file__).parent/'prompts'/v).read_text(encoding='utf-8').removesuffix('\n') for k,v in {
    'SELECT':'agent.txt','WRITER':'writer.txt','ASSESSOR':'assessor.txt',
    'ASSESSMENT_REVIEW':'assessment-review.txt','WRITER_REPAIR':'writer-repair.txt'}.items()}
aggregate_guard=ContextVar('simple_aggregate_guard',default=None)

def measure(wire):
    text=json.dumps(wire,ensure_ascii=False,separators=(',',':'),allow_nan=False)
    count=token_count(text)+64
    return dict(inputEstimate=count,requestBytes=len(text.encode('utf-8')),
                reservation=math.ceil(count*1.75)+wire['max_completion_tokens'])

class Budget:
    def __init__(self,diagnostics,guard):
        self.diagnostics=diagnostics; self.guard=guard; self.ledger=[]; self.moderations=0
        self.deadline=perf_counter()+180
        self.aggregate=aggregate_guard.get()
    def check(self,phase,wire):
        if not self.guard() or perf_counter()>=self.deadline:
            raise CompletionTechnicalError('cancelled_or_deadline')
        size=measure(wire)
        if size['inputEstimate']>INPUT_TOKEN_LIMIT or size['requestBytes']>REQUEST_BYTE_LIMIT or size['reservation']>128000:
            raise CompletionTechnicalError('input_capacity')
        if wire['max_completion_tokens']!=PHASES[phase]:
            raise CompletionTechnicalError('output_cap')
        return size
    def admit(self,phase,wire):
        sequence=[r['phase'] for r in self.ledger]
        allowed={'SELECT':[], 'WRITER':['SELECT'], 'ASSESSOR':['SELECT'],
                 'ASSESSMENT_REVIEW':['SELECT','ASSESSOR'],'WRITER_REPAIR':['SELECT','WRITER']}
        if sequence!=allowed[phase]: raise CompletionTechnicalError('generation_budget_or_phase_order')
        size=self.check(phase,wire)
        if phase=='SELECT' and self.aggregate:
            # Reserve room for either two downstream phases before entering the
            # first call. This uses this request's measured view plus bounded
            # candidate/framing growth, not a flat 48k debit for every call.
            downstream=math.ceil((size['inputEstimate']+6000)*1.75)+1800
            self.aggregate.reserve_path([dict(phase='SELECT',**size),
                dict(phase='FOLLOWUP',reservation=downstream),dict(phase='FOLLOWUP',reservation=downstream)])
        if self.aggregate: self.aggregate.admit(phase,size,wire)
        row=dict(phase=phase,ordinal=len(self.ledger)+1,capacity=size,usage=None,started=perf_counter(),received=False)
        self.ledger.append(row)
        self.diagnostics.emit('component_input',phase=phase,ordinal=row['ordinal'],providerRequest=wire,
                              capacity=size,exactPromptSha256=sha(PROMPTS[phase]))
        self.diagnostics.count('general_invocation')
        self.diagnostics.emit('component_invocation',phase=phase,ordinal=row['ordinal'])
        return row
    def received(self,row,raw):
        if row['received']: raise CompletionTechnicalError('duplicate_provider_receipt')
        row.update(received=True,usage=raw.get('usage'))
        if self.aggregate: self.aggregate.received(row,raw)
        self.diagnostics.count('general_response')
        self.diagnostics.emit('component_response',phase=row['phase'],ordinal=row['ordinal'],raw=raw,
                              usage=raw.get('usage'),latencyMs=(perf_counter()-row['started'])*1000)
    def failed(self,row,exc):
        self.diagnostics.emit('component_failure',phase=row['phase'],ordinal=row['ordinal'],error=type(exc).__name__,
                              usage=None,retainedReservation=row['capacity']['reservation'])
    def reserve_path(self,requests):
        sizes=[dict(phase=p,**self.check(p,w)) for p,w in requests]
        if len(self.ledger)+len(sizes)>3: raise CompletionTechnicalError('path_generation_budget')
        if self.aggregate: self.aggregate.reserve_path(sizes)
        self.diagnostics.emit('mandatory_path_reservation',phases=sizes)

class RawResponse:
    def __init__(self,response,capture,ticket):
        self.response=response; self.capture=capture; self.ticket=ticket
        self.headers=getattr(response,'headers',{}); self.http_response=getattr(response,'http_response',None)
    def parse(self):
        obj=self.response.parse()
        raw=obj.model_dump(mode='json') if hasattr(obj,'model_dump') else obj
        self.capture.raw=raw
        self.capture.budget.received(self.ticket,raw)
        return obj

class Capture:
    def __init__(self,endpoint,budget):
        self.endpoint=endpoint; self.budget=budget; self.phase='SELECT'; self.raw=None
        self.with_raw_response=SimpleNamespace(create=self.create,parse=self.create)
    async def create(self,**kwargs):
        ticket=self.budget.admit(self.phase,kwargs)
        try:
            response=await asyncio.wait_for(self.endpoint.with_raw_response.create(**kwargs),30)
            return RawResponse(response,self,ticket)
        except BaseException as exc:
            self.budget.failed(ticket,exc); raise

class Provider:
    def __init__(self,budget,agent=None,writer=None,assessor=None):
        self.budget=budget
        self.client=AsyncOpenAI(max_retries=0,timeout=30) if any(x is None for x in (agent,writer,assessor)) else None
        default=self.client.chat.completions if self.client else None
        self.capture=Capture(agent or default,budget)
        self.agent=ChatOpenAI(model=MODEL,temperature=0.0,max_retries=0,timeout=30,
            max_completion_tokens=8192,streaming=False,use_responses_api=False,
            client=SimpleNamespace(create=self.no_sync),async_client=self.capture,
            root_async_client=SimpleNamespace(chat=SimpleNamespace(completions=self.capture)),
            api_key='offline-fixture' if agent is not None else None,cache=False)
        self.writer=writer or default; self.assessor=assessor or default
    def no_sync(self,**kwargs): raise CompletionTechnicalError('sync_provider_forbidden')
    async def close(self):
        if self.client: await self.client.close()
    async def choose(self,messages):
        self.capture.phase='SELECT'
        msg=await self.agent.bind_tools(schema.select_tools(),tool_choice='required',parallel_tool_calls=False,strict=True).ainvoke(messages,config={'callbacks':[]})
        raw=self.capture.raw
        if not raw or len(raw['choices'])!=1: raise CompletionTechnicalError('agent_missing_receipt')
        choice=raw['choices'][0]; calls=choice['message'].get('tool_calls',[])
        if choice['finish_reason']!='tool_calls' or len(calls)!=1 or choice['message'].get('refusal'):
            raise CompletionTechnicalError('agent_tool_selection')
        call=calls[0]; args=json.loads(call['function']['arguments'],object_pairs_hook=unique_object)
        name=call['function']['name']
        if name not in schema.select_schemas(): raise CompletionTechnicalError('unknown_tool')
        validate_schema(args,schema.select_schemas()[name])
        if msg.invalid_tool_calls or len(msg.tool_calls)!=1 or msg.tool_calls[0]['args']!=args:
            raise CompletionTechnicalError('agent_parsed_tool_mismatch')
        return msg,dict(name=name,args=args,id=call['id'])
    def wire(self,phase,payload):
        shapes={'WRITER':schema.writer_schema,'WRITER_REPAIR':schema.writer_repair_schema,'ASSESSOR':schema.assessor_schema,
                'ASSESSMENT_REVIEW':schema.review_schema}
        return dict(model=MODEL,temperature=0.3 if phase in ('WRITER','WRITER_REPAIR') else 0.0,
            messages=[dict(role='system',content=PROMPTS[phase]),dict(role='user',content=canonical(payload))],
            response_format=schema.response_format('cbt_'+phase.lower(),shapes[phase]()),max_completion_tokens=PHASES[phase])
    async def structured(self,phase,payload):
        wire=self.wire(phase,payload); ticket=self.budget.admit(phase,wire)
        try:
            endpoint=self.assessor if phase=='ASSESSOR' else self.writer
            result=await asyncio.wait_for(endpoint.create(**wire),30)
            raw=result.model_dump(mode='json') if hasattr(result,'model_dump') else result
        except BaseException as exc:
            self.budget.failed(ticket,exc); raise
        self.budget.received(ticket,raw)
        value=parse(raw)
        if value.status!='USABLE':
            if value.status=='PARSE_ERROR': raise WriterFormatError('json_structure')
            raise CompletionTechnicalError(phase+'_'+value.status)
        try: validate_schema(value.output,wire['response_format']['json_schema']['schema'])
        except ValueError as exc: raise WriterFormatError(str(exc)) from exc
        return value.output
    async def review(self,messages):
        self.capture.phase='ASSESSMENT_REVIEW'
        msg=await self.agent.bind(max_completion_tokens=1200,
            response_format=schema.response_format('cbt_review',schema.review_schema())).ainvoke(messages,config={'callbacks':[]})
        raw=self.capture.raw
        parsed=parse(raw)
        if parsed.status!='USABLE': raise CompletionTechnicalError('review_'+parsed.status)
        validate_schema(parsed.output,schema.review_schema())
        return msg,parsed.output

class WriterFormatError(CompletionTechnicalError):
    pass
