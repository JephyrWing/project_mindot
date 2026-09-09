"""Add non-generating connectivity evidence after user's cause question."""
import json,zipfile,hashlib,os
from pathlib import Path
OUT=Path(__file__).resolve().parent
diagnosis=dict(cause='SANDBOX_OUTBOUND_SOCKET_ACCESS_DENIED',
    insideSandbox=dict(endpointHost='api.openai.com',httpMethod='GET',path='/v1/models',authenticationSent=False,
        generationRequested=False,error='httpx.ConnectError',osError='WinError 10013',
        message='An attempt was made to access a socket in a way forbidden by its access permissions.'),
    outsideSandbox=dict(endpointHost='api.openai.com',httpMethod='GET',path='/v1/models',authenticationSent=False,
        generationRequested=False,httpStatus=401,interpretation='Expected unauthenticated response demonstrates server connectivity.'),
    effectiveProductEnvironment=dict(apiHost='api.openai.com',proxyConfigured=False),
    attribution='Executor started the canary in the restricted sandbox without first checking network access. This is an execution-environment error, not evidence of model/CBT semantic failure.',
    additionalModelCalls=0,canaryRerun=False,formalResults=0,
    usageTreatment='Original failed-attempt usage remains unknown/reserved because per-attempt provider receipt was not captured; the separate connectivity check is not a provider usage receipt.')
(OUT/'transport-diagnosis.json').write_text(json.dumps(diagnosis,ensure_ascii=False,indent=2),encoding='utf-8')
gate=json.loads((OUT/'canary-gate.json').read_text(encoding='utf-8'))
gate['diagnosedCause']=diagnosis['cause'];gate['diagnosisReceipt']='transport-diagnosis.json'
(OUT/'canary-gate.json').write_text(json.dumps(gate,ensure_ascii=False,indent=2),encoding='utf-8')
receipt=json.loads((OUT/'package-receipt.json').read_text())
path=Path(receipt['archives'][0]['path']);assert path.resolve().parent==(OUT/'submissions').resolve()
with zipfile.ZipFile(path) as z:entries={n:z.read(n) for n in z.namelist()}
entries['audit/transport-diagnosis.json']=(OUT/'transport-diagnosis.json').read_bytes()
entries['audit/canary-gate.json']=(OUT/'canary-gate.json').read_bytes()
entries['audit/attach_transport_diagnosis.py']=Path(__file__).read_bytes()
entries['README.md']+=b'\nPost-run cause diagnosis: the sandbox denied outbound socket access (WinError 10013). An unauthenticated GET outside the sandbox reached api.openai.com and returned expected HTTP 401. The executor failed to preflight network access. No additional generation, paid evaluation or canary rerun was made. See audit/transport-diagnosis.json.\n'
manifest=json.loads(entries.pop('manifest.json'))
manifest['files']=[dict(path=n,bytes=len(d),sha256=hashlib.sha256(d).hexdigest()) for n,d in sorted(entries.items())]
entries['manifest.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode()
tmp=path.with_suffix('.diagnosis.tmp')
with zipfile.ZipFile(tmp,'x',compression=zipfile.ZIP_DEFLATED) as z:
    for n,d in sorted(entries.items()):z.writestr(n,d)
with zipfile.ZipFile(tmp) as z:
    assert z.testzip() is None
    for f in manifest['files']:assert hashlib.sha256(z.read(f['path'])).hexdigest()==f['sha256']
os.replace(tmp,path);data=path.read_bytes()
receipt['archives'][0].update(bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),md5=hashlib.md5(data).hexdigest(),files=len(entries))
(OUT/'package-receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
