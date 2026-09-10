"""Actual Agent graph with at most one finite restricted reentry, no retry edge."""
from dataclasses import dataclass, replace
from typing import Annotated, Any, TypedDict
from copy import deepcopy
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage, AnyMessage
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langgraph.runtime import Runtime as GraphRuntime
from .contracts import CbtTurnResponse, CompletionTechnicalError, ANALYSIS_CONTRACT_REVISION
from .diagnostics import canonical, sha
from .memory import restore_state, state_data
from .prompts import AGENT_SYSTEM_PROMPT, PHASE_PROMPTS
from .provider import choose
from .tools import CALLABLES, PHASE_CALLABLES
from . import phases


class GraphState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    accepted: dict
    baseline_accepted: dict
    origin_selection: dict | None
    phase: str
    phase_payload: dict | None
    attempt_id: str
    parent_checkpoint: str | None
    selection: dict | None
    response: dict | None
    completed: bool


@dataclass(frozen=True)
class TurnContext:
    request: Any
    data: dict
    tool_schemas: list
    presentation_aliases: dict
    diagnostics: Any
    budget: Any
    agent_model: Any = None
    assessor_model: Any = None
    writer_model: Any = None
    emergency: Any = None
    action: dict | None = None
    agent_messages: tuple = ()


async def agent_node(state: GraphState,runtime: GraphRuntime[TurnContext]):
    context=runtime.context
    context.diagnostics.emit('analysis_contract',revision=ANALYSIS_CONTRACT_REVISION,
        projectionRevision=context.data['projectionRevision'],
        promptSha256={key:sha(value) for key,value in PHASE_PROMPTS.items()},
        boundSchemaSha256=sha(canonical(context.tool_schemas)))
    messages=[SystemMessage(content=AGENT_SYSTEM_PROMPT),HumanMessage(content=canonical(context.data))]
    selected,message=await choose(messages,context.tool_schemas,context.budget,context.agent_model)
    message.id='AGENT_'+state['attempt_id']+'_SELECT_1'
    context.diagnostics.emit('agent_selected_tool',name=selected.name,callId=selected.call_id,
        rawArguments=selected.arguments_json,decodedArguments=selected.arguments())
    selection={'name':selected.name,'call_id':selected.call_id,'arguments_json':selected.arguments_json,
        'schema_issue':selected.schema_issue,'decoded_arguments':selected.decoded_arguments}
    return {'messages':[message],'selection':selection,'origin_selection':selection,'phase':'SELECT'}

def action_context(state,context):
    from .provider import ToolSelection
    original=ToolSelection(**state['origin_selection'])
    identity='AGENT_'+state['attempt_id']+'_SELECT_1'
    messages=[m for m in state['messages'] if m.id==identity]
    if len(messages)!=1:
        raise CompletionTechnicalError('original_agent_message_missing')
    import json
    return replace(context,action={'name':original.name,'call_id':original.call_id,'arguments':original.arguments(),
        'wireArguments':json.loads(original.arguments_json)},
        agent_messages=tuple(messages))

async def tool_node(state: GraphState,runtime: GraphRuntime[TurnContext]):
    from .provider import ToolSelection
    context=action_context(state,runtime.context)
    selected=ToolSelection(**state['selection']); draft=restore_state(state['accepted'])
    outcome=await CALLABLES[selected.name](selected.arguments(),context,draft)
    return tool_update(state,selected,outcome,draft,1)

def tool_update(state,selected,outcome,draft,ordinal):
    if outcome.phase:
        if state['phase']!='SELECT':
            raise CompletionTechnicalError('second_agent_reentry_forbidden')
        body=outcome.payload['wireEnvelope']
        update={'phase':outcome.phase,'phase_payload':outcome.payload,'response':None}
    else:
        from .state import refresh
        refresh(draft)
        public=CbtTurnResponse.model_validate(outcome.response.model_dump(by_alias=True,mode='json'))
        body=public.model_dump(by_alias=True,mode='json')
        update={'response':body,'phase_payload':None}
    result=ToolMessage(content=canonical(body),tool_call_id=selected.call_id,name=selected.name,
        id=f"TOOL_{state['attempt_id']}_{state['phase']}_{ordinal}")
    return {**update,'accepted':state_data(draft),'messages':[result]}

async def phase_agent_node(state: GraphState,runtime: GraphRuntime[TurnContext]):
    context=action_context(state,runtime.context)
    phase=state['phase']; payload=state['phase_payload']
    phases.verify(payload['frozenAction'],context.data['viewId'])
    # The reentry pair is the pair actually committed by selected_tool, not fabricated history.
    stored=next((m for m in state['messages'] if m.id=='TOOL_'+state['attempt_id']+'_SELECT_1'),None)
    if stored is None or payload['wireEnvelope']['acceptedDraftRevision']!=sha(canonical(state['accepted'])):
        raise CompletionTechnicalError('reentry_tool_or_draft_mismatch')
    messages=phases.messages_for(phase,payload,context,stored=stored)
    ordinal=len(context.budget.ledger)+1
    selected,message=await choose(messages,phases.tools_for(phase,payload,context),context.budget,
        context.agent_model,phase=phase)
    message.id=f"AGENT_{state['attempt_id']}_{phase}_{ordinal}"
    return {'messages':[message],'selection':{'name':selected.name,'call_id':selected.call_id,
        'arguments_json':selected.arguments_json,'schema_issue':selected.schema_issue,
        'decoded_arguments':selected.decoded_arguments}}

async def phase_tool_node(state: GraphState,runtime: GraphRuntime[TurnContext]):
    from .provider import ToolSelection
    context=action_context(state,runtime.context); selected=ToolSelection(**state['selection'])
    payload=state['phase_payload']
    phases.verify(payload['frozenAction'],context.data['viewId'])
    if selected.name=='respond_safety':
        # Shared ledger/USER receipts survive, but CBT projection/candidate never leaks.
        draft=restore_state(state['baseline_accepted'])
        outcome=await CALLABLES['respond_safety'](selected.arguments(),context,draft)
    else:
        draft=restore_state(state['accepted'])
        outcome=await PHASE_CALLABLES[selected.name](selected.arguments(),context,draft,payload)
    return tool_update(state,selected,outcome,draft,len(context.budget.ledger))

def after_selected(state: GraphState):
    return 'phase_agent' if state.get('phase_payload') is not None else 'public_response'


def response_node(state: GraphState,runtime: GraphRuntime[TurnContext]):
    response=CbtTurnResponse.model_validate(state['response'])
    selected=state['selection']
    if response.next_question:
        text=response.next_question.question
        identity='QUESTION_'+sha(str(runtime.context.request.session_id)+':'+response.next_question.question_code)
        metadata={'publicQuestionCode':response.next_question.question_code,'renderedBy':'VALIDATED_TOOL_RESULT'}
    else:
        text=response.proposal_message or canonical({'status':response.status.value,'risk':response.risk.model_dump(by_alias=True,mode='json')})
        identity='PUBLIC_'+state['attempt_id']; metadata={'renderedBy':'VALIDATED_TOOL_RESULT'}
    if selected:
        metadata['toolCallId']=selected['call_id']
    return {'completed':True,'messages':[AIMessage(content=text,id=identity,additional_kwargs=metadata)]}


def emergency_node(state: GraphState,runtime: GraphRuntime[TurnContext]):
    from .rendering import safety_response
    context=runtime.context; draft=restore_state(state['accepted'])
    response=safety_response(context.request,draft,context.emergency,context.diagnostics)
    from .state import refresh
    refresh(draft)
    return {'accepted':state_data(draft),'response':response.model_dump(by_alias=True,mode='json')}


def entry(state: GraphState,runtime: GraphRuntime[TurnContext]):
    return 'emergency' if runtime.context.emergency is not None else 'agent'


def compile_graph(saver):
    graph=StateGraph(GraphState,context_schema=TurnContext)
    graph.add_node('agent',agent_node)
    graph.add_node('selected_tool',tool_node)
    graph.add_node('phase_agent',phase_agent_node)
    graph.add_node('phase_tool',phase_tool_node)
    graph.add_node('public_response',response_node)
    graph.add_node('emergency',emergency_node)
    graph.add_conditional_edges(START,entry,{'agent':'agent','emergency':'emergency'})
    graph.add_edge('emergency','public_response')
    graph.add_edge('agent','selected_tool')
    graph.add_conditional_edges('selected_tool',after_selected,{'phase_agent':'phase_agent','public_response':'public_response'})
    graph.add_edge('phase_agent','phase_tool')
    graph.add_edge('phase_tool','public_response')
    graph.add_edge('public_response',END)
    return graph.compile(checkpointer=saver)


async def execute(graph,initial,config,context):
    """Obtain this attempt's END head from its stream, never from saver latest."""
    final=None
    async for mode,event in graph.astream(initial,config=config,context=context,
            stream_mode=['checkpoints'],durability='sync',version='v1'):
        values=event.get('values',{})
        if (values.get('attempt_id')==initial['attempt_id'] and values.get('completed')
                and values.get('parent_checkpoint')==initial['parent_checkpoint'] and not event['next']):
            final=deepcopy(event)
    if final is None:
        raise CompletionTechnicalError('attempt_end_checkpoint_not_observed')
    head=final['config']['configurable']
    if head['thread_id']!=config['configurable']['thread_id'] or not head.get('checkpoint_id'):
        raise CompletionTechnicalError('foreign_end_checkpoint')
    # Explicit ID read also verifies that the synchronous END checkpoint is persisted.
    persisted=await graph.aget_state(final['config'])
    if persisted.next or not persisted.values.get('completed') or persisted.values['attempt_id']!=initial['attempt_id']:
        raise CompletionTechnicalError('end_checkpoint_not_persisted')
    return head,final['values']
