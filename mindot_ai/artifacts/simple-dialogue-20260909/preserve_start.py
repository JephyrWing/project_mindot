from pathlib import Path
import hashlib, json, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
def digest(data): return hashlib.sha256(data).hexdigest()
package = OUT / 'package'
manifest = json.loads((package / 'manifest.json').read_text(encoding='utf-8'))
for item in manifest['files']:
    data = (package / item['path']).read_bytes()
    assert len(data) == item['bytes'] and digest(data) == item['sha256'], item['path']
files = [ROOT / '.gitignore', ROOT / 'AGENTS.md']
ai = ROOT / 'mindot_ai'
files += [p for p in ai.rglob('*') if p.is_file() and not any(
    x in p.relative_to(ai).parts for x in ('artifacts', '.venv', '__pycache__', '.idea', '.pytest_cache'))
    and p.suffix in ('.py', '.txt', '.md', '.json')]
files += [ai / 'Dockerfile']
with zipfile.ZipFile(OUT / 'starting-worktree.zip', 'x', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(set(files)):
        z.write(p, p.relative_to(ROOT).as_posix())
subprocess.run(['git','diff','--binary','--output='+str(OUT/'starting-worktree.diff')], cwd=ROOT, check=True)
base=json.loads((package/'docs/cbt-q11-simple/implementation-base-manifest.json').read_text(encoding='utf-8'))
comparison=[]
for item in base['files']:
    p=ai/item['path']
    comparison.append({'path':item['path'],'expected':item['sha256'],
        'actual':digest(p.read_bytes()) if p.exists() else None})
receipt={'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
    'status':subprocess.check_output(['git','status','--short'],cwd=ROOT,text=True),
    'packageManifestVerified':True,'baseComparison':comparison,
    'snapshotSha256':digest((OUT/'starting-worktree.zip').read_bytes())}
(OUT/'starting-state.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'filesPreserved':len(set(files)), 'baseMatching':sum(x['expected']==x['actual'] for x in comparison),
    'baseFiles':len(comparison),'differences':[x['path'] for x in comparison if x['expected']!=x['actual']]}))
