"""Revisioned sources, independent review diagnostics and request-local drafts."""
import asyncio
from copy import deepcopy
from dataclasses import dataclass, field
from time import monotonic
from .contracts import (Domain, Move, ANALYSIS_CONTRACT_REVISION,
                        CbtAgentIdempotencyError, CompletionTechnicalError)
from .diagnostics import canonical, sha
from .signals import eligible_control

@dataclass
class Coverage:
    status: str = 'NOT_EXPLORED'
    source_question_codes: list = field(default_factory=list)
    def dump(self):
        return {'status':self.status,'sourceQuestionCodes':self.source_question_codes[:]}
@dataclass(frozen=True)
class Source:
    source_id: str
    revision: str
    item: object
    @property
    def text(self):
        return self.item.answer
    @property
    def key(self):
        return self.source_id+'@'+self.revision
@dataclass(frozen=True)
class EvidenceAtom:
    ref_id: str
    source_key: str
    start: int
    length: int
    domain: Domain | None
    kind: str
    role: str | None
    goal_id: str | None = None
    analysis_revision: str = ANALYSIS_CONTRACT_REVISION
    origin_event_id: str | None = None
    evidence_kind: str = 'CBT'
    gap_binding: dict | None = None
    analysis_id: str | None = None
@dataclass
class AcceptedState:
    analysis_contract_revision: str = ANALYSIS_CONTRACT_REVISION
    projection_revision: str = ''
    bounded_fix_projection_revision: int = 2
    semantic_analyses: dict = field(default_factory=dict)
    current_base_analyses: dict = field(default_factory=dict)
    semantic_projection: list = field(default_factory=list)
    review_required_reasons: dict = field(default_factory=dict)
    coverage: dict = field(default_factory=lambda:{d:Coverage() for d in Domain})
    history: list = field(default_factory=list)
    sources: dict = field(default_factory=dict)
    record_sources: dict = field(default_factory=dict)
    current_record_sources: dict = field(default_factory=dict)
    current_sources: dict = field(default_factory=dict)
    reviews: dict = field(default_factory=dict)
    review_revisions: dict = field(default_factory=dict)
    atoms: tuple = ()
    inactive_refs: set = field(default_factory=set)
    retraction_history: list = field(default_factory=list)
    trusted_controls: dict = field(default_factory=dict)
    controls: list = field(default_factory=list)
    fulfilled_controls: set = field(default_factory=set)
    stop_guidance_pending: bool = False
    blocked_targets: set = field(default_factory=set)
    prior_questions: list = field(default_factory=list)
    fact_boundary_question_used: bool = False
    pending_fact_boundary: dict | None = None
    safety_clarification_count: int = 0
    pending_safety: dict | None = None
    rejected_codes: set = field(default_factory=set)
    pending_assessment: dict | None = None
    pending_question: dict | None = None
    record_id: int | None = None
    record_revision: str | None = None
    thought_revision: str | None = None
    gap_revisions: set = field(default_factory=set)
    safety_episodes: list = field(default_factory=list)
    episodes: dict = field(default_factory=dict)
    focus_episode_id: str | None = None
    safety_exclusions: list = field(default_factory=list)  # Legacy decode only; never current exclusions.
    goals: dict = field(default_factory=dict)
    review_projections: dict = field(default_factory=dict)
    presentation_receipts: list = field(default_factory=list)
    resume_receipts: list = field(default_factory=list)
    request_registry: dict = field(default_factory=dict)
    request_target_questions: dict = field(default_factory=dict)
    request_updates: list = field(default_factory=list)
    request_clarifications: dict = field(default_factory=dict)
    assessment_target: dict = field(default_factory=dict)
    assessment_target_confirmations: dict = field(default_factory=dict)
    gap_registry: dict = field(default_factory=dict)
    gap_usage: dict = field(default_factory=dict)
    assessment_attempts: list = field(default_factory=list)
    safety_overlays: dict = field(default_factory=dict)
    migration_issues: list = field(default_factory=list)
    correction_projection: list = field(default_factory=list)
    @property
    def complete(self):
        return all(c.status != 'NOT_EXPLORED' for c in self.coverage.values())
    def signals_for(self,code):
        from .validity import valid_span
        current=self.current_sources.get(code)
        return {c['type'] for c in self.controls if c['sourceKey']==current and not c.get('fulfilled',False) and
            valid_span(self,self.sources[current].source_id,self.sources[current].revision,
                c['start'],c['length'],purpose='REQUEST_CONTROL')}
    @property
    def latest_signals(self):
        return self.signals_for(self.history[-1].question_code) if self.history else set()
    def dump(self):
        return {'analysisContractRevision':self.analysis_contract_revision,'projectionRevision':self.projection_revision,
                'acceptedCoverage':canonical_coverage(self),'sourceTable':{
                    key:{'sourceId':s.source_id,'sourceRevision':s.revision,'questionCode':s.item.question_code,'answer':s.text}
                    for key,s in self.sources.items()},
                'evidenceAtoms':[atom_reference(self,a,include_inactive=True) for a in self.atoms],
                'inactiveRefIds':sorted(self.inactive_refs),'reviewedRevisions':dict(self.review_revisions),
                'trustedDialogueControls':{k:sorted(v) for k,v in self.trusted_controls.items()},
                'controlRequests':deepcopy(self.controls),'gapRevisions':sorted(self.gap_revisions),
                'priorQuestions':deepcopy(self.prior_questions),'blockedTargets':sorted(self.blocked_targets),
                'factBoundaryQuestionUsed':self.fact_boundary_question_used,
                'pendingFactBoundary':deepcopy(self.pending_fact_boundary),
                'pendingSafety':deepcopy(self.pending_safety),'safetyClarificationCount':self.safety_clarification_count,
                'pendingAssessment':deepcopy(self.pending_assessment),'retractions':deepcopy(self.retraction_history),
                'recordRevision':self.record_revision,'rejectedCodes':sorted(self.rejected_codes),
                'safetyEpisodes':deepcopy(self.episodes),'focusEpisodeId':self.focus_episode_id,
                'goals':deepcopy(self.goals),'reviewProjections':deepcopy(self.review_projections),
                'presentationReceipts':deepcopy(self.presentation_receipts),'resumeReceipts':deepcopy(self.resume_receipts),
                'requestRegistry':deepcopy(self.request_registry),'requestTargetQuestions':deepcopy(self.request_target_questions),
                'migrationIssues':deepcopy(self.migration_issues),'correctionProjection':deepcopy(self.correction_projection),
                'requestUpdates':deepcopy(self.request_updates),'requestClarifications':deepcopy(self.request_clarifications),
                'assessmentTarget':deepcopy(self.assessment_target),'assessmentTargetConfirmations':deepcopy(self.assessment_target_confirmations),
                'gapRegistry':deepcopy(self.gap_registry),'gapUsage':deepcopy(self.gap_usage),
                'boundedFixProjectionRevision':self.bounded_fix_projection_revision,
                'semanticAnalyses':deepcopy(self.semantic_analyses),'semanticProjection':deepcopy(self.semantic_projection),
                'currentBaseAnalyses':deepcopy(self.current_base_analyses),'reviewRequiredReasons':deepcopy(self.review_required_reasons),
                'assessmentAttempts':deepcopy(self.assessment_attempts),'safetyOverlays':deepcopy(self.safety_overlays)}

def source_domain(item):
    return {'EVIDENCE_FOR':Domain.FOR,'EVIDENCE_AGAINST':Domain.AGAINST,
            'ALTERNATIVE_VIEW':Domain.ALTERNATIVE,'BALANCED_THOUGHT':Domain.ACKNOWLEDGEMENT}.get(item.question_purpose.value)
def target_domain(move):
    return {Move.DIRECT_SUPPORT:Domain.FOR,Move.COUNTEREVIDENCE:Domain.AGAINST,
            Move.ALTERNATIVE_HYPOTHESIS:Domain.ALTERNATIVE,Move.BALANCED_SYNTHESIS:Domain.ACKNOWLEDGEMENT}.get(Move(move))
def source_revision(item):
    return sha(item.answer)[:20]
def active_atoms(state):
    return [a for a in state.atoms if a.analysis_revision==ANALYSIS_CONTRACT_REVISION and a.ref_id not in state.inactive_refs and
            gap_atom_current(state,a) and
            state.current_sources.get(state.sources[a.source_key].item.question_code)==a.source_key and
            state.review_revisions.get(state.sources[a.source_key].item.question_code)==a.source_key]

def gap_atom_current(state,atom):
    if atom.evidence_kind!='GAP': return True
    binding=atom.gap_binding; gap=state.gap_registry.get((binding or {}).get('gapId'))
    if not gap or gap.get('thoughtRevision')!=state.thought_revision: return False
    if any(binding.get(k)!=gap.get(k) for k in ('rootQuestionCode','goalId','thoughtRevision')): return False
    source=state.sources.get(atom.source_key)
    if source is None or binding.get('sourceKey')!=source.key or binding.get('questionCode')!=source.item.question_code:
        return False
    if (binding.get('address'),binding.get('revision'))!=(source.source_id,source.revision): return False
    from .gaps import answer_target
    return answer_target(state,binding.get('targetQuestionCode'),gap) is not None
def atom_reference(state,atom,include_inactive=False):
    s=state.sources[atom.source_key]
    value={'refId':atom.ref_id,'sourceId':s.source_id,'sourceRevision':s.revision,
           'sourceQuestionCode':s.item.question_code,'exactExcerpt':s.text[atom.start:atom.start+atom.length],
           'kind':atom.kind,'domain':atom.domain.value if atom.domain is not None else None,'reviewerRole':atom.role,
           'evidenceKind':atom.evidence_kind,'gapBinding':deepcopy(atom.gap_binding),'analysisId':atom.analysis_id}
    if include_inactive:
        value['active']=atom in active_atoms(state)
    return value
def typed_evidence(state):
    return [atom_reference(state,a) for a in cbt_atoms(state)]
def cbt_atoms(state):
    return [a for a in active_atoms(state) if a.evidence_kind=='CBT' and a.domain is not None]
def supplemental_atoms(state):
    return [a for a in active_atoms(state) if a.evidence_kind=='GAP']
def supplemental_evidence(state):
    return [atom_reference(state,a) for a in supplemental_atoms(state)]
def canonical_coverage(state):
    return {d.value:c.dump() for d,c in state.coverage.items()}
def safety_sources(request,state):
    return source_table(request,state)
def refresh(state):
    from . import validity, goals, requests, gaps, assessment_target, safety
    current=set(state.current_sources.values())
    validity.migrate_analyses(state)
    gaps.migrate(state)
    projected_atoms,tombstones,excluded,timeline=validity.refresh_semantics(state)
    state.correction_projection=timeline
    projected_ids={a.ref_id for a in projected_atoms}
    state.inactive_refs={a.ref_id for a in state.atoms if a.source_key not in current or
        a.analysis_revision!=ANALYSIS_CONTRACT_REVISION or
        not gap_atom_current(state,a) or
        a.ref_id not in projected_ids or
        validity.overlaps_range((a.start,a.start+a.length),
            tombstones.get(a.source_key,[])+excluded.get(a.source_key,[]))}
    state.coverage={d:Coverage() for d in Domain}
    for a in cbt_atoms(state):
        c=state.coverage[a.domain]; code=state.sources[a.source_key].item.question_code
        if code not in c.source_question_codes:
            c.source_question_codes.append(code)
        if a.kind=='CONTENT':
            c.status='EVIDENCE_FOUND'
        elif c.status=='NOT_EXPLORED':
            c.status='EXPLICITLY_NONE'
    state.blocked_targets=set()
    for q in state.prior_questions:
        code=q['questionCode']; target=q['targetId']
        if state.signals_for(code) & {'SKIP','FEEDBACK','REPETITION_OBJECTION'}:
            state.blocked_targets.add(target)
        if any(state.sources[a.source_key].item.question_code==code for a in cbt_atoms(state)):
            state.blocked_targets.add(target)
    # One refresh owns every derived projection. Hooks consume the computed
    # atoms/tombstones and must never call refresh recursively.
    safety.refresh_resolutions(state)
    requests.refresh_projection(state)
    goals.update_closures(state)
    gaps.refresh(state)
    assessment_target.refresh_projection(state)
    dependencies={'analysis':state.analysis_contract_revision,'sources':state.current_sources,
        'recordRevision':state.record_revision,'recordSources':state.current_record_sources,'thoughtRevision':state.thought_revision,
        'reviews':state.review_revisions,'reviewProjections':state.review_projections,
        'corrections':state.retraction_history,'correctionProjection':state.correction_projection,
        'semanticAnalyses':state.semantic_analyses,'semanticProjection':state.semantic_projection,
        'currentBaseAnalyses':state.current_base_analyses,'reviewRequiredReasons':state.review_required_reasons,
        'activeRefs':[a.ref_id for a in active_atoms(state)],'coverage':canonical_coverage(state),
        'goals':state.goals,'controls':state.controls,'stop':state.stop_guidance_pending,
        'resume':state.resume_receipts,'requests':state.request_registry,'requestUpdates':state.request_updates,
        'clarifications':state.request_clarifications,'deliveries':state.presentation_receipts,
        'gap':state.pending_fact_boundary,'gapRegistry':state.gap_registry,'gapUsage':state.gap_usage,
        'assessmentTarget':state.assessment_target,'episodes':state.episodes,'overlays':state.safety_overlays}
    state.projection_revision=sha(canonical(dependencies))
def answers_to_review(state):
    return [x for x in state.history if x.answer is not None and
            (state.review_revisions.get(x.question_code)!=state.current_sources[x.question_code] or
             state.review_required_reasons.get(state.current_sources[x.question_code]))]
def locate(text, span):
    excerpt=span.get('exactExcerpt')
    occurrence=span.get('occurrence')
    if not isinstance(excerpt,str) or not excerpt.strip() or len(excerpt)>96:
        raise ValueError('invalid_span')
    positions=[]; offset=0
    while True:
        found=text.find(excerpt,offset)
        if found<0:
            break
        positions.append(found); offset=found+1
    if occurrence is None:
        if len(positions)!=1:
            raise ValueError('ambiguous_or_missing_excerpt')
        occurrence=0
    if type(occurrence) is not int or not 0<=occurrence<len(positions):
        raise ValueError('invalid_occurrence')
    return positions[occurrence],len(excerpt)

def source_table(request,state):
    result=[]
    for field_name,text in [('automaticThought',request.record.automatic_thought),('situation',request.record.situation)]:
        if text:
            result.append({'sourceId':f's{len(result):03d}','address':f'RECORD_{request.record.record_id}_{field_name}',
                'revision':sha(text)[:20],'kind':'USER_RECORD','field':field_name,'text':text})
    for item in state.history:
        if item.answer is not None:
            s=state.sources[state.current_sources[item.question_code]]
            result.append({'sourceId':f's{len(result):03d}','address':s.source_id,'revision':s.revision,
                'kind':'USER_ANSWER','questionCode':item.question_code,'sourceKey':s.key,
                'question':item.question,'purpose':item.question_purpose.value,'text':s.text})
    # Replaced USER revisions remain readable when interpreting corrections/safety.
    # They are explicitly not current answer slots or current facts.
    current=set(state.current_sources.values())
    for source in state.sources.values():
        if source.key not in current:
            result.append({'sourceId':f's{len(result):03d}','address':source.source_id,'revision':source.revision,
                'kind':'ARCHIVED_USER_ANSWER','questionCode':source.item.question_code,'sourceKey':source.key,
                'question':source.item.question,'purpose':source.item.question_purpose.value,'text':source.text})
    current_records={(s['address'],s['revision']) for s in result if s['kind']=='USER_RECORD'}
    for source in state.record_sources.values():
        if (source['address'],source['revision']) not in current_records:
            result.append({**source,'sourceId':f's{len(result):03d}','kind':'ARCHIVED_USER_RECORD'})
    for overlay in state.safety_overlays.values():
        source=overlay.get('source')
        if source and not any((s['address'],s['revision'])==(source['address'],source['revision']) for s in result):
            result.append({**source,'sourceId':f's{len(result):03d}',
                'kind':'ARCHIVED_SAFETY_RECEIPT' if overlay.get('adopted') else 'UNADOPTED_SAFETY_RECEIPT'})
    return result

def resolve_pointer(pointer,table):
    source=next((s for s in table if s['sourceId']==pointer['sourceId']),None)
    if source is None:
        raise ValueError('unknown_source')
    start,length=locate(source['text'],pointer['span'])
    return {'address':source['address'],'revision':source['revision'],'start':start,'length':length,
            'exactExcerpt':source['text'][start:start+length],
            'sourceKey':source.get('sourceKey'),'questionCode':source.get('questionCode')}

def control_id(key,start,length,kind,target):
    return sha(canonical([key,start,length,kind,target]))[:24]

def pending_presentations(state):
    from .requests import active
    return active(state)

def accept_batch(state,raw,diagnostics,*,slots,table):
    from .review import accept
    return accept(state,raw,diagnostics,slots=slots,table=table)

def question_identity(item):
    # Spring stores no route; askedAt belongs to the authoritative stored question.
    return (item.question_code,item.question,item.question_purpose)
def check_history(history,previous,pending=None):
    if len(history)<len(previous):
        raise CbtAgentIdempotencyError('stale_history_prefix')
    for old,new in zip(previous,history):
        if question_identity(old)!=question_identity(new):
            raise CbtAgentIdempotencyError('question_identity_or_order_conflict')
        if old.answer!=new.answer:
            if not new.answered_at or (old.answered_at and new.answered_at<=old.answered_at):
                raise CbtAgentIdempotencyError('stale_answer_revision')
    if pending and len(history)>len(previous):
        q=history[len(previous)]
        if (q.question_code,q.question)!=(pending['questionCode'],pending['question']):
            raise CbtAgentIdempotencyError('pending_question_identity_conflict')

def legacy_observed_move(item):
    routes={'OBSERVABLE_EVENT_DETAIL':Move.OBSERVABLE_DETAIL,'DIRECT_WORD_OR_ACTION':Move.DIRECT_SUPPORT,
        'CONTRADICTORY_FACT':Move.COUNTEREVIDENCE,'EXPECTED_SIGNAL_ABSENCE':Move.COUNTEREVIDENCE,
        'ALTERNATIVE_EXPLANATION':Move.ALTERNATIVE_HYPOTHESIS,'CERTAINTY_REASSESSMENT':Move.FACT_CERTAINTY_CHECK,
        'BALANCED_CONCLUSION':Move.BALANCED_SYNTHESIS,'USER_SELECTED_DIRECTION':Move.USER_DIRECTION}
    if item.semantic_route_type:
        return routes.get(item.semantic_route_type.value,Move.USER_DIRECTION)
    return {Domain.FOR:Move.DIRECT_SUPPORT,Domain.AGAINST:Move.COUNTEREVIDENCE,
            Domain.ALTERNATIVE:Move.ALTERNATIVE_HYPOTHESIS,Domain.ACKNOWLEDGEMENT:Move.BALANCED_SYNTHESIS}.get(source_domain(item),Move.OBSERVABLE_DETAIL)
def is_safety_question(code):
    return code.startswith(('AGS_','R5S','R4S','R3S','SAFETY_CLARIFICATION_')) or (code.startswith('R2') and len(code)>3 and code[3]=='S')

def public_history_question_binding(session_id,record_id,item):
    """Question identity only: never a USER fact or the old goal's source."""
    return {'kind':'PUBLIC_HISTORY_QUESTION_ONLY','sessionId':session_id,'recordId':record_id,
        'rootQuestionCode':item.question_code,'questionCode':item.question_code,
        'questionSha256':sha(item.question),'questionPurpose':item.question_purpose.value,
        'semanticRouteType':item.semantic_route_type.value if item.semantic_route_type else None,
        'historySourceAddress':f'SESSION_{session_id}_RECORD_{record_id}_ANSWER_'+item.question_code}

def restore_public_question_binding(request,item,question,state,index,diagnostics):
    """Migrate only a missing ordinary public-question proof, never goal facts."""
    from .checkpoint import decode
    code=item.question_code
    if ('publicHistoryQuestionBinding' in question or question.get('planSource')!='HISTORY' or
            question.get('kind')!='Q' or question.get('scope')!='CBT' or
            question.get('sourceBindingStatus')!='UNKNOWN_LEGACY_BINDING' or
            question.get('targetSourceBinding') is not None or question.get('contextSourceBindings') or
            question.get('controlPurpose') or question.get('rootGapQuestionCode') or
            question.get('move')=='FACT_CERTAINTY_CHECK' or is_safety_question(code) or
            code.startswith(('AGQ_','AGE_','AGX_','AGB_','AGS_','AGC_','AGU_'))):
        return
    source=state.sources.get(state.current_sources.get(code))
    if (source is None or (question.get('questionCode'),question.get('question'),question.get('targetQuestionCode'))!=
            (code,item.question,code) or question.get('move')!=legacy_observed_move(source.item).value or
            source.item.question!=item.question or source.item.question_purpose!=item.question_purpose or
            (item.semantic_route_type is not None and source.item.semantic_route_type!=item.semantic_route_type)):
        return
    if decode(request,state.history,index,diagnostics)['status']!='PUBLIC_HISTORY':
        return
    proof=public_history_question_binding(request.session_id,request.record.record_id,source.item)
    if source.source_id!=proof['historySourceAddress']:
        return
    if any(key in question and question[key]!=proof[key] for key in ('questionPurpose','semanticRouteType')):
        return
    question.update(questionPurpose=proof['questionPurpose'],semanticRouteType=proof['semanticRouteType'],
        publicHistoryQuestionBinding=proof)

def reconstruct(request,prior,diagnostics):
    from .checkpoint import decode
    history=list(getattr(request,'question_answers',[]))
    if prior is not None:
        check_history(history,prior.history,prior.pending_question)
    state=deepcopy(prior) if prior is not None else AcceptedState()
    migrating=state.analysis_contract_revision!=ANALYSIS_CONTRACT_REVISION
    if migrating:
        state.reviews={}; state.review_revisions={}; state.pending_assessment=None
        state.review_projections={}
        state.analysis_contract_revision=ANALYSIS_CONTRACT_REVISION
    revision=sha(canonical([request.record.record_id,request.record.situation,request.record.automatic_thought]))
    if state.record_revision and state.record_revision!=revision:
        state.pending_assessment=None
        state.reviews={}; state.review_revisions={}
    state.thought_revision=sha(request.record.automatic_thought)[:20]
    if state.pending_fact_boundary and state.pending_fact_boundary.get('thoughtRevision',state.thought_revision)!=state.thought_revision:
        state.pending_fact_boundary=None
    state.fact_boundary_question_used=state.thought_revision in state.gap_revisions
    state.record_id=request.record.record_id
    state.current_record_sources={}
    for field_name,text in [('automaticThought',request.record.automatic_thought),('situation',request.record.situation)]:
        if text:
            address=f'RECORD_{request.record.record_id}_{field_name}'; revision_tag=sha(text)[:20]
            state.record_sources.setdefault(address+'@'+revision_tag,{'address':address,'revision':revision_tag,
                'field':field_name,'text':text})
            state.current_record_sources[field_name]=address+'@'+revision_tag
    state.record_revision=revision
    state.history=deepcopy(history)
    for item in state.history:
        if item.answer is None:
            continue
        s=Source(f'SESSION_{request.session_id}_RECORD_{request.record.record_id}_ANSWER_'+item.question_code,source_revision(item),item)
        previous=state.current_sources.get(item.question_code)
        if previous and previous!=s.key:
            state.inactive_refs.update(a.ref_id for a in state.atoms if a.source_key==previous)
            state.reviews.pop(item.question_code,None); state.review_revisions.pop(item.question_code,None)
            state.pending_assessment=None
        state.sources.setdefault(s.key,s)
        state.current_sources[item.question_code]=s.key
    oldplans={q['questionCode']:q for q in state.prior_questions}
    if state.pending_question:
        oldplans[state.pending_question['questionCode']]=state.pending_question
    state.prior_questions=[]
    for index,item in enumerate(state.history):
        if prior is not None and item.question_code in oldplans:
            q=deepcopy(oldplans[item.question_code])
            restore_public_question_binding(request,item,q,state,index,diagnostics)
            state.prior_questions.append(q)
            continue
        if item.question_code.startswith(('AGQ_','AGE_','AGX_','AGB_','AGS_','AGC_','AGU_')):
            if item.question_code not in oldplans:
                raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_missing_committed_memory')
            q=deepcopy(oldplans[item.question_code])
            if (q['question'],q['questionCode'])!=(item.question,item.question_code):
                raise CbtAgentIdempotencyError('opaque_question_identity_conflict')
            state.prior_questions.append(q)
            continue
        decoded=decode(request,state.history,index,diagnostics)
        if decoded['status'] in ('CURRENT_CORRUPT','UNSUPPORTED_VERSION'):
            raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE')
        marker=decoded.get('control')
        if marker:
            if marker['gapUsed'] and 'thoughtRevisionTag' not in marker:
                raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_legacy_gap_revision')
            same_thought=marker.get('thoughtRevisionTag')==int(state.thought_revision[:6],16)
            if marker['gapUsed'] and same_thought:
                state.gap_revisions.add(state.thought_revision)
                state.fact_boundary_question_used=True
            elif marker['gapUsed']:
                raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_edited_legacy_gap_origin')
            state.safety_clarification_count=marker['safetyCount']
            if index:
                state.trusted_controls.setdefault(state.history[index-1].question_code,set()).update(marker['previousSignals'])
            kind=marker['kind']; move=Move(marker['move'])
            if kind=='C':
                state.stop_guidance_pending=True
            if kind in ('E','X') and index and marker.get('previousRevisionTag')==int(sha(state.history[index-1].answer or '')[:4],16):
                previous=state.history[index-1]
                mode='REQUEST_EXAMPLE' if kind=='E' else 'REQUEST_EXPLANATION'
                state.fulfilled_controls.add((state.current_sources[previous.question_code],mode,previous.question_code))
            target_index=marker['targetIndex']
            target=state.history[target_index].question_code if target_index<index else item.question_code
            if kind=='S':
                origin=marker['safetyOrigin']
                table=source_table(request,state)
                source=next((s for s in table if s.get('questionCode')==state.history[origin].question_code),None) if origin<index else next((s for s in table if s.get('field')==('situation' if origin==1022 else 'automaticThought')),None)
                if source is None or 'originStart' not in marker:
                    raise CompletionTechnicalError('legacy_safety_origin_requires_explicit_recovery')
                a,n=marker['originStart'],marker['originLength']
                if marker['originRevisionTag']==int(source['revision'][:6],16):
                    if a+n>len(source['text']):
                        raise CompletionTechnicalError('safety_origin_span_invalid')
                    primary={'address':source['address'],'revision':source['revision'],'start':a,'length':n,
                        'exactExcerpt':source['text'][a:a+n],'sourceKey':source.get('sourceKey'),'questionCode':source.get('questionCode')}
                    state.pending_safety={'unresolved':marker['safetyKind'],'primaryTrigger':primary,
                        'episodeId':sha(canonical(primary))[:24],'clarificationUsed':True,
                        'originPosition':origin,'questionCode':item.question_code,'question':item.question}
                else:
                    raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE_edited_safety_origin')
            elif marker['safetyCount']==0:
                state.pending_safety=None
            if kind=='B':
                state.pending_fact_boundary={'questionCode':item.question_code,'question':item.question,'answer':item.answer,
                    'thoughtRevision':state.thought_revision,'resolution':'AWAITING_ANSWER'}
        else:
            kind='Q'; move=legacy_observed_move(item); target=item.question_code
            if move==Move.FACT_CERTAINTY_CHECK or is_safety_question(item.question_code):
                raise CompletionTechnicalError('CONTROL_STATE_UNRECOVERABLE')
        domain=target_domain(move)
        q={'questionCode':item.question_code,'question':item.question,'move':move.value,
           'targetId':target,'targetQuestionCode':target,'targetDomain':domain.value if domain else None,
           'kind':kind,'focus':item.question,'planSource':'HISTORY'}
        if decoded['status']=='PUBLIC_HISTORY' and kind=='Q':
            q.update(questionPurpose=item.question_purpose.value,
                semanticRouteType=item.semantic_route_type.value if item.semantic_route_type else None,
                publicHistoryQuestionBinding=public_history_question_binding(request.session_id,request.record.record_id,item))
        if item.question_code in oldplans:
            q.update(deepcopy(oldplans[item.question_code]))
        state.prior_questions.append(q)
    if state.pending_question and state.pending_question['questionCode'] not in {x.question_code for x in history}:
        state.prior_questions.append(deepcopy(state.pending_question))
    if state.pending_question and any(x.question_code==state.pending_question['questionCode'] and
            x.answer is not None for x in history):
        # Its exact committed plan has been archived above; only the active
        # pointer is cleared, never the question identity or its lineage.
        state.pending_question=None
    state.rejected_codes |= {x.code.value for x in getattr(request,'before_distortions',[]) if x.review_status.value=='REJECTED'}
    from .safety import migrate
    from .goals import ensure
    migrate(state)
    ensure(state,request.session_id)
    from . import validity, requests
    validity.migrate_events(state)
    requests.migrate(state,diagnostics)
    from . import gaps
    gaps.migrate(state)
    if migrating:
        state.atoms=(); state.controls=[]; state.safety_exclusions=[]
    refresh(state)
    diagnostics.emit('state_reconstruction',mode='LIVE' if prior is not None else 'COLD_REVIEW_REQUIRED',
                     scope='DRAFT_ONLY',draftState=state.dump())
    return state

def checkpoint(state,request,move,kind,question,**kwargs):
    from .checkpoint import encode
    return encode(state,request,move,kind,question,**kwargs)

@dataclass
class Runtime:
    bundle: object | None = None
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    failures: dict = field(default_factory=dict)
    request_fingerprints: dict = field(default_factory=dict)
    success_aliases: dict = field(default_factory=dict)
    touched: float = field(default_factory=monotonic)
    closed: bool = False
    expiry: asyncio.TimerHandle | None = None
    record_key: int | None = None
    diagnostics: object | None = None
    authoritative_history: list = field(default_factory=list)
    source_ledger: dict = field(default_factory=dict)
    owned_threads: set = field(default_factory=set)
    saver: object | None = None
    active_tasks: set = field(default_factory=set)
    generation: int = 0
    requires_recovery: bool = False
    review_hints: dict = field(default_factory=dict)
    safety_receipts: dict = field(default_factory=dict)
    safety_success_cache: dict = field(default_factory=dict)
    safety_overlay_revision: int = 0
    assessment_attempt_receipts: dict = field(default_factory=dict)
    @property
    def state(self):
        from .memory import restore_state
        return restore_state(self.bundle.accepted_json) if self.bundle else AcceptedState()

class SessionRegistry:
    """Single-process ownership. Cleanup waits for the graph to quiesce."""
    def __init__(self,ttl_seconds=600):
        self.ttl_seconds=ttl_seconds
        self._sessions={}
        self._closed_ids=set()
        self._expired_ids=set()
        self._cleanup_tasks=set()
    def _schedule_cleanup(self,runtime):
        task=asyncio.create_task(self._cleanup(runtime))
        self._cleanup_tasks.add(task)
        task.add_done_callback(self._cleanup_tasks.discard)
    async def _cleanup(self,runtime):
        # A provider may disregard cancellation; never delete its saver underneath it.
        async with runtime.lock:
            if runtime.saver:
                for thread in list(runtime.owned_threads):
                    await runtime.saver.adelete_thread(thread)
                    runtime.owned_threads.discard(thread)
            runtime.bundle=None
            runtime.source_ledger.clear()
            runtime.failures.clear()
    def _expire(self,session_id,runtime):
        if self._sessions.get(session_id) is not runtime:
            return
        if runtime.lock.locked() or runtime.active_tasks:
            runtime.expiry=asyncio.get_running_loop().call_later(1,self._expire,session_id,runtime)
            return
        runtime.closed=True; runtime.generation+=1
        self._expired_ids.add(session_id)
        self._sessions.pop(session_id,None)
        self._schedule_cleanup(runtime)
    def touch(self,session_id,runtime):
        if runtime.closed or self._sessions.get(session_id) is not runtime:
            return
        runtime.touched=monotonic()
        if runtime.expiry:
            runtime.expiry.cancel()
        runtime.expiry=asyncio.get_running_loop().call_later(self.ttl_seconds,self._expire,session_id,runtime)
    async def get(self,session_id):
        r=self._sessions.get(session_id)
        if r and monotonic()-r.touched>=self.ttl_seconds and not r.lock.locked():
            self._expire(session_id,r)
            return None
        return r
    async def get_or_create(self,session_id):
        if session_id in self._closed_ids:
            raise CompletionTechnicalError('session_was_explicitly_closed')
        r=await self.get(session_id); created=r is None
        if created:
            from langgraph.checkpoint.memory import InMemorySaver
            r=Runtime(saver=InMemorySaver(),requires_recovery=session_id in self._expired_ids)
            self._sessions[session_id]=r
        self.touch(session_id,r)
        return r,created
    async def remove(self,session_id):
        self._closed_ids.add(session_id)
        r=self._sessions.pop(session_id,None)
        if r:
            r.closed=True; r.generation+=1
            if r.expiry:
                r.expiry.cancel()
            for task in tuple(r.active_tasks):
                task.cancel()
            self._schedule_cleanup(r)
    async def drain_cleanup(self):
        # Used by subsequent lifecycle tests/shutdown, never by an active turn.
        if self._cleanup_tasks:
            await asyncio.gather(*tuple(self._cleanup_tasks))
