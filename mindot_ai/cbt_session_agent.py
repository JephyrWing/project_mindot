"""세션 상태를 유지하며 CBT tool을 선택하는 운영 Q4R Agent 구현입니다.

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
from dataclasses import dataclass, field
from time import monotonic
from typing import Any
from uuid import UUID

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.tools import StructuredTool
from pydantic import Field, ValidationError, model_validator

from cbt_agent import (
    CBT_MODEL,
    CBT_DEBUG_LOG_ANALYSIS,
    DISTORTION_DEFINITIONS,
    AnalysisMeta,
    AnswerDisposition,
    ApiModel,
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
    GeneratedQuestion,
    LatestUserIntent,
    QuestionPurpose,
    QuestionAnswer,
    QuestionWordingDraft,
    RiskAssessment,
    RiskLevel,
    RiskReasonCode,
    SavedAnswerEvidence,
    SemanticRouteType,
    CONVERSATION_FEEDBACK_INTENTS,
    DIALOGUE_CONTROL_INTENTS,
    _analysis_turn_flags,
    _apply_feedback_constraints,
    _blocked_routes_from_history,
    _build_deterministic_fallback,
    _classify_answer_disposition,
    _completion_candidates,
    _confirmation_candidate_available,
    _classify_explicit_user_intent,
    _explicit_feedback_intent,
    _deterministic_fallback_wording,
    _get_llm,
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
    _write_question,
)


CBT_AGENT_PROMPT_VERSION = "cbt-session-agent-quality-q4r"
CBT_AGENT_SESSION_TTL_SECONDS = int(
    os.getenv("CBT_AGENT_SESSION_TTL_SECONDS", "600")
)
CBT_AGENT_MODEL_OUTPUT_ATTEMPTS = int(
    os.getenv("CBT_AGENT_MODEL_OUTPUT_ATTEMPTS", "2")
)
RECENT_QUESTION_WINDOW = 3


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

    @model_validator(mode="after")
    def validate_unique_state_items(self) -> "CbtSessionState":
        self.asked_routes = list(dict.fromkeys(self.asked_routes))
        self.blocked_routes = list(dict.fromkeys(self.blocked_routes))
        self.evidence_for = _unique_evidence(self.evidence_for)
        self.evidence_against = _unique_evidence(self.evidence_against)
        self.alternative_views = _unique_evidence(self.alternative_views)
        return self


class AgentQuestionPlan(ApiModel):
    """Q4 Agent가 의미 경로와 목적을 직접 계획하는 내부 tool 스키마입니다."""

    question_purpose: QuestionPurpose = Field(alias="questionPurpose")
    semantic_route_type: SemanticRouteType = Field(alias="semanticRouteType")
    latest_user_intent: LatestUserIntent = Field(alias="latestUserIntent")
    question_goal: str = Field(alias="questionGoal", min_length=1, max_length=300)
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


class SafetyStopAction(ApiModel):
    """CBT 흐름을 중단하고 별도 안전 처리를 요청할 때 선택하는 tool입니다."""

    state: CbtSessionState
    suspected_reason: RiskReasonCode = Field(alias="suspectedReason")
    evidence: str = Field(min_length=1, max_length=300)


AgentAction = AskQuestionAction | RequestConfirmationAction | SafetyStopAction


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
    SAFETY_STOP_TOOL.name: SafetyStopAction,
}


AGENT_SYSTEM_PROMPT = """
<role>
Manage one Korean CBT reflection turn and call exactly one available tool.
Update compact state and choose safety, confirmation, or one question
direction. The shared Writer creates final question wording. Do not diagnose,
invent facts, or try to prove automaticThought false.
</role>

<input>
The respondent and reflection subject are always USER. Mentioned people are
third parties. START begins empty, CONTINUE preserves valid state, and
REHYDRATE rebuilds from fullHistory.

Available tools already reflect server eligibility. safetyCandidates,
semanticRouteDefinitions, confirmationAllowed, blockedRoutes,
blockedRouteFamilies, and resolvedButIrrelevantTopics are authoritative.
Never create a new OTHER_SPECIFIC route.
</input>

<state>
Interpret the latest answer by meaning and answerDisposition, not by the prior
questionPurpose. Preserve exact saved-answer excerpts:
- evidenceFor: supports the core claim;
- evidenceAgainst: contradicts it or lowers certainty;
- alternativeViews contains another plausible or balanced view;
- acknowledgement separates fact from inference or revises certainty.

DIALOGUE_CONTROL, UNCLEAR, and SKIPPED are not CBT evidence.
NO_DIRECT_EVIDENCE is never evidenceFor and closes further search for the same
direct support.

Relevance or repetition feedback is not evidence. Preserve all exclusions and
change the actual target, comparison, cause, evidence source, and information
requested. Example, explanation, and difficulty requests are dialogue control.
</state>

<decision>
Use safety_stop only with one exact matching safetyCandidate. Safety has
priority.

Use request_confirmation only when that tool is available. Ground every domain
in saved answers, use tentative distortion language, and provide a concrete
balanced thought. The original thought need not be completely false.

Otherwise use ask_question for one unresolved point that could change the
user's understanding or certainty. Separate observed fact, core claim, and the
inference between them. Ask what the user observed, experienced, judged, or
can consider; never require a third party's actual hidden state.

After NO_DIRECT_EVIDENCE, move to contradiction, uncertainty, certainty, or an
alternative view instead of seeking another direct signal. After relevance or
repetition feedback, do not rephrase the rejected meaning. For
REQUEST_EXAMPLE, supply two short neutral exampleOptions and ask which, if
any, seems plausible.
</decision>

<plan>
questionPurpose, semanticRouteType, selected route definition, and
questionGoal must express one meaning. questionGoal is a short Korean plan,
not final wording. groundingQuestionCodes use only substantive saved answers.
avoidTopics includes answered, irrelevant, repeated, and blocked material.
Call exactly one available tool and never return COMPLETE.
</plan>
""".strip()


@dataclass
class AgentSessionRuntime:
    session_id: int
    state: CbtSessionState
    history: list[QuestionAnswer] = field(default_factory=list)
    pending_question: GeneratedQuestion | None = None
    expires_at: float = 0.0
    last_request_id: UUID | None = None
    last_request_fingerprint: str | None = None
    last_response: CbtTurnResponse | None = None
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    expiry_task: asyncio.Task[None] | None = None


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
    )


def _get_agent_model(
    safety_allowed: bool,
    confirmation_allowed: bool,
) -> Any:
    eligibility = (safety_allowed, confirmation_allowed)
    model = _agent_models.get(eligibility)
    if model is None:
        tools = [ASK_QUESTION_TOOL]
        if safety_allowed:
            tools.append(SAFETY_STOP_TOOL)
        if confirmation_allowed:
            tools.append(REQUEST_CONFIRMATION_TOOL)
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


def _confirmation_eligible_state(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> CbtSessionState:
    """모델 호출 전에 저장 이력으로 확인 도구의 최소 자격을 계산합니다."""

    history = _history_for(request)
    candidates, _ = _completion_candidates(history)

    def candidate_evidence(domain: str) -> list[SavedAnswerEvidence]:
        return [
            SavedAnswerEvidence(
                source_question_code=item["questionCode"],
                excerpt=item["answer"][:300],
            )
            for item in candidates[domain]
        ]

    candidate_state = runtime.state.model_copy(
        update={
            "evidence_for": [
                *runtime.state.evidence_for,
                *candidate_evidence("evidenceFor"),
            ],
            "evidence_against": [
                *runtime.state.evidence_against,
                *candidate_evidence("evidenceAgainst"),
            ],
            "alternative_views": [
                *runtime.state.alternative_views,
                *candidate_evidence("alternativeView"),
            ],
            "acknowledgement": (
                candidate_evidence("acknowledgement")[-1]
                if candidates["acknowledgement"]
                else runtime.state.acknowledgement
            ),
        }
    )
    return _normalize_agent_state(candidate_state, request, runtime)


def _confirmation_tool_allowed(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
) -> bool:
    if not _confirmation_candidate_available(request):
        return False
    state = _confirmation_eligible_state(request, runtime)
    return bool(
        state.evidence_for
        and state.evidence_against
        and state.alternative_views
        and state.acknowledgement is not None
    )


def _build_agent_payload(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
    mode: str,
) -> dict[str, Any]:
    history = (
        request.question_answers if isinstance(request, CbtTurnRequest) else []
    )
    confirmation_allowed = _confirmation_tool_allowed(request, runtime)
    detected_feedback = _explicit_feedback_intent(request)
    latest_answer_meaning_hint, turn_flags = _analysis_turn_flags(
        request,
        detected_feedback,
    )
    blocked_routes = (
        _blocked_routes_from_history(history)
        if mode == "REHYDRATE"
        else _blocked_routes_from_history(history[-1:])
    )
    recent_questions = (
        []
        if mode == "REHYDRATE"
        else history[max(0, len(history) - RECENT_QUESTION_WINDOW - 1):-1]
    )
    completion_candidates, completion_coverage = _completion_candidates(history)
    return {
        "mode": mode,
        "confirmationAllowed": confirmation_allowed,
        "questionSubject": "USER",
        "safetyCandidates": _safety_candidates(request),
        "latestUserIntentHint": (
            detected_feedback.value if detected_feedback is not None else None
        ),
        "latestAnswerMeaningHint": latest_answer_meaning_hint,
        "turnFlags": turn_flags,
        "blockedRoutes": blocked_routes,
        "blockedRouteFamilies": sorted(
            family.value
            for family in _hard_blocked_route_families(history)
        ),
        "resolvedButIrrelevantTopics": _resolved_but_irrelevant_topics(
            history
        ),
        "semanticRouteDefinitions": _semantic_route_definitions_payload(),
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
            if confirmation_allowed
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

    return state.model_copy(
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

    evidence_for = action.state.evidence_for[-1]
    evidence_against = action.state.evidence_against[-1]
    alternative = action.state.alternative_views[-1]
    acknowledgement = action.state.acknowledgement
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


def _validate_agent_action(
    action: AgentAction,
    request: CbtRequest,
) -> CbtAnalysisDraft:
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
        if action.question_plan.semantic_route_type in action.state.blocked_routes:
            raise CbtDraftValidationError(
                "Agent questionPlan.semanticRouteType reuses a blocked state route"
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
        if not (
            action.state.evidence_for
            and action.state.evidence_against
            and action.state.alternative_views
            and action.state.acknowledgement is not None
        ):
            raise CbtDraftValidationError(
                "Agent confirmation requires all four normalized state domains"
            )
        if not isinstance(request, CbtTurnRequest):
            raise CbtDraftValidationError(
                "Agent confirmation requires saved user answers"
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
) -> tuple[AgentAction, CbtAnalysisDraft]:
    payload = _build_agent_payload(request, runtime, mode)
    confirmation_allowed = bool(payload["confirmationAllowed"])
    safety_allowed = bool(payload["safetyCandidates"])
    model = agent_model or _get_agent_model(
        safety_allowed,
        confirmation_allowed,
    )
    feedbacks: list[str] = []

    for attempt in range(CBT_AGENT_MODEL_OUTPUT_ATTEMPTS):
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
                isinstance(action, RequestConfirmationAction)
                and not confirmation_allowed
            ):
                raise CbtDraftValidationError(
                    "request_confirmation is unavailable for this turn"
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
            )
        except ValidationError as exc:
            feedback = ", ".join(
                f"{'.'.join(str(part) for part in error['loc'])}: {error['msg']}"
                for error in exc.errors(include_input=False)
            )[:1_000]
        except CbtDraftValidationError as exc:
            feedback = str(exc)[:1_000]
        else:
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
    wording: QuestionWordingDraft | None = None
    try:
        action, draft = await _select_agent_action(
            request,
            runtime,
            mode,
            agent_model=agent_model,
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
        _log_fallback_usage(
            request,
            architecture="agent",
            failed_stage=exc.stage,
            draft=draft,
        )
    else:
        state_for_runtime = action.state

    if draft.result_type == CbtResultType.QUESTION:
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
        if wording is None:
            try:
                wording = await _write_question(
                    request,
                    question_plan,
                    writer_model=writer_model,
                )
            except CbtModelOutputExhaustedError as exc:
                wording = _deterministic_fallback_wording(
                    request,
                    question_plan,
                )
                _log_fallback_usage(
                    request,
                    architecture="agent",
                    failed_stage=exc.stage,
                    draft=draft,
                )
    response = _with_agent_meta(_to_response(draft, request, wording))

    if response.status == CbtApiStatus.CONTINUE:
        assert response.next_question is not None
        runtime.state = state_for_runtime
        runtime.history = list(_history_for(request))
        runtime.pending_question = response.next_question
    else:
        # 마지막 요청의 HTTP 재시도에도 동일 응답을 돌려줄 수 있도록 TTL까지
        # runtime을 유지합니다. 명시적 중단은 close endpoint가 즉시 제거합니다.
        runtime.state = state_for_runtime
        runtime.history = list(_history_for(request))
        runtime.pending_question = None
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
