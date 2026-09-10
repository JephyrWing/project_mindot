"""Single invocation, raw-first parsing, no SDK/transport/semantic retries."""
import asyncio
import json
import re
from dataclasses import dataclass
from time import perf_counter
from contextvars import ContextVar
import tiktoken
from openai import AsyncOpenAI, APIConnectionError, APITimeoutError
from .contracts import MODEL, CompletionTechnicalError
from .diagnostics import canonical, sha

TIMEOUT_SECONDS = 30.0
PHASES = {'SELECT':('agent',8192),'ASSESSOR':('assessor',1800),'WRITER':('writer',650),
    'ASSESSMENT_REVIEW':('agent',1200),'ARGUMENT_REPAIR':('agent',650),
    'SAFETY_RECHECK':('agent',1200),'WRITER_REPAIR':('writer',650)}
EXTRA_PHASES = {'ASSESSMENT_REVIEW','ARGUMENT_REPAIR','SAFETY_RECHECK','WRITER_REPAIR'}
INPUT_TOKEN_LIMIT = 48000
REQUEST_BYTE_LIMIT = 196608
DEADLINE = ContextVar('cbt_deadline', default=None)
_encoding = None
_client = None

def remaining_timeout(limit=TIMEOUT_SECONDS):
    deadline = DEADLINE.get()
    remaining = limit if deadline is None else min(limit, deadline-perf_counter())
    if remaining <= 0:
        raise CompletionTechnicalError('turn_deadline_exhausted')
    return remaining

def token_count(value):
    global _encoding
    if _encoding is None:
        _encoding = tiktoken.get_encoding('o200k_base')
    text = value if isinstance(value,str) else json.dumps(value,ensure_ascii=False)
    return len(_encoding.encode(text,disallowed_special=()))

def measure(kwargs):
    # Match the installed SDK's httpx JSON encoder, including UTF-8 and compact
    # separators. Whitespace from a different JSON representation is not sent.
    # This remains an estimate, with the same explicit framing reserve/caps.
    serialized=json.dumps(kwargs,ensure_ascii=False,separators=(',',':'),allow_nan=False)
    tokens = token_count(serialized) + 64
    return {'serializedInputTokensWithFramingReserve':tokens,
            'serializedRequestBytes':len(serialized.encode('utf-8')),
            'maxOutputTokens':kwargs.get('max_completion_tokens',kwargs.get('max_tokens',0)),
            'contextReservation':tokens+kwargs['max_completion_tokens']}

def client():
    global _client
    if _client is None:
        _client = AsyncOpenAI(base_url='https://api.openai.com/v1',max_retries=0,timeout=TIMEOUT_SECONDS)
    return _client

async def close_client():
    global _client
    current, _client = _client, None
    if current is not None:
        await current.close()

def validate_schema(value, schema, root=None):
    """Validator for the exact, small supplied schema vocabulary; no coercion."""
    root = schema if root is None else root
    if '$ref' in schema:
        return validate_schema(value,root['$defs'][schema['$ref'].split('/')[-1]],root)
    if 'anyOf' in schema:
        for branch in schema['anyOf']:
            try:
                validate_schema(value,branch,root)
                return
            except ValueError:
                pass
        raise ValueError('schema_union')
    kind = schema.get('type')
    valid = {'object':isinstance(value,dict),'array':isinstance(value,list),
             'string':isinstance(value,str),'null':value is None,
             'boolean':type(value) is bool,'integer':type(value) is int,
             'number':type(value) in (int,float)}
    if kind and not valid[kind]:
        raise ValueError('schema_type_'+kind)
    if 'enum' in schema and value not in schema['enum']:
        raise ValueError('schema_enum')
    if kind in ('integer','number'):
        if value < schema.get('minimum',float('-inf')) or value > schema.get('maximum',float('inf')):
            raise ValueError('schema_numeric_bounds')
    if kind == 'object':
        if not set(schema.get('required',())) <= value.keys():
            raise ValueError('schema_required')
        if schema.get('additionalProperties') is False and not value.keys() <= schema['properties'].keys():
            raise ValueError('schema_additional')
        for k,v in value.items():
            if k in schema.get('properties',{}):
                validate_schema(v,schema['properties'][k],root)
    if kind == 'array':
        if not schema.get('minItems',0) <= len(value) <= schema.get('maxItems',float('inf')):
            raise ValueError('schema_array_length')
        for item in value:
            validate_schema(item,schema['items'],root)
    if kind == 'string':
        if not schema.get('minLength',0) <= len(value) <= schema.get('maxLength',float('inf')):
            raise ValueError('schema_string_length')
        if 'pattern' in schema and not re.search(schema['pattern'],value):
            raise ValueError('schema_pattern')

@dataclass(frozen=True)
class ComponentResult:
    status: str
    output: dict | None = None
    error: str | None = None

def unique_object(pairs):
    result = {}
    for key,value in pairs:
        if key in result:
            raise ValueError('duplicate_json_key')
        result[key]=value
    return result

def parse(raw):
    # Fakes must use the same received Chat Completions envelope as production.
    try:
        if len(raw['choices'])!=1:
            raise ValueError('choice_count')
        choice = raw['choices'][0]
        message = choice['message']
        if message.get('refusal'):
            return ComponentResult('REFUSAL')
        if choice.get('finish_reason') != 'stop':
            return ComponentResult('INCOMPLETE')
        content = message['content']
        if not isinstance(content,str):
            raise ValueError('content_not_string')
        value = json.loads(content,object_pairs_hook=unique_object)
        if not isinstance(value,dict):
            raise ValueError('root_not_object')
        return ComponentResult('USABLE',value)
    except (KeyError,ValueError,TypeError,IndexError):
        return ComponentResult('PARSE_ERROR')

def request_kwargs(component,prompt,response_format,payload,phase=None):
    phase=phase or {'agent':'SELECT','assessor':'ASSESSOR','writer':'WRITER'}[component]
    codec=wire_codec()
    response_format=dict(response_format)
    response_format['json_schema']={**response_format['json_schema'],
        'schema':codec.encode_schema(response_format['json_schema']['schema'])}
    return {'model':MODEL,'temperature':0.3 if component=='writer' else 0.0,
            'messages':[{'role':'system','content':prompt},
                        {'role':'user','content':canonical(codec.encode_value(payload))}],
            'response_format':response_format,'max_completion_tokens':PHASES[phase][1]}

REQUEST_BUDGET = ContextVar('cbt_request_budget',default=None)

def wire_codec():
    from .wire_ids import IdentityCodec
    scope=REQUEST_BUDGET.get()
    return scope.wire_codec if scope is not None else IdentityCodec()

class Admission:
    """Actual SDK entries, not successful responses. Unknown usage retains reservation."""
    def __init__(self,diagnostics,guard=None):
        from .wire_ids import IdentityCodec
        self.diagnostics=diagnostics
        self.guard=guard
        self.wire_codec=IdentityCodec()
        self.attempts={}
        self.ledger=[]
        self.extra={'state':'UNUSED','purpose':None}
        self.reservations=[]
    def snapshot(self):
        return {'generationRemaining':3-len(self.ledger),'extraEntitlement':dict(self.extra),
            'dispatchedPhases':[t['phase'] for t in self.ledger],'automaticRetries':0}
    def reserve_extra(self,purpose):
        if purpose not in EXTRA_PHASES or self.extra['state']!='UNUSED':
            raise CompletionTechnicalError('extra_entitlement_unavailable')
        self.extra={'state':'RESERVED','purpose':purpose}
    def release_extra(self,purpose):
        if self.extra!={'state':'RESERVED','purpose':purpose}:
            raise CompletionTechnicalError('dispatched_extra_cannot_release')
        self.extra={'state':'UNUSED','purpose':None}
    def reserve_path(self,requests):
        """Validate every mandatory future wire before entering the first one."""
        if len(self.ledger)+len(requests)>3:
            raise CompletionTechnicalError('mandatory_path_exceeds_three')
        remaining_timeout(TIMEOUT_SECONDS*len(requests))
        deadline=DEADLINE.get()
        if deadline is not None and deadline-perf_counter()<TIMEOUT_SECONDS*len(requests):
            raise CompletionTechnicalError('mandatory_path_deadline_reservation')
        rows=[]
        for phase,wire in requests:
            role,limit=PHASES[phase]; size=measure(wire)
            if (size['serializedInputTokensWithFramingReserve']>INPUT_TOKEN_LIMIT or
                    size['serializedRequestBytes']>REQUEST_BYTE_LIMIT or size['contextReservation']>128000
                    or size['maxOutputTokens']!=limit):
                raise CompletionTechnicalError('mandatory_phase_capacity:'+phase)
            rows.append({'phase':phase,'capacity':size})
        self.reservations=rows
        self.diagnostics.emit('mandatory_path_reservation',phases=rows,extra=dict(self.extra))
    def admit(self,role,kwargs,phase=None):
        phase=phase or {'agent':'SELECT','assessor':'ASSESSOR','writer':'WRITER'}[role]
        remaining_timeout()
        if self.guard is not None and not self.guard():
            raise CompletionTechnicalError('provider_after_cancel_or_close')
        sequence=[t['phase'] for t in self.ledger]
        allowed={'SELECT':not sequence,
            'ASSESSOR':sequence==['SELECT'] and self.extra=={'state':'RESERVED','purpose':'ASSESSMENT_REVIEW'},
            'WRITER':sequence in (['SELECT'],['SELECT','ARGUMENT_REPAIR'],['SELECT','SAFETY_RECHECK']),
            'ASSESSMENT_REVIEW':sequence==['SELECT','ASSESSOR'],
            'ARGUMENT_REPAIR':sequence==['SELECT'],'SAFETY_RECHECK':sequence==['SELECT'],
            'WRITER_REPAIR':sequence==['SELECT','WRITER']}
        if len(sequence)>=3 or not allowed.get(phase) or PHASES[phase][0]!=role:
            raise CompletionTechnicalError('generation_phase_or_attempt_budget')
        if phase in EXTRA_PHASES and self.extra!={'state':'RESERVED','purpose':phase}:
            raise CompletionTechnicalError('extra_phase_not_reserved')
        size=measure(kwargs)
        if (size['serializedInputTokensWithFramingReserve']>INPUT_TOKEN_LIMIT or
                size['serializedRequestBytes']>REQUEST_BYTE_LIMIT or size['contextReservation']>128000
                or size['maxOutputTokens']!=PHASES[phase][1]):
            raise CompletionTechnicalError('provider_input_or_output_reservation_capacity')
        if kwargs.get('model')!=MODEL or kwargs.get('temperature')!=(0.3 if role=='writer' else 0):
            raise CompletionTechnicalError('provider_role_settings')
        if self.reservations:
            if self.reservations[0]['phase']!=phase:
                raise CompletionTechnicalError('mandatory_phase_order')
            self.reservations.pop(0)
        ordinal=len(sequence)+1
        from .prompts import PHASE_PROMPTS
        self.diagnostics.emit('phase_settings',phase=phase,ordinal=ordinal,model=MODEL,
            temperature=kwargs['temperature'],maxOutputTokens=PHASES[phase][1],
            exactPromptSha256=sha(PHASE_PROMPTS[phase]),promptCodePoints=len(PHASE_PROMPTS[phase]),
            sentSystemPromptHashes=[sha(m['content']) for m in kwargs['messages']
                if m['role'] in ('system','developer') and isinstance(m.get('content'),str)])
        self.diagnostics.emit('component_input',component=role,phase=phase,ordinal=ordinal,providerRequest=kwargs,capacity=size)
        self.diagnostics.emit('wire_identity_bindings',phase=phase,bindings=self.wire_codec.mapping())
        ticket={'component':role,'phase':phase,'ordinal':ordinal,'extraPurpose':self.extra['purpose'],
            'attempt':self.attempts.get(role,0)+1,'inputEstimate':size,'reservation':size['contextReservation'],
            'usage':None,'started':perf_counter(),'settled':False}
        # Actual entry consumes the entitlement even if transport/parse later fails.
        if phase in EXTRA_PHASES:
            self.extra['state']='SPENT'
        self.ledger.append(ticket); self.attempts[role]=ticket['attempt']
        self.diagnostics.count(role+'_invocation'); self.diagnostics.count('general_invocation')
        self.diagnostics.emit('component_invocation',component=role,phase=phase,ordinal=ordinal,extraPurpose=self.extra['purpose'])
        return ticket
    def received(self,ticket,raw):
        if ticket['settled']:
            raise CompletionTechnicalError('duplicate_usage_settlement')
        ticket['settled']=True; ticket['usage']=raw.get('usage')
        usage=ticket['usage']
        ticket['chargedTokens']=(usage['prompt_tokens']+usage['completion_tokens'] if isinstance(usage,dict)
            and all(type(usage.get(k)) is int for k in ('prompt_tokens','completion_tokens')) else ticket['reservation'])
        ticket['latencyMs']=(perf_counter()-ticket['started'])*1000
        self.diagnostics.count(ticket['component']+'_response'); self.diagnostics.count('general_response')
        self.diagnostics.emit('component_response',component=ticket['component'],phase=ticket['phase'],ordinal=ticket['ordinal'],extraPurpose=ticket['extraPurpose'],raw=raw,
            usage=usage,latencyMs=ticket['latencyMs'],cachedTokensAreInputSubset=True)
    def failed(self,ticket,exc):
        if ticket['settled']:
            return
        ticket['settled']=True; ticket['chargedTokens']=ticket['reservation']
        self.diagnostics.emit('component_failure',component=ticket['component'],phase=ticket['phase'],ordinal=ticket['ordinal'],
            extraPurpose=ticket['extraPurpose'],error=type(exc).__name__,
            providerExecution='UNKNOWN',usage=None,chargedReservation=ticket['reservation'],
            latencyMs=(perf_counter()-ticket['started'])*1000)
    def admit_moderation(self,kwargs):
        remaining_timeout()
        if self.guard is not None and not self.guard():
            raise CompletionTechnicalError('moderation_after_cancel_or_close')
        if self.diagnostics.counters.get('moderation_invocation',0)>=1:
            raise CompletionTechnicalError('moderation_attempt_budget')
        self.diagnostics.emit('moderation_input',providerRequest=kwargs,inputTokens=token_count(kwargs),
            bytes=len(json.dumps(kwargs,ensure_ascii=False).encode('utf-8')),genericUsage=False)
        self.diagnostics.count('moderation_invocation')

def budget():
    value=REQUEST_BUDGET.get()
    if value is None:
        raise CompletionTechnicalError('provider_outside_request_scope')
    return value

async def call(component,prompt,response_format,payload,diagnostics,model=None,*,phase=None):
    kwargs=request_kwargs(component,prompt,response_format,payload,phase)
    scope=budget()
    ticket=scope.admit(component,kwargs,phase)
    try:
        endpoint=model if model is not None else client().chat.completions
        response=await asyncio.wait_for(endpoint.create(**kwargs),remaining_timeout())
    except BaseException as exc:
        scope.failed(ticket,exc)
        if isinstance(exc,asyncio.CancelledError):
            raise
        return ComponentResult('NO_RESPONSE' if isinstance(exc,(APIConnectionError,APITimeoutError,TimeoutError))
                               else 'PROVIDER_ERROR',error=type(exc).__name__)
    raw=response.model_dump(mode='json') if hasattr(response,'model_dump') else response
    scope.received(ticket,raw)
    result=parse(raw)
    if result.status=='USABLE':
        try:
            decoded=scope.wire_codec.decode_arguments(result.output,response_format['json_schema']['schema'])
            result=ComponentResult('USABLE',decoded)
        except ValueError as exc:
            result=ComponentResult('PARSE_ERROR',error=type(exc).__name__)
    diagnostics.emit('component_result',component=component,status=result.status)
    return result
