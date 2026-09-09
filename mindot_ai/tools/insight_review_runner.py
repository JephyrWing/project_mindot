"""Post-review offline runner. This file is prepared, NOT executed in this stage.

The external ChatGPT report is read, never created or self-approved here.
No canary/paid dispatch is implicit in offline success.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def check_external_review(path):
    report_path = Path(path).resolve()
    if report_path.is_relative_to(ROOT):
        raise ValueError('ChatGPT review report must be outside the repository')
    report = json.loads(report_path.read_text(encoding='utf-8'))
    if report['reviewStatus'] != 'READY_FOR_TEST' or report.get('unreviewed'):
        raise ValueError('Full branch review is not complete')
    head = git('rev-parse','HEAD')
    remote = git('ls-remote','origin','refs/heads/fix/CBTAI').split()[0]
    if git('branch','--show-current') != 'fix/CBTAI' or head != remote or head != report['reviewedCommitSha']:
        raise ValueError('Review/local/remote SHA mismatch')
    if git('diff','--name-only') or git('diff','--cached','--name-only'):
        raise ValueError('Tracked source changed after review')
    manifest = report['readManifest']
    source = Path(manifest['path'])
    if not source.is_absolute():
        source = report_path.parent / source
    if hashlib.sha256(source.read_bytes()).hexdigest() != manifest['sha256']:
        raise ValueError('Read manifest hash mismatch')
    return head


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--chatgpt-review', required=True)
    parser.add_argument('--suite', choices=['ai','spring','frontend'], required=True)
    args = parser.parse_args()
    check_external_review(args.chatgpt_review)
    commands = {
        'ai': ([sys.executable,'-m','unittest','discover','-s','tests','-p','test_insight_protocol.py'], ROOT/'mindot_ai'),
        'spring': ([str(ROOT/'mindot_back'/'gradlew.bat'),'test','--tests','*InsightServiceTest','--tests','*InsightMappingTest','--no-daemon'], ROOT/'mindot_back'),
        'frontend': (['node','--test','src/utils/reflections/reflectionsApi.test.js','src/utils/reflections/sessionView.test.js'],ROOT/'mindot_front'),
    }
    command, cwd = commands[args.suite]
    raise SystemExit(subprocess.call(command,cwd=cwd))


if __name__ == '__main__':
    main()
