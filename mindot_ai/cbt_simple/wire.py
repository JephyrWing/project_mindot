"""Pure request serialization: no SDK clients, model invocation or application import."""
import json
from pathlib import Path
from langchain_core.messages import SystemMessage,HumanMessage,AIMessage
from . import schema

def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))

MODEL='gpt-4o-mini'
INPUT_TOKEN_LIMIT=48000
REQUEST_BYTE_LIMIT=196608
PHASES={'SELECT':8192,'WRITER':650,'ASSESSOR':1800,'ASSESSMENT_REVIEW':1200,'WRITER_REPAIR':650}
PROMPTS={k:(Path(__file__).parent/'prompts'/v).read_text(encoding='utf-8').removesuffix('\n') for k,v in {
    'SELECT':'agent.txt','WRITER':'writer.txt','ASSESSOR':'assessor.txt',
    'ASSESSMENT_REVIEW':'assessment-review.txt','WRITER_REPAIR':'writer-repair.txt'}.items()}

def messages(phase,payload,pair=()):
    snapshot=payload['snapshot']
    context=dict(record=snapshot['record'],phase=snapshot['phase'],currentProposal=snapshot.get('currentProposal'),
        historicalTypeReviews=snapshot.get('historicalTypeReviews',[]),moderation=snapshot.get('moderation'))
    context.update({k:v for k,v in payload.items() if k!='snapshot'})
    if phase in ('WRITER','WRITER_REPAIR'):
        if payload['mode']=='EXPLAIN_PROPOSAL':
            codes={s['code'] for s in (snapshot.get('currentProposal') or {}).get('suggestions',[])}
            context['distortionDefinitions']=[d for d in schema.DEFINITIONS if d['code'] in codes]
    else:
        context['distortionDefinitions']=schema.DEFINITIONS
    result=[SystemMessage(content=PROMPTS[phase]),HumanMessage(content=canonical(context))]
    for row in snapshot['messages']:
        cls=HumanMessage if row['role']=='USER' else AIMessage
        result.append(cls(content=canonical(dict(messageNumber=row['messageNumber'],speaker=row['role'],content=row['content']))))
    return [*result,*pair]

def wire(phase,payload):
    shapes={'WRITER':schema.writer_schema,'WRITER_REPAIR':schema.writer_repair_schema,'ASSESSOR':schema.assessor_schema,
            'ASSESSMENT_REVIEW':schema.review_schema}
    return dict(model=MODEL,temperature=0.3 if phase in ('WRITER','WRITER_REPAIR') else 0.0,
        messages=[dict(role={'system':'system','human':'user','ai':'assistant'}[m.type],content=m.content) for m in messages(phase,payload)],
        response_format=schema.response_format('cbt_'+phase.lower(),shapes[phase]()),max_completion_tokens=PHASES[phase])
