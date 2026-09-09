"""Offline artifact packaging; no product/API reruns and no secret inclusion."""
import json,zipfile,hashlib,subprocess,re
from pathlib import Path
from datetime import datetime,timezone
from run import OUT,AI,sha,write,verify_lock

def pack():
    lock=verify_lock();gate=json.loads((OUT/'canary-gate.json').read_text(encoding='utf-8'))
    summary=json.loads((OUT/'live/summary.json').read_text(encoding='utf-8'))
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    formal=OUT/'formal/summary.json'
    complete=formal.exists() and json.loads(formal.read_text())['status']=='RAW_COLLECTION_COMPLETE_AWAITING_BLIND_GRADING'
    status='RAW_COLLECTION_COMPLETE_AWAITING_BLIND_GRADING' if complete else gate['status']
    full={}
    # Whole product Python plus tests, requirements, immutable runner and assets.
    for p in AI.rglob('*.py'):
        rel=p.relative_to(AI)
        if any(x in rel.parts for x in ('artifacts','.venv','__pycache__')):continue
        full['source/mindot_ai/'+rel.as_posix()]=p.read_bytes()
    for name in ('requirements.txt','Dockerfile'):
        if (AI/name).exists():full['source/mindot_ai/'+name]=(AI/name).read_bytes()
    for p in (AI/'cbt_simple/prompts').glob('*.txt'):
        full['source/mindot_ai/'+p.relative_to(AI).as_posix()]=p.read_bytes()
    for p in OUT.rglob('*'):
        if not p.is_file():continue
        rel=p.relative_to(OUT)
        if any(x in rel.parts for x in ('__pycache__','offline-old-api','submissions')):continue
        if p.name in ('grader-hash-only.txt','submission-receipt.json') or 'Release-Key' in p.name:continue
        full['audit/'+rel.as_posix()]=p.read_bytes()
    full['source/working-tree.diff']=subprocess.check_output(['git','diff','--binary','--','mindot_ai'],cwd=AI.parent)
    # Compare current product against preserved untracked as well as tracked start.
    import difflib
    changes=[]
    with zipfile.ZipFile(OUT/'starting-worktree.zip') as prior:
        names=set(prior.namelist())
        for name,data in sorted(full.items()):
            if not name.startswith('source/mindot_ai/') or not name.endswith('.py'):continue
            original=name.removeprefix('source/')
            old=prior.read(original) if original in names else b''
            if old!=data:
                changes.extend(difflib.unified_diff(old.decode('utf-8-sig').splitlines(True),data.decode('utf-8-sig').splitlines(True),
                    fromfile='start/'+original,tofile='current/'+original))
    full['source/changes-from-preserved-start.diff']=''.join(changes).encode('utf-8')
    readme=f'''# Mindot simple-dialogue-1 submission

Status: {status}
Execution source lock: {lock['sourceHash']}
Product source hash: {lock['productSourceHash']}
Canary: planned {summary['planned']}, executed {summary['executed']}, committed {summary['committed']}, unexecuted {summary['unexecuted']}.
Formal version results: {368 if complete else 0} / 368.
Observed generic tokens: {summary['usage']['observedGenericTokens']}; unknown reserved tokens: {summary['usage']['unknownReservedTokens']}.

See audit/canary-gate.json for each predefined functional finding, audit/live for actual provider input/output/tool/usage/commit receipts, audit/offline-receipt.json for fake-only counts, and audit/review.md for implementation scope, preservation, consumer inspection and limitations.

No quality reruns, score, ranking, XLSX, git commit/push, deployment or shutdown. One canary round only. Supplied expectations unchanged. Credentials, holdout release key, virtual environment and caches excluded. Rubric is hash-only and excluded from archive text. The full product Python and runner are included, not only a diff. Baseline and known input identities stay original. Original raw text is never replaced by accepted normalized output.

{'Blind-first grading is required; do not read full version mapping before locking grades.' if complete else 'Required canary failure or an actual execution blocker prevents formal collection. This audit is not a completed official evaluation.'}
'''
    full['README.md']=readme.encode('utf-8')
    target=OUT/'submissions';target.mkdir(exist_ok=True)
    def save(name,entries,anonymous=False):
        manifest=dict(status=status,files=[dict(path=n,bytes=len(d),sha256=sha(d)) for n,d in sorted(entries.items())])
        if not anonymous:manifest['sourceHash']=lock['sourceHash']
        entries['manifest.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode()
        for n,d in entries.items():
            if re.search(rb'sk-(?:proj-)?[A-Za-z0-9_-]{35,}',d):raise RuntimeError('POSSIBLE_CREDENTIAL_IN_ARTIFACT:'+n)
        path=target/name
        with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED) as z:
            for n,d in sorted(entries.items()):z.writestr(n,d)
        with zipfile.ZipFile(path) as z:
            assert z.testzip() is None
            for f in manifest['files']:assert sha(z.read(f['path']))==f['sha256']
        return dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path.read_bytes()),md5=hashlib.md5(path.read_bytes()).hexdigest(),files=len(entries))
    receipts=[]
    if complete:
        blind={}
        for p in sorted((OUT/'formal').glob('[0-9][0-9][0-9][0-9].json')):
            row=json.loads(p.read_text(encoding='utf-8'))
            for t in row['turns']:
                payload=t['export']['blindPayload'];identity=payload['identity']
                name='responses/'+identity['case_id']+'-'+row['anonymousVersion']+'-'+str(identity['turn_index'])+'.json'
                blind[name]=json.dumps(payload,ensure_ascii=False,indent=2).encode()
        blind['README.md']=b'# Anonymous raw evaluation\nShared schema, actual results, no scores or version mapping. Grade blind first.\n'
        # No full source identity in blind manifest.
        receipts.append(save('Mindot-Anonymous-Evaluation-Blind-'+stamp+'.zip',blind,anonymous=True))
        receipts.append(save('Mindot-Q10-Q11-Evaluation-Full-'+stamp+'.zip',full))
    else:receipts.append(save('Mindot-Q11-Simple-Dialogue-Audit-'+stamp+'.zip',full))
    write(OUT/'package-receipt.json',dict(status=status,archives=receipts))
    print(json.dumps(receipts))

if __name__=='__main__':pack()
