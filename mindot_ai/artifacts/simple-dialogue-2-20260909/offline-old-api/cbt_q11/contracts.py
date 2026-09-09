"""Internal types; the shared public wire contracts remain untouched."""
from enum import Enum
from typing import Literal
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel
from cbt_agent import (
    AnalysisMeta, CbtApiStatus, CbtAssessmentType, CbtStartRequest, CbtTurnRequest,
    CbtTurnResponse, CONFIRMATION_REQUIRED_FIELDS, DISTORTION_DEFINITIONS,
    DistortionCode, DistortionProposal, GeneratedQuestion, QuestionAnswer,
    QuestionPurpose, ReflectionOutcomeDraft, RiskAssessment, RiskLevel,
    RiskReasonCode, SemanticRouteType,
)
Request = CbtStartRequest | CbtTurnRequest
MODEL = 'gpt-4o-mini'
# Mechanical wire aliases fit the existing ai_jobs.prompt_version VARCHAR(30).
# Exact authoring version/text/hash remain in the supplied prompt manifest.
AGENT_VERSION = 'cbt-agent-correction'
REVIEWER_VERSION = AGENT_VERSION  # Existing facade constant compatibility only.
ASSESSOR_VERSION = 'cbt-agent-assessor'
WRITER_VERSION = 'cbt-agent-writer'
ANALYSIS_CONTRACT_REVISION = 'agent-whole-flow-1'

class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid', alias_generator=to_camel, populate_by_name=True)
class Domain(str, Enum):
    FOR='evidenceFor'
    AGAINST='evidenceAgainst'
    ALTERNATIVE='alternativeViews'
    ACKNOWLEDGEMENT='acknowledgement'
class Move(str, Enum):
    OBSERVABLE_DETAIL='OBSERVABLE_DETAIL'
    DIRECT_SUPPORT='DIRECT_SUPPORT'
    COUNTEREVIDENCE='COUNTEREVIDENCE'
    ALTERNATIVE_HYPOTHESIS='ALTERNATIVE_HYPOTHESIS'
    FACT_CERTAINTY_CHECK='FACT_CERTAINTY_CHECK'
    BALANCED_SYNTHESIS='BALANCED_SYNTHESIS'
    USER_DIRECTION='USER_DIRECTION'
class SignalType(str, Enum):
    REQUEST_EXAMPLE='REQUEST_EXAMPLE'
    SKIP='SKIP'
    FEEDBACK='FEEDBACK'
    UNCLEAR='UNCLEAR'
    REPETITION_OBJECTION='REPETITION_OBJECTION'
    REQUEST_EXPLANATION='REQUEST_EXPLANATION'
    REQUEST_STOP='REQUEST_STOP'
class EffectivePlan(StrictModel):
    model_config = ConfigDict(extra='forbid',alias_generator=to_camel,populate_by_name=True,frozen=True)
    move: Move
    plan_source: str
    question_purpose: QuestionPurpose
    semantic_route_type: SemanticRouteType
    answer_source: str
    evidence_polarity: str
    focus: str
    context_question_codes: tuple[str,...]
    target_domain: Domain | None
    target_question_code: str | None = None
    target_id: str = ''
    semantic_key: str = ''
    example_mode: bool
    preface_required: bool
    verified_example_options: tuple[str,...] = ()
    presentation_mode: Literal['NORMAL','EXAMPLE','EXPLANATION'] = 'NORMAL'
    presentation_request_ids: tuple[str,...] = ()
    scope: Literal['CBT','DIRECTION','STOP','CONTROL','SAFETY'] = 'CBT'
    goal_id: str | None = None
    pending_id: str | None = None
    episode_id: str | None = None
    existing_question: str | None = None
    target_source_binding: dict[str,str] | None = None
    context_source_bindings: tuple[dict[str,str],...] = ()
    source_binding_status: Literal['BOUND','UNKNOWN_LEGACY_BINDING'] = 'BOUND'
    presentation_question_binding: dict | None = None
    control_purpose: str | None = None
    request_resolution: dict | None = None
    presentation_request_kind: str | None = None
    control_stage: str | None = None
    root_control_id: str | None = None
    clarification_id: str | None = None
    assessment_target_decision: str | None = None
    parent_question_code: str | None = None
    root_gap_question_code: str | None = None
    thought_revision: str | None = None
class CbtAgentIdempotencyError(RuntimeError):
    """Existing public 409 boundary."""
class CompletionTechnicalError(RuntimeError):
    """Existing FastAPI generic component failure -> 502 boundary."""

class ReviewRequired(CompletionTechnicalError):
    def __init__(self,reason,source_keys):
        super().__init__(reason)
        self.source_keys=tuple(source_keys)
