import asyncio,json,os,sys,socket
from pathlib import Path
from unittest.mock import patch
OUT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(OUT/'baseline'))
os.environ['OPENAI_API_KEY']='offline-fixture';os.environ['LANGSMITH_TRACING']='false'
import dotenv,httpx
with patch.object(dotenv,'load_dotenv',return_value=False):
    import q10_adapter
    adapter=q10_adapter.Q10Adapter()
def network_guard(event,args):
    if event=='socket.connect' and args[1][0] not in ('127.0.0.1','::1'):raise RuntimeError('offline_network_prohibited')
sys.addaudithook(network_guard)
def output(tool=False):
    args={'coverageReview':{d:dict(status='NOT_EXPLORED',sourceQuestionCode=None,rejectionReason=None) for d in ('evidenceFor','evidenceAgainst','alternativeViews','acknowledgement')},
        'questionPlan':dict(questionPurpose='EVIDENCE_FOR',semanticRouteType='DIRECT_WORD_OR_ACTION',latestUserIntent='START',
            questionGoal='그 생각을 뒷받침하는 직접적인 말이나 행동을 확인한다',answerTarget='전체 능력 평가를 뒷받침하는 말이나 행동',
            answerSource='USER_OBSERVATION',prefaceGoal=None,groundingQuestionCodes=[],avoidTopics=[],exampleOptions=[])}
    items=[dict(id='fc_test',type='function_call',call_id='call_test',name='ask_question',arguments=json.dumps(args,ensure_ascii=False),status='completed')] if tool else [dict(id='msg_test',type='message',role='assistant',status='completed',content=[dict(type='output_text',annotations=[],text=json.dumps(dict(preface=None,question='그 생각을 뒷받침하는 직접적인 말이나 행동이 있었나요?'),ensure_ascii=False))])]
    return dict(id='resp_test',object='response',created_at=1700000000,model='gpt-4o-mini',status='completed',error=None,incomplete_details=None,output=items,
        usage=dict(input_tokens=24,input_tokens_details=dict(cached_tokens=0),output_tokens=12,output_tokens_details=dict(reasoning_tokens=0),total_tokens=36))
async def main():
    request=dict(requestId='761e5328-d87c-4c70-913d-b9592b72b672',sessionId=401,record=dict(recordId=11500,
        situation='보고서 오류 한 곳을 지적받았다.',automaticThought='한 번 실수했으니 나는 늘 일을 망친다.'))
    queue=[output(True),output()];events=[]
    def transport(req):
        if not queue:raise RuntimeError('unplanned_call')
        return httpx.Response(200,json=queue.pop(0),headers={'content-type':'application/json'},request=req)
    try:
        result=await adapter.invoke(request,observer=lambda k,v:events.append(dict(kind=k,payload=v)),fake_transport=transport)
        assert result['publicResponse'] is not None,result['error']
        import neutral
        exported=neutral.export('Q10','B','fixture',0,request,result['publicResponse'],result['acceptedState'],{},result['observations'])
        (OUT/'offline-q10.json').write_text(json.dumps(dict(result=result,export=exported),ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(dict(status='PASS',actualLiveCalls=0,fakePhysicalAttempts=result['actualPhysicalAttempts'])))
    finally:await adapter.close_all()
asyncio.run(main())
