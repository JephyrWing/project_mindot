"""Stable server-owned goal/pending identity. No server-selected semantic objective."""
from uuid import uuid4
from copy import deepcopy
from .contracts import CompletionTechnicalError
from .diagnostics import sha
from .state import active_atoms


def scope_of(question):
    return question.get('scope') or {'S':'SAFETY','C':'STOP','U':'DIRECTION'}.get(question['kind'],'CBT')


def ensure(state,session_id):
    from .validity import binding
    questions=list(state.prior_questions)
    if state.pending_question and all(q['questionCode']!=state.pending_question['questionCode'] for q in questions):
        questions.append(state.pending_question)
    for question in questions:
        scope=scope_of(question); question['scope']=scope
        if scope=='CONTROL' or question.get('controlPurpose') in ('REQUEST_TARGET','ASSESSMENT_TARGET'):
            question['scope']='CONTROL'
            if question.get('controlPurpose')!='GAP_ANSWER_WAIT':
                question['goalId']=None
            continue
        if scope=='SAFETY':
            episode=next((e for e in state.episodes.values() if e.get('questionCode')==question['questionCode']),None)
            question['episodeId']=question.get('episodeId') or (episode['episodeId'] if episode else None)
            if not question['episodeId']:
                raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_safety_binding')
            question['goalId']=None
            question.setdefault('targetSourceBinding',binding(state.episodes[question['episodeId']]['primaryTrigger']))
            question.setdefault('contextSourceBindings',[])
            question.setdefault('sourceBindingStatus','BOUND')
            continue
        identity=question.get('goalId') or 'G_'+sha(str(session_id)+'|'+scope+'|'+question['targetId'])[:24]
        question['goalId']=identity
        state.goals.setdefault(identity,{'goalId':identity,'scope':scope,'move':question['move'],'focus':question['focus'],
            'targetDomain':question.get('targetDomain'),'targetSourceKey':None,'relatedGoalId':None,'status':'OPEN',
            'sourceRevisions':list(state.current_sources.values()),'recordRevision':state.record_revision,'questionCodes':[]})
        goal=state.goals[identity]
        if 'targetSourceBinding' not in goal:
            key=goal.get('targetSourceKey')
            if 'targetSourceBinding' in question:
                goal['targetSourceBinding']=deepcopy(question['targetSourceBinding'])
                goal['sourceBindingStatus']=question.get('sourceBindingStatus','BOUND')
            elif key in state.sources:
                source=state.sources[key]
                goal['targetSourceBinding']={'address':source.source_id,'revision':source.revision}
                goal['sourceBindingStatus']='BOUND'
            else:
                # Legacy null sourceKey did not distinguish a record target from
                # an intentional null. Preserve that uncertainty, never guess.
                goal['targetSourceBinding']=None
                goal['sourceBindingStatus']='UNKNOWN_LEGACY_BINDING'
            goal['contextSourceBindings']=deepcopy(question.get('contextSourceBindings',[]))
        question.setdefault('targetSourceBinding',deepcopy(goal['targetSourceBinding']))
        question.setdefault('contextSourceBindings',deepcopy(goal['contextSourceBindings']))
        question.setdefault('sourceBindingStatus',goal.get('sourceBindingStatus','BOUND'))
        # Presentation can know the exact question without recovering the old
        # goal's fact-source. A derived null must not upgrade that goal.
        if goal.get('sourceBindingStatus')=='UNKNOWN_LEGACY_BINDING':
            question['sourceBindingStatus']='UNKNOWN_LEGACY_BINDING'
        if question['questionCode'] not in goal['questionCodes']:
            goal['questionCodes'].append(question['questionCode'])
    if state.pending_question:
        stored=next((q for q in questions if q['questionCode']==state.pending_question['questionCode']),None)
        if stored:
            state.pending_question.update({k:deepcopy(stored.get(k)) for k in
                ('scope','goalId','episodeId','targetSourceBinding','contextSourceBindings','sourceBindingStatus')})


def new_goal(state,move,focus,scope,target_binding=None,related=None,context_bindings=()):
    from .state import target_domain
    identity='G_'+uuid4().hex
    domain=target_domain(move)
    state.goals[identity]={'goalId':identity,'scope':scope,'move':move.value,'focus':focus,
        'targetDomain':domain.value if domain else None,'targetSourceBinding':deepcopy(target_binding),
        'contextSourceBindings':deepcopy(list(context_bindings)),'sourceBindingStatus':'BOUND','relatedGoalId':related,
        'status':'OPEN','sourceRevisions':list(state.current_sources.values()),'recordRevision':state.record_revision,'questionCodes':[]}
    return identity


def update_closures(state):
    for goal in state.goals.values():
        goal['status']='OPEN'
    by_code={q['questionCode']:q for q in state.prior_questions}
    if state.pending_question:
        by_code[state.pending_question['questionCode']]=state.pending_question
    for history_index,item in enumerate(state.history):
        code=item.question_code; q=by_code.get(code,{})
        if state.review_revisions.get(code)!=state.current_sources.get(code):
            continue
        goal_id=q.get('goalId')
        goal=state.goals.get(goal_id)
        atoms=[a for a in active_atoms(state) if a.evidence_kind=='CBT' and
            state.sources[a.source_key].item.question_code==code]
        for atom in atoms:
            target=state.goals.get(atom.goal_id or goal_id)
            if target and target['move']=='FACT_CERTAINTY_CHECK':
                continue  # Gap answer validity is owned by gaps.refresh, not 4domain goals.
            if target and history_index>=target.get('revisitAfter',0) and (atom.goal_id or not target['targetDomain'] or target['targetDomain']==atom.domain.value):
                target['status']='ABSENT' if atom.kind=='EXPLICIT_NONE' else 'ANSWERED'
        if goal and goal['move']!='FACT_CERTAINTY_CHECK' and history_index>=goal.get('revisitAfter',0):
            signals=state.signals_for(code)
            if signals & {'SKIP','FEEDBACK','REPETITION_OBJECTION'}:
                goal['status']='DECLINED'
            elif goal['scope']=='DIRECTION' and not signals & {'REQUEST_EXAMPLE','REQUEST_EXPLANATION','UNCLEAR'}:
                goal['status']='ANSWERED'
    if not state.stop_guidance_pending:
        for goal in state.goals.values():
            if goal['scope']=='STOP':
                goal['status']='ANSWERED'


def pending_views(state):
    from .requests import question_targets
    return {k:v for k,v in question_targets(state).items() if v['executable']}


def apply_resume(state,pointer,table):
    from .state import resolve_pointer
    if pointer is None:
        return
    if not state.stop_guidance_pending:
        raise CompletionTechnicalError('resume_without_stop')
    source=resolve_pointer(pointer,table)
    from .validity import overlaps, validate_pointer
    try:
        validate_pointer(state,source,'RESUME',latest=True)
    except ValueError as exc:
        raise CompletionTechnicalError('resume_'+str(exc)) from None
    if any(a.source_key==source['sourceKey'] and overlaps(a.start,a.length,source['start'],source['length']) for a in active_atoms(state)):
        raise CompletionTechnicalError('resume_control_overlaps_contribution')
    projection=state.review_projections.get(source['questionCode'])
    if projection:
        projection['controlRanges'].append({'start':source['start'],'length':source['length']})
    # Semantic intent comes from the Agent; an explicit pointer is required, not a keyword.
    if source not in state.resume_receipts:
        state.resume_receipts.append(deepcopy(source))
    state.stop_guidance_pending=False
    for control in state.controls:
        if control['type']=='REQUEST_STOP':
            control['fulfilled']=True
            state.fulfilled_controls.add((control['sourceKey'],control['type'],control['target']))
