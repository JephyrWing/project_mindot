"""Syntax/schema/serialization only. Does not instantiate providers or run tests."""
import ast
import hashlib
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'mindot_ai'))
from cbt_simple import schema, wire
import tiktoken


def main():
    out = Path(sys.argv[1])
    paths = [ROOT/'mindot_ai/app.py',ROOT/'mindot_ai/cbt_session_agent.py',ROOT/'mindot_ai/pattern_explanation.py',
             *sorted((ROOT/'mindot_ai/cbt_simple').glob('*.py')),
             *sorted((ROOT/'mindot_ai/tools').glob('insight_*.py')),
             ROOT/'mindot_ai/tests/test_insight_protocol.py',ROOT/'mindot_ai/tests/test_question_proposal_fix.py',
             ROOT/'mindot_ai/tests/test_q13_thought_change.py']
    syntax = []
    for path in paths:
        ast.parse(path.read_text(encoding='utf-8-sig'), filename=str(path))
        syntax.append(dict(path=path.relative_to(ROOT).as_posix(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    def inspect(shape):
        if shape.get('type') == 'object' or 'properties' in shape:
            if shape.get('additionalProperties') is not False or set(shape['required']) != set(shape['properties']):
                raise ValueError('strict_object_shape')
            for child in shape['properties'].values(): inspect(child)
        if 'items' in shape: inspect(shape['items'])
        for child in shape.get('anyOf',[]): inspect(child)
    for shape in [*schema.select_schemas().values(),schema.assessor_schema(),schema.review_schema()]:
        inspect(shape)
    expected_prompts={
        'agent.txt':(1884,'9beb197fbeada4a1c7994bb434a3629f2322d5f621790a935f2f882710c85a5b'),
        'assessor.txt':(1301,'5cc7f7c0db317bb6da49f268d07abe62fb0861304b30afb6b72ebb82c93089a7'),
        'assessment-review.txt':(601,'e90d4dc21712b0b61611b691b6fa795af3e4393bd74d5660c73bda0762ad3f90'),
    }
    prompt_contract={}
    for name,(characters,expected_sha) in expected_prompts.items():
        text=(ROOT/'mindot_ai/cbt_simple/prompts'/name).read_text(encoding='utf-8-sig').strip()
        actual=(len(text),hashlib.sha256(text.encode('utf-8')).hexdigest())
        if actual!=(characters,expected_sha):raise ValueError('q13_prompt_mismatch:'+name)
        prompt_contract[name]=dict(characters=characters,sha256=expected_sha)
    tokenizer=tiktoken.get_encoding('o200k_base')
    rows=[]
    for name,count in [('initial',0),('20_messages',20),('80_messages',80)]:
        snapshot=dict(record=dict(recordId=1,situation='회의에서 수정을 요청받았다.',automaticThought='나는 일을 잘 못한다.'),
            phase='DIALOGUE',historicalTypeReviews=[],currentProposal=None,
            messages=[dict(messageNumber=n+1,role='USER' if n%2 else 'ASSISTANT',content='상황과 생각을 살펴보고 있습니다. '*20) for n in range(count)])
        payload=dict(snapshot=snapshot)
        for phase in wire.PHASES:
            if phase=='SELECT':
                request=dict(model=wire.MODEL,temperature=0.0,stream=False,max_completion_tokens=wire.PHASES[phase],
                    messages=[dict(role={'system':'system','human':'user','ai':'assistant'}[m.type],content=m.content) for m in wire.messages(phase,payload)],
                    tools=schema.select_tools(),tool_choice='required',parallel_tool_calls=False)
            else:
                request=wire.wire(phase,payload)
            text=json.dumps(request,ensure_ascii=False,separators=(',',':'),allow_nan=False)
            tokens=len(tokenizer.encode(text,disallowed_special=()))+64
            rows.append(dict(sample=name,phase=phase,inputEstimate=tokens,requestBytes=len(text.encode('utf-8')),
                outputCap=wire.PHASES[phase],reservedContext=math.ceil(tokens*1.75)+wire.PHASES[phase]))
    prompts={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'mindot_ai/cbt_simple/prompts').glob('*.txt')}
    result=dict(kind='STATIC_ONLY',syntax=syntax,strictSchemaStructure='CHECKED_NOT_PROVIDER_ACCEPTANCE',
        inputTokenLimit=wire.INPUT_TOKEN_LIMIT,requestByteLimit=wire.REQUEST_BYTE_LIMIT,phases=wire.PHASES,
        generationPathCalls=dict(questionOrHelp=1,completionSuccess=3,
            completionNotEstablished=2,additionalAfterCompletionDecision=0),
        serialization=rows,promptHashes=prompts,q13PromptContract=prompt_contract,
        modelCalls=0,tests='NOT_RUN_PENDING_GPT_FULL_BRANCH_REVIEW',
        limitations=['Representative synthetic serialization only; no Agent/Assessor execution.',
            'SELECT includes configured SDK fields; actual framework wire and usage must be observed after review.',
            'Final review actual call/tool result is measured again at the provider boundary.'])
    out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(syntaxFiles=len(syntax),schema='static structure checked',modelCalls=0,output=str(out))))


if __name__=='__main__': main()
