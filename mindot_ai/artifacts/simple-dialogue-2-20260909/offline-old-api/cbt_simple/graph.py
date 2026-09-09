"""Agent selects real callables; only Assessor extracts final evidence."""
from copy import deepcopy
from typing import TypedDict, Annotated
from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage, AIMessage
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from cbt_q11.contracts import (CbtTurnResponse,CbtApiStatus,CbtAssessmentType,GeneratedQuestion,
    AnalysisMeta,ReflectionOutcomeDraft,RiskAssessment,RiskLevel,RiskReasonCode,
    DistortionProposal,DISTORTION_DEFINITIONS,CONFIRMATION_REQUIRED_FIELDS,Move,CompletionTechnicalError)
from cbt_q11.planner import COMPILER
from cbt_q11.safety import CLARIFICATION_QUESTIONS
from cbt_q11.diagnostics import canonical,sha
from . import state as st
from .provider import PROMPTS,WriterFormatError

CONTROLS={
    'STOP':'중단 요청을 확인했어요. 성찰을 종료하려면 화면 아래의 ‘성찰 완전히 중단’을 눌러 주세요.',
    'CLARIFY_TARGET':'어느 질문이나 내용에 대한 도움을 원하시는지 짧게 알려주실 수 있나요?',
    'WAIT':'앞서 확인하던 내용은 아직 답변을 기다리고 있어요. 그 질문에 답하거나 이번에는 넘어가고 싶다고 알려주실 수 있나요?',
    'ASSESSMENT_TARGET':'처음 기록한 생각을 지금은 철회하거나 수정하신 것으로 이해했어요. 기록의 처음 생각을 수정하거나, 원래 생각을 계속 살펴보고 싶은지 알려주실 수 있나요?',
    'USER_DIRECTION':'지금 어떤 부분을 더 살펴보면 도움이 될까요?',
    'UNRESOLVED':'지금 이야기만으로는 생각에 대한 판정을 충분히 뒷받침하기 어려워요. 더 전할 내용이 있으면 이어서 말씀해 주세요. 여기서 멈추려면 화면 아래의 ‘성찰 완전히 중단’을 눌러 주세요.'}

class GraphState(TypedDict):
    messages: Annotated[list,add_messages]
    accepted: dict
    response: dict | None
    completed: bool
    selection: dict | None
    candidate: dict | None

def base(request,question=None,*,risk=None,result=None,ack=None,evidence_for=None,evidence_against=None):
    terminal=result is not None
    distortion=terminal and result['type']=='DISTORTION_PRESENT'
    return CbtTurnResponse(request_id=request.request_id,
        status=CbtApiStatus.CONFIRM_REQUIRED if terminal else (CbtApiStatus.CONTINUE if question else CbtApiStatus.SAFETY_STOP),
        assessment_type=CbtAssessmentType(result['type']) if terminal else None,next_question=question,
        before_distortions=[DistortionProposal(code=result['matchedCode'],classifier_confidence=0.5)] if distortion else [],
        outcome_draft=ReflectionOutcomeDraft(evidence_for_text=evidence_for,evidence_against_text=evidence_against,
            alternative_thought_text=result['calibratedThought'],after_distortions=[]) if terminal else None,
        confirmation_required_fields=list(CONFIRMATION_REQUIRED_FIELDS) if terminal else [],
        acknowledgement_evidence=ack['quote'] if ack else None,
        acknowledgement_source_question_code=ack['questionCode'] if ack else None,
        proposal_message=result['proposalMessage'] if terminal else None,
        risk=risk or RiskAssessment(level=RiskLevel.NONE,reason_code=None),
        meta=AnalysisMeta(model='gpt-4o-mini',prompt_version='simple-dialogue-1'))

def question_response(request,state,message,plan,*,control=None,risk=None):
    st.require(isinstance(message,str) and bool(message.strip()) and len(message)<=500,'public_message_capacity')
    mapping=COMPILER[Move(plan['move'])]
    code='SD_'+sha(canonical([request.session_id,str(request.request_id),message]))[:24].upper()
    target=state['questions'].get(plan.get('targetQuestionCode'))
    purpose=target['questionPurpose'] if target else mapping.purpose
    route=(target.get('semanticRouteType') or mapping.route) if target else mapping.route
    q=GeneratedQuestion(question_code=code,question_purpose=purpose,semantic_route_type=route,question=message)
    item=q.model_dump(by_alias=True,mode='json')|dict(move=plan['move'],focus=plan['focus'],mode=plan.get('mode','NORMAL'),
        control=control,targetQuestionCode=plan.get('targetQuestionCode'),gapId=plan.get('gapId'),episodeId=plan.get('episodeId'))
    state['questions'][code]=item; state['pendingQuestion']=code
    state['goals'][code]=dict(questionCode=code,status='OPEN',focus=plan['focus'])
    return base(request,q,risk=risk)

def control(request,state,mode,target=None):
    plan=dict(move='USER_DIRECTION',focus=mode,targetQuestionCode=None)
    if mode=='WAIT':
        gap=state['gap']
        st.require(gap is not None and gap['answerState']=='AWAITING_ANSWER','wait_without_pending_gap')
        st.require(target in (gap['gapId'],gap['questionCode']), 'wait_target_mismatch')
        plan.update(targetQuestionCode=gap['questionCode'],gapId=gap['gapId'])
    if mode=='ASSESSMENT_TARGET':
        st.require(not st.source_valid(state,state['record']['automaticThoughtSourceId']),'assessment_target_not_withdrawn')
    if mode=='STOP':
        state['stop']=dict(id='STOP'+str(len(state['events'])+1),status='ACTIVE',sourceIds=list(state['current'].values()))
    return question_response(request,state,CONTROLS[mode],plan,control=mode)

def restrictions(state,name,args):
    safety=any(e['status']=='ACTIVE' for e in state['safety'].values())
    stopped=state['stop'] and state['stop']['status']=='ACTIVE'
    if name=='respond_control':
        if stopped: st.require(args['mode']=='STOP','control_bypasses_stop')
        if safety: st.require(args['mode']=='STOP','control_bypasses_safety')
    if name not in ('respond_safety','respond_control'):
        st.require(not stopped,'dialogue_stopped')
        if safety:
            st.require(name=='present_pending_question','active_safety_episode')
    if name in ('write_turn','assess_completion'):
        st.require(not any(r['status']=='PENDING' for r in state['requests'].values()),'unfulfilled_help_request')
        st.require(not state['gap'] or state['gap']['answerState']!='AWAITING_ANSWER','gap_answer_wait_required')
    if name=='assess_completion':
        st.require(st.source_valid(state,state['record']['automaticThoughtSourceId']),'assessment_target_withdrawn')

def candidate_boundary(state,result):
    kind=result['type']; pointers=[]
    if kind in ('DISTORTION_PRESENT','NO_CLEAR_DISTORTION'):
        ref=result['automaticThought']
        st.require(ref['sourceId']==state['record']['automaticThoughtSourceId'],'assessment_wrong_original_thought')
        pointers.append(st.span(state,**dict(identity=ref['sourceId'],quote=ref['quote'],occurrence=ref['occurrence'])))
        if kind=='DISTORTION_PRESENT': st.require(result['matchedCode'] not in state['rejectedCodes'],'rejected_distortion')
        seen=[]
        for e in result['evidence']:
            p=st.span(state,e['sourceId'],e['quote'],e['occurrence'])
            st.require(not any(p['sourceId']==old['sourceId'] and p['start']<old['end'] and old['start']<p['end'] for old in seen),'duplicate_evidence_span')
            seen.append(p); pointers.append(p)
    else:
        st.references(state,result['sourceIds'])
        if kind=='FACT_BOUNDARY_REQUIRED':
            st.require(state['gap'] is None,'gap_budget_used')
            st.require(bool(result['question'].strip()),'blank_gap_question')
            st.require(all(q['question']!=result['question'] for q in state['questions'].values()),'repeated_gap_question')
    return pointers

def render_candidate(request,state,result,pointers):
    kind=result['type']
    if kind=='UNRESOLVED': return control(request,state,'UNRESOLVED')
    if kind=='FACT_BOUNDARY_REQUIRED':
        gid='G1'
        response=question_response(request,state,result['question'],dict(move='FACT_CERTAINTY_CHECK',focus=result['missingFact'],gapId=gid))
        state['gap']=dict(gapId=gid,questionCode=response.next_question.question_code,question=result['question'],
            missingFact=result['missingFact'],whyDecisionDependsOnIt=result['whyDecisionDependsOnIt'],
            answerState='AWAITING_ANSWER',used=True)
        return response
    def evidence_text(domain):
        text='\n'.join(e['quote'] for e in result['evidence'] if e['domain']==domain and e['role']!='EXPLICIT_NONE')
        st.require(len(text)<=4000,'evidence_capacity')
        return text or None
    ack=next((e for e in result['evidence'] if e['domain']=='acknowledgement' and e['role']=='BALANCED_SYNTHESIS'
              and state['sources'][e['sourceId']].get('questionCode')),None)
    if ack: ack=ack|dict(questionCode=state['sources'][ack['sourceId']]['questionCode'])
    response=base(request,result=result,ack=ack,evidence_for=evidence_text('evidenceFor'),evidence_against=evidence_text('evidenceAgainst'))
    state['terminal']=dict(result=deepcopy(result),pointers=pointers,invalidated=False)
    state['pendingQuestion']=None
    return response

def safety_response(request,state,action):
    trigger=action['trigger']; p=st.span(state,trigger['sourceId'],trigger['quote'],trigger['occurrence'])
    st.references(state,action['sourceIds'])
    eid=action['episodeId']
    if eid is not None: st.require(eid in state['safety'],'unknown_safety_episode')
    else:
        eid=next((i for i,e in state['safety'].items() if e['trigger']==p),None) or 'E'+str(len(state['safety'])+1)
    episode=state['safety'].setdefault(eid,dict(id=eid,trigger=p,clarificationUsed=False,history=[]))
    episode.update(status='ACTIVE',reasonCode=action['reasonCode'])
    episode['history'].append(deepcopy(action))
    if action['action']=='STOP':
        level=RiskLevel.CRISIS if action['urgency']=='IMMEDIATE' else RiskLevel.REVIEW
        state['pendingQuestion']=None
        return base(request,risk=RiskAssessment(level=level,reason_code=RiskReasonCode(action['reasonCode'])))
    st.require(not episode['clarificationUsed'],'safety_clarification_budget_used')
    episode['clarificationUsed']=True
    response=question_response(request,state,CLARIFICATION_QUESTIONS[action['clarificationGoal']],
        dict(move='USER_DIRECTION',focus=action['clarificationGoal'],episodeId=eid),
        control='SAFETY_CLARIFICATION',risk=RiskAssessment(level=RiskLevel.REVIEW,reason_code=RiskReasonCode.AMBIGUOUS_SAFETY_SIGNAL))
    episode['questionCode']=response.next_question.question_code
    return response

def compile_graph(saver,request,provider,diagnostics,moderation):
    async def select(g):
        view=st.view(g['accepted']); view['moderation']=moderation
        msg,selection=await provider.choose([SystemMessage(content=PROMPTS['SELECT']),HumanMessage(content=canonical(view))])
        diagnostics.emit('tool_selection',selection=selection)
        return dict(messages=[msg],selection=selection)

    async def writer(state,plan):
        payload=dict(view=st.view(state,2),plan=plan)
        provider.budget.reserve_path([('WRITER',provider.wire('WRITER',payload))])
        try:
            value=await provider.structured('WRITER',payload)
            if not value['message'].strip(): raise WriterFormatError('blank_message')
        except WriterFormatError as exc:
            repair=dict(**payload,formatError=str(exc))
            value=await provider.structured('WRITER_REPAIR',repair)
            if not value['message'].strip(): raise CompletionTechnicalError('writer_repair_blank')
        diagnostics.effective_plan=deepcopy(plan)
        return question_response(request,state,value['message'],plan)

    async def write_turn(state,args):
        target=args['targetQuestionCode']
        if target:
            st.question(state,target)
            st.require(state['goals'].get(target,{}).get('status','OPEN')=='OPEN','closed_question_target')
        plan=dict(move=args['move'],focus=args['focus'],sourceIds=args['sourceIds'],targetQuestionCode=target,mode='NORMAL')
        return await writer(state,plan)

    async def present_pending_question(state,args):
        selected=args['request']
        req=st.new_request(state,selected) if selected['origin']=='NEW' else state['requests'].get(selected['requestId'])
        st.require(req is not None and req['status']=='PENDING','selected_request_not_pending')
        st.require(st.valid_pointer(state,req['origin']),'request_origin_withdrawn')
        target=st.question(state,req['targetQuestionCode'])
        active_safety=[e for e in state['safety'].values() if e['status']=='ACTIVE']
        if active_safety: st.require(target.get('episodeId') in [e['id'] for e in active_safety],'help_bypasses_active_safety')
        mode='EXPLANATION' if target.get('episodeId') else req['kind']
        plan=dict(move=target.get('move','USER_DIRECTION'),focus=target.get('focus',target['question']),mode=mode,
            targetQuestionCode=target['questionCode'],requestId=req['requestId'],requestKind=req['kind'],
            sourceIds=[req['origin']['sourceId']],gapId=target.get('gapId'),episodeId=target.get('episodeId'))
        response=await writer(state,plan)
        req['status']='FULFILLED'
        req['deliveries'].append(dict(questionCode=response.next_question.question_code,mode=mode,attemptId=str(request.request_id)))
        return response

    async def assess_completion(state,args):
        payload=dict(view=st.view(state,2),distortionDefinitions=[{'code':code.value,**d} for code,d in DISTORTION_DEFINITIONS.items()])
        # Reserve bounded review output space before the assessment dispatch.
        review_reserve=dict(view=st.view(state,1),candidateReservation='x'*7200)
        provider.budget.reserve_path([('ASSESSOR',provider.wire('ASSESSOR',payload)),
                                     ('ASSESSMENT_REVIEW',provider.wire('ASSESSMENT_REVIEW',review_reserve))])
        raw=await provider.structured('ASSESSOR',payload)
        result=raw['result']; pointers=candidate_boundary(state,result)
        diagnostics.terminal_assessment=deepcopy(result)
        return dict(result=result,pointers=pointers)

    async def respond_control(state,args):
        target=args['targetId']
        if target is not None:
            known=set(state['questions'])|set(state['requests'])|set(state['safety'])
            if state['gap']: known.add(state['gap']['gapId'])
            if state['stop']: known.add(state['stop']['id'])
            st.require(target in known,'unknown_control_target')
        return control(request,state,args['mode'],target)

    async def respond_safety(state,args): return safety_response(request,state,args['safety'])

    callables=dict(write_turn=write_turn,present_pending_question=present_pending_question,
                   assess_completion=assess_completion,respond_control=respond_control,respond_safety=respond_safety)

    async def tool(g):
        state=deepcopy(g['accepted']); selection=g['selection']; args=selection['args']; name=selection['name']
        if name!='respond_safety':
            st.updates(state,args['updates'])
            st.references(state,args.get('sourceIds',[]))
            restrictions(state,name,args)
        result=await callables[name](state,args)
        assessment=name=='assess_completion'
        body=result if assessment else result.model_dump(by_alias=True,mode='json')
        tm=ToolMessage(content=canonical(body),tool_call_id=selection['id'],name=name)
        diagnostics.emit('tool_result',tool=name,toolCallId=selection['id'],result=body)
        return dict(accepted=state,messages=[tm],candidate=result if assessment else None,
                    response=None if assessment else body,completed=not assessment)

    async def review(g):
        state=deepcopy(g['accepted'])
        # Same Agent object, original AI call and actual ToolMessage, current raw view.
        pair=g['messages'][-2:]
        msg,decision=await provider.review([SystemMessage(content=PROMPTS['ASSESSMENT_REVIEW']),
            HumanMessage(content=canonical(st.view(state,1))),*pair])
        diagnostics.emit('assessment_review',decision=decision)
        st.require(decision['decision']=='ACCEPT','agent_rejected_assessment')
        result=g['candidate']
        response=render_candidate(request,state,result['result'],result['pointers'])
        return dict(accepted=state,response=response.model_dump(by_alias=True,mode='json'),messages=[msg],completed=True)

    graph=StateGraph(GraphState)
    graph.add_node('agent',select); graph.add_node('tools',tool); graph.add_node('assessment_review',review)
    graph.add_edge(START,'agent'); graph.add_edge('agent','tools')
    graph.add_conditional_edges('tools',lambda g:'assessment_review' if g['candidate'] is not None else END)
    graph.add_edge('assessment_review',END)
    return graph.compile(checkpointer=saver)
