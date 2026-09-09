import io,json,sys,unittest,subprocess,zipfile,hashlib
from pathlib import Path
OUT=Path(__file__).resolve().parents[1];AI=OUT.parents[1]
sys.path.insert(0,str(AI/'tests'))
import test_simple_dialogue as t
import neutral
log=io.StringIO();result=unittest.TextTestRunner(stream=log,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(t))
(OUT/'offline-tests.log').write_text(log.getvalue(),encoding='utf-8')
assert result.wasSuccessful(),log.getvalue()
records=[];measurements=[]
for i,row in enumerate(t.OBSERVATIONS):
    records.append(neutral.export('Q11','A','fixture'+str(i),0,row['request'],row['response'],row['state'],{},row['observations']))
    measurements.extend(t.measure(wire) for wire in row['wires'])
failure=neutral.export('Q11','A','failure',0,t.start(900).model_dump(by_alias=True,mode='json'),None,None,{})
assert failure['blindPayload']['final']['actualDecision']=='TECHNICAL_NO_OUTPUT'
(OUT/'offline-exports.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
# Confirm unchanged FastAPI OpenAPI using the preserved facade and current facade
# in isolated child processes; no real credential or dotenv is read.
script="import os;os.environ['OPENAI_API_KEY']='offline';import dotenv;dotenv.load_dotenv=lambda *a,**k:False;import app,json;print(json.dumps(app.app.openapi(),sort_keys=True))"
current=subprocess.check_output([sys.executable,'-c',script],cwd=AI)
prior_dir=OUT/'offline-old-api';prior_dir.mkdir(exist_ok=True)
with zipfile.ZipFile(OUT/'starting-worktree.zip') as z:
    for name in z.namelist():
        if name.startswith('mindot_ai/') and '/tests/' not in name:
            dest=prior_dir/Path(name).relative_to('mindot_ai');dest.parent.mkdir(exist_ok=True,parents=True);dest.write_bytes(z.read(name))
prior=subprocess.check_output([sys.executable,'-c',script],cwd=prior_dir)
assert json.loads(prior)==json.loads(current),'OpenAPI changed'
(OUT/'openapi.json').write_bytes(current)
q10=subprocess.run([sys.executable,str(OUT/'runner/offline_q10.py')],cwd=OUT,capture_output=True,text=True,encoding='utf-8')
(OUT/'offline-q10.log').write_text(q10.stdout+q10.stderr,encoding='utf-8')
assert q10.returncode==0,q10.stdout+q10.stderr
receipt=dict(status='PASS',testsRun=result.testsRun,testsFailed=0,successfulFakeProductRequests=len(t.OBSERVATIONS),
    sdkFakeGenerationDispatches=sum(len(r['wires']) for r in t.OBSERVATIONS),actualLiveCalls=0,
    commonNeutralExports=len(records)+2,publicOpenAPIEqual=True,openapiSha256=hashlib.sha256(current).hexdigest(),
    maximumInputEstimate=max(x['inputEstimate'] for x in measurements),maximumRequestBytes=max(x['requestBytes'] for x in measurements),
    capacityDefaultSufficient=True,inputTokenCap=48000,inputByteCap=196608,measurements=measurements)
(OUT/'offline-receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in receipt.items() if k!='measurements'}))
