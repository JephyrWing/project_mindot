"""Version-isolated worker. Raw is journaled before provider parsing returns."""
import argparse,asyncio,json,sys,os,math,tiktoken
from pathlib import Path
OUT=Path(__file__).resolve().parents[1];AI=OUT.parents[1]
p=argparse.ArgumentParser();p.add_argument('version',choices=['Q10','Q11']);p.add_argument('ceiling',type=int);args=p.parse_args()
sys.path.insert(0,str(OUT/'baseline' if args.version=='Q10' else AI))
from dotenv import load_dotenv
load_dotenv(AI.parent/'infra/.env.local',override=False)
os.environ['LANGSMITH_TRACING']='false';os.environ['CBT_DEBUG_LOG_ANALYSIS']='false'
def append(path,value):
    with path.open('a',encoding='utf-8') as f:
        f.write(json.dumps(value,ensure_ascii=False,separators=(',',':'))+'\n');f.flush();os.fsync(f.fileno())
class Budget:
    def __init__(self):self.calls=0;self.moderations=0;self.charge=0;self.observed=0;self.ticket={}
    def reserve_path(self,sizes):
        if self.charge+sum(s['reservation'] for s in sizes)>args.ceiling*3*92192:raise RuntimeError('FORMAL_BUDGET_BLOCKED')
    def admit(self,phase,size,wire):
        if self.calls>=args.ceiling*3:raise RuntimeError('FORMAL_CALL_BUDGET')
        self.reserve_path([size]);self.calls+=1;self.charge+=size['reservation']
    def received(self,ticket,raw):
        u=raw.get('usage')
        if u and all(type(u.get(k)) is int for k in ('prompt_tokens','completion_tokens')):
            total=u['prompt_tokens']+u['completion_tokens'];self.observed+=total;self.charge+=total-ticket['capacity']['reservation']
    def moderation(self,wire):
        if self.moderations>=args.ceiling:raise RuntimeError('FORMAL_MODERATION_BUDGET')
        self.moderations+=1

async def main():
    budget=Budget()
    if args.version=='Q10':
        import q10_adapter
        adapter=q10_adapter.Q10Adapter()
    else:
        from cbt_q11.state import SessionRegistry
        from cbt_q11.diagnostics import diagnostic_sink
        from cbt_simple.provider import aggregate_guard
        from cbt_agent import CbtStartRequest,CbtTurnRequest
        from cbt_session_agent import generate_agent_cbt_start,generate_agent_cbt_turn
        registry=SessionRegistry(600)
    try:
        while True:
            line=await asyncio.to_thread(sys.stdin.readline)
            if not line:break
            command=json.loads(line)
            if command['op']=='shutdown':break
            if command['op']=='restore_budget':
                if budget.calls or budget.moderations:raise RuntimeError('BUDGET_RESTORE_AFTER_DISPATCH_FORBIDDEN')
                saved=command['usage'];budget.calls=saved['dispatches'];budget.moderations=saved['moderations']
                budget.observed=saved['observedTokens'];budget.charge=saved['observedPlusReservations']
                print('{"budgetRestored":true}',flush=True);continue
            if command['op']=='remove':
                sid=command['sessionId']
                if args.version=='Q10':
                    # Explicit test fixture removes runtime to observe cold restore;
                    # it does not call the user cancellation API.
                    runtime=adapter.registry._sessions.pop(sid,None)
                    if runtime and runtime.expiry_task:runtime.expiry_task.cancel()
                    adapter.accepted.pop(sid,None)
                else:
                    runtime=registry._sessions.pop(sid,None)
                    if runtime and runtime.expiry:runtime.expiry.cancel()
                print('{"removed":true}',flush=True);continue
            req=command['request'];path=Path(command['journal']);response=None;error=None;state=None;observations={}
            try:
                if args.version=='Q10':
                    def observer(kind,value):
                        if kind=='provider_dispatch':
                            if budget.calls>=args.ceiling*12:raise RuntimeError('BASELINE_PHYSICAL_CALL_CAP')
                            # Original model output cap and SDK retry policy stay intact.
                            wire=value['requestBody']
                            serialized=json.dumps(wire,ensure_ascii=False,separators=(',',':'))
                            estimate=len(tiktoken.get_encoding('o200k_base').encode(serialized))+64
                            output_cap=wire.get('max_output_tokens') or 16384
                            reservation=math.ceil(estimate*1.75)+output_cap
                            if reservation>128000:raise RuntimeError('BASELINE_CONTEXT_RESERVATION_CAP')
                            value['capacity']=dict(inputEstimate=estimate,requestBytes=len(serialized.encode()),
                                outputReservation=output_cap,inputReservationFactor=1.75,reservation=reservation)
                            budget.calls+=1;budget.ticket[value['invocationId']]=reservation;budget.charge+=reservation
                            if budget.charge>args.ceiling*12*128000:raise RuntimeError('BASELINE_TOKEN_RESERVATION_CAP')
                        if kind=='provider_received':
                            u=value.get('usage')
                            if u and all(type(u.get(k)) is int for k in ('input_tokens','output_tokens')):
                                tokens=u['input_tokens']+u['output_tokens'];budget.observed+=tokens
                                budget.charge+=tokens-budget.ticket[value['invocationId']]
                        append(path,dict(event=kind,**value))
                    result=await adapter.invoke(req,observer=observer)
                    response=result['publicResponse'];error=result['error'];state=result['acceptedState'];observations=result['observations']
                else:
                    dt=diagnostic_sink.set(lambda e:append(path,e));bt=aggregate_guard.set(budget)
                    try:
                        cls=CbtTurnRequest if 'questionAnswers' in req else CbtStartRequest
                        method=generate_agent_cbt_turn if cls is CbtTurnRequest else generate_agent_cbt_start
                        r=await method(cls.model_validate(req),registry=registry)
                        response=r.model_dump(by_alias=True,mode='json')
                    finally:diagnostic_sink.reset(dt);aggregate_guard.reset(bt)
                    runtime=await registry.get(req['sessionId'])
                    state=json.loads(runtime.bundle.accepted_json) if runtime and runtime.bundle else None
                    observations={'effectivePlan':runtime.diagnostics.effective_plan if runtime else None}
            except Exception as exc:error=dict(type=type(exc).__name__,reason=str(exc))
            if args.version=='Q11':
                runtime=await registry.get(req['sessionId'])
                state=json.loads(runtime.bundle.accepted_json) if runtime and runtime.bundle else None
                observations={'effectivePlan':runtime.diagnostics.effective_plan if runtime else None}
            result=dict(response=response,error=error,state=state,observations=observations,
                        usage=dict(dispatches=budget.calls,moderations=budget.moderations,observedTokens=budget.observed,observedPlusReservations=budget.charge))
            print(json.dumps(result,ensure_ascii=False),flush=True)
    finally:
        if args.version=='Q10':await adapter.close_all()
        else:
            for sid in list(registry._sessions):await registry.remove(sid)
            await registry.drain_cleanup()
asyncio.run(main())
