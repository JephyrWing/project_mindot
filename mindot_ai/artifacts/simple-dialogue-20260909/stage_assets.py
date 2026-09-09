from pathlib import Path
import zipfile,json,hashlib
OUT=Path(__file__).resolve().parent
AI=OUT.parents[1]
index=json.loads((OUT/'package/docs/cbt-q11-evaluation/asset-index.json').read_text(encoding='utf-8'))
audit=AI/'artifacts/q11-validation-paid-20260909-final/Mindot-Q11-Final-Attempt-Full-Audit-20260908T162158Z.zip'
with zipfile.ZipFile(audit) as z:
    for item in index['baseline']['files']:
        data=z.read('audit/task-artifact/assets/Q10/mindot_ai/'+item['relativePath'])
        assert hashlib.sha256(data).hexdigest()==item['sha256'] and len(data)==item['bytes']
        dest=OUT/'baseline'/item['relativePath']; dest.parent.mkdir(exist_ok=True,parents=True);dest.write_bytes(data)
    for name in ('q10_adapter.py','neutral_export.py','input_plan.py','formal_collect.py','journal.py','product_worker.py'):
        dest=OUT/'historical-runner'/name;dest.parent.mkdir(exist_ok=True,parents=True)
        dest.write_bytes(z.read('audit/task-artifact/frozen-runner/'+name))
for item in index['knownInputs']['files']:
    original=Path('C:/Users/human-09/AppData/Local/Temp/cbt-q11-evaluation/20260903T002750Z/inputs')/item['relativePath']
    data=original.read_bytes()
    assert hashlib.sha256(data).hexdigest()==item['sha256'] and len(data)==item['bytes'],item['relativePath']
    dest=OUT/'known'/item['relativePath'];dest.parent.mkdir(exist_ok=True,parents=True);dest.write_bytes(data)
print('Verified Q10 6 files and known inputs 7 files; key and hidden plaintext not accessed.')
