"""Pure shared export: no model calls, grading, or inferred missing coverage."""
from copy import deepcopy
import hashlib,json
import legacy_export as old

SCHEMA=deepcopy(old.SCHEMA)
def nullable(node):
    node=deepcopy(node);node['type']=[node['type'],'null'] if isinstance(node['type'],str) else list(dict.fromkeys(node['type']+['null']))
    return node
for key in ('priorVerifiedCoverage','inputExplorationCoverage','completionCandidateCoverage','coverageReview','acceptedCoverage','acceptedEvidenceAtoms'):
    SCHEMA['properties']['acceptedState']['properties'][key]=nullable(SCHEMA['properties']['acceptedState']['properties'][key])
SCHEMA['properties']['final']['properties']['exampleOptions']=nullable(old.array(old.TEXT))
SCHEMA['properties']['boundary']['properties']['effectiveQuestionPlan']['properties']['exampleOptions']=nullable(old.array(old.TEXT))
SCHEMA['properties']['dialogueControl']['properties']['kind']=old.enum('ANSWER_WAIT','USER_STOP','SUMMARY_ONLY','NONE','UNKNOWN')
SCHEMA['properties']['dialogueState']=old.obj({
    'sources':nullable(old.array(old.obj({'sourceRef':old.TEXT,'questionRef':old.TEXT,'text':old.TEXT,'current':old.BOOL,
        'withdrawnRanges':old.array(old.obj({'start':old.NUMBER,'end':old.NUMBER}))}))),
    'corrections':nullable(old.array(old.obj({'operation':old.TEXT,'instruction':old.POINTER,'target':old.POINTER}))),
    'requests':nullable(old.array(old.obj({'requestRef':old.TEXT,'origin':old.POINTER,'kind':old.TEXT,'status':old.TEXT,
        'targetQuestionRef':old.TEXT,'deliveryQuestionRefs':old.array(old.TEXT)}))),
    'goals':nullable(old.array(old.obj({'questionRef':old.TEXT,'status':old.TEXT,'note':old.TEXT})))})

def export(implementation,anonymous,case_id,turn_index,request,response,state,context=None,observations=None):
    ids=old.OpaqueMap();obs=deepcopy(observations or {});s=state or {}
    adapted=deepcopy(s)
    if implementation=='Q11':
        adapted={'history':request.get('questionAnswers',[]),'sources':{},'gap_registry':{},'episodes':{}}
        table=[]
        for sid,source in s.get('sources',{}).items():
            row=deepcopy(source)|{'sourceKey':source['key']}
            table.append(row)
        obs['inputView']={'sources':table}
        q=s.get('questions',{}).get(s.get('pendingQuestion'),{})
        adapted['pending_question']=dict(q,scope='STOP' if q.get('control') in ('STOP','UNRESOLVED') else 'SAFETY' if q.get('episodeId') else 'CBT')
        plan=obs.get('effectivePlan')
        if plan:
            obs['effectivePlan']=dict(plan,questionGoal=plan.get('focus'),presentationMode=plan.get('mode'),
                contextQuestionCodes=[s['sources'][sid].get('questionCode') for sid in plan.get('sourceIds',[]) if s['sources'][sid].get('questionCode')])
        terminal=(s.get('terminal') or {}).get('result') if response else None
        if terminal:
            obs['terminalAssessment']={'type':terminal['type'],'automaticThoughtExcerpt':terminal['automaticThought']['quote'],
                'matchedDistortionCode':terminal['matchedCode'],'definitionMatchReason':terminal['reason'],
                'calibratedThought':terminal['calibratedThought']}
            obs['currentEvidence']=[dict(refId='e'+str(i),sourceId=e['sourceId'],sourceKey=s['sources'][e['sourceId']]['key'],
                sourceQuestionCode=s['sources'][e['sourceId']].get('questionCode'),exactExcerpt=e['quote'],domain=e['domain'],
                reviewerRole=e['role'],kind='EXPLICIT_NONE' if e['role']=='EXPLICIT_NONE' else 'CONTENT') for i,e in enumerate(terminal['evidence'])]
        else: obs.pop('terminalAssessment',None)
    context=deepcopy(context or {})
    # Historical suite labels are full-only identifiers. Keep shared categories.
    context['suite']=context.get('suite','KNOWN')
    for key in ('situation_family','vulnerability_type'):
        if context.get(key) and old.LEAK.search(context[key]): context[key]=None
    output=old.export_turn(implementation=implementation,anonymous_version=anonymous,case_key=case_id,
        response_key=str(turn_index),turn_index=turn_index,public_request=request,public_response=response,
        accepted_state=adapted,grading_context=context,observations=obs,id_map=ids)
    payload=output['blindPayload']; scope=payload['identity']['case_id']+':'+anonymous
    ref=lambda k,v:ids.ref(k,v,scope)
    for k in ('priorVerifiedCoverage','inputExplorationCoverage','completionCandidateCoverage','coverageReview','acceptedCoverage'):
        val=payload['acceptedState'][k]
        if implementation=='Q11' or all(x['status']=='UNKNOWN' for x in val.values()): payload['acceptedState'][k]=None
    if implementation=='Q11' and not (s.get('terminal') and response): payload['acceptedState']['acceptedEvidenceAtoms']=None
    if implementation=='Q11':
        payload['final']['exampleOptions']=None
        payload['boundary']['effectiveQuestionPlan']['exampleOptions']=None
    controls=payload['dialogueControl']
    if implementation=='Q11' and response:
        q=s.get('questions',{}).get(s.get('pendingQuestion'),{})
        mode=q.get('control')
        controls['kind']={'STOP':'USER_STOP','UNRESOLVED':'SUMMARY_ONLY','WAIT':'ANSWER_WAIT'}.get(mode,'NONE')
        if mode in ('STOP','UNRESOLVED'):
            payload['final'].update(actualDecision='STOP',actualAssessment=None,actualDistortions=[])
        if mode=='WAIT':
            controls.update(targetQuestionRef=ref('q',(s.get('gap') or {}).get('questionCode')),availableResponses=['ANSWER','SKIP'])
            payload['final']['actionClass']='UNCERTAIN'
        gap=s.get('gap');gp=payload['boundary']['factBoundaryGap']
        if gap:
            gp.update(questionRef=ref('q',gap['questionCode']),question=gap.get('question'),missingFact=gap.get('missingFact'),
                whyDecisionDependsOnIt=gap.get('whyDecisionDependsOnIt'),answerState=gap['answerState'])
        else: gp['answerState']='NOT_ASKED'
        payload['boundary']['factBoundaryQuestionCount']=int(gap is not None)
    if implementation=='Q10' and payload['final']['actualDecision']=='STOP': controls['kind']='USER_STOP'
    def pointer(p):
        if p is None: return old.empty(old.POINTER)
        source=s['sources'][p['sourceId']]
        return dict(sourceRef=ref('s',source['key']),questionRef=ref('q',source.get('questionCode')),
                    start=p['start'],length=p['end']-p['start'],excerpt=source['text'][p['start']:p['end']])
    dialogue={k:None for k in SCHEMA['properties']['dialogueState']['properties']}
    if implementation=='Q11' and state:
        safety=payload['safety']
        safety['safetyCandidates']=[]
        for e in s['safety'].values():
            item=old.empty(old.SAFETY_ITEM)
            item.update(safetyRef=ref('v',e['id']),reason=e.get('reasonCode'),
                state={'ACTIVE':'UNRESOLVED','RESOLVED':'RESOLVED'}.get(e['status'],'UNKNOWN'),
                origin=pointer(e['trigger']),resolutionEvidence=[pointer(e['resolution'])] if e.get('resolution') else [])
            safety['safetyCandidates'].append(item)
        safety['safetyEvidence']=[pointer(e['trigger']) for e in s['safety'].values() if e['status']=='ACTIVE']
        dialogue['sources']=[dict(sourceRef=ref('s',v['key']),questionRef=ref('q',v.get('questionCode')),text=v['text'],
            current=s['current'].get(v['address'])==v['sourceId'],withdrawnRanges=[dict(start=a,end=b) for a,b in s.get('withdrawn',{}).get(v['sourceId'],[])]) for v in s['sources'].values()]
        dialogue['corrections']=[dict(operation=c['operation'],instruction=pointer(c['instruction']),target=pointer(c['target'])) for c in s['corrections']]
        dialogue['requests']=[dict(requestRef=ref('v',r['requestId']),origin=pointer(r['origin']),kind=r['kind'],status=r['status'],
            targetQuestionRef=ref('q',r['targetQuestionCode']),deliveryQuestionRefs=[ref('q',d.get('questionCode')) for d in r['deliveries']]) for r in s['requests'].values()]
        dialogue['goals']=[dict(questionRef=ref('q',g['questionCode']),status=g['status'],note=g.get('note')) for g in s['goals'].values()]
    payload['dialogueState']=dialogue
    old.validate(payload,SCHEMA)
    # Added text is original USER content, not metadata to redact.
    old.NATURAL_LANGUAGE_KEYS=old.NATURAL_LANGUAGE_KEYS|{'text','note'}
    old.check_metadata_leaks(payload)
    output['blindPayloadSha256']=hashlib.sha256(old.canonical_bytes(payload)).hexdigest()
    output['fullOnly']['referenceMap']=ids.records()
    output['fullOnly']['schemaSha256']=hashlib.sha256(old.canonical_bytes(SCHEMA)).hexdigest()
    output['fullOnly']['limitation']='Shared keys/nulls remove direct version metadata; missing internal observations may remain identifying.'
    return output
