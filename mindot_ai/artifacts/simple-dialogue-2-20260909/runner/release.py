"""Gate first, authorized key file only, sealed bytes/hash checks before use."""
import argparse,hashlib,io,json,tarfile
from pathlib import Path,PurePosixPath
from datetime import datetime,timezone
from run import OUT,verify_lock,write,sha,canon
import input_plan

def ready():
    lock=verify_lock()
    gate=json.loads((OUT/'canary-gate.json').read_text(encoding='utf-8'))
    if gate['status']!='CANARY_ELIGIBLE':raise RuntimeError('CANARY_NOT_ELIGIBLE')
    receipt=dict(status='READY_FOR_HOLDOUT_LOCK',canaryEligible=True,formalLocksComplete=True,
        sourceHash=lock['sourceHash'],productSourceHash=lock['productSourceHash'],
        canaryGateSha256=sha((OUT/'canary-gate.json').read_bytes()),
        knownScheduleSha256=sha((OUT/'known-schedule.json').read_bytes()),
        preReleaseTokenReservationFormula={'Q10':'productRequestCeiling * 12 * 128000',
            'Q11':'productRequestCeiling * 3 * 92192'},
        reservationPolicy='Ceilings are conservative bounds. Dispatch debits measured compact SDK input using o200k+64, times 1.75, plus existing phase output cap; receipt settles actual usage. Unknown usage retains reservation.',
        timestamp=datetime.now(timezone.utc).isoformat(),hiddenKeyRead=False)
    write(OUT/'READY_FOR_HOLDOUT_LOCK.json',receipt)
    return receipt

def release(key_path):
    verify_lock()
    receipt=json.loads((OUT/'READY_FOR_HOLDOUT_LOCK.json').read_text(encoding='utf-8'))
    if receipt['status']!='READY_FOR_HOLDOUT_LOCK' or not receipt['canaryEligible']:
        raise RuntimeError('HOLDOUT_GATE_NOT_READY')
    root=OUT/'package/docs/cbt-q11-evaluation'
    manifest=json.loads((root/'holdout-manifest.json').read_text(encoding='utf-8'))
    cipher=(root/manifest['archive']['fileName']).read_bytes()
    assert sha(cipher)==manifest['archive']['sha256'] and len(cipher)==manifest['archive']['bytes']
    assert cipher[:8]==b'Salted__'
    # OpenSSL file: passphrase consumes only the first line. Never print it.
    secret=Path(key_path).read_bytes().splitlines()[0]
    material=hashlib.pbkdf2_hmac('sha256',secret,cipher[8:16],250000,48)
    from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes
    from cryptography.hazmat.primitives.padding import PKCS7
    decrypt=Cipher(algorithms.AES(material[:32]),modes.CBC(material[32:])).decryptor()
    padded=decrypt.update(cipher[16:])+decrypt.finalize()
    unpad=PKCS7(128).unpadder();plain=unpad.update(padded)+unpad.finalize()
    assert sha(plain)==manifest['sealedContent']['plainTarSha256']
    files={}
    with tarfile.open(fileobj=io.BytesIO(plain)) as tar:
        for member in tar.getmembers():
            p=PurePosixPath(member.name)
            assert not p.is_absolute() and '..' not in p.parts and '\\' not in member.name and ':' not in member.name
            if member.isdir():continue
            assert member.isfile() and member.name not in files
            files[member.name]=tar.extractfile(member).read()
    inner_names=[name for name,data in files.items() if sha(data)==manifest['sealedContent']['contentManifestSha256']]
    assert len(inner_names)==1
    inner=json.loads(files[inner_names[0]])
    entries=inner['files']
    if isinstance(entries,dict):entries=[dict(relativePath=k,**v) for k,v in entries.items()]
    checked={inner_names[0]}
    for entry in entries:
        name=entry.get('relativePath') or entry.get('path') or entry.get('fileName') or entry.get('name')
        matches=[n for n in files if n==name or n==str(PurePosixPath(inner_names[0]).parent/name)]
        assert len(matches)==1
        name=matches[0];assert sha(files[name])==entry['sha256']
        if 'bytes' in entry:assert len(files[name])==entry['bytes']
        checked.add(name)
    assert checked==set(files)
    dest=OUT/'released-hidden';dest.mkdir(exist_ok=True)
    singles=[];longs=[]
    for name,data in files.items():
        path=dest/name;path.parent.mkdir(exist_ok=True,parents=True);path.write_bytes(data)
        if name.endswith('.json'):
            value=json.loads(data)
            if isinstance(value,list) and len(value)==12 and all('caseId' in r for r in value):singles.append(path)
            if isinstance(value,list) and len(value)==2 and all('sessionCaseId' in r for r in value):longs.append(path)
    assert len(singles)==len(longs)==1
    known=input_plan.load_known(OUT/'known')
    catalog=input_plan.load_hidden(singles[0],longs[0],gate_receipt=receipt,expected_gate_sha256=input_plan.sha(receipt),
        single_sha256=sha(singles[0].read_bytes()),long_sha256=sha(longs[0].read_bytes()),
        common_definitions=known['commonSemanticRouteDefinitions'])
    write(OUT/'released-hidden-catalog.json',catalog)
    write(OUT/'holdout-release-receipt.json',dict(status='HASH_VERIFIED_RELEASED',keyIncluded=False,
        gateSha256=input_plan.sha(receipt),files=[dict(path=n,bytes=len(d),sha256=sha(d)) for n,d in files.items()],
        plainTarSha256=sha(plain),catalogSha256=sha((OUT/'released-hidden-catalog.json').read_bytes()),
        logicalCases=len(catalog['cases'])))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['ready','release']);p.add_argument('--key-file');args=p.parse_args()
    if args.mode=='ready':ready();print('READY_FOR_HOLDOUT_LOCK')
    else:release(args.key_file);print('HASH_VERIFIED_RELEASED')
