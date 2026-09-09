"""Three-branch assessment with current source references, never role-as-truth."""
import re
from copy import deepcopy
from .contracts import (
    AnalysisMeta, ASSESSOR_VERSION, CbtApiStatus, CbtAssessmentType, CompletionTechnicalError,
    CbtTurnResponse, CONFIRMATION_REQUIRED_FIELDS, DISTORTION_DEFINITIONS, DistortionProposal,
    DistortionCode, Domain, MODEL, ReflectionOutcomeDraft, RiskAssessment, RiskLevel,
)
from .diagnostics import canonical, sha
from .llm import call, validate_schema
from .provider_schemas import assessor_schema, response_format
from .prompts import COMPLETION_ASSESSOR_SYSTEM_PROMPT
from .state import active_atoms, atom_reference, typed_evidence, supplemental_evidence, canonical_coverage

def source_references(request,state):
    return [*typed_evidence(state),*supplemental_evidence(state)]

def schema_for(state):
    refs=[r['refId'] for r in source_references(None,state)]
    if len(refs)!=len(set(refs)):
        raise CompletionTechnicalError('duplicate_assessment_reference_identity')
    allowed=[c.value for c in DISTORTION_DEFINITIONS if c.value not in state.rejected_codes]
    return assessor_schema(refs,allowed,not state.fact_boundary_question_used)

def payload(request,state,view=None):
    from .policy import select_sources
    from . import views
    table=view['sources'] if view else select_sources(request,state)
    readable={s.get('sourceKey') for s in table}
    if not {a.source_key for a in active_atoms(state)}<=readable:
        raise CompletionTechnicalError('assessment_hidden_active_source')
    return {**views.draft(state,table),'record':{k:v for k,v in request.record.model_dump(by_alias=True,mode='json').items() if k not in ('situation','automaticThought')},
        'pendingAssessment':views.assessment(state),
        'dialogueConstraints':{'blockedTargets':sorted(state.blocked_targets),'controls':state.controls},
        'rejectedCodes':sorted(state.rejected_codes),
        'rejectedAssessmentAttempts':[deepcopy(a) for a in state.assessment_attempts
            if a.get('status')=='REJECTED' and a.get('thoughtRevision')==state.thought_revision],
        'distortionDefinitions':[{'code':code.value,**definition} for code,definition in DISTORTION_DEFINITIONS.items()],
        'boundaryState':{'questionAllowed':not state.fact_boundary_question_used,
            'gapUsed':state.fact_boundary_question_used,'thoughtRevision':state.thought_revision,
            'previousQuestion':state.pending_fact_boundary.get('question') if state.pending_fact_boundary else None}}
def exact_refs(values,allowed):
    if len(set(values))!=len(values) or not set(values)<=allowed:
        raise ValueError('unknown_inactive_or_duplicate_ref')
def valid_boundary_question(question):
    return (isinstance(question,str) and 1<=len(question)<=240 and question.count('?')==1
            and bool(re.search(r'(요|까)\?$',question)) and not any(c in question for c in '？\n\r'))
def validate(raw,request,state):
    from . import gaps, assessment_target
    if not state.complete:
        raise ValueError('incomplete_coverage')
    if gaps.unresolved(state):
        raise ValueError('gap_answer_dependency_not_reviewed')
    assessment_target.require_eligible(state)
    validate_schema(raw,schema_for(state))
    result=raw['result']; allowed={r['refId'] for r in source_references(request,state)}
    exact_refs(result['usedReferenceIds'],allowed)
    if result['type']=='FACT_BOUNDARY_REQUIRED':
        if state.fact_boundary_question_used or not valid_boundary_question(result['question']):
            raise ValueError('gap_budget_or_question')
        if any(result['question']==q['question'] for q in state.prior_questions):
            raise ValueError('repeated_boundary_question')
    else:
        if result['automaticThoughtExcerpt'] not in request.record.automatic_thought:
            raise ValueError('thought_excerpt_not_exact')
        from .validity import validate_pointer
        text=request.record.automatic_thought; excerpt=result['automaticThoughtExcerpt']
        positions=[i for i in range(len(text)) if text.startswith(excerpt,i)]
        # The terminal schema has no occurrence field. Ambiguous occurrences
        # crossing a withdrawal cannot silently select the favorable one.
        if not positions:
            raise ValueError('thought_excerpt_not_exact')
        for start in positions:
            validate_pointer(state,{'address':f'RECORD_{request.record.record_id}_automaticThought',
                'revision':sha(text)[:20],'start':start,'length':len(excerpt),'exactExcerpt':excerpt},
                'ASSESSMENT_TARGET')
        if result['type']=='DISTORTION_PRESENT' and result['matchedDistortionCode'] in state.rejected_codes:
            raise ValueError('rejected_distortion')
    return result

async def assess(request,state,diagnostics,model=None,*,view=None):
    if not state.complete:
        raise AssertionError('incomplete_cannot_assess')
    response=await call('assessor',COMPLETION_ASSESSOR_SYSTEM_PROMPT,response_format('completion_assessment',schema_for(state)),
                        payload(request,state,view),diagnostics,model,phase='ASSESSOR')
    if response.status!='USABLE':
        raise CompletionTechnicalError('assessor_'+response.status)
    try:
        result=validate(response.output,request,state)
    except (ValueError,TypeError,KeyError):
        diagnostics.invalid('assessor','result','invalid_assessment')
        raise CompletionTechnicalError('assessment_invalid') from None
    diagnostics.terminal_assessment=result
    diagnostics.emit('assessment_candidate',result=result,derivedSourceRefIds=result['usedReferenceIds'],scope='UNACCEPTED_DRAFT')
    return result
def whole_excerpts(values,limit=4000):
    text='\n'.join(dict.fromkeys(values))
    if len(text)>limit:
        raise CompletionTechnicalError('evidence_dto_capacity_no_truncation')
    return text or None
def coverage_text(state,domain):
    if state.coverage[domain].status!='EVIDENCE_FOUND':
        return None
    return whole_excerpts([atom_reference(state,a)['exactExcerpt'] for a in active_atoms(state)
                           if a.domain==domain and a.kind=='CONTENT'])
def render(request,state,result,diagnostics):
    if not state.complete or result['type']=='FACT_BOUNDARY_REQUIRED':
        raise CompletionTechnicalError('terminal_requires_valid_result')
    validate({'result':result},request,state)
    distortion=result['type']=='DISTORTION_PRESENT'
    proposals=[DistortionProposal(code=result['matchedDistortionCode'],classifier_confidence=0.5)] if distortion else []
    explanation=(DISTORTION_DEFINITIONS[DistortionCode(result['matchedDistortionCode'])]['nameKo']+' 가능성: '+result['definitionMatchReason']
                 if distortion else '현재 자료에서 명확한 왜곡을 특정하지 않았습니다. '+result['withinFactBoundaryReason'])
    material=['처음 생각에서 검토한 부분: '+result['automaticThoughtExcerpt']]
    labels={'OBSERVED_FACT':'사용자가 관찰한 내용','REPORTED_FACT':'사용자가 전한 내용','USER_INFERENCE':'사용자의 해석',
            'ALTERNATIVE_HYPOTHESIS':'사용자가 제시한 가능성','BALANCED_SYNTHESIS':'사용자가 정리한 생각'}
    material.extend(('사실 경계 보충 — ' if r.get('evidenceKind')=='GAP' else '')+
                    labels.get(r['reviewerRole'],'사용자의 명시적 부재 답변')+': '+r['exactExcerpt']
                    for r in source_references(request,state) if r['refId'] in result['usedReferenceIds'])
    material.extend(['사실과 해석의 경계: '+result.get('boundaryExplanation',result.get('withinFactBoundaryReason','')),explanation,
        '정리한 생각: '+result['calibratedThought'],'맞지 않는 부분은 수정하거나 거부할 수 있어요.'])
    ack=next((atom_reference(state,a) for a in active_atoms(state) if a.domain==Domain.ACKNOWLEDGEMENT),None)
    response=CbtTurnResponse(request_id=request.request_id,status=CbtApiStatus.CONFIRM_REQUIRED,
        assessment_type=CbtAssessmentType(result['type']),next_question=None,before_distortions=proposals,
        outcome_draft=ReflectionOutcomeDraft(evidence_for_text=coverage_text(state,Domain.FOR),
            evidence_against_text=coverage_text(state,Domain.AGAINST),alternative_thought_text=result['calibratedThought'],after_distortions=[]),
        confirmation_required_fields=list(CONFIRMATION_REQUIRED_FIELDS),
        acknowledgement_evidence=ack['exactExcerpt'] if ack else None,
        acknowledgement_source_question_code=ack['sourceQuestionCode'] if ack else None,
        proposal_message='\n'.join(material),risk=RiskAssessment(level=RiskLevel.NONE,reason_code=None),
        meta=AnalysisMeta(model=MODEL,prompt_version=ASSESSOR_VERSION))
    diagnostics.emit('terminal_render',response=response.model_dump(by_alias=True,mode='json'))
    return response
