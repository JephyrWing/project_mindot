"""Frozen formal schedule and original inputs. Invoked only after canary gate."""
import asyncio,json,sys,os
from pathlib import Path
from datetime import datetime,timezone,timedelta
from copy import deepcopy
import input_plan,neutral
from run import OUT,AI,verify_lock,write,append

async def main():
    lock=verify_lock()
    gate=json.loads((OUT/'canary-gate.json').read_text(encoding='utf-8'))
    if gate['status']!='CANARY_ELIGIBLE':raise RuntimeError('CANARY_NOT_ELIGIBLE')
    # Decryption is separately executed only after READY_FOR_HOLDOUT_LOCK.
    hidden=json.loads((OUT/'released-hidden-catalog.json').read_text(encoding='utf-8'))
    known=input_plan.load_known(OUT/'known');identities=input_plan.IdentityMap()
    schedule=input_plan.build_schedule(known,hidden,identities=identities)
    cases={c['caseKey']:c for c in known['cases']+hidden['cases']}
    catalog={'sources':known['sources']|hidden['sources']}
    ceiling=schedule['ceilings']['productRequestsPerVersion']
    dest=OUT/'formal';dest.mkdir(exist_ok=True)
    marker=dest/'started.json'
    completed={};resume_usage={}
    if marker.exists():
        previous=json.loads(marker.read_text(encoding='utf-8'))
        if previous['sourceHash']!=lock['sourceHash'] or previous['schedule']['scheduleSha256']!=schedule['scheduleSha256']:
            raise RuntimeError('RESUME_LOCK_MISMATCH')
        for p in sorted(dest.glob('[0-9][0-9][0-9][0-9].json')):
            saved=json.loads(p.read_text(encoding='utf-8'));completed[p.stem]=saved
            resume_usage[saved['version']]=saved['turns'][-1]['usage']
            for turn in range(len(saved['turns'])):identities.identity(saved['caseKey'],saved['version'],turn)
        completed_pairs={(r['caseKey'],r['version']) for r in completed.values()}
        journal=dest/'journal.jsonl'
        for line in journal.read_text(encoding='utf-8').splitlines() if journal.exists() else []:
            event=json.loads(line)
            if (event['caseKey'],event['version']) not in completed_pairs:
                raise RuntimeError('PARTIAL_CASE_CHECKPOINT_UNAVAILABLE_NO_REPLAY')
        for partial in dest.glob('*-partial.json'):
            if partial.name.split('-')[0] not in completed:raise RuntimeError('PARTIAL_CASE_CHECKPOINT_UNAVAILABLE_NO_REPLAY')
    else:write(marker,dict(sourceHash=lock['sourceHash'],schedule=schedule,
        reservationCaps=dict(Q10=ceiling*12*128000,Q11=ceiling*3*92192),
        capInterpretation='Ceiling reservations, not predicted usage; actual dispatched inputs and returned usage recorded separately.'))
    workers={}
    for version in ('Q10','Q11'):
        err=(dest/(version+'.stderr.log')).open('wb')
        process=await asyncio.create_subprocess_exec(sys.executable,str(OUT/'runner/formal_worker.py'),version,str(ceiling),
            stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=err,limit=16777216)
        workers[version]=(process,err)
    records=[]
    async def send(version,command):
        process,_=workers[version];process.stdin.write((json.dumps(command,ensure_ascii=False)+'\n').encode())
        await process.stdin.drain()
        line=await asyncio.wait_for(process.stdout.readline(),210)
        if not line:raise RuntimeError('WORKER_RECORDING_FAILURE')
        return json.loads(line)
    try:
        for version,usage in resume_usage.items():await send(version,dict(op='restore_budget',usage=usage))
        for case_index,item in enumerate(schedule['schedule']):
            verify_lock();case=cases[item['caseKey']]
            for version_index,version in enumerate(item['versionOrder']):
                req=input_plan.initial_request(case,version,catalog,identities);turns=[]
                casekey=str(case_index*2+version_index).zfill(4)
                if casekey in completed:
                    saved=completed[casekey]
                    if saved['caseKey']!=case['caseKey'] or saved['version']!=version:raise RuntimeError('RESUME_CASE_MAPPING_MISMATCH')
                    records.append(saved);continue
                started=datetime.now(timezone.utc)
                for turn in range(case['maxTurns']):
                    append(dest/'journal.jsonl',dict(event='request',caseKey=case['caseKey'],version=version,turn=turn,request=req))
                    result=await send(version,dict(op='invoke',request=req,journal=str(dest/(casekey+'-'+str(turn)+'.events.jsonl'))))
                    row=dict(request=req,**result,executionOutcome='RESPONSE_COMMITTED' if result['response'] else 'NO_RESPONSE')
                    try:
                        row['export']=neutral.export(version,item['anonymousVersions'][version],case['caseKey'],turn,req,result['response'],
                            result['state'],case.get('gradingContext',{}),result['observations'])
                    except Exception as exc:row['exportError']=dict(type=type(exc).__name__,reason=str(exc))
                    turns.append(row)
                    write(dest/(casekey+'-partial.json'),dict(caseKey=case['caseKey'],version=version,turns=turns))
                    append(dest/'journal.jsonl',dict(event='result',caseKey=case['caseKey'],version=version,turn=turn,response=result['response'],error=result['error']))
                    if not result['response'] or case['kind']=='single':break
                    asked=(datetime(2026,9,8,tzinfo=timezone.utc)+timedelta(minutes=turn*2)).isoformat()
                    answered=(datetime.fromisoformat(asked)+timedelta(seconds=60)).isoformat()
                    next_req=input_plan.followup_request(case,version,req,result['response'],turn+1,identities,asked_at=asked,answered_at=answered)
                    if next_req is None:break
                    if input_plan.remove_before_next(case,turn):await send(version,dict(op='remove',sessionId=req['sessionId']))
                    req=next_req
                    if (datetime.now(timezone.utc)-started).total_seconds()>case['maxTurns']*180+30:raise RuntimeError('SESSION_DEADLINE')
                row=dict(caseKey=case['caseKey'],version=version,anonymousVersion=item['anonymousVersions'][version],turns=turns,
                    remainingTurnsNotObserved=case['maxTurns']-len(turns),
                    runtimeRemovalObserved=any(t['request'].get('questionAnswers') and i>0 and input_plan.remove_before_next(case,i-1) for i,t in enumerate(turns)))
                write(dest/(casekey+'.json'),row);records.append(row)
                print(json.dumps(dict(results=len(records),expected=368,lastVersion=version,turns=len(turns))),flush=True)
        if len(records)!=368:raise RuntimeError('OFFICIAL_DENOMINATOR_MISMATCH')
        write(dest/'summary.json',dict(status='RAW_COLLECTION_COMPLETE_AWAITING_BLIND_GRADING',results=368,
            turnRecords=sum(len(r['turns']) for r in records),mapping=identities.full_only()))
    finally:
        for process,err in workers.values():
            if process.returncode is None:
                process.stdin.write(b'{"op":"shutdown"}\n');await process.stdin.drain()
                try:await asyncio.wait_for(process.wait(),30)
                except TimeoutError:process.terminate()
            err.close()
if __name__=='__main__':asyncio.run(main())
