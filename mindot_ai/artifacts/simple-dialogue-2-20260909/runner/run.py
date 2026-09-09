"""One locked canary round; durable raw receipts and no quality retries."""
import argparse,asyncio,hashlib,json,math,os,sys,time
from pathlib import Path
from datetime import datetime,timezone,timedelta
from copy import deepcopy
OUT=Path(__file__).resolve().parents[1]
AI=OUT.parents[1]
sys.path.insert(0,str(AI))
def canon(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha(data):return hashlib.sha256(data).hexdigest()
def write(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def append(path,value):
    with path.open('a',encoding='utf-8') as f:
        f.write(canon(value)+'\n');f.flush();os.fsync(f.fileno())

def source_files():
    roots=[AI/'cbt_simple',AI/'cbt_q11',OUT/'runner',OUT/'baseline']
    files=[AI/'cbt_session_agent.py',AI/'cbt_agent.py',AI/'app.py',AI/'records_agent.py',AI/'requirements.txt',AI/'tests/test_simple_dialogue.py']
    files += [p for root in roots for p in root.rglob('*') if p.is_file() and p.suffix in ('.py','.txt') and '__pycache__' not in p.parts]
    files += list((OUT/'known').glob('*.json'))
    files += [OUT/'package/docs/cbt-q11-simple/canary-plan.json',OUT/'package/manifest.json',OUT/'grader-hash-only.txt']
    files += [OUT/'openapi.json',OUT/'offline-receipt.json',OUT/'revision-checks.json',OUT/'review.md']
    files += [p for p in (OUT/'package').rglob('*') if p.is_file()]
    return sorted(set(files))

def lock():
    from cbt_simple.provider import PHASES,PROMPTS,INPUT_TOKEN_LIMIT,REQUEST_BYTE_LIMIT
    from cbt_simple import schema
    import neutral,input_plan
    known=input_plan.load_known(OUT/'known')
    schedule=input_plan.build_schedule(known)
    write(OUT/'known-schedule.json',schedule)
    import importlib.metadata
    files=[dict(path=p.relative_to(AI).as_posix(),bytes=p.stat().st_size,sha256=sha(p.read_bytes())) for p in source_files()]
    write(OUT/'classification-sidecar.json',known['classificationSidecar'])
    value=dict(revision='simple-dialogue-2',knownRevision=known['knownRevision'],rubricVersion='1.12',createdAt=datetime.now(timezone.utc).isoformat(),files=files,
        sourceHash=sha(canon(files).encode()),model='gpt-4o-mini',temperatures=dict(agent=0,assessor=0,writer=.3),
        phaseOutputCaps=PHASES,inputTokens=INPUT_TOKEN_LIMIT,inputBytes=REQUEST_BYTE_LIMIT,inputReservationFactor=1.75,
        modelContext=128000,modelContextSource='https://developers.openai.com/api/docs/models/gpt-4o-mini',
        providerTimeoutSeconds=30,productTimeoutSeconds=180,checkpointTTLSeconds=600,
        canaryTokenCap=1750000,generationCap=36,moderationCap=12,productRequestCap=12,
        prompts={k:dict(sha256=sha(v.encode()),codepoints=len(v)) for k,v in PROMPTS.items()},
        schemaSha256=sha(canon(schema.provider_examples()).encode()),neutralSchemaSha256=sha(canon(neutral.SCHEMA).encode()),
        python=sys.executable,pythonVersion=sys.version,
        dependencies={n:importlib.metadata.version(n) for n in ('openai','langchain-openai','langchain-core','langgraph','pydantic','tiktoken','fastapi')},
        officialPreReleaseFormula='known220 + hiddenSingle12 + sum(hiddenLong.maxTurns) per version',
        knownCeilings=schedule['ceilings'],knownScheduleSha256=schedule['scheduleSha256'],classificationSidecarSha256=sha(canon(known['classificationSidecar']).encode()),formalSeed=20260908,modelSeed='NOT_APPLIED_BOTH_VERSIONS',
        hiddenKeyRead=False,officialResults=0)
    product=[f for f in files if not f['path'].startswith('artifacts/')]
    value['productSourceHash']=sha(canon(product).encode())
    value['sourceHashScope']='All locked product, runner, baseline, supplied input/expectation and audit files; productSourceHash separately identifies product files.'
    write(OUT/'execution-lock.json',value);write(OUT/'neutral-schema.json',neutral.SCHEMA)
    return value

def verify_lock():
    value=json.loads((OUT/'execution-lock.json').read_text(encoding='utf-8'))
    for f in value['files']:
        p=AI/f['path']
        if sha(p.read_bytes())!=f['sha256']:raise RuntimeError('LOCK_CHANGED:'+f['path'])
    return value

class Guard:
    def __init__(self,path):self.path=path;self.generations=0;self.moderations=0;self.charged=0;self.actual=0;self.pending={}
    def check(self,reservation,calls=1):
        if self.generations+calls>36 or self.charged+reservation>1750000:raise RuntimeError('BUDGET_BLOCKED')
    def reserve_path(self,sizes):self.check(sum(s['reservation'] for s in sizes),len(sizes))
    def admit(self,phase,size,wire):
        self.check(size['reservation']);self.generations+=1;self.charged+=size['reservation']
        append(self.path,dict(event='budget_reservation',phase=phase,reservation=size['reservation'],generation=self.generations,charged=self.charged))
    def received(self,ticket,raw):
        u=raw.get('usage')
        if isinstance(u,dict) and all(type(u.get(k)) is int for k in ('prompt_tokens','completion_tokens')):
            tokens=u['prompt_tokens']+u['completion_tokens'];self.actual+=tokens
            self.charged+=tokens-ticket['capacity']['reservation']
        append(self.path,dict(event='budget_settlement',phase=ticket['phase'],usage=u,charged=self.charged,observedTokens=self.actual))
    def moderation(self,wire):
        if self.moderations>=12:raise RuntimeError('MODERATION_BUDGET_BLOCKED')
        self.moderations+=1;append(self.path,dict(event='moderation_reservation',count=self.moderations))
    def summary(self):return dict(generationDispatches=self.generations,moderationDispatches=self.moderations,
        observedGenericTokens=self.actual,observedPlusUnknownReservations=self.charged,unknownReservedTokens=self.charged-self.actual)

def child_request(case,parent):
    response=parent['response'];req=deepcopy(parent['request'])
    if not response or not response.get('nextQuestion'):return None
    q=deepcopy(response['nextQuestion']);asked=datetime.fromisoformat(case['parentQuestionAskedAt'].replace('Z','+00:00'))
    q.update(answer=case['answer'],askedAt=case['parentQuestionAskedAt'],answeredAt=(asked+timedelta(seconds=case['answeredAtOffsetSeconds'])).isoformat())
    req.update(requestId=case['requestId'],currentStep=q['questionCode'],questionAnswers=req.get('questionAnswers',[])+[q],beforeDistortions=req.get('beforeDistortions',[]))
    return req

def parent_confirmation_candidate(parent):
    if (parent.get('response') or {}).get('status')=='CONFIRM_REQUIRED':return parent['caseId']
    return parent.get('confirmationAncestor')

async def canary():
    lock=verify_lock()
    marker=OUT/'CANARY_STARTED.json'
    if marker.exists(): raise RuntimeError('ONE_ROUND_ALREADY_STARTED_NO_RETRY')
    from dotenv import load_dotenv
    load_dotenv(AI.parent/'infra/.env.local',override=False)
    if not os.environ.get('OPENAI_API_KEY'):raise RuntimeError('OPENAI_API_KEY_UNAVAILABLE')
    os.environ['LANGSMITH_TRACING']='false';os.environ['CBT_DEBUG_LOG_ANALYSIS']='false'
    from cbt_agent import CbtStartRequest,CbtTurnRequest
    from cbt_session_agent import generate_agent_cbt_start,generate_agent_cbt_turn
    from cbt_q11.state import SessionRegistry
    from cbt_q11.diagnostics import diagnostic_sink
    from cbt_simple.provider import aggregate_guard
    import neutral
    plan=json.loads((OUT/'package/docs/cbt-q11-simple/canary-plan.json').read_text(encoding='utf-8'))
    results={};registry=SessionRegistry(600);live=OUT/'live';live.mkdir(exist_ok=True)
    guard=Guard(live/'budget.jsonl');token=aggregate_guard.set(guard)
    write(marker,dict(sourceHash=lock['sourceHash'],startedAt=datetime.now(timezone.utc).isoformat(),round=1))
    try:
        for case in plan['cases']:
            verify_lock()
            if 'parentId' in case:
                req=child_request(case,results[case['parentId']])
            else:req=deepcopy(case['request'])
            if req is None:
                ancestor=parent_confirmation_candidate(results[case['parentId']])
                row=dict(caseId=case['id'],status='NOT_RUN',executionOutcome='NOT_RUN',
                    acceptanceOutcome='UNRESOLVED' if ancestor else 'BLOCKED_BY_PARENT',confirmationAncestor=ancestor,
                    parentConfirmationRequiresFunctionReview=bool(ancestor),request=None,response=None,error=None)
            else:
                started=time.perf_counter();events=[];response=None;error=None
                def sink(event):
                    append(live/(case['id']+'.events.jsonl'),event);events.append(event)
                dt=diagnostic_sink.set(sink)
                try:
                    append(live/'journal.jsonl',dict(event='product_request',caseId=case['id'],request=req))
                    cls=CbtTurnRequest if 'questionAnswers' in req else CbtStartRequest
                    method=generate_agent_cbt_turn if cls is CbtTurnRequest else generate_agent_cbt_start
                    response=await method(cls.model_validate(req),registry=registry)
                    response=response.model_dump(by_alias=True,mode='json')
                except Exception as exc:error=dict(type=type(exc).__name__,reason=str(exc))
                finally:diagnostic_sink.reset(dt)
                runtime=await registry.get(req['sessionId'])
                state=json.loads(runtime.bundle.accepted_json) if runtime and runtime.bundle else None
                technical=[];expected=case['expected']
                if response:
                    if response['status'] not in expected.get('allowedStatuses',[expected.get('status')]):technical.append('unexpected_public_status')
                    if 'assessmentType' in expected and response['assessmentType']!=expected['assessmentType']:technical.append('unexpected_assessment_type')
                    if 'requiresNextQuestion' in expected and bool(response['nextQuestion'])!=expected['requiresNextQuestion']:technical.append('next_question_presence')
                else:technical.append('no_committed_response')
                row=dict(caseId=case['id'],status='RESPONSE_COMMITTED' if response else 'NO_RESPONSE',
                    executionOutcome='RESPONSE_COMMITTED' if response else 'NO_RESPONSE',acceptanceOutcome='UNRESOLVED' if response else 'FAIL',
                    request=req,response=response,error=error,acceptanceChecks=technical,elapsedSeconds=time.perf_counter()-started,acceptedState=state,
                    generationDispatches=sum(e['event']=='component_invocation' for e in events),
                    moderationDispatches=sum(e['event']=='moderation_input' for e in events))
                observation=dict(effectivePlan=runtime.diagnostics.effective_plan if runtime else None)
                try:row['export']=neutral.export('Q11','A',case['id'],0,req,response,state,{'suite':'DEVELOPMENT','caseType':'CANARY'},observation)
                except Exception as exc:row['exportError']=dict(type=type(exc).__name__,reason=str(exc))
                append(live/'journal.jsonl',dict(event='product_result',caseId=case['id'],status=row['status'],response=response,error=error))
            results[case['id']]=row;write(live/(case['id']+'.json'),row)
            print(canon({k:row.get(k) for k in ('caseId','status','error','generationDispatches')}),flush=True)
        write(live/'summary.json',dict(planned=12,executed=sum(x['request'] is not None for x in results.values()),
            committed=sum(x['response'] is not None for x in results.values()),unexecuted=sum(x['request'] is None for x in results.values()),
            usage=guard.summary(),sourceHash=lock['sourceHash'],formalResults=0,status='AWAITING_CANARY_FUNCTION_REVIEW'))
    finally:
        aggregate_guard.reset(token)
        for sid in list(registry._sessions):await registry.remove(sid)
        await registry.drain_cleanup()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['lock','verify','canary']);args=p.parse_args()
    if args.mode=='lock':print(canon(lock()))
    elif args.mode=='verify':print(verify_lock()['sourceHash'])
    else:asyncio.run(canary())
