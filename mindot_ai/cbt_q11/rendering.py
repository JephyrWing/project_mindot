"""Public response rendering only, called inside the selected Agent tool."""
from .contracts import (
    AnalysisMeta,CbtApiStatus,CbtTurnResponse,GeneratedQuestion,MODEL,Move,REVIEWER_VERSION,
    QuestionPurpose,RiskAssessment,RiskLevel,RiskReasonCode,SemanticRouteType)
from .diagnostics import canonical,sha
from .state import checkpoint
from . import safety,planner,requests
from copy import deepcopy

def base_response(request,*,question=None,risk=None):
    return CbtTurnResponse(request_id=request.request_id,
        status=CbtApiStatus.CONTINUE if question else CbtApiStatus.SAFETY_STOP,
        assessment_type=None,next_question=question,before_distortions=[],outcome_draft=None,
        confirmation_required_fields=[],acknowledgement_evidence=None,acknowledgement_source_question_code=None,
        proposal_message=None,risk=risk or RiskAssessment(level=RiskLevel.NONE,reason_code=None),
        meta=AnalysisMeta(model=MODEL,prompt_version=REVIEWER_VERSION))
def safety_response(request,state,decision,diagnostics):
    if not decision.episode_id:
        decision=safety.register_emergency(state,decision)
    diagnostics.safety=decision.dump()
    diagnostics.emit('safety_precedence',decision=decision.dump())
    episode=state.episodes[decision.episode_id]
    episode.update(status='ACTIVE',level=decision.level)
    state.focus_episode_id=decision.episode_id
    if decision.action=='STOP':
        if state.pending_question and not any(q['questionCode']==state.pending_question['questionCode'] for q in state.prior_questions):
            state.prior_questions.append(deepcopy(state.pending_question))
        state.pending_question=None
        requests.refresh_projection(state)
        safety.sync_focus(state)
        return base_response(request,risk=RiskAssessment(level=RiskLevel(decision.level),reason_code=RiskReasonCode(decision.reason)))
    message=safety.CLARIFICATION_QUESTIONS[decision.unresolved]
    episode.update(clarificationUsed=True,unresolved=decision.unresolved,clarificationGoal=decision.clarification_goal,question=message)
    plan=planner.bind(planner.build(Move.USER_DIRECTION,message,(),state,diagnostics,source='SAFETY'),
        scope='SAFETY',episode_id=decision.episode_id,
        target_binding={k:episode['primaryTrigger'][k] for k in ('address','revision')})
    response=question_response(request,state,plan,message,kind='S')
    episode['questionCode']=response.next_question.question_code
    safety.sync_focus(state)
    return response

def question_response(request,state,plan,message,*,kind=None):
    kind=kind or {'NORMAL':'Q','EXAMPLE':'E','EXPLANATION':'X'}[plan.presentation_mode]
    code=checkpoint(state,request,plan.move,kind,message,plan=plan)
    question=GeneratedQuestion(question_code=code,question_purpose=plan.question_purpose,
                               semantic_route_type=plan.semantic_route_type,question=message)
    control_prompt=plan.presentation_mode=='NORMAL' and plan.scope=='CONTROL'
    if plan.presentation_request_ids and not control_prompt:
        requests.deliver(state,plan.presentation_request_ids,plan.pending_id,plan.presentation_mode,
            code,request.request_id,plan.presentation_request_kind)
    parent_code=plan.parent_question_code or (state.history[-1].question_code if state.history else None)
    parent=requests.all_questions(state).get(parent_code,{})
    if state.pending_question and not any(q['questionCode']==state.pending_question['questionCode'] for q in state.prior_questions):
        state.prior_questions.append(deepcopy(state.pending_question))
    root=plan.root_control_id
    clarification_id=plan.clarification_id
    if control_prompt and plan.control_purpose=='REQUEST_TARGET' and clarification_id is None:
        clarification_id='CLAR_'+sha(code)[:24]; root='CTRL_'+sha(code)[:24]
        state.request_clarifications[clarification_id]={'clarificationId':clarification_id,'rootControlId':root,
            'questionCode':code,'questionCodes':[],'requestIds':list(plan.presentation_request_ids),
            'remainingRequestIds':list(plan.presentation_request_ids),'state':'ASKED','latestAnswerSourceKey':None}
    if control_prompt and plan.control_purpose=='ASSESSMENT_TARGET':
        from . import assessment_target
        root=assessment_target.commit_question(state,plan,code,message)
    if control_prompt and plan.control_purpose=='GAP_ANSWER_WAIT':
        from . import gaps
        root=gaps.commit_wait(state,plan,code,parent_code)
    groups=[g for g in state.request_clarifications.values() if g['state']!='CLOSED']
    inherited=list(dict.fromkeys([*parent.get('clarificationIds',[]),*[g['clarificationId'] for g in groups],
        *([clarification_id] if clarification_id else [])]))
    root_gap=code if kind=='B' else plan.root_gap_question_code or parent.get('rootGapQuestionCode')
    source_status=plan.source_binding_status
    if state.goals.get(plan.goal_id,{}).get('sourceBindingStatus')=='UNKNOWN_LEGACY_BINDING':
        source_status='UNKNOWN_LEGACY_BINDING'
    state.pending_question={'questionCode':code,'question':message,'move':plan.move.value,'kind':kind,
        'targetId':plan.goal_id or plan.episode_id or code,'targetQuestionCode':plan.target_question_code or code,
        'targetDomain':plan.target_domain.value if plan.target_domain else None,'focus':plan.focus,
        'planSource':plan.plan_source,'scope':plan.scope,'goalId':plan.goal_id,'episodeId':plan.episode_id,
        'targetSourceBinding':deepcopy(plan.target_source_binding),
        'contextSourceBindings':deepcopy(list(plan.context_source_bindings)),
        'sourceBindingStatus':source_status,
        'presentationQuestionBinding':deepcopy(plan.presentation_question_binding),
        'questionPurpose':plan.question_purpose.value,'semanticRouteType':plan.semantic_route_type.value,
        'presentationTargetPendingId':plan.pending_id,'controlPurpose':plan.control_purpose,
        'requestIds':list(plan.presentation_request_ids),'rootControlId':root or parent.get('rootControlId'),
        'parentQuestionCode':parent_code,'rootGapQuestionCode':root_gap,
        'thoughtRevision':state.thought_revision,'clarificationId':clarification_id,
        'clarificationIds':inherited,'controlStage':plan.control_stage}
    for group in groups:
        if code not in group['questionCodes']: group['questionCodes'].append(code)
    actual_root=state.pending_question['rootControlId']
    for confirmation in state.assessment_target_confirmations.values():
        if confirmation['rootControlId']==actual_root and code not in confirmation['questionCodes']:
            confirmation['questionCodes'].append(code)
    if control_prompt and plan.control_purpose=='REQUEST_TARGET':
        group=state.request_clarifications[clarification_id]
        state.request_target_questions[code]={'questionCode':code,'requestIds':list(plan.presentation_request_ids),
            'remainingRequestIds':list(group['remainingRequestIds']),'clarificationId':clarification_id,
            'rootControlId':root,'status':group['state'],'state':group['state'],'question':message}
    if plan.goal_id:
        goal=state.goals[plan.goal_id]
        goal['sourceRevisions']=list(dict.fromkeys([*goal['sourceRevisions'],*state.current_sources.values()]))
        goal['recordRevision']=state.record_revision
        if code not in goal['questionCodes']:
            goal['questionCodes'].append(code)
    if plan.scope=='SAFETY':
        episode=state.episodes[plan.episode_id]
        episode['questionCode']=code
        safety.sync_focus(state)
    else:
        state.pending_assessment=None
    # Gap acceptance/usage is installed by the accepting tool with the immutable candidate.
    requests.refresh_projection(state)
    risk=RiskAssessment(level=RiskLevel.REVIEW,reason_code=RiskReasonCode.AMBIGUOUS_SAFETY_SIGNAL) if plan.scope=='SAFETY' else None
    return base_response(request,question=question,risk=risk)

def stop_guidance(request,state,diagnostics):
    from . import goals
    state.stop_guidance_pending=True
    message='중단 요청을 확인했습니다. 아직 세션 취소가 완료된 것은 아닙니다. 화면 아래의 ‘성찰 완전히 중단’을 눌러 취소를 확인해 주세요.'
    identity=next((g['goalId'] for g in state.goals.values() if g['scope']=='STOP' and g['status']=='OPEN'),None)
    identity=identity or goals.new_goal(state,Move.USER_DIRECTION,message,'STOP')
    plan=planner.bind(planner.build(Move.USER_DIRECTION,message,(),state,diagnostics,source='EXISTING_CANCEL_GUIDANCE'),
        scope='STOP',goal_id=identity)
    diagnostics.emit('cancel_guidance',cancelCompleted=False,existingUiAction='성찰 완전히 중단',normalExploration=False)
    return question_response(request,state,plan,message,kind='C')
