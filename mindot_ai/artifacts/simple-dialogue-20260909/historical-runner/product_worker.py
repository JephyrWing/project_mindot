"""FORMAL worker; gated credentials, exact original adapter, no local retries."""
import argparse
import asyncio
import hashlib
import json
import logging
import os
from pathlib import Path
import sys
import tempfile
import threading


class ParentRejected(BaseException):
    pass


def send(value):
    print(json.dumps(value, ensure_ascii=False, separators=(',', ':')), flush=True)


def read():
    line = sys.stdin.readline()
    if not line:
        raise ParentRejected('parent_eof')
    return json.loads(line)


async def serve(adapter, journal, version):
    while True:
        # Parent process owns hard idle/request deadlines. A daemon avoids
        # executor shutdown hanging on a dead stdin while the child is idle.
        loop = asyncio.get_running_loop()
        future = loop.create_future()
        def reader():
            try:
                value = read()
                loop.call_soon_threadsafe(lambda: None if future.done() else future.set_result(value))
            except BaseException as exc:
                loop.call_soon_threadsafe(lambda error=exc: None if future.done() else future.set_exception(error))
        threading.Thread(target=reader, daemon=True).start()
        command = await future
        if command['action'] == 'remove':
            await adapter.close(command['sessionId'])
            send({'kind': 'closed'})
            continue
        if command['action'] != 'invoke':
            raise ParentRejected('invalid_parent_command')
        request = command['request']
        journal.append('request_start', {'request': request, 'version': version})
        def observer(kind, payload):
            # Child raw storage is complete before asking the parent to account
            # usage. Parent ACK also gates every provider dispatch beforehand.
            journal.append(kind, payload)
            send({'kind': 'event', 'event': kind, 'payload': payload})
            if read().get('action') != 'ACK':
                raise ParentRejected('parent_rejected_event')
        result = await asyncio.wait_for(adapter.invoke(request, observer=observer), timeout=180)
        journal.append('request_end', {'result': result})
        send({'kind': 'result', 'result': result})


def main():
    parser = argparse.ArgumentParser()
    for name in ('config', 'lock-manifest', 'lock-sha256', 'holdout-gate', 'holdout-gate-sha256',
                 'version', 'source', 'work-directory'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--live-authorized', action='store_true')
    args = parser.parse_args()
    if not args.live_authorized or args.version not in ('Q10', 'Q11'):
        raise RuntimeError('live_formal_flag_and_version_required')
    from journal import Journal, verify_lock
    lock_data = Path(args.lock_manifest).read_bytes()
    if hashlib.sha256(lock_data).hexdigest() != args.lock_sha256:
        raise RuntimeError('formal_lock_hash_mismatch')
    rows = json.loads(lock_data)['files']
    verify_lock(rows)
    locked = {str(Path(row['path']).resolve()).casefold() for row in rows}
    def require(path):
        if str(Path(path).resolve()).casefold() not in locked:
            raise RuntimeError('formal_executable_or_config_not_locked')
    require(args.config)
    for name in ('product_worker.py', 'ipc_worker.py', 'journal.py', 'q10_adapter.py', 'q11_adapter.py'):
        require(Path(__file__).resolve().with_name(name))
    config = json.loads(Path(args.config).read_text(encoding='utf-8-sig'))
    if config['stage'] != 'FORMAL':
        raise RuntimeError('not_formal_configuration')
    source = Path(args.source).resolve()
    if source != Path(config['sourceByVersion'][args.version]).resolve():
        raise RuntimeError('source_role_mismatch')
    for path in source.rglob('*.py'):
        require(path)
    gate_bytes = Path(args.holdout_gate).read_bytes()
    if hashlib.sha256(gate_bytes).hexdigest() != args.holdout_gate_sha256:
        raise RuntimeError('holdout_gate_hash_mismatch')
    gate = json.loads(gate_bytes)
    if (gate['status'] != 'HOLDOUT_VERIFIED' or gate['formalLockSha256'] != args.lock_sha256
            or gate['canaryEligible'] is not True or gate['singleCases'] != 12 or gate['longCases'] != 2):
        raise RuntimeError('formal_release_gate_not_satisfied')
    work = Path(args.work_directory).resolve()
    if not work.is_relative_to(Path(config['runtimeWorkRoot']).resolve()) or 'mindot_ai' not in work.parts:
        raise RuntimeError('formal_runtime_outside_authorized_directory')
    with (work / 'worker.claim').open('xb') as claim:
        claim.write(b'NON_REPEATABLE_PROCESS_START\n')
        claim.flush()
        os.fsync(claim.fileno())
    os.environ['TEMP'] = os.environ['TMP'] = str(work)
    tempfile.tempdir = str(work)
    os.environ.update(OPENAI_CBT_MODEL='gpt-4o-mini', OPENAI_CBT_WRITER_TEMPERATURE='0.3',
        CBT_DEBUG_LOG_ANALYSIS='false', LANGCHAIN_TRACING_V2='false', LANGSMITH_TRACING='false',
        TIKTOKEN_CACHE_DIR=config['tokenizerCache'])
    for name in ('OPENAI_API_BASE', 'OPENAI_BASE_URL', 'LANGCHAIN_API_KEY', 'LANGSMITH_API_KEY'):
        os.environ.pop(name, None)
    # The parent launches a minimal environment, preserving no team credentials,
    # endpoint overrides or tracing keys. Load only the authorized OpenAI key.
    import dotenv
    values = dotenv.dotenv_values(config['credentialPath'])
    if not values.get('OPENAI_API_KEY'):
        raise RuntimeError('authorized_key_missing')
    os.environ['OPENAI_API_KEY'] = values['OPENAI_API_KEY']
    del values
    dotenv.load_dotenv = lambda *args, **kwargs: False
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(source))
    logging.disable(logging.CRITICAL)
    if args.version == 'Q11':
        from q11_adapter import Q11Adapter
        adapter = Q11Adapter()
    else:
        from q10_adapter import Q10Adapter
        adapter = Q10Adapter()
    journal = Journal(work / 'raw-events.jsonl')
    try:
        asyncio.run(serve(adapter, journal, args.version))
    finally:
        os.environ.pop('OPENAI_API_KEY', None)


if __name__ == '__main__':
    try:
        main()
    except BaseException as exc:
        send({'kind': 'fatal', 'errorType': type(exc).__name__})
        raise SystemExit(2)
