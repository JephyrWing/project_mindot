"""Correct artifact metadata only; frozen evaluation files stay byte-identical."""
from pathlib import Path
import json,zipfile,hashlib,os
OUT=Path(__file__).resolve().parent
receipt=json.loads((OUT/'package-receipt.json').read_text())
identity=json.loads((OUT/'source-identity-clarification.json').read_text())
path=Path(receipt['archives'][0]['path'])
assert path.resolve().parent==(OUT/'submissions').resolve()
with zipfile.ZipFile(path) as z:entries={n:z.read(n) for n in z.namelist()}
text=entries['README.md'].decode('utf-8')
text=text.replace('Product source hash: '+identity['executionSourceHash'],
    'Product source hash (corrected scope): '+identity['actualProductSourceHash'])
text+='\nExecution attempted 8 generation + 8 Moderation SDK calls; no provider response was observed. Observed usage is 0, which is not proof of zero provider charge. 146553 estimated generation tokens remain reserved for unknown usage. The runtime reports APIConnectionError; no more precise transport cause was captured. Formal collection is 0/368, and the hidden key/plaintext remains unopened.\n'
text+='\nThe original execution-lock.json is unchanged. Its productSourceHash label accidentally included artifact paths on Windows; audit/source-identity-clarification.json provides the corrected product-only scope without changing any locked bytes. This is a reporting correction, not an evaluation rerun or source change.\n'
entries['README.md']=text.encode()
entries['audit/finalize_submission_metadata.py']=Path(__file__).read_bytes()
manifest=json.loads(entries.pop('manifest.json'))
manifest['files']=[dict(path=n,bytes=len(d),sha256=hashlib.sha256(d).hexdigest()) for n,d in sorted(entries.items())]
entries['manifest.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode()
tmp=path.with_suffix('.metadata.tmp')
with zipfile.ZipFile(tmp,'x',compression=zipfile.ZIP_DEFLATED) as z:
    for n,d in sorted(entries.items()):z.writestr(n,d)
with zipfile.ZipFile(tmp) as z:
    assert z.testzip() is None
    for f in manifest['files']:assert hashlib.sha256(z.read(f['path'])).hexdigest()==f['sha256']
os.replace(tmp,path)
data=path.read_bytes()
receipt['archives'][0].update(bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),md5=hashlib.md5(data).hexdigest(),files=len(entries))
(OUT/'package-receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
