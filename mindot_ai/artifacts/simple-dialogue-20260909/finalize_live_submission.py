"""Reporting-only metadata, explicitly separate from the frozen run."""
from pathlib import Path
import json,zipfile,hashlib,os
OUT=Path(__file__).resolve().parent
receipt=json.loads((OUT/'package-receipt.json').read_text())
identity=json.loads((OUT/'source-identity-clarification.json').read_text())
path=Path(receipt['archives'][0]['path']);assert path.resolve().parent==(OUT/'submissions').resolve()
with zipfile.ZipFile(path) as z:entries={n:z.read(n) for n in z.namelist()}
readme=entries['README.md'].decode()
readme=readme.replace('Product source hash: '+identity['executionSourceHash'],
    'Product source hash (corrected scope): '+identity['actualProductSourceHash'])
readme=readme.replace('One canary round only.',
    'One network-enabled model canary round, plus the preserved environment-blocked attempt. The user explicitly authorized changing only the execution environment and continuing; no product/runner/prompt/input changed.')
readme+='''
## Actual functional gate

CANARY_BLOCKED: 4 PASS, 6 FAIL, 1 UNRESOLVED, 1 BLOCKED_BY_PARENT under the predefined functional acceptance criteria, not rubric scores. Eleven product requests ran; eight public responses committed; three were rejected with closed_question_target. One dependent request could not run. The model repeated the prior question after an explicit draft request and failed to give the requested stop guidance. The fact-scope check remains unresolved rather than being promoted to PASS. Explanation/example delivery and clear current-risk handling were observed; Assessor, Agent assessment review and terminal draft were not observed live. Formal evaluation is 0/368 and the holdout key/plaintext remains unopened.

Actual model responses: 18 (11 SELECT, 7 WRITER); Moderation responses: 11. Input usage 57,163, output usage 1,994, total 59,157 generation tokens. This model round has no unknown usage reservation. The earlier sandbox-blocked attempt remains separately documented with 8 generation SDK attempts, 8 Moderation SDK attempts, no observed model response and 146,553 unknown reserved tokens. The combined observed-plus-unknown-reservation record is 205,710, not a bill or a claim that the blocked attempt incurred a charge.

The environment-blocked-attempt directory and environment-resume-authorization.md preserve the execution correction and explicit user authorization. Earlier finalize_audit.py/finalize_submission_metadata.py/attach_transport_diagnosis.py were historical postprocessors for that earlier local archive; their hardcoded earlier counts are not the final result. The definitive current gate is audit/canary-gate.json with audit/live/summary.json and its raw events. No model output was replaced and no quality retry was performed.

The original execution-lock.json is unchanged. Its productSourceHash label accidentally included artifact paths on Windows; audit/source-identity-clarification.json supplies the corrected product-only scope. This reporting correction does not change any locked bytes or evaluated result.
'''
entries['README.md']=readme.encode()
entries['audit/finalize_live_submission.py']=Path(__file__).read_bytes()
manifest=json.loads(entries.pop('manifest.json'))
manifest['files']=[dict(path=n,bytes=len(d),sha256=hashlib.sha256(d).hexdigest()) for n,d in sorted(entries.items())]
entries['manifest.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode()
tmp=path.with_suffix('.final.tmp')
with zipfile.ZipFile(tmp,'x',compression=zipfile.ZIP_DEFLATED) as z:
    for n,d in sorted(entries.items()):z.writestr(n,d)
with zipfile.ZipFile(tmp) as z:
    assert z.testzip() is None
    for f in manifest['files']:assert hashlib.sha256(z.read(f['path'])).hexdigest()==f['sha256']
os.replace(tmp,path);data=path.read_bytes()
receipt['archives'][0].update(bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),md5=hashlib.md5(data).hexdigest(),files=len(entries))
(OUT/'package-receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
