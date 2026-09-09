"""Reference-only projections: each full USER source body has one payload location."""
from copy import deepcopy
from .diagnostics import canonical,sha
from .state import active_atoms, canonical_coverage
from . import validity, gaps, requests, assessment_target, safety

def pointers(value):
    if isinstance(value,list): return [pointers(v) for v in value]
    if isinstance(value,dict):
        return {k:pointers(v) for k,v in value.items() if not
            (k=='exactExcerpt' and 'address' in value)}
    return deepcopy(value)

def evidence(state,*,inactive=False):
    active={a.ref_id for a in active_atoms(state)}
    return [{'refId':a.ref_id,'address':state.sources[a.source_key].source_id,
        'revision':state.sources[a.source_key].revision,'sourceKey':a.source_key,
        'start':a.start,'length':a.length,'domain':a.domain.value if a.domain else None,'kind':a.kind,
        'role':a.role,'goalId':a.goal_id,'active':a.ref_id in active,'originEventId':a.origin_event_id,
        'evidenceKind':a.evidence_kind,'gapBinding':pointers(a.gap_binding),'analysisId':a.analysis_id}
        for a in state.atoms if (a.ref_id not in active if inactive else a.ref_id in active)]

def corrections(state):
    current={e['id'] for e in validity.current_events(state)}
    return [{'eventId':e['id'],'action':e['action'],'target':pointers(e['target']),
        'correctionSourceKey':e['correctionSourceKey'],'start':e['start'],'length':e['length'],
        'currentOrigin':e['id'] in current,'replacementReviewRevision':e.get('replacementAnalysisRevision'),
        'acceptanceEvidence':pointers(e.get('acceptanceEvidence')),
        'canonicalOrder':e.get('canonicalOrder'),
        'replacementRefIds':[a.ref_id for a in state.atoms if a.origin_event_id==e['id']]}
        for e in state.retraction_history]

def gap(state):
    raw=state.pending_fact_boundary
    if raw is None: return None
    candidate=raw.get('acceptedCandidate') or {}
    return pointers({**{k:deepcopy(v) for k,v in raw.items() if k not in ('answer','review','followupCorrections','acceptedCandidate')},
        'acceptedCandidate':{'candidateId':candidate.get('candidateId'),'hash':candidate.get('hash')},
        'reviewDigest':sha(canonical(raw.get('review')))})

def history_recovery(state):
    # Receipt bodies exist only in sources; the clinical history was not adopted
    # merely because this safety receipt is readable.
    return {key:{'receiptId':value['receiptId'],'attemptId':value['attemptId'],
        'source':pointers({k:v for k,v in value['source'].items() if k!='text'}),
        'originalBinding':pointers(value['originalBinding']),
        'episodeId':value['episode']['episodeId'],'responseSha256':sha(canonical(value['response'])),
        'unadoptedHistory':value['unadoptedHistory'],'recoveryRequired':value['recoveryRequired'],
        'adoptedSource':pointers(value.get('adoptedSource'))}
        for key,value in state.safety_overlays.items()}

def draft(state,table):
    visible={s.get('sourceKey') for s in table}
    return {'projectionRevision':state.projection_revision,
        'boundedFixProjectionRevision':state.bounded_fix_projection_revision,
        'semanticAnalyses':pointers(state.semantic_analyses),
        'semanticProjection':deepcopy(state.semantic_projection),
        'reviewRequiredReasons':deepcopy(state.review_required_reasons),
        'acceptedCoverage':canonical_coverage(state),'sources':deepcopy(table),
        'sourceValidity':validity.source_validity(state,table),'activeContributions':evidence(state),
        'supplementalEvidence':[r for r in evidence(state) if r['evidenceKind']=='GAP'],
        'gapAnswerBindings':pointers(gaps.gap_answer_bindings(state)),
        'inactiveContributions':[a for a in evidence(state,inactive=True) if a['sourceKey'] in visible],
        'correctionProjection':deepcopy(state.correction_projection),
        'reviewDigest':sha(canonical(state.review_projections)),
        'controls':pointers(state.controls),'priorRequests':pointers(state.request_registry),
        'priorCorrections':corrections(state),
        'requestClarifications':pointers(state.request_clarifications),
        'requestUpdateHistory':pointers(state.request_updates),
        'assessmentTarget':pointers(assessment_target.view(state)),
        'gapState':gap(state),'gapUsage':deepcopy(state.gap_usage),
        'assessmentAttempts':pointers(state.assessment_attempts),
        'historyRecovery':history_recovery(state),
        **pointers(safety.catalog(state,table)),
        'presentationReceipts':pointers(state.presentation_receipts)}

def assessment(state):
    value=state.pending_assessment
    if value is None: return None
    return {'hash':sha(canonical(value)),'status':value.get('status'),'assessmentType':value.get('assessmentType')}
