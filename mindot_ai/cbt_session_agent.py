"""세션 상태와 탐색 완결성을 유지하며 CBT tool을 선택하는 Q6 Agent입니다.

Spring의 JSONB가 영속 원본입니다. 살아 있는 세션에서는 전체 문답 대신 구조화된
작업 상태와 최신 문답만 메인 Agent에 전달하고, 질문 문장만 보조 LLM이 작성합니다.
세션이 없거나 만료된 TURN 요청은 Spring이 보낸 전체 이력으로 다시 수화합니다.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
from dataclasses import dataclass, field
from enum import Enum
from time import monotonic
from typing import Any, Literal
from uuid import UUID

from langchain_core.exceptions import OutputParserException
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.tools import StructuredTool
from pydantic import Field, ValidationError, model_validator

from cbt_agent import (
    CBT_MODEL,
    CBT_DEBUG_LOG_ANALYSIS,
    CONFIRMATION_REQUIRED_FIELDS,
    DISTORTION_DEFINITIONS,
    FALLBACK_QUESTION_BY_ROUTE,
    AnalysisMeta,
    AnswerDisposition,
    ApiModel,
    CbtAssessmentType,
    CbtAnalysisDraft,
    CbtApiStatus,
    CbtConfirmationDraft,
    CbtDraftValidationError,
    CbtModelOutputExhaustedError,
    CbtRequest,
    CbtResultType,
    CbtStartRequest,
    CbtTurnRequest,
    CbtTurnResponse,
    CbtQuestionPlan,
    CbtSemanticProgress,
    DistortionCode,
    DistortionProposal,
    GeneratedQuestion,
    LatestUserIntent,
    QuestionPurpose,
    QuestionAnswer,
    QuestionWordingDraft,
    ReflectionOutcomeDraft,
    RiskAssessment,
    RiskLevel,
    RiskReasonCode,
    SavedAnswerEvidence,
    SemanticRouteType,
    CONVERSATION_FEEDBACK_INTENTS,
    DIALOGUE_CONTROL_INTENTS,
    _analysis_turn_flags,
    _apply_feedback_constraints,
    _build_deterministic_fallback,
    _build_writer_payload,
    _classify_answer_disposition,
    _completion_candidates,
    _confirmation_candidate_available,
    _classify_explicit_user_intent,
    _explicit_feedback_intent,
    _deterministic_fallback_wording,
    _get_llm,
    _get_writer_llm,
    _hard_blocked_route_families,
    _is_explicit_dialogue_refusal,
    _log_fallback_usage,
    _resolved_but_irrelevant_topics,
    _safety_candidates,
    _semantic_route_definitions_payload,
    _direction_code,
    _to_response,
    _validate_analysis_draft,
    _validate_safety_decision,
)


CBT_AGENT_PROMPT_VERSION = "cbt-session-agent-quality-q6"
CBT_AGENT_SESSION_TTL_SECONDS = int(
    os.getenv("CBT_AGENT_SESSION_TTL_SECONDS", "600")
)
CBT_AGENT_MODEL_OUTPUT_ATTEMPTS = int(
    os.getenv("CBT_AGENT_MODEL_OUTPUT_ATTEMPTS", "2")
)
RECENT_QUESTION_WINDOW = 3


class EvidencePolarity(str, Enum):
    SUPPORTS_CORE_CLAIM = "SUPPORTS_CORE_CLAIM"
    CONTRADICTS_OR_LOWERS_CERTAINTY = "CONTRADICTS_OR_LOWERS_CERTAINTY"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class Q5AnswerSource(str, Enum):
    USER_OBSERVATION = "USER_OBSERVATION"
    USER_EXPERIENCE = "USER_EXPERIENCE"
    USER_JUDGMENT = "USER_JUDGMENT"
    USER_HYPOTHESIS = "USER_HYPOTHESIS"
    SAVED_FACT_SYNTHESIS = "SAVED_FACT_SYNTHESIS"


class ExplorationStatus(str, Enum):
    EVIDENCE_FOUND = "EVIDENCE_FOUND"
    EXPLICITLY_NONE = "EXPLICITLY_NONE"
    NOT_EXPLORED = "NOT_EXPLORED"


class ExplorationDimension(ApiModel):
    status: ExplorationStatus
    grounding: SavedAnswerEvidence | None

    @model_validator(mode="after")
    def validate_grounding(self) -> "ExplorationDimension":
        if self.status == ExplorationStatus.NOT_EXPLORED:
            if self.grounding is not None:
                raise ValueError("NOT_EXPLORED must not have grounding")
        elif self.grounding is None:
            raise ValueError(f"{self.status.value} requires exact grounding")
        return self


class ExplorationCoverage(ApiModel):
    evidence_for: ExplorationDimension = Field(alias="evidenceFor")
    evidence_against: ExplorationDimension = Field(alias="evidenceAgainst")
    alternative_views: ExplorationDimension = Field(alias="alternativeViews")
    acknowledgement: ExplorationDimension

    def dimensions(self) -> tuple[ExplorationDimension, ...]:
        return (
            self.evidence_for,
            self.evidence_against,
            self.alternative_views,
            self.acknowledgement,
        )

    def is_complete(self) -> bool:
        return all(
            item.status != ExplorationStatus.NOT_EXPLORED
            for item in self.dimensions()
        )


ROUTE_SEMANTIC_SIGNATURES: dict[SemanticRouteType, dict[str, str]] = {
    SemanticRouteType.OBSERVABLE_EVENT_DETAIL: {
        "targetType": "EVENT_DETAIL",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "USER_OBSERVATION",
        "informationRequestType": "OBSERVABLE_DETAIL",
    },
    SemanticRouteType.DIRECT_WORD_OR_ACTION: {
        "targetType": "CORE_CLAIM_SUPPORT",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "DIRECT_SIGNAL",
        "informationRequestType": "DIRECT_SUPPORT",
    },
    SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: {
        "targetType": "THIRD_PARTY_REACTION",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "THIRD_PARTY_SIGNAL",
        "informationRequestType": "SIGNAL_ABSENCE",
    },
    SemanticRouteType.CONTRADICTORY_FACT: {
        "targetType": "CORE_CLAIM_CERTAINTY",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "KNOWN_FACT",
        "informationRequestType": "CONTRADICTORY_EVIDENCE",
    },
    SemanticRouteType.OTHER_PEOPLE_COMPARISON: {
        "targetType": "THIRD_PARTY_REACTION",
        "comparisonType": "PEOPLE_COMPARISON",
        "causeType": "NONE",
        "evidenceSourceType": "THIRD_PARTY_SIGNAL",
        "informationRequestType": "OBSERVED_COMPARISON",
    },
    SemanticRouteType.CERTAINTY_REASSESSMENT: {
        "targetType": "USER_CERTAINTY",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "USER_JUDGMENT",
        "informationRequestType": "CERTAINTY_REASSESSMENT",
    },
    SemanticRouteType.ALTERNATIVE_EXPLANATION: {
        "targetType": "INFERRED_CAUSE",
        "comparisonType": "NONE",
        "causeType": "ALTERNATIVE_CAUSE",
        "evidenceSourceType": "USER_HYPOTHESIS",
        "informationRequestType": "ALTERNATIVE_EXPLANATION",
    },
    SemanticRouteType.BALANCED_CONCLUSION: {
        "targetType": "BALANCED_CORE_CLAIM",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "SAVED_FACTS",
        "informationRequestType": "BALANCED_SYNTHESIS",
    },
    SemanticRouteType.EMOTION_OR_TRIGGER: {
        "targetType": "USER_EMOTION",
        "comparisonType": "NONE",
        "causeType": "USER_TRIGGER",
        "evidenceSourceType": "USER_EXPERIENCE",
        "informationRequestType": "EMOTION_REFLECTION",
    },
    SemanticRouteType.USER_SELECTED_DIRECTION: {
        "targetType": "USER_CHOICE",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "USER_JUDGMENT",
        "informationRequestType": "USER_SELECTED_DIRECTION",
    },
    SemanticRouteType.OTHER_SPECIFIC: {
        "targetType": "LEGACY",
        "comparisonType": "NONE",
        "causeType": "NONE",
        "evidenceSourceType": "LEGACY",
        "informationRequestType": "LEGACY",
    },
}


BLOCKED_SIGNATURE_DIMENSIONS: dict[SemanticRouteType, tuple[str, ...]] = {
    SemanticRouteType.OBSERVABLE_EVENT_DETAIL: (
        "targetType",
        "informationRequestType",
    ),
    SemanticRouteType.DIRECT_WORD_OR_ACTION: (
        "evidenceSourceType",
        "informationRequestType",
    ),
    SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: (
        "targetType",
        "evidenceSourceType",
        "informationRequestType",
    ),
    SemanticRouteType.CONTRADICTORY_FACT: (
        "targetType",
        "evidenceSourceType",
        "informationRequestType",
    ),
    SemanticRouteType.OTHER_PEOPLE_COMPARISON: (
        "targetType",
        "comparisonType",
        "evidenceSourceType",
    ),
    SemanticRouteType.CERTAINTY_REASSESSMENT: (
        "targetType",
        "informationRequestType",
    ),
    SemanticRouteType.ALTERNATIVE_EXPLANATION: (
        "targetType",
        "causeType",
        "informationRequestType",
    ),
    SemanticRouteType.BALANCED_CONCLUSION: (
        "targetType",
        "informationRequestType",
    ),
    SemanticRouteType.EMOTION_OR_TRIGGER: (
        "targetType",
        "causeType",
        "informationRequestType",
    ),
    SemanticRouteType.USER_SELECTED_DIRECTION: (
        "targetType",
        "informationRequestType",
    ),
    SemanticRouteType.OTHER_SPECIFIC: (),
}


logger = logging.getLogger("uvicorn.error")


def _unique_evidence(
    items: list[SavedAnswerEvidence],
) -> list[SavedAnswerEvidence]:
    unique: dict[str, SavedAnswerEvidence] = {}
    for item in items:
        unique.setdefault(item.source_question_code, item)
    return list(unique.values())


class CbtSessionState(ApiModel):
    """메인 Agent가 전체 원문 대신 다음 턴까지 유지하는 작은 작업 상태입니다."""

    situation_summary: str | None = Field(
        alias="situationSummary",
        max_length=800,
    )
    emotion_summary: str | None = Field(
        alias="emotionSummary",
        max_length=800,
    )
    evidence_for: list[SavedAnswerEvidence] = Field(
        alias="evidenceFor",
        max_length=12,
        description="Exact saved-answer excerpts supporting automaticThought.",
    )
    evidence_against: list[SavedAnswerEvidence] = Field(
        alias="evidenceAgainst",
        max_length=12,
        description="Exact saved-answer excerpts reducing certainty in automaticThought.",
    )
    alternative_views: list[SavedAnswerEvidence] = Field(
        alias="alternativeViews",
        max_length=12,
        description="Exact saved-answer excerpts with an alternative or balanced view.",
    )
    asked_routes: list[SemanticRouteType] = Field(
        alias="askedRoutes",
        max_length=24,
        description="Structured semantic routes already asked.",
    )
    blocked_routes: list[SemanticRouteType] = Field(
        alias="blockedRoutes",
        max_length=16,
        description="Structured routes rejected as irrelevant or repetitive.",
    )
    acknowledgement: SavedAnswerEvidence | None = Field(
        description="Exact saved excerpt showing fact/inference or certainty reassessment."
    )
    direct_support_closed: bool = Field(
        alias="directSupportClosed",
        description="True after a saved NO_DIRECT_EVIDENCE answer closes direct support.",
    )
    exploration_coverage: ExplorationCoverage = Field(
        alias="explorationCoverage",
        description="Grounded completion state for each CBT exploration domain.",
    )

    @model_validator(mode="after")
    def validate_unique_state_items(self) -> "CbtSessionState":
        self.asked_routes = list(dict.fromkeys(self.asked_routes))
        self.blocked_routes = list(dict.fromkeys(self.blocked_routes))
        self.evidence_for = _unique_evidence(self.evidence_for)
        self.evidence_against = _unique_evidence(self.evidence_against)
        self.alternative_views = _unique_evidence(self.alternative_views)
        return self


class AgentQuestionPlan(ApiModel):
    """Q5 Agent가 의미 경로와 답변 가능한 대상을 계획하는 내부 스키마입니다."""

    question_purpose: QuestionPurpose = Field(alias="questionPurpose")
    semantic_route_type: SemanticRouteType = Field(alias="semanticRouteType")
    latest_user_intent: LatestUserIntent = Field(alias="latestUserIntent")
    question_goal: str = Field(alias="questionGoal", min_length=1, max_length=300)
    answer_target: str = Field(alias="answerTarget", min_length=1, max_length=200)
    answer_source: Q5AnswerSource = Field(alias="answerSource")
    preface_goal: str | None = Field(alias="prefaceGoal", max_length=300)
    grounding_question_codes: list[str] = Field(
        alias="groundingQuestionCodes",
        max_length=5,
    )
    avoid_topics: list[str] = Field(alias="avoidTopics", max_length=8)
    example_options: list[str] = Field(
        alias="exampleOptions",
        max_length=2,
    )

    @model_validator(mode="after")
    def validate_plan(self) -> "AgentQuestionPlan":
        self.grounding_question_codes = list(
            dict.fromkeys(self.grounding_question_codes)
        )
        self.avoid_topics = list(dict.fromkeys(self.avoid_topics))
        self.example_options = list(dict.fromkeys(self.example_options))
        if self.latest_user_intent == LatestUserIntent.REQUEST_EXAMPLE:
            if len(self.example_options) != 2:
                raise ValueError(
                    "REQUEST_EXAMPLE requires exactly two exampleOptions"
                )
            if any(not option or len(option) > 100 for option in self.example_options):
                raise ValueError(
                    "Each REQUEST_EXAMPLE option must contain 1 to 100 characters"
                )
        elif self.example_options:
            raise ValueError(
                "exampleOptions must be empty unless latestUserIntent is REQUEST_EXAMPLE"
            )
        if self.latest_user_intent in DIALOGUE_CONTROL_INTENTS:
            self.preface_goal = self.preface_goal or (
                "Briefly respond to the user's request or conversation feedback "
                "before asking the next question."
            )
        return self


class AskQuestionAction(ApiModel):
    """다음 CBT 질문의 의미를 직접 계획하는 Agent tool action입니다."""

    state: CbtSessionState
    question_plan: AgentQuestionPlan = Field(alias="questionPlan")


class RequestConfirmationAction(ApiModel):
    """사용자에게 인지 왜곡 제안 확인을 요청할 때 선택하는 tool입니다."""

    state: CbtSessionState
    confirmation: CbtConfirmationDraft


class ConsideredDistortionCandidate(ApiModel):
    code: DistortionCode
    considered_reason: str = Field(
        alias="consideredReason",
        min_length=1,
        max_length=500,
    )
    not_supported_reason: str = Field(
        alias="notSupportedReason",
        min_length=1,
        max_length=500,
    )
    grounding_question_codes: list[str] = Field(
        alias="groundingQuestionCodes",
        min_length=1,
        max_length=6,
    )

    @model_validator(mode="after")
    def unique_grounding_codes(self) -> "ConsideredDistortionCandidate":
        self.grounding_question_codes = list(
            dict.fromkeys(self.grounding_question_codes)
        )
        return self


class RequestNoClearDistortionConfirmationAction(ApiModel):
    state: CbtSessionState
    assessment_type: Literal[CbtAssessmentType.NO_CLEAR_DISTORTION] = Field(
        alias="assessmentType"
    )
    fact_summary: str = Field(alias="factSummary", min_length=1, max_length=800)
    thought_summary: str = Field(
        alias="thoughtSummary",
        min_length=1,
        max_length=800,
    )
    assessment_rationale: str = Field(
        alias="assessmentRationale",
        min_length=1,
        max_length=1_000,
    )
    calibrated_thought: str = Field(
        alias="calibratedThought",
        min_length=1,
        max_length=1_000,
    )
    considered_distortion_candidates: list[ConsideredDistortionCandidate] = Field(
        alias="consideredDistortionCandidates",
        max_length=5,
    )


class SafetyStopAction(ApiModel):
    """CBT 흐름을 중단하고 별도 안전 처리를 요청할 때 선택하는 tool입니다."""

    state: CbtSessionState
    suspected_reason: RiskReasonCode = Field(alias="suspectedReason")
    evidence: str = Field(min_length=1, max_length=300)


AgentAction = (
    AskQuestionAction
    | RequestConfirmationAction
    | RequestNoClearDistortionConfirmationAction
    | SafetyStopAction
)


def _tool_schema_only(**_: Any) -> str:
    """tool 스키마 등록용 함수이며 실제 실행은 FastAPI orchestration이 담당합니다."""

    return "handled"


ASK_QUESTION_TOOL = StructuredTool.from_function(
    func=_tool_schema_only,
    name="ask_question",
    description=(
        "Update compact CBT state and plan exactly one new question direction. "
        "Final Korean wording is handled separately."
    ),
    args_schema=AskQuestionAction,
)
REQUEST_CONFIRMATION_TOOL = StructuredTool.from_function(
    func=_tool_schema_only,
    name="request_confirmation",
    description=(
        "Propose confirmation only from meaningfully sufficient saved user evidence."
    ),
    args_schema=RequestConfirmationAction,
)
REQUEST_NO_CLEAR_DISTORTION_CONFIRMATION_TOOL = StructuredTool.from_function(
    func=_tool_schema_only,
    name="request_no_clear_distortion_confirmation",
    description=(
        "Request confirmation that supplied completed exploration does not "
        "clearly ground a specific defined cognitive distortion."
    ),
    args_schema=RequestNoClearDistortionConfirmationAction,
)
SAFETY_STOP_TOOL = StructuredTool.from_function(
    func=_tool_schema_only,
    name="safety_stop",
    description=(
        "Stop for a plausible harm-or-immediate-danger signal and include an "
        "exact excerpt from supplied user text."
    ),
    args_schema=SafetyStopAction,
)
ACTION_SCHEMAS: dict[str, type[ApiModel]] = {
    ASK_QUESTION_TOOL.name: AskQuestionAction,
    REQUEST_CONFIRMATION_TOOL.name: RequestConfirmationAction,
    REQUEST_NO_CLEAR_DISTORTION_CONFIRMATION_TOOL.name: (
        RequestNoClearDistortionConfirmationAction
    ),
    SAFETY_STOP_TOOL.name: SafetyStopAction,
}


AGENT_SYSTEM_PROMPT = """
<role>
Manage one Korean CBT reflection turn and call exactly one available tool.
Update grounded state, assess exploration, then choose safety, one completion,
or one question plan. Never write the final user-facing wording, diagnose,
invent facts, or try to prove automaticThought false.
</role>

<input>
The reflection subject is USER; mentioned people are third parties. START is
empty, CONTINUE preserves valid state, and
REHYDRATE rebuilds from fullHistory.

Available tools and semanticRouteDefinitions already reflect server
eligibility. safetyCandidates, completionAssessmentAllowed,
explorationCoverage, directSupportClosed, blocked semantic dimensions, and
exclusions are authoritative. Select only a supplied route and never create
OTHER_SPECIFIC. Safety always wins over completion.
</input>

<state>
Interpret the latest answer by meaning and answerDisposition, not by the prior
questionPurpose. Store exact excerpts only. evidenceFor supports the core
claim; evidenceAgainst lowers its certainty; alternativeViews holds another
plausible view; acknowledgement separates fact from inference or calibrates
certainty.

For each exploration domain use EVIDENCE_FOUND only with actual evidence,
EXPLICITLY_NONE only when the USER explicitly completed that exploration with
an exact saved closure, and NOT_EXPLORED otherwise. Never create evidence from
a closure. DIALOGUE_CONTROL, UNCLEAR, SKIPPED, relevance feedback, and
repetition feedback fill no domain. NO_DIRECT_EVIDENCE closes direct support
as EXPLICITLY_NONE and is never evidenceFor.

Preserve exclusions. Do not reuse a blocked target, comparison, cause,
evidence source, assumed causal link, or information request through another
route.
</state>

<decision>
Use safety_stop only with one exact matching safetyCandidate. Safety has
priority.

When completion tools are available, distinguish these outcomes from saved
answers and supplied definitions:
- request_confirmation: a specific distortion definition is concretely
  grounded by an inference, expansion, prediction, label, emotional proof,
  discounted positive, or personalization. A partly true thought may still
  contain distortion. Keep tentative language and a calibrated thought.
- request_no_clear_distortion_confirmation: every exploration domain is
  complete, but no specific definition is clearly grounded. Negative facts or
  strong emotion alone are not distortion. This does not declare the thought
  objectively true. Record only genuinely considered candidates and why each
  lacks support.

If information is insufficient or any domain is NOT_EXPLORED, the assessment
is UNCERTAIN: use ask_question, not a completion tool. Ask one unresolved item
that could change the assessment.

For a question, separate observed fact, core claim, and their inference. Ask
what the USER observed, experienced, judged, or can consider; never require a
third party's actual hidden state.

After directSupportClosed, choose contradiction, lower certainty, certainty
reassessment, an alternative view, or generalization review. For
REQUEST_EXAMPLE, supply exactly two short neutral exampleOptions; the server
will render them. Otherwise exampleOptions must be empty.
</decision>

<plan>
questionPurpose, semanticRouteType, selected route definition, and
questionGoal express one meaning. questionGoal is a Korean plan. answerTarget
names exactly one piece of information the USER can
provide. answerSource must match that target. Do not use a vague instruction
such as thinking more deeply. groundingQuestionCodes use only substantive
saved answers; avoidTopics includes answered, irrelevant, repeated, and
blocked material. Completion actions must ground every code and excerpt in
saved USER answers. Call one available tool and never return COMPLETE.
</plan>
""".strip()


Q5_WRITER_PROMPT = """
<role>
Write one natural Korean response from the supplied plan. Do not re-plan,
classify, diagnose, add evidence, or change the selected direction.
</role>

<rules>
evidencePolarity, answerTarget, and answerSource are authoritative. Ask for
exactly the one piece of USER-provided information in answerTarget. Never
require a third party's actual hidden
emotion, thought, motive, intention, or cause.

Respect groundingAnswers, latestInteraction, previousQuestions, and all
blocked semantic dimensions. Do not repeat a closed meaning, invent facts,
force optimism, or add a second question. If evidencePolarity is
CONTRADICTS_OR_LOWERS_CERTAINTY, ask only for a fact or experience that lowers
certainty; never reverse it into support for the core claim.

If prefaceRequired is false, return preface=null. Otherwise fulfill
prefaceGoal briefly without a question mark. REQUEST_EXAMPLE is rendered by
the server and is not a Writer task. The question must be one line, end with
exactly one question mark, and contain no other question mark. Use simple
natural Korean honorifics.
</rules>
""".strip()


class PendingAssessment(ApiModel):
    assessment_type: CbtAssessmentType = Field(alias="assessmentType")
    exploration_coverage: ExplorationCoverage = Field(alias="explorationCoverage")
    before_distortions: list[DistortionProposal] = Field(alias="beforeDistortions")
    outcome_draft: ReflectionOutcomeDraft = Field(alias="outcomeDraft")
    proposal_message: str = Field(alias="proposalMessage", max_length=4_000)


@dataclass
class AgentSessionRuntime:
    session_id: int
    state: CbtSessionState
    history: list[QuestionAnswer] = field(default_factory=list)
    pending_question: GeneratedQuestion | None = None
    pending_assessment: PendingAssessment | None = None
    expires_at: float = 0.0
    last_request_id: UUID | None = None
    last_request_fingerprint: str | None = None
    last_response: CbtTurnResponse | None = None
    last_diagnostics: "Q5TurnDiagnostics | None" = None
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    expiry_task: asyncio.Task[None] | None = None


@dataclass
class Q5TurnDiagnostics:
    """외부 DTO에 노출하지 않는 Q5 실행·평가 진단 정보입니다."""

    mode: str
    available_tools: list[str] = field(default_factory=list)
    available_routes: list[str] = field(default_factory=list)
    agent_attempt_count: int = 0
    agent_validation_failures: list[str] = field(default_factory=list)
    selected_tool: str | None = None
    completion_assessment_allowed: bool = False
    assessment_type: str | None = None
    exploration_coverage: dict[str, Any] = field(default_factory=dict)
    writer_called: bool = False
    writer_attempt_count: int = 0
    writer_validation_failures: list[str] = field(default_factory=list)
    render_source: str | None = None
    example_options: list[str] = field(default_factory=list)
    deterministic_preface: str | None = None
    deterministic_question: str | None = None
    deterministic_renderer_input: dict[str, Any] | None = None
    deterministic_renderer_output: dict[str, Any] | None = None
    answer_target: str | None = None
    answer_source: str | None = None
    evidence_polarity: str | None = None
    deterministic_fallback_used: bool = False
    fallback_used: bool = False
    fallback_reason: str | None = None


class CbtAgentSessionRegistry:
    """단일 FastAPI 프로세스에서 TTL 동안 CBT Agent 작업 상태를 유지합니다."""

    def __init__(self, ttl_seconds: int) -> None:
        if ttl_seconds <= 0:
            raise ValueError("CBT Agent session TTL must be positive")
        self._ttl_seconds = ttl_seconds
        self._sessions: dict[int, AgentSessionRuntime] = {}
        self._lock = asyncio.Lock()

    async def get_or_create(
        self,
        session_id: int,
    ) -> tuple[AgentSessionRuntime, bool]:
        """동시 START/TURN이 같은 session runtime을 공유하게 합니다."""

        async with self._lock:
            runtime = self._sessions.get(session_id)
            if runtime is not None and runtime.expires_at > monotonic():
                self._schedule_expiry(runtime)
                return runtime, False
            if runtime is not None:
                self._remove_locked(runtime)
            runtime = AgentSessionRuntime(
                session_id=session_id,
                state=_empty_session_state(),
            )
            self._sessions[session_id] = runtime
            self._schedule_expiry(runtime)
            return runtime, True

    async def get(self, session_id: int) -> AgentSessionRuntime | None:
        async with self._lock:
            runtime = self._sessions.get(session_id)
            if runtime is None:
                return None
            if runtime.expires_at <= monotonic():
                self._remove_locked(runtime)
                return None
            self._schedule_expiry(runtime)
            return runtime

    async def remove(self, session_id: int) -> None:
        async with self._lock:
            runtime = self._sessions.get(session_id)
            if runtime is not None:
                self._remove_locked(runtime)

    async def size(self) -> int:
        async with self._lock:
            return len(self._sessions)

    def _schedule_expiry(self, runtime: AgentSessionRuntime) -> None:
        if runtime.expiry_task is not None:
            runtime.expiry_task.cancel()
        runtime.expires_at = monotonic() + self._ttl_seconds
        runtime.expiry_task = asyncio.create_task(
            self._expire(runtime.session_id, runtime)
        )

    async def _expire(
        self,
        session_id: int,
        expected: AgentSessionRuntime,
    ) -> None:
        try:
            await asyncio.sleep(self._ttl_seconds)
            async with self._lock:
                current = self._sessions.get(session_id)
                if current is expected and current.expires_at <= monotonic():
                    self._remove_locked(current, cancel_task=False)
        except asyncio.CancelledError:
            return

    def _remove_locked(
        self,
        runtime: AgentSessionRuntime,
        *,
        cancel_task: bool = True,
    ) -> None:
        self._sessions.pop(runtime.session_id, None)
        if cancel_task and runtime.expiry_task is not None:
            runtime.expiry_task.cancel()
        runtime.expiry_task = None


_registry = CbtAgentSessionRegistry(CBT_AGENT_SESSION_TTL_SECONDS)
_agent_models: dict[tuple[bool, bool], Any] = {}
_q5_writer_model: Any | None = None


class CbtAgentIdempotencyError(RuntimeError):
    """같은 requestId가 서로 다른 payload에 재사용된 경우입니다."""


def _request_fingerprint(request: CbtRequest) -> str:
    """requestId를 제외한 CBT 판단 입력 전체의 안정 fingerprint입니다."""

    payload = request.model_dump(by_alias=True, mode="json")
    payload.pop("requestId", None)
    payload["requestType"] = (
        "TURN" if isinstance(request, CbtTurnRequest) else "START"
    )
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _cached_agent_response(
    runtime: AgentSessionRuntime,
    request: CbtRequest,
    fingerprint: str,
) -> CbtTurnResponse | None:
    """session lock 안에서만 호출하는 동일 프로세스 멱등성 검사입니다."""

    if runtime.last_request_id == request.request_id:
        if runtime.last_request_fingerprint != fingerprint:
            raise CbtAgentIdempotencyError(
                "The same requestId cannot be reused with a different CBT payload"
            )
    if (
        runtime.last_request_fingerprint == fingerprint
        and runtime.last_response is not None
    ):
        return runtime.last_response.model_copy(
            update={"request_id": request.request_id}
        )
    return None


def _remember_agent_response(
    runtime: AgentSessionRuntime,
    request: CbtRequest,
    fingerprint: str,
    response: CbtTurnResponse,
) -> None:
    runtime.last_request_id = request.request_id
    runtime.last_request_fingerprint = fingerprint
    runtime.last_response = response


def _empty_exploration_coverage() -> ExplorationCoverage:
    unexplored = lambda: ExplorationDimension(
        status=ExplorationStatus.NOT_EXPLORED,
        grounding=None,
    )
    return ExplorationCoverage(
        evidence_for=unexplored(),
        evidence_against=unexplored(),
        alternative_views=unexplored(),
        acknowledgement=unexplored(),
    )


def _empty_session_state() -> CbtSessionState:
    return CbtSessionState(
        situation_summary=None,
        emotion_summary=None,
        evidence_for=[],
        evidence_against=[],
        alternative_views=[],
        asked_routes=[],
        blocked_routes=[],
        acknowledgement=None,
        direct_support_closed=False,
        exploration_coverage=_empty_exploration_coverage(),
    )


def _get_agent_model(
    safety_allowed: bool,
    completion_assessment_allowed: bool,
) -> Any:
    eligibility = (safety_allowed, completion_assessment_allowed)
    model = _agent_models.get(eligibility)
    if model is None:
        tools = [ASK_QUESTION_TOOL]
        if safety_allowed:
            tools.append(SAFETY_STOP_TOOL)
        if completion_assessment_allowed:
            tools.extend(
                [
                    REQUEST_CONFIRMATION_TOOL,
                    REQUEST_NO_CLEAR_DISTORTION_CONFIRMATION_TOOL,
                ]
            )
        model = _get_llm().bind_tools(
            tools,
            tool_choice="required",
            strict=True,
            parallel_tool_calls=False,
        )
        _agent_models[eligibility] = model
    return model


def _question_dump(item: QuestionAnswer) -> dict[str, Any]:
    return {
        "questionCode": item.question_code,
        "questionPurpose": item.question_purpose.value,
        "semanticRouteType": (
            item.semantic_route_type.value
            if item.semantic_route_type is not None
            else None
        ),
        "question": item.question,
        "answer": item.answer,
        "answerDisposition": _classify_answer_disposition(item).value,
    }


def _exploration_domain(item: QuestionAnswer) -> str | None:
    if (
        item.question_purpose == QuestionPurpose.EVIDENCE_FOR
        or item.semantic_route_type == SemanticRouteType.DIRECT_WORD_OR_ACTION
    ):
        return "evidence_for"
    if (
        item.question_purpose == QuestionPurpose.EVIDENCE_AGAINST
        or item.semantic_route_type
        in {
            SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
            SemanticRouteType.CONTRADICTORY_FACT,
        }
    ):
        return "evidence_against"
    if (
        item.question_purpose == QuestionPurpose.ALTERNATIVE_VIEW
        or item.semantic_route_type == SemanticRouteType.ALTERNATIVE_EXPLANATION
    ):
        return "alternative_views"
    if (
        item.question_purpose == QuestionPurpose.BALANCED_THOUGHT
        or item.semantic_route_type
        in {
            SemanticRouteType.CERTAINTY_REASSESSMENT,
            SemanticRouteType.BALANCED_CONCLUSION,
        }
    ):
        return "acknowledgement"
    return None


def _is_explicit_none_exploration(item: QuestionAnswer) -> bool:
    disposition = _classify_answer_disposition(item)
    if disposition in {
        AnswerDisposition.DIALOGUE_CONTROL,
        AnswerDisposition.UNCLEAR,
        AnswerDisposition.SKIPPED,
    }:
        return False
    if item.semantic_route_type == SemanticRouteType.EXPECTED_SIGNAL_ABSENCE:
        # 이 route에서 "없었다"는 답은 탐색 실패가 아니라, 예상 신호가
        # 관찰되지 않았다는 실제 counter-evidence일 수 있습니다.
        return False
    if disposition == AnswerDisposition.NO_DIRECT_EVIDENCE:
        return True
    normalized = " ".join((item.answer or "").lower().split())
    if any(
        re.search(pattern, normalized)
        for pattern in (
            r"없(?:는|었던)?\s*(?:건|것은)\s*아니",
            r"없지(?:는|\s*)\s*않",
            r"없진\s*않",
        )
    ):
        return False
    return bool(
        re.search(
            r"(?:딱히|전혀|추가로)?\s*(?:그런\s*)?"
            r"(?:(?:건|것은|근거는|가능성은)\s*)?"
            r"(?:없어|없어요|없다|없었어|없었어요|없습니다|없네요|없음)"
            r"|떠오르지\s*않|생각나지\s*않|찾지\s*못",
            normalized,
        )
    )


def _history_exploration_candidates(
    history: list[QuestionAnswer],
) -> tuple[dict[str, list[SavedAnswerEvidence]], dict[str, SavedAnswerEvidence]]:
    found: dict[str, list[SavedAnswerEvidence]] = {
        "evidence_for": [],
        "evidence_against": [],
        "alternative_views": [],
        "acknowledgement": [],
    }
    closures: dict[str, SavedAnswerEvidence] = {}
    for item in history:
        domain = _exploration_domain(item)
        if domain is None or item.answer is None:
            continue
        disposition = _classify_answer_disposition(item)
        if disposition in {
            AnswerDisposition.DIALOGUE_CONTROL,
            AnswerDisposition.UNCLEAR,
            AnswerDisposition.SKIPPED,
        }:
            continue
        evidence = SavedAnswerEvidence(
            source_question_code=item.question_code,
            excerpt=item.answer[:300],
        )
        if _is_explicit_none_exploration(item):
            closures[domain] = evidence
        else:
            found[domain].append(evidence)
    return found, closures


def _coverage_from_state_and_history(
    state: CbtSessionState,
    history: list[QuestionAnswer],
) -> ExplorationCoverage:
    found, closures = _history_exploration_candidates(history)

    def dimension(
        domain: str,
        evidence: SavedAnswerEvidence | None,
    ) -> ExplorationDimension:
        if evidence is not None:
            return ExplorationDimension(
                status=ExplorationStatus.EVIDENCE_FOUND,
                grounding=evidence,
            )
        if found[domain]:
            return ExplorationDimension(
                status=ExplorationStatus.EVIDENCE_FOUND,
                grounding=found[domain][-1],
            )
        closure = closures.get(domain)
        if closure is not None:
            return ExplorationDimension(
                status=ExplorationStatus.EXPLICITLY_NONE,
                grounding=closure,
            )
        return ExplorationDimension(
            status=ExplorationStatus.NOT_EXPLORED,
            grounding=None,
        )

    return ExplorationCoverage(
        evidence_for=dimension(
            "evidence_for",
            state.evidence_for[-1] if state.evidence_for else None,
        ),
        evidence_against=dimension(
            "evidence_against",
            state.evidence_against[-1] if state.evidence_against else None,
        ),
        alternative_views=dimension(
            "alternative_views",
            state.alternative_views[-1] if state.alternative_views else None,
        ),
        acknowledgement=dimension("acknowledgement", state.acknowledgement),
    )


def _assessment_eligible_state(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> CbtSessionState:
    return _normalize_agent_state(runtime.state, request, runtime)


def _completion_assessment_allowed(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> bool:
    history = _history_for(request)
    if not history or _safety_candidates(request) or runtime.pending_assessment:
        return False
    if _classify_answer_disposition(history[-1]) in {
        AnswerDisposition.DIALOGUE_CONTROL,
        AnswerDisposition.UNCLEAR,
        AnswerDisposition.SKIPPED,
    }:
        return False
    return _assessment_eligible_state(request, runtime).exploration_coverage.is_complete()


def _history_direct_support_closed(history: list[QuestionAnswer]) -> bool:
    return any(
        _classify_answer_disposition(item)
        == AnswerDisposition.NO_DIRECT_EVIDENCE
        and _exploration_domain(item) == "evidence_for"
        for item in history
    )


def _effective_direct_support_closed(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> bool:
    return runtime.state.direct_support_closed or _history_direct_support_closed(
        _history_for(request)
    )


def _blocked_semantic_dimensions(
    history: list[QuestionAnswer],
) -> dict[str, list[str]]:
    blocked: dict[str, list[str]] = {}
    for item in history:
        route_type = item.semantic_route_type
        if (
            route_type is None
            or _classify_explicit_user_intent(item.answer)
            not in CONVERSATION_FEEDBACK_INTENTS
        ):
            continue
        signature = ROUTE_SEMANTIC_SIGNATURES[route_type]
        for dimension in BLOCKED_SIGNATURE_DIMENSIONS[route_type]:
            value = signature[dimension]
            blocked.setdefault(dimension, [])
            if value not in blocked[dimension]:
                blocked[dimension].append(value)
    return blocked


def _q5_blocked_routes(history: list[QuestionAnswer]) -> list[dict[str, Any]]:
    enriched: list[dict[str, Any]] = []
    for item in history:
        route_type = item.semantic_route_type
        if (
            route_type is None
            or _classify_explicit_user_intent(item.answer)
            not in CONVERSATION_FEEDBACK_INTENTS
        ):
            continue
        signature = ROUTE_SEMANTIC_SIGNATURES[route_type]
        blocked_values = {
            dimension: signature[dimension]
            for dimension in BLOCKED_SIGNATURE_DIMENSIONS[route_type]
        }
        enriched.append(
            {
                "sourceQuestionCode": item.question_code,
                "questionPurpose": item.question_purpose.value,
                "semanticRouteType": route_type.value,
                "rejectedQuestion": item.question,
                "blockedSemantics": list(blocked_values),
                "blockedSemanticValues": blocked_values,
                "semanticSignature": signature,
            }
        )
    return enriched


def _route_uses_blocked_semantics(
    route_type: SemanticRouteType,
    blocked_dimensions: dict[str, list[str]],
) -> bool:
    signature = ROUTE_SEMANTIC_SIGNATURES[route_type]
    return any(
        signature.get(dimension) in values
        for dimension, values in blocked_dimensions.items()
    )


def _available_route_definitions(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> list[dict[str, Any]]:
    history = _history_for(request)
    blocked_dimensions = _blocked_semantic_dimensions(history)
    blocked_routes = {
        item.semantic_route_type
        for item in history
        if item.semantic_route_type is not None
        and _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
    }
    blocked_families = {
        family.value for family in _hard_blocked_route_families(history)
    }
    direct_support_closed = _effective_direct_support_closed(request, runtime)
    definitions: list[dict[str, Any]] = []
    for definition in _semantic_route_definitions_payload():
        route_type = SemanticRouteType(definition["semanticRouteType"])
        if route_type == SemanticRouteType.OTHER_SPECIFIC:
            continue
        if route_type in blocked_routes:
            continue
        if definition["semanticRouteFamily"] in blocked_families:
            continue
        if direct_support_closed and route_type in {
            SemanticRouteType.DIRECT_WORD_OR_ACTION,
            SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
        }:
            continue
        if _route_uses_blocked_semantics(route_type, blocked_dimensions):
            continue
        definitions.append(
            {
                **definition,
                "semanticSignature": ROUTE_SEMANTIC_SIGNATURES[route_type],
            }
        )
    return definitions


def _evidence_polarity(plan: AgentQuestionPlan) -> EvidencePolarity:
    if (
        plan.question_purpose == QuestionPurpose.EVIDENCE_FOR
        and plan.semantic_route_type == SemanticRouteType.DIRECT_WORD_OR_ACTION
    ):
        return EvidencePolarity.SUPPORTS_CORE_CLAIM
    if (
        plan.question_purpose == QuestionPurpose.EVIDENCE_AGAINST
        and plan.semantic_route_type
        in {
            SemanticRouteType.CONTRADICTORY_FACT,
            SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
        }
    ):
        return EvidencePolarity.CONTRADICTS_OR_LOWERS_CERTAINTY
    return EvidencePolarity.NOT_APPLICABLE


ANSWER_SOURCES_BY_ROUTE: dict[
    SemanticRouteType,
    frozenset[Q5AnswerSource],
] = {
    SemanticRouteType.OBSERVABLE_EVENT_DETAIL: frozenset(
        {Q5AnswerSource.USER_OBSERVATION}
    ),
    SemanticRouteType.DIRECT_WORD_OR_ACTION: frozenset(
        {Q5AnswerSource.USER_OBSERVATION}
    ),
    SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: frozenset(
        {Q5AnswerSource.USER_OBSERVATION}
    ),
    SemanticRouteType.CONTRADICTORY_FACT: frozenset(
        {
            Q5AnswerSource.USER_OBSERVATION,
            Q5AnswerSource.USER_EXPERIENCE,
            Q5AnswerSource.SAVED_FACT_SYNTHESIS,
        }
    ),
    SemanticRouteType.OTHER_PEOPLE_COMPARISON: frozenset(
        {Q5AnswerSource.USER_OBSERVATION}
    ),
    SemanticRouteType.CERTAINTY_REASSESSMENT: frozenset(
        {Q5AnswerSource.USER_JUDGMENT}
    ),
    SemanticRouteType.ALTERNATIVE_EXPLANATION: frozenset(
        {Q5AnswerSource.USER_HYPOTHESIS}
    ),
    SemanticRouteType.BALANCED_CONCLUSION: frozenset(
        {Q5AnswerSource.SAVED_FACT_SYNTHESIS, Q5AnswerSource.USER_JUDGMENT}
    ),
    SemanticRouteType.EMOTION_OR_TRIGGER: frozenset(
        {Q5AnswerSource.USER_EXPERIENCE}
    ),
    SemanticRouteType.USER_SELECTED_DIRECTION: frozenset(
        {
            Q5AnswerSource.USER_EXPERIENCE,
            Q5AnswerSource.USER_JUDGMENT,
        }
    ),
    SemanticRouteType.OTHER_SPECIFIC: frozenset(),
}


def _build_agent_payload(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
    mode: str,
) -> dict[str, Any]:
    history = (
        request.question_answers if isinstance(request, CbtTurnRequest) else []
    )
    assessment_state = _assessment_eligible_state(request, runtime)
    completion_assessment_allowed = _completion_assessment_allowed(
        request,
        runtime,
    )
    detected_feedback = _explicit_feedback_intent(request)
    latest_answer_meaning_hint, turn_flags = _analysis_turn_flags(
        request,
        detected_feedback,
    )
    blocked_history = history if mode == "REHYDRATE" else history[-1:]
    blocked_routes = _q5_blocked_routes(blocked_history)
    blocked_semantic_dimensions = _blocked_semantic_dimensions(history)
    direct_support_closed = _effective_direct_support_closed(request, runtime)
    recent_questions = (
        []
        if mode == "REHYDRATE"
        else history[max(0, len(history) - RECENT_QUESTION_WINDOW - 1):-1]
    )
    completion_candidates, completion_coverage = _completion_candidates(history)
    return {
        "mode": mode,
        # confirmationAllowed is retained as a temporary internal compatibility
        # hint. completionAssessmentAllowed is the authoritative Q6 gate.
        "confirmationAllowed": completion_assessment_allowed,
        "completionAssessmentAllowed": completion_assessment_allowed,
        "explorationCoverage": assessment_state.exploration_coverage.model_dump(
            by_alias=True,
            mode="json",
        ),
        "pendingAssessment": (
            runtime.pending_assessment.model_dump(by_alias=True, mode="json")
            if runtime.pending_assessment is not None
            else None
        ),
        "questionSubject": "USER",
        "safetyCandidates": _safety_candidates(request),
        "latestUserIntentHint": (
            detected_feedback.value if detected_feedback is not None else None
        ),
        "latestAnswerMeaningHint": latest_answer_meaning_hint,
        "turnFlags": turn_flags,
        "directSupportClosed": direct_support_closed,
        "blockedRoutes": blocked_routes,
        "blockedSemanticDimensions": blocked_semantic_dimensions,
        "blockedRouteFamilies": sorted(
            family.value
            for family in _hard_blocked_route_families(history)
        ),
        "resolvedButIrrelevantTopics": _resolved_but_irrelevant_topics(
            history
        ),
        "semanticRouteDefinitions": _available_route_definitions(
            request,
            runtime,
        ),
        "completionCandidates": completion_candidates,
        "completionCandidateCoverage": completion_coverage,
        "record": request.record.model_dump(by_alias=True, mode="json"),
        "currentState": (
            None
            if mode == "REHYDRATE"
            else runtime.state.model_dump(by_alias=True, mode="json")
        ),
        "latestInteraction": _question_dump(history[-1]) if history else None,
        "recentQuestions": [
            _question_dump(item)
            for item in recent_questions
        ],
        "fullHistory": (
            [_question_dump(item) for item in history]
            if mode == "REHYDRATE"
            else []
        ),
        "beforeDistortions": (
            [
                item.model_dump(by_alias=True, mode="json")
                for item in request.before_distortions
            ]
            if isinstance(request, CbtTurnRequest)
            else []
        ),
        "distortionDefinitions": (
            [
                {"code": code.value, **DISTORTION_DEFINITIONS[code]}
                for code in DistortionCode
            ]
            if completion_assessment_allowed
            else []
        ),
    }


def _parse_agent_action(message: Any) -> AgentAction:
    tool_calls = getattr(message, "tool_calls", None)
    if not isinstance(tool_calls, list) or len(tool_calls) != 1:
        raise CbtDraftValidationError("CBT Agent must call exactly one tool")
    tool_call = tool_calls[0]
    name = tool_call.get("name")
    args = tool_call.get("args")
    schema = ACTION_SCHEMAS.get(name)
    if schema is None or not isinstance(args, dict):
        raise CbtDraftValidationError("CBT Agent selected an unknown tool")
    return schema.model_validate(args)


def _to_shared_question_plan(plan: AgentQuestionPlan) -> CbtQuestionPlan:
    """Agent가 고른 의미를 바꾸지 않고 공통 Writer 계약으로 변환합니다."""

    return CbtQuestionPlan(
        direction_code=_direction_code(
            plan.semantic_route_type,
            plan.question_purpose,
        ),
        question_purpose=plan.question_purpose,
        semantic_route_type=plan.semantic_route_type,
        latest_user_intent=plan.latest_user_intent,
        question_goal=plan.question_goal,
        preface_goal=plan.preface_goal,
        grounding_question_codes=plan.grounding_question_codes,
        avoid_topics=plan.avoid_topics,
        example_options=plan.example_options,
    )


def _get_q5_writer_model() -> Any:
    global _q5_writer_model
    if _q5_writer_model is None:
        _q5_writer_model = _get_writer_llm().with_structured_output(
            QuestionWordingDraft,
            method="json_schema",
            strict=True,
        )
    return _q5_writer_model


def _build_q5_writer_payload(
    request: CbtRequest,
    agent_plan: AgentQuestionPlan,
    shared_plan: CbtQuestionPlan,
    runtime: AgentSessionRuntime,
) -> dict[str, Any]:
    payload = _build_writer_payload(request, shared_plan)
    polarity = _evidence_polarity(agent_plan)
    payload.update(
        {
            "answerTarget": agent_plan.answer_target,
            "answerSource": agent_plan.answer_source.value,
            "evidencePolarity": polarity.value,
            "directSupportClosed": _effective_direct_support_closed(
                request,
                runtime,
            ),
            "blockedSemanticDimensions": _blocked_semantic_dimensions(
                _history_for(request)
            ),
        }
    )
    payload["selectedRouteDefinition"] = {
        **payload["selectedRouteDefinition"],
        "semanticSignature": ROUTE_SEMANTIC_SIGNATURES[
            shared_plan.semantic_route_type
        ],
    }
    payload["plan"] = {
        **payload["plan"],
        "answerTarget": agent_plan.answer_target,
        "answerSource": agent_plan.answer_source.value,
        "evidencePolarity": polarity.value,
    }
    return payload


def _validate_q5_wording(wording: QuestionWordingDraft) -> None:
    question = wording.question.strip()
    if "\n" in question or "\r" in question:
        raise CbtDraftValidationError("Writer question must be one line")
    if not question.endswith("?") or question.count("?") != 1:
        raise CbtDraftValidationError(
            "Writer question must end with exactly one question mark"
        )
    if wording.preface is not None and "?" in wording.preface:
        raise CbtDraftValidationError(
            "Writer preface must not contain a question mark"
        )


async def _write_q5_question(
    request: CbtRequest,
    agent_plan: AgentQuestionPlan,
    shared_plan: CbtQuestionPlan,
    runtime: AgentSessionRuntime,
    diagnostics: Q5TurnDiagnostics,
    *,
    writer_model: Any | None = None,
) -> QuestionWordingDraft:
    payload = _build_q5_writer_payload(
        request,
        agent_plan,
        shared_plan,
        runtime,
    )
    model = writer_model or _get_q5_writer_model()
    feedbacks: list[str] = []
    diagnostics.writer_called = True
    for attempt in range(CBT_AGENT_MODEL_OUTPUT_ATTEMPTS):
        diagnostics.writer_attempt_count = attempt + 1
        messages = [SystemMessage(content=Q5_WRITER_PROMPT)]
        if feedbacks:
            messages.append(
                SystemMessage(
                    content=(
                        "Previous wording failed these structural validations:\n- "
                        + "\n- ".join(feedbacks)
                        + "\nKeep the same plan and answerTarget. Return one "
                        "single-line question ending in exactly one question mark."
                    )
                )
            )
        messages.append(HumanMessage(content=json.dumps(payload, ensure_ascii=False)))
        try:
            result = await model.ainvoke(messages)
            wording = QuestionWordingDraft.model_validate(result)
            _validate_q5_wording(wording)
        except ValidationError as exc:
            feedback = ", ".join(
                f"{'.'.join(str(part) for part in error['loc'])}: {error['msg']}"
                for error in exc.errors(include_input=False)
            )[:1_000]
        except OutputParserException:
            feedback = "Writer response could not be parsed as QuestionWordingDraft"
        except CbtDraftValidationError as exc:
            feedback = str(exc)[:1_000]
        else:
            return wording.model_copy(
                update={
                    "preface": (
                        wording.preface.strip()
                        if wording.preface is not None
                        else None
                    ),
                    "question": wording.question.strip(),
                }
            )
        feedbacks.append(feedback)
        diagnostics.writer_validation_failures.append(feedback)
        if CBT_DEBUG_LOG_ANALYSIS:
            logger.info(
                "CBT Q5 Writer retry: requestId=%s sessionId=%s attempt=%s reason=%s",
                request.request_id,
                request.session_id,
                attempt + 1,
                feedback,
            )
    raise CbtModelOutputExhaustedError("question wording", feedbacks) from None


def _clean_deterministic_text(value: str) -> str:
    compact = " ".join(value.replace("?", "").replace("？", "").split())
    return compact.rstrip(" .!。！")


def _render_example_question(
    plan: AgentQuestionPlan,
    diagnostics: Q5TurnDiagnostics,
) -> QuestionWordingDraft:
    first, second = (
        _clean_deterministic_text(option) for option in plan.example_options
    )
    preface = f"예를 들면, '{first}'일 수도 있고 '{second}'일 수도 있어요."
    question = "이 중 지금 상황에 가까워 보이는 것이 있나요?"
    diagnostics.writer_called = False
    diagnostics.render_source = "DETERMINISTIC_EXAMPLE"
    diagnostics.example_options = list(plan.example_options)
    diagnostics.deterministic_preface = preface
    diagnostics.deterministic_question = question
    return QuestionWordingDraft(preface=preface, question=question)


def _render_evidence_against_after_no_direct(
    diagnostics: Q5TurnDiagnostics,
) -> QuestionWordingDraft:
    question = "그 생각과 맞지 않거나 확신을 조금 낮추는 사실이나 경험이 있었나요?"
    diagnostics.writer_called = False
    diagnostics.render_source = "DETERMINISTIC_EVIDENCE_AGAINST_AFTER_NO_DIRECT"
    diagnostics.deterministic_question = question
    return QuestionWordingDraft(preface=None, question=question)


def _render_answer_target_fallback(
    plan: AgentQuestionPlan,
    diagnostics: Q5TurnDiagnostics,
) -> QuestionWordingDraft:
    target = _clean_deterministic_text(plan.answer_target)
    question = f"{target}에 관해 말씀해 주실 수 있나요?"
    diagnostics.render_source = "DETERMINISTIC_ANSWER_TARGET_FALLBACK"
    diagnostics.deterministic_question = question
    diagnostics.deterministic_fallback_used = True
    return QuestionWordingDraft(preface=None, question=question)


def _constrain_agent_fallback(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
    draft: CbtAnalysisDraft,
    wording: QuestionWordingDraft | None,
) -> tuple[CbtAnalysisDraft, QuestionWordingDraft | None]:
    """Q4R fallback을 Q5에서 확정적으로 허용된 의미에만 제한합니다."""

    if draft.result_type != CbtResultType.QUESTION:
        return draft, wording
    assert draft.question_plan is not None
    definitions = _available_route_definitions(request, runtime)
    allowed_routes = {
        SemanticRouteType(item["semanticRouteType"])
        for item in definitions
    }
    if draft.question_plan.semantic_route_type not in allowed_routes:
        if not definitions:
            raise CbtModelOutputExhaustedError(
                "agent fallback",
                ["No unblocked semantic route remains for deterministic fallback"],
            )
        definition = definitions[0]
        route = SemanticRouteType(definition["semanticRouteType"])
        purpose = QuestionPurpose(definition["allowedQuestionPurposes"][0])
        question = FALLBACK_QUESTION_BY_ROUTE[route]
        plan = CbtQuestionPlan(
            direction_code=_direction_code(route, purpose),
            question_purpose=purpose,
            semantic_route_type=route,
            latest_user_intent=draft.question_plan.latest_user_intent,
            question_goal=f"사용자에게 물을 목표: {question}",
            preface_goal=None,
            grounding_question_codes=[],
            avoid_topics=draft.question_plan.avoid_topics,
            example_options=[],
        )
        draft = draft.model_copy(update={"question_plan": plan})
        wording = _deterministic_fallback_wording(request, plan)
    if (
        wording is not None
        and _explicit_feedback_intent(request)
        == LatestUserIntent.REQUEST_EXAMPLE
    ):
        # Agent가 유효한 예시를 내지 못했을 때 서버가 예시를 지어내지 않습니다.
        wording = QuestionWordingDraft(preface=None, question=wording.question)
    return draft, wording


def _history_for(request: CbtRequest) -> list[QuestionAnswer]:
    return request.question_answers if isinstance(request, CbtTurnRequest) else []


def _normalize_agent_state(
    state: CbtSessionState,
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> CbtSessionState:
    """서버가 확정할 수 있는 상태를 이력 기준으로 병합·정규화합니다."""

    history = _history_for(request)
    answers_by_code = {item.question_code: item for item in history}

    def valid_evidence(
        evidence: SavedAnswerEvidence,
        *,
        evidence_for: bool = False,
    ) -> bool:
        source = answers_by_code.get(evidence.source_question_code)
        if (
            source is None
            or source.answer is None
            or evidence.excerpt not in source.answer
        ):
            return False
        disposition = _classify_answer_disposition(source)
        if disposition in {
            AnswerDisposition.DIALOGUE_CONTROL,
            AnswerDisposition.UNCLEAR,
            AnswerDisposition.SKIPPED,
        }:
            return False
        if _is_explicit_none_exploration(source):
            return False
        if evidence_for and disposition == AnswerDisposition.NO_DIRECT_EVIDENCE:
            return False
        return True

    previous = runtime.state
    evidence_for = [
        item
        for item in _unique_evidence(
            [*previous.evidence_for, *state.evidence_for]
        )
        if valid_evidence(item, evidence_for=True)
    ][:12]
    evidence_against = [
        item
        for item in _unique_evidence(
            [*previous.evidence_against, *state.evidence_against]
        )
        if valid_evidence(item)
    ][:12]
    alternative_views = [
        item
        for item in _unique_evidence(
            [*previous.alternative_views, *state.alternative_views]
        )
        if valid_evidence(item)
    ][:12]

    acknowledgement = state.acknowledgement
    if acknowledgement is None or not valid_evidence(acknowledgement):
        acknowledgement = previous.acknowledgement
    if acknowledgement is not None and not valid_evidence(acknowledgement):
        acknowledgement = None

    feedback_routes = [
        item.semantic_route_type
        for item in history
        if item.semantic_route_type is not None
        if _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
    ]
    blocked_routes = list(
        dict.fromkeys(
            [
                *previous.blocked_routes,
                *feedback_routes,
            ]
        )
    )
    asked_routes = [
        item.semantic_route_type
        for item in history
        if item.semantic_route_type is not None
    ]
    direct_support_closed = (
        previous.direct_support_closed
        or state.direct_support_closed
        or _history_direct_support_closed(history)
    )

    normalized = state.model_copy(
        update={
            "situation_summary": (
                state.situation_summary or previous.situation_summary
            ),
            "emotion_summary": state.emotion_summary or previous.emotion_summary,
            "evidence_for": evidence_for,
            "evidence_against": evidence_against,
            "alternative_views": alternative_views,
            "asked_routes": list(
                dict.fromkeys(
                    [*previous.asked_routes, *asked_routes]
                )
            )[-24:],
            "blocked_routes": list(dict.fromkeys(blocked_routes))[-16:],
            "acknowledgement": acknowledgement,
            "direct_support_closed": direct_support_closed,
        }
    )
    return normalized.model_copy(
        update={
            "exploration_coverage": _coverage_from_state_and_history(
                normalized,
                history,
            )
        }
    )


def _validate_safety_action(
    action: SafetyStopAction,
    request: CbtRequest,
) -> None:
    _validate_safety_decision(
        action.suspected_reason,
        action.evidence,
        request,
    )


def _render_confirmation(
    action: RequestConfirmationAction,
    request: CbtTurnRequest,
) -> CbtConfirmationDraft:
    """구조화 근거를 외부 기존 confirmation 필드에 결정론적으로 매핑합니다."""

    coverage = action.state.exploration_coverage
    if not coverage.is_complete():
        raise CbtDraftValidationError(
            "Distortion confirmation requires complete exploration coverage"
        )
    evidence_for = coverage.evidence_for.grounding
    evidence_against = coverage.evidence_against.grounding
    alternative = coverage.alternative_views.grounding
    acknowledgement = coverage.acknowledgement.grounding
    assert evidence_for is not None
    assert evidence_against is not None
    assert alternative is not None
    assert acknowledgement is not None

    rejected_codes = {
        item.code
        for item in request.before_distortions
        if item.review_status.value == "REJECTED"
    }
    proposed_codes = {
        item.code for item in action.confirmation.before_distortions
    }
    proposed_codes.update(
        item.code for item in action.confirmation.outcome_draft.after_distortions
    )
    if not proposed_codes or proposed_codes & rejected_codes:
        raise CbtDraftValidationError(
            "Confirmation distortion must be defined and not previously rejected"
        )

    primary_distortion = action.confirmation.before_distortions[0].code
    definition = DISTORTION_DEFINITIONS[primary_distortion]
    balanced_thought = action.confirmation.outcome_draft.alternative_thought_text

    def display(item: SavedAnswerEvidence) -> str:
        excerpt = item.excerpt[:120]
        return f"[{item.source_question_code}] {excerpt}"

    proposal_message = "\n".join(
        (
            f"처음 생각을 지지한 근거: {display(evidence_for)}",
            f"확신을 낮추는 근거: {display(evidence_against)}",
            f"가능한 다른 관점: {display(alternative)}",
            f"사실과 추론·확신을 나눈 내용: {display(acknowledgement)}",
            (
                "잠정적으로 살펴볼 인지 왜곡: "
                f"{definition['nameKo']} ({primary_distortion.value})"
            ),
            f"균형 잡힌 생각: {balanced_thought}",
        )
    )
    outcome = action.confirmation.outcome_draft.model_copy(
        update={
            "evidence_for_text": evidence_for.excerpt,
            "evidence_against_text": evidence_against.excerpt,
            "alternative_thought_text": balanced_thought,
        }
    )
    return action.confirmation.model_copy(
        update={
            "outcome_draft": outcome,
            "proposal_message": proposal_message[:1_000],
        }
    )


def _validate_grounding(
    evidence: SavedAnswerEvidence,
    request: CbtTurnRequest,
    *,
    allow_explicit_none: bool = True,
) -> QuestionAnswer:
    source = next(
        (
            item
            for item in request.question_answers
            if item.question_code == evidence.source_question_code
        ),
        None,
    )
    if (
        source is None
        or source.answer is None
        or evidence.excerpt not in source.answer
    ):
        raise CbtDraftValidationError(
            "Every completion grounding must be an exact saved USER answer excerpt"
        )
    if _classify_answer_disposition(source) in {
        AnswerDisposition.DIALOGUE_CONTROL,
        AnswerDisposition.UNCLEAR,
        AnswerDisposition.SKIPPED,
    }:
        raise CbtDraftValidationError(
            "Dialogue-control, unclear, or skipped text cannot ground completion"
        )
    if not allow_explicit_none and _is_explicit_none_exploration(source):
        raise CbtDraftValidationError(
            "An explicit exploration closure cannot be used as positive evidence"
        )
    return source


def _validate_no_clear_distortion_action(
    action: RequestNoClearDistortionConfirmationAction,
    request: CbtTurnRequest,
) -> None:
    if _safety_candidates(request):
        raise CbtDraftValidationError(
            "Current safety candidates forbid NO_CLEAR_DISTORTION"
        )
    coverage = action.state.exploration_coverage
    if not coverage.is_complete():
        raise CbtDraftValidationError(
            "NO_CLEAR_DISTORTION requires complete exploration coverage"
        )
    for dimension in coverage.dimensions():
        assert dimension.grounding is not None
        source = _validate_grounding(dimension.grounding, request)
        explicit_none = _is_explicit_none_exploration(source)
        if (
            dimension.status == ExplorationStatus.EXPLICITLY_NONE
            and not explicit_none
        ) or (
            dimension.status == ExplorationStatus.EVIDENCE_FOUND
            and explicit_none
        ):
            raise CbtDraftValidationError(
                "Exploration status must match its saved grounding meaning"
            )

    rejected_codes = {
        item.code
        for item in request.before_distortions
        if item.review_status.value == "REJECTED"
    }
    history_codes = {item.question_code for item in request.question_answers}
    for candidate in action.considered_distortion_candidates:
        if candidate.code in rejected_codes:
            raise CbtDraftValidationError(
                "A rejected distortion cannot be proposed or reconsidered"
            )
        if not set(candidate.grounding_question_codes) <= history_codes:
            raise CbtDraftValidationError(
                "Considered distortion grounding codes must exist in saved history"
            )
        for code in candidate.grounding_question_codes:
            source = next(
                item for item in request.question_answers
                if item.question_code == code
            )
            if _classify_answer_disposition(source) in {
                AnswerDisposition.DIALOGUE_CONTROL,
                AnswerDisposition.UNCLEAR,
                AnswerDisposition.SKIPPED,
            }:
                raise CbtDraftValidationError(
                    "Considered distortions cannot use non-substantive answers"
                )


def _coverage_line(
    label: str,
    dimension: ExplorationDimension,
) -> str:
    assert dimension.grounding is not None
    status_label = (
        "확인된 답변"
        if dimension.status == ExplorationStatus.EVIDENCE_FOUND
        else "더 떠오르지 않는다고 한 답변"
    )
    excerpt = dimension.grounding.excerpt[:120]
    return (
        f"{label} ({status_label}): "
        f"[{dimension.grounding.source_question_code}] {excerpt}"
    )


def _render_no_clear_distortion_response(
    action: RequestNoClearDistortionConfirmationAction,
    request: CbtTurnRequest,
    diagnostics: Q5TurnDiagnostics,
) -> CbtTurnResponse:
    """Writer 없이 저장된 근거와 보정 생각을 Q6 확인 응답으로 매핑합니다."""

    coverage = action.state.exploration_coverage
    acknowledgement = coverage.acknowledgement.grounding
    assert acknowledgement is not None
    renderer_input = {
        "assessmentType": action.assessment_type.value,
        "explorationCoverage": coverage.model_dump(by_alias=True, mode="json"),
        "factSummary": action.fact_summary,
        "thoughtSummary": action.thought_summary,
        "assessmentRationale": action.assessment_rationale,
        "calibratedThought": action.calibrated_thought,
        "consideredDistortionCandidates": [
            item.model_dump(by_alias=True, mode="json")
            for item in action.considered_distortion_candidates
        ],
    }
    proposal_message = "\n".join(
        (
            _coverage_line("처음 생각을 지지하는 방향", coverage.evidence_for),
            _coverage_line("확신을 낮추는 방향", coverage.evidence_against),
            _coverage_line("다른 관점", coverage.alternative_views),
            _coverage_line("사실과 생각을 나눈 내용", coverage.acknowledgement),
            f"기록된 상황: {request.record.situation or '구체적 상황 미입력'}",
            f"처음 떠오른 생각: {request.record.automatic_thought}",
            (
                "현재 저장된 답변과 제공된 정의만으로는 특정 인지 왜곡이 "
                "뚜렷하게 근거화되지는 않았습니다. 이 평가는 현실의 진위를 "
                "확정하는 판단이 아닙니다."
            ),
            f"조정해 본 생각: {action.calibrated_thought}",
            "지금까지 확인한 내용을 이대로 정리해도 괜찮을까요?",
        )
    )[:4_000]
    outcome = ReflectionOutcomeDraft(
        evidence_for_text=coverage.evidence_for.grounding.excerpt,
        evidence_against_text=coverage.evidence_against.grounding.excerpt,
        alternative_thought_text=action.calibrated_thought,
        after_distortions=[],
    )
    response = CbtTurnResponse(
        request_id=request.request_id,
        status=CbtApiStatus.CONFIRM_REQUIRED,
        assessment_type=CbtAssessmentType.NO_CLEAR_DISTORTION,
        next_question=None,
        before_distortions=[],
        outcome_draft=outcome,
        confirmation_required_fields=list(CONFIRMATION_REQUIRED_FIELDS),
        acknowledgement_evidence=acknowledgement.excerpt,
        acknowledgement_source_question_code=(
            acknowledgement.source_question_code
        ),
        proposal_message=proposal_message,
        risk=RiskAssessment(level=RiskLevel.NONE, reason_code=None),
        meta=AnalysisMeta(
            model=CBT_MODEL,
            prompt_version=CBT_AGENT_PROMPT_VERSION,
        ),
    )
    diagnostics.deterministic_renderer_input = renderer_input
    diagnostics.deterministic_renderer_output = response.model_dump(
        by_alias=True,
        mode="json",
    )
    return response


def _validate_agent_action(
    action: AgentAction,
    request: CbtRequest,
    *,
    available_routes: set[SemanticRouteType],
    completion_assessment_allowed: bool,
) -> CbtAnalysisDraft | None:
    def representative(
        items: list[SavedAnswerEvidence],
    ) -> SavedAnswerEvidence | None:
        return items[-1] if items else None

    progress = CbtSemanticProgress(
        evidence_for=representative(action.state.evidence_for),
        evidence_against=representative(action.state.evidence_against),
        alternative_view=representative(action.state.alternative_views),
        acknowledgement=action.state.acknowledgement,
    )

    none_risk = RiskAssessment(level=RiskLevel.NONE, reason_code=None)
    if isinstance(action, AskQuestionAction):
        route_type = action.question_plan.semantic_route_type
        if route_type not in available_routes:
            raise CbtDraftValidationError(
                "Agent questionPlan.semanticRouteType is unavailable for this turn"
            )
        if action.question_plan.semantic_route_type in action.state.blocked_routes:
            raise CbtDraftValidationError(
                "Agent questionPlan.semanticRouteType reuses a blocked state route"
            )
        if (
            action.state.direct_support_closed
            and route_type
            in {
                SemanticRouteType.DIRECT_WORD_OR_ACTION,
                SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
            }
        ):
            raise CbtDraftValidationError(
                "directSupportClosed forbids every direct-support route"
            )
        if action.question_plan.answer_source not in ANSWER_SOURCES_BY_ROUTE[
            route_type
        ]:
            raise CbtDraftValidationError(
                "answerSource is incompatible with semanticRouteType"
            )
        draft = CbtAnalysisDraft(
            result_type=CbtResultType.QUESTION,
            semantic_progress=progress,
            question_plan=_to_shared_question_plan(action.question_plan),
            confirmation=None,
            risk=none_risk,
            safety_evidence=None,
        )
    elif isinstance(action, RequestConfirmationAction):
        if not completion_assessment_allowed:
            raise CbtDraftValidationError(
                "Distortion confirmation is not eligible for this turn"
            )
        if not isinstance(request, CbtTurnRequest):
            raise CbtDraftValidationError(
                "Agent confirmation requires saved user answers"
            )
        coverage = action.state.exploration_coverage
        if not coverage.is_complete():
            raise CbtDraftValidationError(
                "Distortion confirmation requires complete exploration coverage"
            )
        progress = CbtSemanticProgress(
            evidence_for=coverage.evidence_for.grounding,
            evidence_against=coverage.evidence_against.grounding,
            alternative_view=coverage.alternative_views.grounding,
            acknowledgement=coverage.acknowledgement.grounding,
        )
        action.confirmation = _render_confirmation(action, request)
        draft = CbtAnalysisDraft(
            result_type=CbtResultType.CONFIRMATION_REQUIRED,
            semantic_progress=progress,
            question_plan=None,
            confirmation=action.confirmation,
            risk=none_risk,
            safety_evidence=None,
        )
    elif isinstance(action, RequestNoClearDistortionConfirmationAction):
        if not completion_assessment_allowed:
            raise CbtDraftValidationError(
                "NO_CLEAR_DISTORTION confirmation is not eligible for this turn"
            )
        if not isinstance(request, CbtTurnRequest):
            raise CbtDraftValidationError(
                "NO_CLEAR_DISTORTION requires saved user answers"
            )
        _validate_no_clear_distortion_action(action, request)
        return None
    else:
        _validate_safety_action(action, request)
        candidate_level = (
            RiskLevel.CRISIS
            if action.suspected_reason == RiskReasonCode.IMMEDIATE_DANGER
            else RiskLevel.REVIEW
        )
        draft = CbtAnalysisDraft(
            result_type=CbtResultType.SAFETY_STOP,
            semantic_progress=progress,
            question_plan=None,
            confirmation=None,
            risk=RiskAssessment(
                level=candidate_level,
                reason_code=action.suspected_reason,
            ),
            safety_evidence=action.evidence,
        )
    _validate_analysis_draft(draft, request)
    return draft


async def _select_agent_action(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
    mode: str,
    *,
    agent_model: Any | None = None,
    diagnostics: Q5TurnDiagnostics,
) -> tuple[AgentAction, CbtAnalysisDraft | None]:
    payload = _build_agent_payload(request, runtime, mode)
    completion_assessment_allowed = bool(
        payload["completionAssessmentAllowed"]
    )
    safety_allowed = bool(payload["safetyCandidates"])
    available_routes = {
        SemanticRouteType(item["semanticRouteType"])
        for item in payload["semanticRouteDefinitions"]
    }
    diagnostics.available_tools = [ASK_QUESTION_TOOL.name]
    if safety_allowed:
        diagnostics.available_tools.append(SAFETY_STOP_TOOL.name)
    if completion_assessment_allowed:
        diagnostics.available_tools.extend(
            [
                REQUEST_CONFIRMATION_TOOL.name,
                REQUEST_NO_CLEAR_DISTORTION_CONFIRMATION_TOOL.name,
            ]
        )
    diagnostics.completion_assessment_allowed = completion_assessment_allowed
    diagnostics.exploration_coverage = dict(payload["explorationCoverage"])
    diagnostics.available_routes = sorted(route.value for route in available_routes)
    model = agent_model or _get_agent_model(
        safety_allowed,
        completion_assessment_allowed,
    )
    feedbacks: list[str] = []

    for attempt in range(CBT_AGENT_MODEL_OUTPUT_ATTEMPTS):
        diagnostics.agent_attempt_count = attempt + 1
        messages = [SystemMessage(content=AGENT_SYSTEM_PROMPT)]
        if feedbacks:
            messages.append(
                SystemMessage(
                    content=(
                        "Previous tool calls failed these validations:\n- "
                        + "\n- ".join(feedbacks)
                        + "\nRe-read the same payload, preserve valid session "
                        "state, and call exactly one corrected tool."
                    )
                )
            )
        messages.append(
            HumanMessage(content=json.dumps(payload, ensure_ascii=False))
        )
        try:
            message = await model.ainvoke(messages)
            action = _parse_agent_action(message)
            action.state = _normalize_agent_state(
                action.state,
                request,
                runtime,
            )
            if safety_allowed and not isinstance(action, SafetyStopAction):
                raise CbtDraftValidationError(
                    "A matching current-user safetyCandidate requires safety_stop"
                )
            if (
                isinstance(
                    action,
                    (
                        RequestConfirmationAction,
                        RequestNoClearDistortionConfirmationAction,
                    ),
                )
                and not completion_assessment_allowed
            ):
                raise CbtDraftValidationError(
                    "Completion assessment tools are unavailable for this turn"
                )
            explicit_intent = _explicit_feedback_intent(request)
            if isinstance(action, AskQuestionAction) and explicit_intent is not None:
                if (
                    explicit_intent == LatestUserIntent.REQUEST_EXAMPLE
                    and len(action.question_plan.example_options) != 2
                ):
                    raise CbtDraftValidationError(
                        "REQUEST_EXAMPLE requires exactly two exampleOptions"
                    )
                action.question_plan = action.question_plan.model_copy(
                    update={"latest_user_intent": explicit_intent}
                )
            history = _history_for(request)
            if (
                isinstance(action, AskQuestionAction)
                and history
                and _is_explicit_dialogue_refusal(history[-1].answer)
            ):
                action.question_plan = action.question_plan.model_copy(
                    update={
                        "latest_user_intent": LatestUserIntent.UNCLEAR,
                        "preface_goal": (
                            action.question_plan.preface_goal
                            or "Briefly acknowledge the refusal and offer a simpler "
                            "or different direction."
                        ),
                    }
                )
            draft = _validate_agent_action(
                action,
                request,
                available_routes=available_routes,
                completion_assessment_allowed=completion_assessment_allowed,
            )
        except ValidationError as exc:
            feedback = ", ".join(
                f"{'.'.join(str(part) for part in error['loc'])}: {error['msg']}"
                for error in exc.errors(include_input=False)
            )[:1_000]
        except CbtDraftValidationError as exc:
            feedback = str(exc)[:1_000]
        else:
            diagnostics.selected_tool = (
                ASK_QUESTION_TOOL.name
                if isinstance(action, AskQuestionAction)
                else REQUEST_CONFIRMATION_TOOL.name
                if isinstance(action, RequestConfirmationAction)
                else REQUEST_NO_CLEAR_DISTORTION_CONFIRMATION_TOOL.name
                if isinstance(action, RequestNoClearDistortionConfirmationAction)
                else SAFETY_STOP_TOOL.name
            )
            if isinstance(action, RequestConfirmationAction):
                diagnostics.assessment_type = (
                    CbtAssessmentType.DISTORTION_PRESENT.value
                )
            elif isinstance(action, RequestNoClearDistortionConfirmationAction):
                diagnostics.assessment_type = (
                    CbtAssessmentType.NO_CLEAR_DISTORTION.value
                )
            if CBT_DEBUG_LOG_ANALYSIS:
                logger.info(
                    "CBT Agent first-stage action: requestId=%s sessionId=%s "
                    "mode=%s tool=%s action=%s",
                    request.request_id,
                    request.session_id,
                    mode,
                    type(action).__name__,
                    action.model_dump_json(by_alias=True),
                )
            return action, draft

        feedbacks.append(feedback)
        diagnostics.agent_validation_failures.append(feedback)

        if CBT_DEBUG_LOG_ANALYSIS:
            logger.info(
                "CBT Agent first-stage retry: requestId=%s sessionId=%s "
                "mode=%s attempt=%s reason=%s",
                request.request_id,
                request.session_id,
                mode,
                attempt + 1,
                feedback,
            )

    raise CbtModelOutputExhaustedError("agent action", feedbacks) from None


def _with_agent_meta(response: CbtTurnResponse) -> CbtTurnResponse:
    return response.model_copy(
        update={
            "meta": AnalysisMeta(
                model=CBT_MODEL,
                prompt_version=CBT_AGENT_PROMPT_VERSION,
            )
        }
    )


def _is_live_continuation(
    runtime: AgentSessionRuntime,
    request: CbtTurnRequest,
) -> bool:
    if runtime.pending_question is None:
        return False
    if len(request.question_answers) != len(runtime.history) + 1:
        return False
    for saved, incoming in zip(runtime.history, request.question_answers):
        if saved.model_dump() != incoming.model_dump():
            return False
    latest = request.question_answers[-1]
    pending = runtime.pending_question
    return (
        latest.question_code == pending.question_code
        and latest.question_purpose == pending.question_purpose
        and latest.semantic_route_type == pending.semantic_route_type
        and latest.question == pending.question
    )


async def _run_agent_turn(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
    mode: str,
    *,
    agent_model: Any | None = None,
    writer_model: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    diagnostics = Q5TurnDiagnostics(mode=mode)
    runtime.last_diagnostics = diagnostics
    wording: QuestionWordingDraft | None = None
    agent_plan: AgentQuestionPlan | None = None
    no_clear_response: CbtTurnResponse | None = None
    try:
        action, draft = await _select_agent_action(
            request,
            runtime,
            mode,
            agent_model=agent_model,
            diagnostics=diagnostics,
        )
    except CbtModelOutputExhaustedError as exc:
        state_for_runtime = _normalize_agent_state(
            runtime.state,
            request,
            runtime,
        )

        def representative(
            items: list[SavedAnswerEvidence],
        ) -> SavedAnswerEvidence | None:
            return items[-1] if items else None

        progress = CbtSemanticProgress(
            evidence_for=representative(state_for_runtime.evidence_for),
            evidence_against=representative(state_for_runtime.evidence_against),
            alternative_view=representative(state_for_runtime.alternative_views),
            acknowledgement=state_for_runtime.acknowledgement,
        )
        draft, wording = _build_deterministic_fallback(
            request,
            semantic_progress=progress,
        )
        draft, wording = _constrain_agent_fallback(
            request,
            runtime,
            draft,
            wording,
        )
        diagnostics.fallback_used = True
        diagnostics.fallback_reason = f"{exc.stage}: {'; '.join(exc.feedbacks)}"
        diagnostics.render_source = "DETERMINISTIC_AGENT_FALLBACK"
        if wording is not None:
            diagnostics.deterministic_preface = wording.preface
            diagnostics.deterministic_question = wording.question
        _log_fallback_usage(
            request,
            architecture="agent",
            failed_stage=exc.stage,
            draft=draft,
        )
    else:
        state_for_runtime = action.state
        if isinstance(action, AskQuestionAction):
            agent_plan = action.question_plan
            diagnostics.answer_target = agent_plan.answer_target
            diagnostics.answer_source = agent_plan.answer_source.value
            diagnostics.evidence_polarity = _evidence_polarity(
                agent_plan
            ).value
            diagnostics.example_options = list(agent_plan.example_options)
        elif isinstance(action, RequestConfirmationAction):
            diagnostics.render_source = "DETERMINISTIC_CONFIRMATION"
        elif isinstance(action, RequestNoClearDistortionConfirmationAction):
            if not isinstance(request, CbtTurnRequest):
                raise CbtDraftValidationError(
                    "NO_CLEAR_DISTORTION requires a TURN request"
                )
            diagnostics.render_source = "DETERMINISTIC_NO_CLEAR_DISTORTION"
            no_clear_response = _render_no_clear_distortion_response(
                action,
                request,
                diagnostics,
            )
        else:
            diagnostics.render_source = "SAFETY_STOP"

    if no_clear_response is None and draft is not None and (
        draft.result_type == CbtResultType.QUESTION
    ):
        assert draft.question_plan is not None
        explicit_intent = _explicit_feedback_intent(request)
        is_dialogue_refusal = (
            isinstance(request, CbtTurnRequest)
            and _is_explicit_dialogue_refusal(
                request.question_answers[-1].answer
            )
        )
        question_plan = _apply_feedback_constraints(
            request,
            draft.question_plan,
        )
        if question_plan is not draft.question_plan:
            draft = draft.model_copy(
                update={"question_plan": question_plan}
            )
        if (
            (
                explicit_intent in DIALOGUE_CONTROL_INTENTS
                or is_dialogue_refusal
            )
            and isinstance(request, CbtTurnRequest)
        ):
            latest = request.question_answers[-1]
            blocked_routes = state_for_runtime.blocked_routes
            if (
                explicit_intent in CONVERSATION_FEEDBACK_INTENTS
                and latest.semantic_route_type is not None
            ):
                blocked_routes = list(
                    dict.fromkeys(
                        [*blocked_routes, latest.semantic_route_type]
                    )
                )[-16:]
            state_for_runtime = state_for_runtime.model_copy(
                update={
                    "evidence_for": [
                        item
                        for item in state_for_runtime.evidence_for
                        if item.source_question_code != latest.question_code
                    ],
                    "evidence_against": [
                        item
                        for item in state_for_runtime.evidence_against
                        if item.source_question_code != latest.question_code
                    ],
                    "alternative_views": [
                        item
                        for item in state_for_runtime.alternative_views
                        if item.source_question_code != latest.question_code
                    ],
                    "blocked_routes": blocked_routes,
                    "acknowledgement": (
                        None
                        if state_for_runtime.acknowledgement is not None
                        and state_for_runtime.acknowledgement.source_question_code
                        == latest.question_code
                        else state_for_runtime.acknowledgement
                    ),
                }
            )
        if wording is None and agent_plan is not None:
            if explicit_intent == LatestUserIntent.REQUEST_EXAMPLE:
                wording = _render_example_question(agent_plan, diagnostics)
            elif (
                state_for_runtime.direct_support_closed
                and question_plan.question_purpose
                == QuestionPurpose.EVIDENCE_AGAINST
                and question_plan.semantic_route_type
                == SemanticRouteType.CONTRADICTORY_FACT
            ):
                wording = _render_evidence_against_after_no_direct(
                    diagnostics
                )
            else:
                try:
                    wording = await _write_q5_question(
                        request,
                        agent_plan,
                        question_plan,
                        runtime,
                        diagnostics,
                        writer_model=writer_model,
                    )
                    diagnostics.render_source = "MODEL_WRITER"
                except CbtModelOutputExhaustedError as exc:
                    wording = _render_answer_target_fallback(
                        agent_plan,
                        diagnostics,
                    )
                    diagnostics.fallback_used = True
                    diagnostics.fallback_reason = (
                        f"{exc.stage}: {'; '.join(exc.feedbacks)}"
                    )
                    _log_fallback_usage(
                        request,
                        architecture="agent",
                        failed_stage=exc.stage,
                        draft=draft,
                    )
        elif wording is None:
            wording = _deterministic_fallback_wording(
                request,
                question_plan,
            )
            diagnostics.fallback_used = True
            diagnostics.fallback_reason = (
                "agent action: Agent fallback did not provide question wording"
            )
            diagnostics.render_source = "DETERMINISTIC_AGENT_FALLBACK"
            diagnostics.deterministic_preface = wording.preface
            diagnostics.deterministic_question = wording.question
    if no_clear_response is not None:
        response = no_clear_response
    else:
        assert draft is not None
        response = _with_agent_meta(_to_response(draft, request, wording))

    if response.status == CbtApiStatus.CONTINUE:
        assert response.next_question is not None
        runtime.state = state_for_runtime
        runtime.history = list(_history_for(request))
        runtime.pending_question = response.next_question
        runtime.pending_assessment = None
    else:
        # 마지막 요청의 HTTP 재시도에도 동일 응답을 돌려줄 수 있도록 TTL까지
        # runtime을 유지합니다. 명시적 중단은 close endpoint가 즉시 제거합니다.
        runtime.state = state_for_runtime
        runtime.history = list(_history_for(request))
        runtime.pending_question = None
        if response.status == CbtApiStatus.CONFIRM_REQUIRED:
            assert response.assessment_type is not None
            assert response.outcome_draft is not None
            assert response.proposal_message is not None
            runtime.pending_assessment = PendingAssessment(
                assessment_type=response.assessment_type,
                exploration_coverage=state_for_runtime.exploration_coverage,
                before_distortions=response.before_distortions,
                outcome_draft=response.outcome_draft,
                proposal_message=response.proposal_message,
            )
        else:
            runtime.pending_assessment = None
    await registry.get(runtime.session_id)
    return response


async def generate_agent_cbt_start(
    request: CbtStartRequest,
    *,
    agent_model: Any | None = None,
    writer_model: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    """새 Agent 세션을 만들고 첫 질문 또는 안전 중단을 반환합니다."""

    runtime, _ = await registry.get_or_create(request.session_id)
    fingerprint = _request_fingerprint(request)
    async with runtime.lock:
        cached = _cached_agent_response(runtime, request, fingerprint)
        if cached is not None:
            _remember_agent_response(runtime, request, fingerprint, cached)
            return cached

        # 중복이 아닌 새 START는 같은 sessionId의 실험 runtime을 초기화합니다.
        runtime.state = _empty_session_state()
        runtime.history = []
        runtime.pending_question = None
        runtime.pending_assessment = None
        response = await _run_agent_turn(
            request,
            runtime,
            "START",
            agent_model=agent_model,
            writer_model=writer_model,
            registry=registry,
        )
        _remember_agent_response(runtime, request, fingerprint, response)
        return response


async def generate_agent_cbt_turn(
    request: CbtTurnRequest,
    *,
    agent_model: Any | None = None,
    writer_model: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    """살아 있는 Agent를 재개하고, 없으면 전체 JSONB 문맥으로 재수화합니다."""

    runtime, created = await registry.get_or_create(request.session_id)
    mode = "REHYDRATE" if created else "CONTINUE"
    fingerprint = _request_fingerprint(request)

    async with runtime.lock:
        cached = _cached_agent_response(runtime, request, fingerprint)
        if cached is not None:
            _remember_agent_response(runtime, request, fingerprint, cached)
            return cached
        if mode == "CONTINUE" and not _is_live_continuation(runtime, request):
            runtime.state = _empty_session_state()
            runtime.history = []
            runtime.pending_question = None
            runtime.pending_assessment = None
            mode = "REHYDRATE"
        response = await _run_agent_turn(
            request,
            runtime,
            mode,
            agent_model=agent_model,
            writer_model=writer_model,
            registry=registry,
        )
        _remember_agent_response(runtime, request, fingerprint, response)
        return response


async def close_agent_cbt_session(
    session_id: int,
    *,
    registry: CbtAgentSessionRegistry = _registry,
) -> None:
    """사용자가 CBT를 명시적으로 중단했을 때 인메모리 Agent를 종료합니다."""

    await registry.remove(session_id)
