"""Actual SELECT and limited phase callables; all semantic writes remain in a draft."""
from copy import deepcopy
from . import completion, planner, policy, rendering, safety, writer, goals, phases, llm, requests, gaps, assessment_target
from .contracts import CompletionTechnicalError, Move, EffectivePlan
from .state import pending_presentations, resolve_pointer, refresh
from .signals import eligible_control
from .provider_schemas import response_format
from .prompts import COMPLETION_ASSESSOR_SYSTEM_PROMPT, QUESTION_WRITER_SYSTEM_PROMPT
from .capacity import largest_argument

def frozen(context):
    return phases.freeze(context.action,context.data['viewId'])

def current_control(pointer,context,draft):
    resolved=resolve_pointer(pointer,context.data['sources'])
    source=next(s for s in context.data['sources'] if s['address']==resolved['address'] and s['revision']==resolved['revision'])
    if not draft.history or source.get('sourceKey')!=draft.current_sources[draft.history[-1].question_code]:
        raise CompletionTechnicalError('control_requires_current_user_source')
    if not eligible_control(source['text'],resolved['exactExcerpt'],resolved['start']):
        raise CompletionTechnicalError('control_quoted_or_negated_source')
    return resolved

def normal(args,context,draft):
    policy.apply_reviews(draft,args,context.data,context.diagnostics)
    goals.apply_resume(draft,args['resume'],context.data['sources'])
    requests.apply_updates(draft,args['requestUpdates'],context.data['sources'],
        aliases=policy.activate_aliases(draft,context.data,context.presentation_aliases))
    refresh(draft)
    conflict=None
    try:
        review=safety.evaluate_review(args['safetyReview'],draft,context.data['sources'],
            context.data['episodeSlots'],context.data['moderation'])
    except safety.SafetyConflict as exc:
        conflict=exc; review={'resolutions':[]}
    if not conflict:
        safety.apply_review(draft,review)
    refresh(draft)
    return conflict

def recheck(conflict,context,draft,prepared,*,following=()):
    context.budget.reserve_extra('SAFETY_RECHECK')
    value={'frozenAction':frozen(context),'conflict':str(conflict),'prepared':prepared,
        'continuationAllowed':context.action['name']!='assess_completion'}
    phases.reserve_reentry('SAFETY_RECHECK',value,context,draft,following)
    return phases.Outcome(phase='SAFETY_RECHECK',payload=value)

def writer_wire(context,draft,plan):
    fmt=response_format('question_'+plan.presentation_mode.lower(),writer.writer_schema(plan.presentation_mode))
    return llm.request_kwargs('writer',QUESTION_WRITER_SYSTEM_PROMPT,fmt,writer.payload(context.request,draft,plan,context.data))

async def dispatch_prepared(prepared,context,draft):
    kind=prepared['kind']
    if kind=='WRITE':
        plan=EffectivePlan.model_validate(prepared['plan'])
        text=await writer.write(context.request,draft,plan,context.diagnostics,context.writer_model,view=context.data)
        return phases.Outcome(rendering.question_response(context.request,draft,plan,text))
    if kind=='STOP':
        return phases.Outcome(rendering.stop_guidance(context.request,draft,context.diagnostics))
    if kind=='CONTROL':
        plan=EffectivePlan.model_validate(prepared['plan'])
        return phases.Outcome(rendering.question_response(context.request,draft,plan,plan.focus,kind='U'))
    if kind=='DIRECTION':
        question='지금 본인의 경험에서 다음으로 살펴보고 싶은 부분은 무엇인가요?'
        identity=goals.new_goal(draft,Move.USER_DIRECTION,question,'DIRECTION')
        plan=planner.bind(planner.build(Move.USER_DIRECTION,question,(),draft,context.diagnostics,source='AGENT_CONTROL'),
            scope='DIRECTION',goal_id=identity)
        return phases.Outcome(rendering.question_response(context.request,draft,plan,question,kind='U'))
    raise CompletionTechnicalError('unknown_frozen_operation')

async def write_turn(arguments,context,draft):
    conflict=normal(arguments,context,draft)
    policy.no_controls_bypassed(draft)
    plan=planner.compile_goal(arguments['goal'],draft,context.data['sources'],context.diagnostics)
    prepared={'kind':'WRITE','plan':plan.model_dump(by_alias=True,mode='json')}
    if conflict:
        return recheck(conflict,context,draft,prepared,following=[('WRITER',writer_wire(context,draft,plan))])
    return await dispatch_prepared(prepared,context,draft)

async def present_pending_question(arguments,context,draft):
    value=arguments['presentation']
    binding=context.data['pendingInteractions'].get(value['targetPendingId'])
    if not binding:
        raise CompletionTechnicalError('unknown_presentation_target')
    if binding['scope']=='SAFETY':
        episode=draft.episodes.get(binding['episodeId'])
        if not episode or episode['status']!='ACTIVE' or not episode['clarificationUsed']:
            raise CompletionTechnicalError('safety_presentation_without_pending')
        pointer=resolve_pointer(value['requestSource'],context.data['sources'])
        if pointer.get('sourceKey') not in draft.current_sources.values() or not eligible_control(
                draft.sources[pointer['sourceKey']].text,pointer['exactExcerpt'],pointer['start']):
            raise CompletionTechnicalError('safety_request_requires_current_user_revision')
        for ref in value['safety']['contextReferences']:
            resolve_pointer(ref,context.data['sources'])
        if safety.detect_request(context.request):
            raise CompletionTechnicalError('emergency_cannot_present')
        requests.apply_updates(draft,value['requestUpdates'],context.data['sources'],
            aliases=policy.activate_aliases(draft,context.data,context.presentation_aliases))
        request=requests.safety_request(draft,pointer,value['targetPendingId'],
            value['requestType'],context.data['sources'])
        refresh(draft)
        plan=planner.presentation_plan(value,draft,context.data,{},context.diagnostics)
        plan=plan.model_copy(update={'presentation_request_ids':(request['requestId'],),
            'presentation_request_kind':value['requestType']})
        text=await writer.write(context.request,draft,plan,context.diagnostics,context.writer_model,view=context.data)
        response=rendering.question_response(context.request,draft,plan,text)
        return phases.Outcome(response)
    conflict=normal(value,context,draft)
    policy.no_controls_bypassed(draft,stop_presentation=binding['scope']=='STOP')
    if binding['scope']=='STOP' and (not draft.stop_guidance_pending or value['resume'] is not None):
        raise CompletionTechnicalError('stop_presentation_must_preserve_stop')
    aliases=policy.activate_aliases(draft,context.data,context.presentation_aliases)
    # Validate ALL non-request bindings before opening the one narrow repair.
    plan=planner.presentation_plan(value,draft,context.data,aliases,context.diagnostics,validate_requests=False)
    try:
        plan=planner.presentation_plan(value,draft,context.data,aliases,context.diagnostics)
    except planner.PresentationBindingError:
        if conflict:
            raise CompletionTechnicalError('multiple_repairs_not_permitted')
        allowed=[alias for alias,identity in aliases.items() if identity in plan.presentation_request_ids]
        context.budget.reserve_extra('ARGUMENT_REPAIR')
        payload={'frozenAction':frozen(context),'issue':'requestIds_binding_only',
            'allowedRequestIds':allowed,'activatedBindings':aliases}
        phases.reserve_reentry('ARGUMENT_REPAIR',payload,context,draft,[('WRITER',writer_wire(context,draft,plan))])
        return phases.Outcome(phase='ARGUMENT_REPAIR',payload=payload)
    prepared={'kind':'WRITE','plan':plan.model_dump(by_alias=True,mode='json')}
    if conflict:
        return recheck(conflict,context,draft,prepared,following=[('WRITER',writer_wire(context,draft,plan))])
    return await dispatch_prepared(prepared,context,draft)

async def assess_completion(arguments,context,draft):
    conflict=normal(arguments,context,draft)
    policy.assessment_eligible(draft,allow_pending_safety=bool(conflict))
    if conflict:
        return recheck(conflict,context,draft,{'kind':'ASSESS'})
    scope=context.budget; scope.reserve_extra('ASSESSMENT_REVIEW')
    schema=completion.schema_for(draft)
    # Pure worst-count candidate estimate, not a generated/accepted model answer.
    maximum=largest_argument(schema,schema,[s['text'] for s in context.data['sources']])['result']
    preflight={'candidate':phases.candidate(maximum,draft,context),'frozenAction':frozen(context),
        'assessmentContext':completion.payload(context.request,draft,context.data)}
    phases.prepare_envelope('ASSESSMENT_REVIEW',preflight,context,draft,after_assessor=True)
    assessor_wire=llm.request_kwargs('assessor',COMPLETION_ASSESSOR_SYSTEM_PROMPT,
        response_format('completion_assessment',schema),completion.payload(context.request,draft,context.data))
    review_wire=phases.wire(phases.messages_for('ASSESSMENT_REVIEW',preflight,context),
        phases.tools_for('ASSESSMENT_REVIEW',preflight,context),'ASSESSMENT_REVIEW')
    from .capacity import agent_admission
    agent_admission(phases.messages_for('ASSESSMENT_REVIEW',preflight,context),
        phases.tools_for('ASSESSMENT_REVIEW',preflight,context),context.data['sources'],context.diagnostics,'ASSESSMENT_REVIEW')
    scope.reserve_path([('ASSESSOR',assessor_wire),('ASSESSMENT_REVIEW',review_wire)])
    result=await completion.assess(context.request,draft,context.diagnostics,context.assessor_model,view=context.data)
    payload={'candidate':phases.candidate(result,draft,context),'frozenAction':frozen(context),
        'assessmentContext':completion.payload(context.request,draft,context.data)}
    phases.reserve_reentry('ASSESSMENT_REVIEW',payload,context,draft)
    return phases.Outcome(phase='ASSESSMENT_REVIEW',payload=payload)

async def respond_safety(arguments,context,draft):
    decision=safety.resolve_directive(arguments['safety'],draft,context.data['sources'])
    return phases.Outcome(rendering.safety_response(context.request,draft,decision,context.diagnostics))

async def respond_control(arguments,context,draft):
    control=arguments['control']
    if control['type']=='STOP':
        conflict=None
        try:
            resolved=safety.evaluate_review(control['safetyReview'],draft,context.data['sources'],
                context.data['episodeSlots'],context.data['moderation'])
        except safety.SafetyConflict as exc:
            conflict=exc
        current_control(control['source'],context,draft)
        if conflict:
            return recheck(conflict,context,draft,{'kind':'STOP'})
        safety.apply_review(draft,resolved)
        return await dispatch_prepared({'kind':'STOP'},context,draft)
    conflict=normal(control,context,draft)
    policy.no_controls_bypassed(draft)
    if control['type']=='GAP_ANSWER_WAIT':
        if conflict or safety.active_episodes(draft):
            raise CompletionTechnicalError('gap_wait_cannot_bypass_safety')
        if pending_presentations(draft) or not assessment_target.eligible(draft):
            raise CompletionTechnicalError('gap_wait_cannot_bypass_requests_or_target')
        plan=gaps.wait_plan(draft,control['targetPendingId'],context.diagnostics)
        return await dispatch_prepared({'kind':'CONTROL','plan':plan.model_dump(by_alias=True,mode='json')},context,draft)
    if control['type']=='REQUEST_TARGET':
        if conflict or safety.active_episodes(draft):
            raise CompletionTechnicalError('request_target_cannot_bypass_safety')
        aliases=policy.activate_aliases(draft,context.data,context.presentation_aliases)
        plan=requests.control_plan(control,draft,context.data,aliases,context.diagnostics)
        return await dispatch_prepared({'kind':'CONTROL','plan':plan.model_dump(by_alias=True,mode='json')},context,draft)
    if control['type']=='ASSESSMENT_TARGET':
        if conflict or safety.active_episodes(draft):
            raise CompletionTechnicalError('assessment_target_cannot_bypass_safety')
        if pending_presentations(draft):
            raise CompletionTechnicalError('assessment_target_cannot_bypass_requests')
        plan=assessment_target.control_plan(control['decision'],control['source'],draft,
            context.data['sources'],context.diagnostics)
        return await dispatch_prepared({'kind':'CONTROL','plan':plan.model_dump(by_alias=True,mode='json')},context,draft)
    if pending_presentations(draft) or gaps.unresolved(draft) or not assessment_target.eligible(draft):
        raise CompletionTechnicalError('direction_cannot_bypass_pending')
    for pointer in control['contextReferences']:
        resolve_pointer(pointer,context.data['sources'])
    if any(g['scope']=='DIRECTION' and g['status']=='OPEN' for g in draft.goals.values()):
        raise CompletionTechnicalError('direction_still_unresolved')
    if conflict:
        return recheck(conflict,context,draft,{'kind':'DIRECTION'})
    return await dispatch_prepared({'kind':'DIRECTION'},context,draft)

async def accept_assessment(arguments,context,draft,payload):
    value=payload['candidate']
    if arguments['candidateId']!=value['candidateId']:
        raise CompletionTechnicalError('candidate_identity')
    phases.verify_candidate(value,draft,context)
    result=completion.validate({'result':value['result']},context.request,draft)
    if result['type']=='FACT_BOUNDARY_REQUIRED':
        plan=planner.boundary_plan(result,draft,context.diagnostics)
        context.diagnostics.fact_boundary_gap=result
        response=rendering.question_response(context.request,draft,plan,result['question'],kind='B')
        gaps.accept_gap(draft,value,response.next_question.question_code,plan.goal_id)
        refresh(draft)
        return phases.Outcome(response)
    response=completion.render(context.request,draft,result,context.diagnostics)
    draft.pending_assessment=response.model_dump(by_alias=True,mode='json'); draft.pending_question=None
    gaps.record_attempt(draft,value,'ACCEPTED')
    refresh(draft)
    return phases.Outcome(response)

async def reject_assessment(arguments,context,draft,payload):
    if arguments['candidateId']!=payload['candidate']['candidateId']:
        raise CompletionTechnicalError('candidate_identity')
    context.diagnostics.emit('assessment_rejected',candidateId=arguments['candidateId'],
        candidate=deepcopy(payload['candidate']),reason=arguments['reason'])
    raise CompletionTechnicalError('assessment_rejected')

async def decline_action(arguments,context,draft,payload):
    context.diagnostics.emit('action_declined',reason=arguments['reason'])
    raise CompletionTechnicalError('limited_phase_declined')

async def repair_presentation_binding(arguments,context,draft,payload):
    phases.verify(payload['frozenAction'],context.data['viewId'])
    value=deepcopy(payload['frozenAction']['arguments']['presentation'])
    value['requestIds']=arguments['requestIds']
    plan=planner.presentation_plan(value,draft,context.data,payload['activatedBindings'],context.diagnostics)
    return await dispatch_prepared({'kind':'WRITE','plan':plan.model_dump(by_alias=True,mode='json')},context,draft)

async def continue_selected_tool(arguments,context,draft,payload):
    phases.verify(payload['frozenAction'],context.data['viewId'])
    if not payload['continuationAllowed']:
        raise CompletionTechnicalError('assessment_recheck_would_require_four')
    result=safety.evaluate_review(arguments['safetyReview'],draft,context.data['sources'],
        context.data['episodeSlots'],context.data['moderation'])
    # Recheck changes safetyReview only; pure exclusions and contributions stay frozen.
    safety.apply_review(draft,result)
    refresh(draft)
    return await dispatch_prepared(payload['prepared'],context,draft)

CALLABLES={'write_turn':write_turn,'assess_completion':assess_completion,
    'present_pending_question':present_pending_question,'respond_safety':respond_safety,'respond_control':respond_control}
PHASE_CALLABLES={'accept_assessment':accept_assessment,'reject_assessment':reject_assessment,
    'decline_action':decline_action,'repair_presentation_binding':repair_presentation_binding,
    'continue_selected_tool':continue_selected_tool}
