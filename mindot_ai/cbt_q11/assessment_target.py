"""Original-record quotation eligibility; confirmation is control, never new evidence."""
from copy import deepcopy
from .contracts import CompletionTechnicalError, Move
from .diagnostics import sha
from . import validity

MESSAGES={
    'ASK_CONFIRMATION':'앞서 적은 생각이 그때 실제로 떠오른 생각이 맞는지 확인해 주실 수 있을까요?',
    'WAIT_FOR_CONFIRMATION':'평가할 원래 생각이 확인되지 않아 아직 판정을 진행할 수 없습니다. 원래 기록이 맞는지 확인해 주세요.',
    'RECORD_CHANGE_REQUIRED':'다른 생각을 살펴보려면 먼저 기록 내용을 수정하거나 새 기록으로 시작해 주세요.'}

def refresh_projection(state):
    address=f'RECORD_{state.record_id}_automaticThought'
    raw=state.record_sources.get(address+'@'+str(state.thought_revision))
    if raw is None:
        state.assessment_target={'status':'NEEDS_REVIEW','thoughtRevision':state.thought_revision,
            'targetSourceBinding':{'address':address,'revision':state.thought_revision},'validRanges':[]}
        return
    _,tombstones,_,_=validity.reduce(state,[])
    retracted=tombstones.get(address+'@'+state.thought_revision,[])
    ranges=validity.subtract([(0,len(raw['text']))],retracted)
    confirmation=state.assessment_target_confirmations.get(state.thought_revision,{})
    accepted_proofs=confirmation.get('recordChangeProofs',[])
    if not accepted_proofs and confirmation.get('recordChangeProof'): accepted_proofs=[confirmation['recordChangeProof']]
    current_proof=None
    for proof in accepted_proofs:
        try:
            validity.validate_pointer(state,proof,'REQUEST_CONTROL')
            current_proof=proof
        except ValueError:
            continue
    # Only actual active RETRACT coverage of the original source enters this control.
    fully_withdrawn=bool(retracted) and not ranges
    status='VALID' if ranges else 'CONFIRMATION_REQUIRED' if fully_withdrawn else 'NEEDS_REVIEW'
    if fully_withdrawn and current_proof: status='RECORD_CHANGE_REQUIRED'
    state.assessment_target={'status':status,'thoughtRevision':state.thought_revision,
        'targetSourceBinding':{'address':address,'revision':state.thought_revision},
        'validRanges':[list(r) for r in ranges],'activeRetractions':[list(r) for r in retracted],
        'confirmationUsed':bool(confirmation.get('questionCode')),'rootControlId':confirmation.get('rootControlId'),
        'questionCodes':list(confirmation.get('questionCodes',[])),
        'recordChangeProof':deepcopy(current_proof)}

def eligible(state):
    return state.assessment_target.get('status')=='VALID'

def require_eligible(state):
    if not eligible(state):
        raise CompletionTechnicalError('assessment_target_confirmation_required')

def view(state):
    return deepcopy(state.assessment_target)

def control_plan(decision,source,state,table,diagnostics):
    from . import planner, requests
    refresh_projection(state)
    current=state.assessment_target
    if current['status'] not in ('CONFIRMATION_REQUIRED','RECORD_CHANGE_REQUIRED'):
        raise CompletionTechnicalError('assessment_target_requires_full_original_withdrawal')
    confirmation=state.assessment_target_confirmations.get(state.thought_revision,{})
    if decision=='ASK_CONFIRMATION':
        if source is not None or confirmation.get('questionCode'):
            raise CompletionTechnicalError('assessment_target_confirmation_already_used')
    elif decision=='WAIT_FOR_CONFIRMATION':
        if source is not None or not confirmation.get('questionCode'):
            raise CompletionTechnicalError('assessment_target_wait_requires_confirmation')
    elif decision=='RECORD_CHANGE_REQUIRED':
        if source is None:
            raise CompletionTechnicalError('record_change_requires_current_user_proof')
        resolved=requests.proof(state,source,table)
        if not confirmation.get('questionCode'):
            raise CompletionTechnicalError('record_change_requires_confirmation')
        saved=requests.all_questions(state).get(resolved['questionCode'],{})
        if saved.get('rootControlId')!=confirmation['rootControlId']:
            raise CompletionTechnicalError('record_change_control_lineage')
        # Stored proof is never a replacement thought; a new record requires public input.
        confirmation.setdefault('recordChangeProof',deepcopy(resolved))
        proofs=confirmation.setdefault('recordChangeProofs',[])
        if resolved not in proofs: proofs.append(deepcopy(resolved))
        projection=state.review_projections.get(resolved['questionCode'])
        if projection:
            span={'start':resolved['start'],'length':resolved['length']}
            if span not in projection['controlRanges']: projection['controlRanges'].append(span)
        refresh_projection(state)
    else:
        raise CompletionTechnicalError('unknown_assessment_target_decision')
    message=MESSAGES[decision]
    plan=planner.bind(planner.build(Move.USER_DIRECTION,message,(),state,diagnostics,source='ASSESSMENT_TARGET'),
        scope='CONTROL',control_purpose='ASSESSMENT_TARGET',existing=message,
        target_binding=current['targetSourceBinding'])
    return plan.model_copy(update={'assessment_target_decision':decision,'root_control_id':confirmation.get('rootControlId'),
        'thought_revision':state.thought_revision})

def commit_question(state,plan,code,message):
    key=plan.thought_revision
    if key!=state.thought_revision:
        raise CompletionTechnicalError('assessment_target_stale_thought')
    confirmation=state.assessment_target_confirmations.setdefault(key,{'rootControlId':'ATCTRL_'+sha(code)[:24],
        'questionCode':code,'questionCodes':[],'targetSourceBinding':deepcopy(plan.target_source_binding)})
    if code not in confirmation['questionCodes']: confirmation['questionCodes'].append(code)
    confirmation['lastDecision']=plan.assessment_target_decision
    # Each wire text has its own identity; the original ASK text remains immutable.
    confirmation.setdefault('responses',[]).append({'questionCode':code,'question':message,'decision':plan.assessment_target_decision})
    refresh_projection(state)
    return confirmation['rootControlId']
