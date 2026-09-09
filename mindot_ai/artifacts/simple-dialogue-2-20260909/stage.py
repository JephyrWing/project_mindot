from pathlib import Path
import hashlib,json,zipfile,subprocess,shutil,base64
OUT=Path(__file__).resolve().parent;AI=OUT.parents[1];PKG=OUT/'package';OLD=AI/'artifacts/simple-dialogue-20260909'
sha=lambda b:hashlib.sha256(b).hexdigest()
manifest=json.loads((PKG/'manifest.json').read_text())
for f in manifest['files']:
    data=(PKG/f['path']).read_bytes();assert len(data)==f['bytes'] and sha(data)==f['sha256'],f['path']
base=json.loads((PKG/'docs/cbt-q11-simple/implementation-base-manifest.json').read_text())
prior=OLD/'submissions'/base['auditName'];assert sha(prior.read_bytes())==base['auditSha256']
different=[]
for f in base['files']:
    p=AI/f['path']
    if not p.exists() or sha(p.read_bytes())!=f['sha256']:different.append(f['path'])
files=[p for p in AI.rglob('*') if p.is_file() and not any(x in p.relative_to(AI).parts for x in ('artifacts','.venv','__pycache__')) and (p.suffix in ('.py','.txt') or p.name=='Dockerfile')]
with zipfile.ZipFile(OUT/'starting-worktree.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
    for p in files:z.write(p,'mindot_ai/'+p.relative_to(AI).as_posix())
(OUT/'starting-worktree.diff').write_bytes(subprocess.check_output(['git','diff','--binary'],cwd=AI.parent))
(OUT/'starting-state.json').write_text(json.dumps(dict(baseMatched=len(base['files'])-len(different),baseFiles=len(base['files']),differences=different,
    preservedFiles=len(files),preservedZipSha256=sha((OUT/'starting-worktree.zip').read_bytes()),priorAuditSha256=sha(prior.read_bytes()),
    sourceStart='actual simple-dialogue-1 submission; no rebuild from REPAIR-13',
    iterationLogReadOnly=True,iterationLogModifiedAt='2026-09-09T05:25:14.654Z'),indent=2),encoding='utf-8')
for name in ('baseline','runner'):shutil.copytree(OLD/name,OUT/name,ignore=shutil.ignore_patterns('__pycache__'))
shutil.copytree(PKG/'docs/cbt-q11-evaluation/known',OUT/'known')
(OUT/'grader-hash-only.txt').write_bytes(base64.b64decode((OUT/'rubric-transfer.b64').read_bytes()))
(OUT/'rubric-transfer.b64').unlink()
asset=json.loads((PKG/'docs/cbt-q11-evaluation/asset-index.json').read_text())
rubric=(OUT/'grader-hash-only.txt').read_bytes()
assert sha(rubric)==asset['rubric']['sha256'] and len(rubric)==asset['rubric']['bytes']
shutil.copyfile(PKG/'docs/cbt-q11-simple/schema.py',AI/'cbt_simple/schema.py')
for p in (PKG/'docs/cbt-q11-simple/prompts').glob('*.txt'):shutil.copyfile(p,AI/'cbt_simple/prompts'/p.name)
print(json.dumps(dict(packageFilesVerified=len(manifest['files']),baseMatched=len(base['files'])-len(different),baseDifferences=different,preserved=len(files),rubricHashVerified=True)))
