"""Mandatory source view and mechanical eligibility. Stored memory is never trimmed."""
from copy import deepcopy
from .contracts import CompletionTechnicalError, ANALYSIS_CONTRACT_REVISION
from .state import answers_to_review, source_table, canonical_coverage, pending_presentations, active_atoms, refresh
from .provider_schemas import select_tools
from .prompts import TOOL_DESCRIPTIONS
from .diagnostics import canonical, sha
from .memory import context_conversation
from . import goals, safety, requests, validity, gaps, assessment_target

MAX_REVIEW_SLOTS=8
MAX_USER_SOURCES=32

def select_sources(request,state):
    full=source_table(request,state)
    required={(s['address'],s['revision']) for s in full if s['kind']=='USER_RECORD'}
    keys={state.current_sources[q.question_code] for q in answers_to_review(state)}
    keys.update(a.source_key for a in active_atoms(state))
    keys.update(state.current_sources[q.question_code] for q in state.history[-4:] if q.answer is not None)
    keys.update(c['sourceKey'] for c in pending_presentations(state))
    keys.update(c['sourceKey'] for c in state.controls if not c['fulfilled'] and c['sourceKey'] in state.current_sources.values())
    # Correction origins/targets form a readable dependency closure. Keeping the
    # complete correction ledger here includes correction-clause chains too.
    for event in state.retraction_history:
        keys.add(event['correctionSourceKey'])
        target=event['target']; required.add((target['address'],target['revision']))
    required_episodes=(list(state.episodes.values()) if safety.review_catalog_required(state)
        else safety.active_episodes(state))
    for episode in required_episodes:
        for pointer in safety.episode_bindings(episode):
            required.add((pointer['address'],pointer['revision']))
    # Exposed targets and goals remain actionable only with readable original
    # bindings. Include the current revision beside every archived binding.
    for owner in [*state.goals.values(),*requests.question_targets(state).values()]:
        for pointer in [owner.get('targetSourceBinding'),*owner.get('contextSourceBindings',[])]:
            if pointer: required.add((pointer['address'],pointer['revision']))
    for entry in state.request_registry.values():
        pointer=entry.get('source')
        if pointer: required.add((pointer['address'],pointer['revision']))
        resolution=entry.get('targetResolution')
        if resolution:
            pointer=resolution['source']; required.add((pointer['address'],pointer['revision']))
    for update in state.request_updates:
        pointer=update['source']; required.add((pointer['address'],pointer['revision']))
    target_proof=state.assessment_target.get('recordChangeProof')
    if target_proof:
        required.add((target_proof['address'],target_proof['revision']))
    bound_addresses={a for a,_ in required}
    required.update((s['address'],s['revision']) for s in full if
        s['address'] in bound_addresses and s['kind'] in ('USER_RECORD','USER_ANSWER'))
    if state.pending_fact_boundary:
        answer_key=(state.pending_fact_boundary.get('answerProjection') or {}).get('sourceKey')
        if answer_key: keys.add(answer_key)
    chosen=[deepcopy(s) for s in full if s.get('sourceKey') in keys or (s['address'],s['revision']) in required]
    if not chosen or len(chosen)>MAX_USER_SOURCES:
        raise CompletionTechnicalError('mandatory_readable_source_capacity')
    found={(s['address'],s['revision']) for s in chosen}
    if not required<=found or not keys<={s.get('sourceKey') for s in chosen}:
        raise CompletionTechnicalError('mandatory_source_missing')
    # Without an unreviewed USER, historical resolved episodes may be optional.
    # With a USER to review their entire closure already passed the hard cap.
    for episode in state.episodes.values():
        refs=safety.episode_bindings(episode)
        extra={(p['address'],p['revision']) for p in refs}-found
        rows=[s for s in full if (s['address'],s['revision']) in extra]
        if len(rows)==len(extra) and len(chosen)+len(rows)<=MAX_USER_SOURCES:
            chosen.extend(deepcopy(rows)); found.update(extra)
    for i,source in enumerate(chosen):
        source['sourceId']=f's{i:03d}'
    return chosen

def prepare(request,state,messages,moderation):
    goals.ensure(state,request.session_id); refresh(state)
    table=select_sources(request,state)
    lookup={s['questionCode']:s for s in table if s['kind']=='USER_ANSWER'}
    slots={f'slot_{i:03d}':{'sourceId':lookup[x.question_code]['sourceId'],
        'sourceKey':lookup[x.question_code]['sourceKey'],'revision':lookup[x.question_code]['revision'],
        'analysisContractRevision':ANALYSIS_CONTRACT_REVISION,
        'currentAnalysisId':state.current_base_analyses.get(lookup[x.question_code]['sourceKey']),
        'requiredReasons':deepcopy(state.review_required_reasons.get(lookup[x.question_code]['sourceKey'],[]))}
        for i,x in enumerate(answers_to_review(state))}
    if len(slots)>MAX_REVIEW_SLOTS:
        raise CompletionTechnicalError('review_slot_capacity')
    aliases={}; presentation={}
    for control in pending_presentations(state):
        alias='p:'+control['requestId']; aliases[alias]=control['requestId']
        previous=presentation.get(alias,{})
        presentation[alias]={'requestId':control['requestId'],'kinds':sorted(set(previous.get('kinds',[])+[control['type']])),
            'targetPendingId':control['targetPendingId'],
            'sourceKey':control['sourceKey'],'start':control['start'],'length':control['length'],'state':'ACTIVE'}
    for slot,binding in slots.items():
        for index in range(2):
            presentation[slot+':signal_'+str(index)]={'slot':slot,'sourceKey':binding['sourceKey'],'signalIndex':index,
                'state':'RESERVED_NOT_AUTHORIZED'}
    atoms=[{'refId':a.ref_id,'sourceKey':a.source_key,'start':a.start,'length':a.length,
        'domain':a.domain.value if a.domain else None,'kind':a.kind,'role':a.role,'goalId':a.goal_id,
        'evidenceKind':a.evidence_kind,'gapBinding':deepcopy(a.gap_binding),'analysisId':a.analysis_id}
        for a in active_atoms(state)]
    record={k:v for k,v in request.record.model_dump(by_alias=True,mode='json').items() if k not in ('automaticThought','situation')}
    record['textSources']={s['field']:s['sourceId'] for s in table if s['kind']=='USER_RECORD'}
    pending=goals.pending_views(state)
    episodes=safety.active_episodes(state)
    episode_slots={f'episode_{i:03d}':{'episodeId':e['episodeId'],'origin':deepcopy(e['primaryTrigger'])}
        for i,e in enumerate(episodes)}
    visible={s.get('sourceKey') for s in table}
    goal_view={key:{**{k:v for k,v in goal.items() if k not in ('sourceRevisions','targetSourceKey')},
        'visibleSourceRevisions':[r for r in goal['sourceRevisions'] if r in visible]}
        for key,goal in state.goals.items()}
    data={'phase':'SELECT','record':record,'sources':table,'reviewSlots':slots,
        'conversation':context_conversation(messages,table),
        'projectionRevision':state.projection_revision,
        'boundedFixProjectionRevision':state.bounded_fix_projection_revision,
        'acceptedState':{'analysisContractRevision':state.analysis_contract_revision,
            'projectionRevision':state.projection_revision,'coverage':canonical_coverage(state),
            'contributions':atoms,'controls':[c for c in state.controls if c['sourceKey'] in visible]},
        'goals':goal_view,'pendingInteractions':pending,'questionTargets':requests.question_targets(state),
        'presentationRequests':presentation,'priorRequests':deepcopy(state.request_registry),
        'presentationReceipts':deepcopy(state.presentation_receipts),
        'sourceValidity':validity.source_validity(state,table),'episodeSlots':episode_slots,
        'pendingAssessment':state.pending_assessment,
        'stopPending':state.stop_guidance_pending,
        'budget':{'generationRemaining':3,'extraEntitlement':'UNUSED','automaticRetries':0,
            'gapUsed':state.fact_boundary_question_used},
        'rejectedCodes':sorted(state.rejected_codes),'moderation':moderation}
    from . import views
    data['semanticAnalyses']=views.pointers(state.semantic_analyses)
    data['semanticProjection']=deepcopy(state.semantic_projection)
    data['reviewRequiredReasons']=deepcopy(state.review_required_reasons)
    data['supplementalEvidence']=[r for r in views.evidence(state) if r['evidenceKind']=='GAP']
    data['gapAnswerBindings']=views.pointers(gaps.gap_answer_bindings(state))
    data['pendingAssessment']=views.assessment(state)
    data['priorCorrections']=views.corrections(state)
    data['requestClarifications']=views.pointers(state.request_clarifications)
    data['requestUpdateHistory']=views.pointers(state.request_updates)
    data['assessmentTarget']=views.pointers(assessment_target.view(state))
    data['gapState']=views.gap(state)
    data['gapUsage']=deepcopy(state.gap_usage)
    data['assessmentAttempts']=views.pointers(state.assessment_attempts)
    data['historyRecovery']=views.history_recovery(state)
    data.update(views.pointers(safety.catalog(state,table)))
    for key in ('priorRequests','presentationReceipts','episodeSlots'):
        data[key]=views.pointers(data[key])
    data['viewId']='V_'+sha(canonical(data))
    groups={scope:[k for k,v in pending.items() if v['scope']==scope] for scope in ('CBT','DIRECTION','STOP','CONTROL','SAFETY')}
    schemas=select_tools(review_slots=slots,source_ids=[s['sourceId'] for s in table],goal_ids=list(goal_view),
        pending_groups=groups,request_ids=list(presentation),episode_slots=list(episode_slots),
        question_target_ids=list(data['questionTargets']),prior_request_ids=list(data['priorRequests']),
        prior_correction_ids=[e['eventId'] for e in data['priorCorrections'] if e['correctionSourceKey'] in state.current_sources.values()],
        clarification_ids=requests.clarification_ids(state),
        episode_ids=[e['episodeId'] for e in data['actionableEpisodes']],
        stop_pending=state.stop_guidance_pending,descriptions=TOOL_DESCRIPTIONS)
    return data,schemas,aliases

def activate_aliases(state,data,existing):
    return requests.aliases(state,data)

def apply_reviews(state,args,data,diagnostics):
    from .state import accept_batch
    lookup={s['sourceId']:s for s in data['sources']}; slots={}
    for slot,binding in data['reviewSlots'].items():
        source=lookup[binding['sourceId']]
        if (state.current_sources.get(source['questionCode'])!=binding['sourceKey'] or source['revision']!=binding['revision']
                or binding['analysisContractRevision']!=ANALYSIS_CONTRACT_REVISION):
            raise CompletionTechnicalError('stale_review_slot_revision')
        slots[slot]=source['questionCode']
    accept_batch(state,args['reviews'],diagnostics,slots=slots,table=data['sources'])
    refresh(state)

def no_controls_bypassed(state,*,stop_presentation=False):
    if stop_presentation:
        return
    current=set(state.current_sources.values())
    if state.stop_guidance_pending or any(c['sourceKey'] in current and c['type']=='REQUEST_STOP' and not c['fulfilled'] for c in state.controls):
        raise CompletionTechnicalError('stop_requires_control_tool_or_resume')

def assessment_eligible(state,*,allow_pending_safety=False):
    refresh(state)
    no_controls_bypassed(state)
    if not state.complete or (safety.active_episodes(state) and not allow_pending_safety) or pending_presentations(state):
        raise CompletionTechnicalError('assessment_not_eligible')
    if not assessment_target.eligible(state):
        raise CompletionTechnicalError('assessment_target_unresolved')
    if gaps.unresolved(state):
        raise CompletionTechnicalError('gap_followup_not_reviewed')
