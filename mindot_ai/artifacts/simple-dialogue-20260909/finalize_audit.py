"""Post-run factual receipt only. Does not modify frozen code or rerun API."""
import json,hashlib,sys
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT/'runner'))
from run import verify_lock,write,canon,sha
lock=verify_lock()
plan=json.loads((OUT/'package/docs/cbt-q11-simple/canary-plan.json').read_text(encoding='utf-8'))
summary=json.loads((OUT/'live/summary.json').read_text(encoding='utf-8'))
cases=[]
for case in plan['cases']:
    row=json.loads((OUT/'live'/ (case['id']+'.json')).read_text(encoding='utf-8'))
    cases.append(dict(caseId=case['id'],required=True,
        status='BLOCKED_BY_PARENT' if row['request'] is None else 'FAIL_NO_PRODUCT_RESPONSE',
        functionalVerdict='NOT_OBSERVED_NO_MODEL_RESPONSE',
        reason='Actual parent nextQuestion unavailable; fixed child input was not fabricated.' if row['request'] is None else row['error'],
        rawFile='live/'+case['id']+'.json'))
gate=dict(status='CANARY_BLOCKED',reason='APIConnectionError on every runnable root; no provider generation response and no product commit.',
    firstFailure='normal-1-start',planned=12,executed=8,passed=0,failed=8,unexecuted=4,
    semanticFailuresObserved=0,semanticSuccessesObserved=0,
    explanation='Eight missing public responses fail required acceptance. They do not demonstrate a semantic question/safety defect because no model output was received. Provider receipt and charge cannot be proved from local connection errors; usage stays unknown, not zero-cost.',
    sourceHash=lock['sourceHash'],lockedBytesUnchanged=True,canaryRounds=1,qualityRetries=0,technicalRetries=0,
    generationAttempts=8,moderationAttempts=8,generationResponses=0,moderationResponses=0,
    observedGenericTokens=0,unknownReservedTokens=146553,formalResults=0,formalExpected=368,
    hiddenKeyRead=False,hiddenPlaintextRead=False,holdoutReleaseAttempted=False,
    cases=cases,notObservedLive=['contextual questions','adaptive followups','mixed help delivery','correction retention',
        'completion assessment','fact-boundary branch','clear danger response','noncurrent quote handling','stop guidance'])
write(OUT/'canary-gate.json',gate)
summary['status']='CANARY_BLOCKED';write(OUT/'live/summary.json',summary)
# Original lock itself remains untouched. Clarify an audit-label bug: Windows
# relative paths used backslashes, so the original product-only prefix test
# included artifact entries. The execution lock still covered every file.
product=[f for f in lock['files'] if not f['path'].replace('\\','/').startswith('artifacts/')]
write(OUT/'source-identity-clarification.json',dict(executionSourceHash=lock['sourceHash'],
    actualProductSourceHash=sha(canon(product).encode()),productFiles=product,
    originalProductSourceHashLabelIncorrect=True,
    explanation='Original productSourceHash equals the complete execution source set due to Windows path separators. All original bytes/hashes remain immutable and verified. This sidecar corrects only the label/scope; no product, prompt, input, runner or provider output changed.'))
print(json.dumps({k:v for k,v in gate.items() if k not in ('cases','notObservedLive','explanation')}))
