"""Offline checks of revised input metadata and deferred parent review only."""
import ast,json
from pathlib import Path
import input_plan,neutral
from run import OUT,AI,write,parent_confirmation_candidate,child_request

known=input_plan.load_known(OUT/'known');ids=input_plan.IdentityMap();exports=0
for case in known['cases']:
    if case['gradingContext']['classificationApplicability']!='MULTIPLE_VALID_ACTIONS':continue
    for version in ('Q10','Q11'):
        req=input_plan.initial_request(case,version,known,ids)
        assert not set(req)&{'allowedDecisions','expectedDecision','expectedAssessment','assessmentPolicy','evaluationContext'}
        exported=neutral.export(version,'A',case['caseKey'],0,req,None,None,case['gradingContext'])['blindPayload']['input']
        for key in ('allowedDecisions','expectedDecision','expectedAssessment','assessmentPolicy','classificationApplicability'):
            assert exported[key]==case['gradingContext'][key],key
        exports+=1
assert exports==20
parent={'caseId':'parent','request':{},'response':{'status':'CONFIRM_REQUIRED','nextQuestion':None}}
assert child_request({},parent) is None and parent_confirmation_candidate(parent)=='parent'
assert parent_confirmation_candidate({'response':None,'confirmationAncestor':'parent'})=='parent'
assert parent_confirmation_candidate({'response':None}) is None
for p in (OUT/'runner').glob('*.py'):ast.parse(p.read_text(encoding='utf-8-sig'))
source=OUT/'package/docs/cbt-q11-simple'
assert (AI/'cbt_simple/schema.py').read_bytes()==(source/'schema.py').read_bytes()
for p in (AI/'cbt_simple/prompts').glob('*.txt'):assert p.read_bytes()==(source/'prompts'/p.name).read_bytes()
receipt=dict(status='PASS',actualLiveCalls=0,multiActionCases=10,nullableSharedExports=exports,
    knownCases=len(known['cases']),fixedSingleGoldCount=len(known['classificationSidecar']['fixedSingleExpectedAction']),
    goldSafetyCount=len(known['classificationSidecar']['goldSafetyCases']),
    parentConfirmationDeferredUntilFunctionReview=True,promptSchemaBytesExact=True,runnerSyntaxValid=True)
write(OUT/'revision-checks.json',receipt);print(json.dumps(receipt))
