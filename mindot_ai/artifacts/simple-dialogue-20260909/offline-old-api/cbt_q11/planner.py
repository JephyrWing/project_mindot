"""One atomic move mapping; stable target reuse is not a focus-hash oracle."""
from dataclasses import dataclass
from types import MappingProxyType
from .contracts import (Move, EffectivePlan, CompletionTechnicalError,
                        QuestionPurpose as P, SemanticRouteType as R)
from .diagnostics import sha
from .state import target_domain, pending_presentations

@dataclass(frozen=True)
class Mapping:
    route: R
    purpose: P
    source: str
    polarity: str
    focus: str
COMPILER=MappingProxyType({
    Move.OBSERVABLE_DETAIL:Mapping(R.OBSERVABLE_EVENT_DETAIL,P.SITUATION_REFLECTION,'USER_OBSERVATION','NOT_APPLICABLE','직접 관찰하거나 확인한 사건의 구체적인 사실'),
    Move.DIRECT_SUPPORT:Mapping(R.DIRECT_WORD_OR_ACTION,P.EVIDENCE_FOR,'USER_OBSERVATION','SUPPORTS_CORE_CLAIM','처음 생각을 직접 뒷받침하는 말이나 행동'),
    Move.COUNTEREVIDENCE:Mapping(R.CONTRADICTORY_FACT,P.EVIDENCE_AGAINST,'USER_OBSERVATION','CONTRADICTS_OR_LOWERS_CERTAINTY','처음 생각의 확신을 낮추는 구체적인 사실이나 예외'),
    Move.ALTERNATIVE_HYPOTHESIS:Mapping(R.ALTERNATIVE_EXPLANATION,P.ALTERNATIVE_VIEW,'USER_HYPOTHESIS','NOT_APPLICABLE','사실로 단정하지 않는 다른 설명의 가능성'),
    Move.BALANCED_SYNTHESIS:Mapping(R.BALANCED_CONCLUSION,P.BALANCED_THOUGHT,'SAVED_FACT_SYNTHESIS','NOT_APPLICABLE','확인한 사실과 생각의 확장을 함께 고려한 관점'),
    Move.USER_DIRECTION:Mapping(R.USER_SELECTED_DIRECTION,P.FREE_REFLECTION,'USER_CHOICE','NOT_APPLICABLE','지금 본인의 경험에서 다음으로 살펴보고 싶은 부분'),
    Move.FACT_CERTAINTY_CHECK:Mapping(R.CERTAINTY_REASSESSMENT,P.BALANCED_THOUGHT,'USER_RECALLABLE_FACT','NOT_APPLICABLE','판정의 경계를 바꾸는 확인 가능한 누락 사실'),
})
def build(move,focus,context,state,diagnostics,*,source='AGENT_TOOL',target=None,example=False,preface=False,mode='NORMAL',requests=()):
    move=Move(move); mapping=COMPILER[move]; domain=target_domain(move)
    plan=EffectivePlan(move=move,plan_source=source,question_purpose=mapping.purpose,semantic_route_type=mapping.route,
        answer_source=mapping.source,evidence_polarity=mapping.polarity,focus=focus,context_question_codes=tuple(context),
        target_domain=domain,target_question_code=target,
        target_id=target or 'TARGET_'+str(len(state.history))+'_'+sha(focus)[:8].upper(),
        semantic_key=sha(str(target)+'|'+focus)[:16],example_mode=example,preface_required=preface,
        presentation_mode=mode,presentation_request_ids=tuple(requests))
    diagnostics.effective_plan=plan.model_dump(by_alias=True,mode='json')
    diagnostics.emit('compiler',effectiveQuestionPlan=diagnostics.effective_plan)
    return plan

def bind(plan,*,scope='CBT',goal_id=None,pending_id=None,episode_id=None,existing=None,
         target_binding=None,context_bindings=(),resolution=None,control_purpose=None):
    return plan.model_copy(update={'scope':scope,'goal_id':goal_id,'pending_id':pending_id,
        'episode_id':episode_id,'existing_question':existing,'target_source_binding':target_binding,
        'context_source_bindings':tuple(context_bindings),'request_resolution':resolution,'control_purpose':control_purpose})

def compile_goal(goal,state,table,diagnostics):
    from . import goals, gaps, assessment_target
    from . import validity
    from .state import resolve_pointer
    if pending_presentations(state) or state.complete or gaps.unresolved(state) or not assessment_target.eligible(state):
        raise CompletionTechnicalError('normal_tool_ineligible')
    lookup={s['sourceId']:s for s in table}
    kind=goal['kind']
    if kind=='NEW':
        move=Move(goal['move']); target=lookup.get(goal['targetSourceId'])
        if len(set(goal['contextSourceIds']))!=len(goal['contextSourceIds']):
            raise CompletionTechnicalError('duplicate_goal_context')
        related=state.goals.get(goal['relatedGoalId'])
        if goal['relatedGoalId'] and not related:
            raise CompletionTechnicalError('unknown_related_goal')
        if related and related['status']!='OPEN':
            raise CompletionTechnicalError('closed_related_goal_requires_revisit_evidence')
        # Exact stable goal identity and declared relatedGoal are mechanical guards.
        # Unrelated paraphrases still require the Agent's semantic repetition judgment.
        if any(g['scope']=='CBT' and g['move']==move.value and g['focus']==goal['focus'] for g in state.goals.values()):
            raise CompletionTechnicalError('existing_exact_goal_requires_identity')
        goal_id=goals.new_goal(state,move,goal['focus'],'CBT',validity.binding(target) if target else None,
            goal['relatedGoalId'],[validity.binding(lookup[s]) for s in goal['contextSourceIds']])
        context=[lookup[s]['questionCode'] for s in goal['contextSourceIds'] if lookup[s].get('questionCode')]
        plan=build(move,goal['focus'],context,state,diagnostics)
    else:
        stored=state.goals.get(goal['goalId'])
        if not stored or stored['scope']!='CBT' or stored['move']==Move.FACT_CERTAINTY_CHECK.value:
            raise CompletionTechnicalError('invalid_continuation_goal')
        if stored.get('sourceBindingStatus')!='BOUND':
            raise CompletionTechnicalError('GOAL_BINDING_UNRECOVERABLE')
        if kind=='CONTINUE' and stored['status']!='OPEN':
            raise CompletionTechnicalError('closed_goal_requires_new_evidence')
        if kind=='REVISIT':
            refs=[resolve_pointer(p,table) for p in goal['newEvidence']]
            if len({(p['address'],p['revision'],p['start'],p['length']) for p in refs})!=len(refs):
                raise CompletionTechnicalError('duplicate_new_evidence')
            def new_current(p):
                if p['sourceKey']:
                    return p['sourceKey'] in state.current_sources.values() and p['sourceKey'] not in stored['sourceRevisions']
                return stored.get('recordRevision')!=state.record_revision and any(s['kind']=='USER_RECORD' and
                    s['address']==p['address'] and s['revision']==p['revision'] for s in table)
            if not all(new_current(p) for p in refs):
                raise CompletionTechnicalError('revisit_requires_new_current_revision')
            for ref in refs:
                if not validity.valid_span(state,ref['address'],ref['revision'],ref['start'],ref['length']):
                    raise CompletionTechnicalError('revisit_evidence_excluded')
                selected=validity.binding(ref)
                if selected not in stored['contextSourceBindings']: stored['contextSourceBindings'].append(selected)
            stored['sourceRevisions']=list(state.current_sources.values())
            stored['recordRevision']=state.record_revision
            stored['revisitAfter']=len(state.history)
            stored['status']='OPEN'
        goal_id=stored['goalId']
        plan=build(stored['move'],stored['focus'],(),state,diagnostics,
            target=stored['questionCodes'][-1] if stored['questionCodes'] else None)
    selected=state.goals[goal_id]
    return bind(plan,goal_id=goal_id,target_binding=selected['targetSourceBinding'],
        context_bindings=selected['contextSourceBindings'])

class PresentationBindingError(CompletionTechnicalError):
    pass

def presentation_plan(arguments,state,data,aliases,diagnostics,*,validate_requests=True):
    from .goals import pending_views
    from . import requests
    binding=data['pendingInteractions'].get(arguments['targetPendingId'])
    current=pending_views(state).get(arguments['targetPendingId'])
    if not binding or current!=binding:
        raise CompletionTechnicalError('stale_pending_binding')
    question_binding=requests.presentation_question_binding(state,requests.all_questions(state)[binding['questionCode']])
    if binding.get('sourceBindingStatus')=='UNKNOWN_LEGACY_BINDING' and (
            question_binding is None or binding.get('presentationQuestionBinding')!=question_binding):
        raise CompletionTechnicalError('GOAL_BINDING_UNRECOVERABLE_presentation')
    mode=arguments['mode']; scope=binding['scope']; ids=[]
    if scope!='SAFETY':
        active=requests.ordered(state)
        if not active: raise CompletionTechnicalError('presentation_without_active_request')
        definite=[c for c in active if c['targetPendingId'] is not None]
        if definite:
            oldest=definite[0]; selected_target=oldest['targetPendingId']
            same=[c for c in definite if c['targetPendingId']==selected_target]
        else:
            raise CompletionTechnicalError('target_resolution_required')
        wanted='REQUEST_EXAMPLE' if any(c['type']=='REQUEST_EXAMPLE' for c in same) else 'REQUEST_EXPLANATION'
        if arguments['targetPendingId']!=selected_target or 'REQUEST_'+mode!=wanted:
            raise CompletionTechnicalError('presentation_target_or_mode_priority')
        allowed={alias:c['requestId'] for alias,identity in aliases.items() for c in same
            if c['requestId']==identity and c['type']==wanted}
        # Non-request fields must be valid even when requestIds alone can be repaired.
        eligible=list(dict.fromkeys(allowed.values()))
        if not eligible: raise CompletionTechnicalError('no_eligible_presentation_binding')
        # requestUpdates have already been validated/applied once to this draft.
        # Only the selected subset must be delivered; other targets/kinds remain.
        ids=eligible
        if validate_requests:
            selected=arguments['requestIds']
            if not selected or len(set(selected))!=len(selected) or not set(selected)<=set(allowed):
                raise PresentationBindingError('requestIds_binding_only')
            ids=[allowed[key] for key in selected]
            if len(set(ids))!=len(ids): raise PresentationBindingError('requestIds_binding_only')
        expected_stage=binding.get('controlStage') or 'ASK_TARGET'
        if arguments['stage']!=expected_stage:
            raise CompletionTechnicalError('presentation_control_stage_mismatch')
    plan=build(binding['move'],binding['focus'],(),state,diagnostics,target=binding['questionCode'],
        mode=mode,example=mode=='EXAMPLE',requests=ids)
    plan=bind(plan,scope=scope,goal_id=binding['goalId'],pending_id=binding['pendingId'],
        episode_id=binding['episodeId'],existing=binding['question'],
        target_binding=binding['targetSourceBinding'],context_bindings=binding['contextSourceBindings'],
        control_purpose=binding.get('controlPurpose'))
    updates={'root_control_id':binding.get('rootControlId'),
        'clarification_id':binding.get('clarificationId'),'control_stage':binding.get('controlStage'),
        'parent_question_code':state.history[-1].question_code if state.history else None,
        'root_gap_question_code':binding.get('rootGapQuestionCode'),
        'source_binding_status':binding.get('sourceBindingStatus','BOUND'),
        'presentation_question_binding':question_binding}
    if question_binding is not None:
        updates['question_purpose']=P(question_binding['questionPurpose'])
        if question_binding['semanticRouteType'] is not None:
            updates['semantic_route_type']=R(question_binding['semanticRouteType'])
    return plan.model_copy(update=updates)

def boundary_plan(result,state,diagnostics):
    from . import goals
    if not state.complete or state.fact_boundary_question_used or result['type']!='FACT_BOUNDARY_REQUIRED':
        raise CompletionTechnicalError('invalid_boundary_transition')
    identity=goals.new_goal(state,Move.FACT_CERTAINTY_CHECK,result['missingFact'],'CBT')
    return bind(build(Move.FACT_CERTAINTY_CHECK,result['missingFact'],(),state,diagnostics,source='ASSESSOR_TOOL'),
        goal_id=identity)
