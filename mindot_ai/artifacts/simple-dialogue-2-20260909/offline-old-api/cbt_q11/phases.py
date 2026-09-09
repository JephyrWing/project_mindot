"""One finite Agent reentry, immutable action/candidate and genuine tool pair."""
from dataclasses import dataclass
from copy import deepcopy
from langchain_core.messages import SystemMessage, ToolMessage
from .contracts import CompletionTechnicalError
from .diagnostics import canonical, sha
from .prompts import PHASE_PROMPTS, TOOL_DESCRIPTIONS
from . import provider_schemas as schemas

@dataclass(frozen=True)
class Outcome:
    response: object | None = None
    phase: str | None = None
    payload: dict | None = None

def freeze(action,view_id):
    value={'viewId':view_id,'name':action['name'],'arguments':deepcopy(action['arguments'])}
    return {**value,'digest':sha(canonical(value))}

def verify(action,view_id):
    raw={k:v for k,v in action.items() if k!='digest'}
    if action['viewId']!=view_id or action['digest']!=sha(canonical(raw)):
        raise CompletionTechnicalError('frozen_action_binding_changed')

def tools_for(phase,payload,context):
    data=context.data; sources=[s['sourceId'] for s in data['sources']]
    catalog=payload['wireEnvelope']['phaseView'].get('actionableEpisodes',[])
    episode_ids=[e['episodeId'] for e in catalog]
    common={'descriptions':TOOL_DESCRIPTIONS}
    if phase=='ASSESSMENT_REVIEW':
        return schemas.assessment_review_tools(candidate_id=payload['candidate']['candidateId'],
            source_ids=sources,episode_ids=episode_ids,**common)
    if phase=='ARGUMENT_REPAIR':
        return schemas.argument_repair_tools(request_ids=payload['allowedRequestIds'],**common)
    if phase=='SAFETY_RECHECK':
        return schemas.safety_recheck_tools(source_ids=sources,episode_slots=list(data['episodeSlots']),
            episode_ids=episode_ids,continuation_allowed=payload['continuationAllowed'],**common)
    raise CompletionTechnicalError('unknown_reentry_phase')

def prepare_envelope(phase,payload,context,draft,*,after_assessor=False):
    from .memory import state_data
    from . import views,safety,requests
    snapshot=context.budget.snapshot()
    if after_assessor:
        snapshot={**snapshot,'generationRemaining':snapshot['generationRemaining']-1,
            'dispatchedPhases':[*snapshot['dispatchedPhases'],'ASSESSOR']}
    if phase=='ASSESSMENT_REVIEW':
        phase_view={'candidate':deepcopy(payload['candidate']),**deepcopy(payload['assessmentContext'])}
    elif phase=='ARGUMENT_REPAIR':
        value=payload['frozenAction']['arguments']['presentation']
        phase_view={**views.draft(draft,context.data['sources']),
            'frozenBinding':views.pointers({k:value[k] for k in ('targetPendingId','mode','requestUpdates','stage')}),
            'issue':payload['issue'],'allowedRequestIds':payload['allowedRequestIds'],
            'activatedBindings':payload['activatedBindings'],'questionTargets':requests.question_targets(draft),
            'safetyReviewDigest':sha(canonical(value['safetyReview']))}
    else:
        phase_view={**views.draft(draft,context.data['sources']),
            'frozenOperation':views.pointers(payload['prepared']),'conflict':payload['conflict'],
            'continuationAllowed':payload['continuationAllowed'],
            'episodeSlots':deepcopy(context.data['episodeSlots']),'moderation':deepcopy(context.data['moderation'])}
    phase_view.update(views.pointers(safety.catalog(draft,context.data['sources'])))
    payload['wireEnvelope']={'phase':phase,'viewId':context.data['viewId'],
        'projectionRevision':draft.projection_revision,
        'acceptedDraftRevision':sha(canonical(state_data(draft))),
        'actionDigest':payload['frozenAction']['digest'],'currentBudget':snapshot,'phaseView':phase_view}
    return payload['wireEnvelope']

def messages_for(phase,payload,context,stored=None):
    action=context.action
    if not action or len(context.agent_messages)!=1 or len(context.agent_messages[0].tool_calls)!=1:
        raise CompletionTechnicalError('phase_requires_actual_agent_tool_call')
    call=context.agent_messages[0].tool_calls[0]
    if (call['id']!=action['call_id'] or call['name']!=action['name'] or
            call['args']!=action.get('wireArguments',action['arguments'])):
        raise CompletionTechnicalError('phase_orphan_tool_result')
    envelope=payload['wireEnvelope']; content=canonical(envelope)
    if envelope['phase']!=phase or envelope['actionDigest']!=payload['frozenAction']['digest']:
        raise CompletionTechnicalError('phase_envelope_binding')
    if stored is not None:
        if stored.content!=content or stored.tool_call_id!=action['call_id'] or stored.name!=action['name']:
            raise CompletionTechnicalError('reentry_tool_pair_mismatch')
        result=stored
    else:
        # Admission uses the exact envelope subsequently emitted by the callable.
        result=ToolMessage(content=content,tool_call_id=action['call_id'],name=action['name'],
            id=f'TOOL_{context.request.request_id}_SELECT_1')
    return [SystemMessage(content=PHASE_PROMPTS[phase]),context.agent_messages[0],result]

def wire(messages,tools,phase):
    from .provider import agent_wire
    return agent_wire(messages,tools,phase)

def reserve_reentry(phase,payload,context,draft,following=()):
    from .capacity import agent_admission
    prepare_envelope(phase,payload,context,draft)
    agent_admission(messages_for(phase,payload,context),tools_for(phase,payload,context),
        context.data['sources'],context.diagnostics,phase)
    context.budget.reserve_path([(phase,wire(messages_for(phase,payload,context),tools_for(phase,payload,context),phase)),*following])

def candidate(result,state,context):
    from .memory import state_data
    from uuid import uuid4
    from . import gaps
    value={'candidateId':'CAND_'+uuid4().hex,'result':deepcopy(result),
        'projectionRevision':state.projection_revision,'gapAnswerDependency':gaps.candidate_dependency(state),
        'thoughtRevision':state.thought_revision,'sourceBindings':[
            {'sourceId':s['sourceId'],'address':s['address'],'revision':s['revision']} for s in context.data['sources']],
        'acceptedRevision':sha(canonical(state_data(state))),
        'reviewDigest':sha(canonical(context.action['arguments'].get('reviews',{}))),
        'viewId':context.data['viewId']}
    return {**value,'hash':sha(canonical(value))}

def verify_candidate(value,state,context):
    from .memory import state_data
    raw={k:v for k,v in value.items() if k!='hash'}
    if value['hash']!=sha(canonical(raw)) or value['acceptedRevision']!=sha(canonical(state_data(state))):
        raise CompletionTechnicalError('candidate_hash_or_state_changed')
    if value['thoughtRevision']!=state.thought_revision or value['viewId']!=context.data['viewId']:
        raise CompletionTechnicalError('candidate_revision_changed')
    from . import gaps
    if value['projectionRevision']!=state.projection_revision or value['gapAnswerDependency']!=gaps.candidate_dependency(state):
        raise CompletionTechnicalError('candidate_projection_dependency_changed')
