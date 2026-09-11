"""A single LangGraph Agent selects and writes one real tool response."""
from copy import deepcopy
from typing import TypedDict
from uuid import uuid4
from langchain_core.messages import ToolMessage
from langgraph.graph import StateGraph,START,END
from langgraph.checkpoint.memory import InMemorySaver
from .state import require,candidate_boundary,render
from .contracts import ProtocolError
from cbt_q11.contracts import CompletionTechnicalError
from .schema import validate,assessor_schema

CONTROL='질문은 여기서 멈출게요. 나중에 이어하려면 ‘나중에 이어하기’를, 이 성찰을 완전히 종료하려면 ‘성찰 완전히 중단’을 선택해 주세요.'
AFTER_NOT_ESTABLISHED='지금까지의 대화에서는 처음 판단을 다시 살펴본 뒤 생각이 달라졌다는 뜻이 아직 분명하지 않아, 확인용 제안은 만들지 않았어요. 생각이 그대로여도 괜찮아요. 더 살펴보고 싶으면 말씀해 주세요. 여기서 멈추고 나중에 이어가거나 성찰을 완전히 중단할 수도 있어요.'
INVALID_CANDIDATE='생각을 정리하는 과정에서 오류가 생겨 이번 제안을 보여드리지 못했어요. 같은 답변을 다시 설명하실 필요는 없어요. 나중에 이어하거나 이 성찰을 완전히 중단할 수 있어요.'
CANDIDATE_REJECTED='작성한 제안이 말씀하신 뜻을 정확히 담았다고 확인하지 못해 보여드리지 않았어요. 생각이 바뀌지 않았다는 뜻은 아니에요. 더 전하고 싶은 내용이 있으면 말씀해 주세요. 여기서 멈추거나 나중에 이어갈 수도 있어요.'
SAFETY='지금은 안전이 우선입니다. 즉시 위험하다면 119 또는 112에 연락하고, 가까운 믿을 수 있는 사람에게 알려 혼자 있지 않도록 도움을 요청해 주세요.'
CLARIFY='말씀하신 위험이 지금 본인에게 해당하나요? 지금 자신이나 다른 사람을 해칠 생각이 있거나 즉시 도움이 필요한 상황인지 알려주세요.'

class GraphState(TypedDict,total=False):
    selection: dict
    agentMessage: object
    toolMessage: object
    candidate: dict
    result: dict

async def execute(snapshot,provider,diagnostics):
    payload=dict(snapshot=snapshot)
    def response(outcome,text,proposal=None,issue=None):
        return dict(outcome=outcome,phase='PROPOSAL_REVIEW' if proposal is not None else 'DIALOGUE',
            text=text,currentProposal=proposal,issue=issue)
    def display_text(text):
        if not text.strip() or len(text)>500:
            raise CompletionTechnicalError('display_text_format')
        return text
    async def ask_question(text):
        # A new question is a real return to dialogue, including after a user
        # corrects or withdraws the meaning behind an active proposal.
        return response('QUESTION',display_text(text))
    async def offer_help(text):
        proposal=deepcopy(snapshot.get('currentProposal')) if snapshot['phase']=='PROPOSAL_REVIEW' else None
        # EXPLAIN_PROPOSAL remains an external outcome for Spring compatibility;
        # the Agent-facing HELP/EXPLAIN_PROPOSAL mode split no longer exists.
        return response('EXPLAIN_PROPOSAL' if proposal is not None else 'HELP',display_text(text),proposal)
    async def assess_completion():
        # Reserve both mandatory remaining calls before invoking the Assessor.
        provider.budget.reserve_path([('ASSESSOR',provider.wire('ASSESSOR',payload)),
            ('ASSESSMENT_REVIEW',provider.wire('ASSESSMENT_REVIEW',dict(**payload,candidateReservation='검토 '*6000)))])
        candidate=await provider.structured('ASSESSOR',payload)
        diagnostics.emit('candidate',candidate=candidate)
        # Preserve the prior technical error contract for malformed schema values.
        # The raw candidate is already recorded; do not validate again downstream.
        try:validate(candidate,assessor_schema())
        except ValueError as exc:raise CompletionTechnicalError('assessor_schema:'+str(exc)) from exc
        return candidate
    async def respond_control():return response('CONTROL',CONTROL)
    async def respond_safety(action,reason):
        require(bool(reason.strip()),'blank_safety_reason')
        return response('SAFETY_STOP' if action=='STOP' else 'SAFETY_CLARIFY',SAFETY if action=='STOP' else CLARIFY)
    tools={'ask_question':ask_question,'offer_help':offer_help,'assess_completion':assess_completion,
        'respond_control':respond_control,'respond_safety':respond_safety}
    async def select(g):
        message,call=await provider.choose(provider.messages('SELECT',payload))
        return dict(agentMessage=message,selection=call)
    async def invoke(g):
        call=g['selection'];name=call['name']
        value=await tools[name](**call['args'])
        from cbt_q11.diagnostics import canonical
        if name=='assess_completion':
            try:value,approvable=candidate_boundary(value,snapshot,diagnostics)
            except (ValueError,ProtocolError) as exc:
                diagnostics.emit('candidate_rejected',reason=type(exc).__name__)
                return dict(result=response('UNRESOLVED',INVALID_CANDIDATE,issue='INVALID_CANDIDATE'))
            if not approvable:return dict(result=response('UNRESOLVED',AFTER_NOT_ESTABLISHED,issue='AFTER_NOT_ESTABLISHED'))
        receipt=ToolMessage(content=canonical(value),name=name,tool_call_id=call['id'])
        diagnostics.emit('tool_result',name=name,callId=call['id'],result=value)
        if name=='assess_completion':
            return dict(toolMessage=receipt,candidate=value)
        return dict(toolMessage=receipt,result=value)
    async def review(g):
        # Actual call and matching ToolMessage, not manufactured callable history.
        _,decision=await provider.review(provider.messages('ASSESSMENT_REVIEW',payload,(g['agentMessage'],g['toolMessage'])))
        diagnostics.emit('assessment_review',decision=decision)
        if not decision['accept']:return dict(result=response('UNRESOLVED',CANDIDATE_REJECTED,issue='CANDIDATE_REJECTED'))
        c=deepcopy(g['candidate']);before=snapshot['record']['automaticThought']
        proposal=dict(**c,proposalId=str(uuid4()),basedOnRevision=snapshot['revision'],resultFormatVersion='cbt-insight-1',
            beforeText=c['beforeCorrection']['text'] if c['beforeCorrection'] else before,originalBeforeText=before)
        return dict(result=response('PROPOSAL',render(proposal),proposal))
    graph=StateGraph(GraphState)
    graph.add_node('select',select);graph.add_node('tool',invoke);graph.add_node('review',review)
    graph.add_edge(START,'select');graph.add_edge('select','tool')
    graph.add_conditional_edges('tool',lambda g:'review' if g.get('candidate') else END)
    graph.add_edge('review',END)
    compiled=graph.compile(checkpointer=InMemorySaver())
    result=await compiled.ainvoke({},config={'configurable':{'thread_id':str(uuid4())},'callbacks':[],'recursion_limit':6})
    return result['result']
