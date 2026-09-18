"""Bundle an already embedded/verified release SQL for the startup JDBC runner.

No API or database access. Original package data and vectors are not regenerated.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'mindot_back/src/main/resources/db/demo'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package-dir', required=True, type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.package_dir / 'manifest.json').read_text(encoding='utf-8'))
    entry = next(row for row in manifest['files'] if row['path'] == 'seed.sql')
    original = (args.package_dir / 'seed.sql').read_bytes()
    original_hash = hashlib.sha256(original).hexdigest()
    if len(original) != entry['bytes'] or original_hash != entry['sha256']:
        raise ValueError('Release SQL does not match its verified manifest')
    sql = original.decode('utf-8').replace('\r\n', '\n')
    lines = sql.splitlines()
    if lines.count('BEGIN;') != 1 or lines.count('COMMIT;') != 1:
        raise ValueError('Expected exactly one outer release transaction')
    # The startup importer owns commit/rollback. Keep the PL/pgSQL block intact.
    sql = '\n'.join(line for line in lines if line not in {
        'BEGIN;', 'COMMIT;',
        'SELECT kind,seed_key,id FROM _mindot_ids ORDER BY kind,seed_key;',
    }) + '\n'
    payload = sql.encode('utf-8')
    digest = hashlib.sha256(payload).hexdigest()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / 'mindot-demo-u1-v1.sql.gz').write_bytes(gzip.compress(payload, compresslevel=9, mtime=0))
    (OUTPUT / 'mindot-demo-u1-v1.sql.sha256').write_text(digest + '\n', encoding='ascii')
    provenance = dict(namespace='mindot-demo-u1-v1', anchor_date='2026-09-18', timezone='Asia/Seoul',
                      source_release=args.package_dir.name, source_sql_sha256=original_hash,
                      bundled_sql_sha256=digest, bundled_sql_bytes=len(payload),
                      product_source_sha='9931dd36ea3b6e599851672f0fbc8c164d2c600b',
                      emotion_records=1000, completed_cbt=200, distortion_rows=343,
                      embedding_model='text-embedding-3-small', dimensions=1536, stored_vectors=1400,
                      adaptations=['Outer BEGIN/COMMIT removed; JDBC owns transaction', 'ID mapping output removed'],
                      embeddings='Precomputed real API results; no startup API calls')
    (OUTPUT / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(provenance))


if __name__ == '__main__':
    main()
