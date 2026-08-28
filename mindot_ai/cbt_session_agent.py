"""세션 상태를 유지하며 CBT tool을 선택하는 비교 실험용 Agent 구현입니다.

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
    _confirmation_candidate_available,
    _classify_explicit_user_intent,
    _explicit_feedback_intent,
    _explicit_safety_reason_from_text,
    _contextual_safety_hint,
    _deterministic_fallback_wording,
    _get_llm,
    _is_clearly_non_current_user_safety_text,
    _is_explicit_dialogue_refusal,
    _hard_blocked_route_families,
    _log_fallback_usage,
    _resolved_but_irrelevant_topics,
    _to_response,
    _validate_analysis_draft,
    _write_question,
)


CBT_AGENT_PROMPT_VERSION = "cbt-session-agent-quality-v1"
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


class AskQuestionAction(ApiModel):
    """다음 CBT 질문을 작성하고 Spring에 전달할 때 선택하는 tool입니다."""

    state: CbtSessionState
    question_plan: CbtQuestionPlan = Field(alias="questionPlan")


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
AGENT_TOOLS = (
    ASK_QUESTION_TOOL,
    REQUEST_CONFIRMATION_TOOL,
    SAFETY_STOP_TOOL,
)
ACTION_SCHEMAS: dict[str, type[ApiModel]] = {
    ASK_QUESTION_TOOL.name: AskQuestionAction,
    REQUEST_CONFIRMATION_TOOL.name: RequestConfirmationAction,
    SAFETY_STOP_TOOL.name: SafetyStopAction,
}


AGENT_SYSTEM_PROMPT = """
<role>
Manage one Korean CBT reflection session and call exactly one tool. Update
compact semantic state and select the next action and direction. The shared
Writer creates final wording. Do not diagnose, invent facts, or try to prove
automaticThought false.
</role>

<context>
The reflection subject and respondent are always the USER. People mentioned in
the situation are third parties the user observed or interpreted. Never plan a
question asking a third party to report their own thought or emotion.

START begins with empty state.
CONTINUE preserves valid currentState and processes latestInteraction.
REHYDRATE rebuilds state from fullHistory.

latestUserIntentHint is authoritative for explicit feedback and requests.
latestSafetyHint is only a candidate; verify it from the original text.
questionSubject is fixed to USER.

Top-level blockedRoutes and currentState.blockedRoutes are hard exclusions.
blockedRouteFamilies are server-owned hard exclusions.
resolvedButIrrelevantTopics are closed and cannot ground a new question.
Do not discard valid prior evidence or exclusions.
</context>

<state>
Interpret answers by actual meaning and answerDisposition, never by
questionPurpose alone.

Maintain:
- evidenceFor: exact excerpts supporting automaticThought;
- evidenceAgainst: exact excerpts contradicting it or lowering certainty;
- alternativeViews: exact excerpts containing another plausible view;
- acknowledgement: an exact excerpt distinguishing fact from inference or
  reassessing certainty;
- askedRoutes: semanticRouteType values already asked;
- blockedRoutes: routes explicitly rejected or resolved.

DIALOGUE_CONTROL, UNCLEAR, and SKIPPED are not CBT evidence.
NO_DIRECT_EVIDENCE is never evidenceFor. It may be evidenceAgainst when it
lowers certainty. DIRECT_SUPPORT is then resolved.

For RELEVANCE_FEEDBACK or REPETITION_FEEDBACK:
- do not store the latest answer as evidence or acknowledgement;
- preserve the server-provided route and family exclusions;
- do not reuse the rejected target, comparison, cause, evidence source, or
  causal link through new wording or another purpose;
- do not disguise the same meaning as OTHER_SPECIFIC.

For REQUEST_EXAMPLE, REQUEST_EXPLANATION, or DIFFICULTY_FEEDBACK, do not store
the request as evidence. Use prefaceGoal for one brief response.
</state>

<direction>
Separate observed facts, the core claim in automaticThought, and the user's
unresolved inference between them. Start from the core claim, not an adjacent
detail. Select one point whose answer could most change understanding or
certainty. There is no fixed purpose order.

Purpose:
- SITUATION_REFLECTION: one needed observable fact;
- EMOTION_REFLECTION: the user's own emotion or trigger;
- EVIDENCE_FOR: support for the core claim;
- EVIDENCE_AGAINST: contradiction, uncertainty, or missing expected support;
- ALTERNATIVE_VIEW: another explanation consistent with known facts;
- BALANCED_THOUGHT: a fair conclusion containing evidence and uncertainty;
- FREE_REFLECTION: the user chooses where to continue.

Never ask what a third party felt or thought. Ask what the user noticed,
experienced, inferred, or now concludes.

Do not follow an irrelevant detail, repeat an answered or blocked meaning, seek
another direct signal after none was reported, force optimism, or assume the
original thought is entirely false.

For ask_question:
- questionPurpose, semanticRouteType, and questionGoal describe one direction;
- questionGoal is a short Korean plan, not final wording:
  "확인된 사실: ...; 핵심 주장: ...; 미해결 간극: ...; 사용자에게 물을 목표: ...";
- groundingQuestionCodes contain only substantive saved answers;
- avoidTopics include answered, irrelevant, repeated, and blocked material;
- prefaceGoal is used only for a necessary reply or transition.
</direction>

<confirmation>
Call request_confirmation only when confirmationAllowed and normalized state
contains valid saved evidence for evidenceFor, evidenceAgainst,
alternativeViews, and acknowledgement.

The user need not declare automaticThought false; "possible but not certain"
may qualify.

Use exact saved excerpts, supplied distortionDefinitions, and tentative
language. Never use dialogue feedback as evidence, invent scores, or
re-propose a rejected distortion.
</confirmation>

<safety>
Call safety_stop only for a plausible current self-harm, suicide,
harm-to-others, or immediate-danger signal. Include one exact supporting
excerpt. Profanity, sadness, anxiety, frustration, refusal, difficulty, and
dialogue criticism alone are not safety signals.
</safety>

<tools>
Call exactly one:
- safety_stop for a plausible current danger signal;
- request_confirmation when all current completion conditions are satisfied;
- ask_question for one new, unblocked direction.

Never write the final user-facing question and never return COMPLETE.
</tools>
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
_agent_models: dict[bool, Any] = {}


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
    confirmation_allowed: bool,
) -> Any:
    model = _agent_models.get(confirmation_allowed)
    if model is None:
        tools = list(AGENT_TOOLS)
        if not confirmation_allowed:
            tools.remove(REQUEST_CONFIRMATION_TOOL)
        model = _get_llm().bind_tools(
            tools,
            tool_choice="required",
            strict=True,
            parallel_tool_calls=False,
        )
        _agent_models[confirmation_allowed] = model
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


def _build_agent_payload(
    request: CbtRequest,
    runtime: AgentSessionRuntime,
    mode: str,
) -> dict[str, Any]:
    history = (
        request.question_answers if isinstance(request, CbtTurnRequest) else []
    )
    confirmation_allowed = _confirmation_candidate_available(request)
    detected_feedback = _explicit_feedback_intent(request)
    latest_safety_hint = _contextual_safety_hint(request)
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
    return {
        "mode": mode,
        "confirmationAllowed": confirmation_allowed,
        "questionSubject": "USER",
        "latestSafetyHint": (
            latest_safety_hint.value if latest_safety_hint is not None else None
        ),
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


def _safety_source_texts(request: CbtRequest) -> list[str]:
    texts = [request.record.automatic_thought]
    if request.record.situation is not None:
        texts.append(request.record.situation)
    texts.extend(
        item.answer
        for item in _history_for(request)
        if item.answer is not None
    )
    return texts


def _validate_safety_action(
    action: SafetyStopAction,
    request: CbtRequest,
) -> None:
    matching_sources = [
        source
        for source in _safety_source_texts(request)
        if action.evidence in source
    ]
    if not matching_sources:
        raise CbtDraftValidationError(
            "Safety evidence must be an exact excerpt from supplied user text"
        )

    evidence_reason = _explicit_safety_reason_from_text(action.evidence)
    if evidence_reason is None:
        raise CbtDraftValidationError(
            "Safety evidence must itself contain an explicit harm or danger signal"
        )

    if not any(
        not _is_clearly_non_current_user_safety_text(source)
        for source in matching_sources
    ):
        raise CbtDraftValidationError(
            "Safety evidence is negated, resolved historical context, hypothetical, "
            "or attributed only to a third party"
        )

    if action.suspected_reason not in {
        evidence_reason,
        RiskReasonCode.AMBIGUOUS_SAFETY_SIGNAL,
    }:
        raise CbtDraftValidationError(
            "Safety suspectedReason contradicts the supplied evidence"
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
        if (
            action.state.blocked_routes
            and action.question_plan.semantic_route_type
            == SemanticRouteType.OTHER_SPECIFIC
        ):
            raise CbtDraftValidationError(
                "OTHER_SPECIFIC cannot disguise a known blocked Agent route"
            )
        draft = CbtAnalysisDraft(
            result_type=CbtResultType.QUESTION,
            semantic_progress=progress,
            question_plan=action.question_plan,
            confirmation=None,
            risk=none_risk,
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
        draft = CbtAnalysisDraft(
            result_type=CbtResultType.CONFIRMATION_REQUIRED,
            semantic_progress=progress,
            question_plan=None,
            confirmation=action.confirmation,
            risk=none_risk,
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
    confirmation_allowed = _confirmation_candidate_available(request)
    model = agent_model or _get_agent_model(confirmation_allowed)
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
