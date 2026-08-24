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
from collections import OrderedDict
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
    QuestionPurpose,
    QuestionWordingDraft,
    RiskAssessment,
    RiskLevel,
    RiskReasonCode,
    SavedAnswerEvidence,
    SemanticRouteType,
    CONVERSATION_FEEDBACK_INTENTS,
    DIALOGUE_CONTROL_INTENTS,
    WRITER_PREVIOUS_QUESTION_LIMIT,
    _analysis_turn_flags,
    _apply_feedback_constraints,
    _blocked_routes_from_history,
    _classify_answer_disposition,
    _confirmation_candidate_available,
    _classify_explicit_user_intent,
    _explicit_feedback_intent,
    _contextual_safety_hint,
    _get_llm,
    _get_writer_model,
    _invoke_structured,
    _is_explicit_dialogue_refusal,
    _to_response,
    _validate_analysis_draft,
    _validate_wording_draft,
    WRITER_PROMPT,
)


CBT_AGENT_PROMPT_VERSION = "cbt-session-agent-dev"
CBT_AGENT_SESSION_TTL_SECONDS = int(
    os.getenv("CBT_AGENT_SESSION_TTL_SECONDS", "600")
)
CBT_AGENT_MODEL_OUTPUT_ATTEMPTS = int(
    os.getenv("CBT_AGENT_MODEL_OUTPUT_ATTEMPTS", "2")
)
RECENT_QUESTION_WINDOW = 3
AGENT_IDEMPOTENCY_CACHE_MAX_ENTRIES = 32


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
    covered_purposes: list[QuestionPurpose] = Field(
        alias="coveredPurposes",
        description="Purposes present in the saved question history.",
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
    last_user_intent: LatestUserIntent = Field(
        alias="lastUserIntent",
        description="Intent of the latest saved answer.",
    )
    turn_count: int = Field(
        alias="turnCount",
        ge=0,
        description="Number of saved question-answer pairs.",
    )

    @model_validator(mode="after")
    def validate_unique_state_items(self) -> "CbtSessionState":
        self.covered_purposes = list(dict.fromkeys(self.covered_purposes))
        self.asked_routes = list(dict.fromkeys(self.asked_routes))
        self.blocked_routes = list(dict.fromkeys(self.blocked_routes))
        self.evidence_for = _unique_evidence(self.evidence_for)
        self.evidence_against = _unique_evidence(self.evidence_against)
        self.alternative_views = _unique_evidence(self.alternative_views)
        return self


class AgentQuestionPlan(ApiModel):
    """Agent tool 경계에서 복구 가능한 형식 잡음을 정규화하는 질문 계획입니다."""

    question_purpose: QuestionPurpose = Field(
        alias="questionPurpose",
        description="Semantic purpose of the next question direction.",
    )
    semantic_route_type: SemanticRouteType = Field(
        alias="semanticRouteType",
        description="Structured information route for the next question.",
    )
    latest_user_intent: LatestUserIntent = Field(
        alias="latestUserIntent",
        description="Intent of the latest saved answer.",
    )
    question_goal: str = Field(
        alias="questionGoal",
        min_length=1,
        max_length=300,
        description="Short Korean semantic plan for one next question direction.",
    )
    preface_goal: str | None = Field(
        alias="prefaceGoal",
        max_length=300,
        description="Optional goal for a necessary preface or transition.",
    )
    grounding_question_codes: list[str] = Field(
        alias="groundingQuestionCodes",
        max_length=5,
        description="Saved question codes containing grounding answers.",
    )
    avoid_topics: list[str] = Field(
        alias="avoidTopics",
        max_length=8,
        description="Topics and semantic routes excluded from wording.",
    )

    @model_validator(mode="after")
    def normalize_plan(self) -> "AgentQuestionPlan":
        self.grounding_question_codes = list(
            dict.fromkeys(self.grounding_question_codes)
        )
        self.avoid_topics = list(dict.fromkeys(self.avoid_topics))
        if self.latest_user_intent in {
            LatestUserIntent.REQUEST_EXAMPLE,
            LatestUserIntent.REQUEST_EXPLANATION,
            LatestUserIntent.RELEVANCE_FEEDBACK,
            LatestUserIntent.DIFFICULTY_FEEDBACK,
            LatestUserIntent.REPETITION_FEEDBACK,
        } and self.preface_goal is None:
            self.preface_goal = (
                "Briefly respond to the user's request or conversation feedback "
                "before asking the next question."
            )
        return self

    def to_cbt_plan(self) -> CbtQuestionPlan:
        return CbtQuestionPlan.model_validate(
            self.model_dump(by_alias=True, mode="json")
        )


class AskQuestionAction(ApiModel):
    """다음 CBT 질문을 작성하고 Spring에 전달할 때 선택하는 tool입니다."""

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


class SafetyVerification(ApiModel):
    """Agent의 안전 중단 후보를 독립적으로 재검증한 결과입니다."""

    confirmed: bool
    risk: RiskAssessment
    evidence: str | None = Field(min_length=1, max_length=300)

    @model_validator(mode="after")
    def validate_result_shape(self) -> "SafetyVerification":
        if self.confirmed:
            if self.risk.level == RiskLevel.NONE or self.evidence is None:
                raise ValueError(
                    "confirmed safety requires a risk and exact evidence"
                )
        elif self.risk.level != RiskLevel.NONE or self.evidence is not None:
            raise ValueError(
                "rejected safety requires NONE risk and null evidence"
            )
        return self


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
        "Submit a plausible harm-or-immediate-danger candidate for independent "
        "verification."
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
Manage one Korean CBT self-reflection session. Update compact semantic state and
call exactly one available tool. You decide state, action, and question direction;
the separate wording model writes the final Korean question. Do not diagnose or
try to prove automaticThought wrong.
</role>

<priority>
Use this order:
1. Plausible self-harm, suicide, harm to others, or immediate danger.
2. The meaning of latestInteraction and any authoritative server hint.
3. Semantic state update.
4. request_confirmation if meaningfully ready; otherwise one new question plan.

Tone, profanity, sadness, frustration, refusal, and criticism of the dialogue are
not safety signals by themselves.
</priority>

<context>
On START, begin from empty state. On CONTINUE, preserve valid currentState and
process latestInteraction. On REHYDRATE, rebuild state from every fullHistory item
before planning. For a legacy rejected question with semanticRouteType=null, infer
the most specific existing SemanticRouteType and preserve it in blockedRoutes. Do
not discard valid prior evidence or blocked routes.

latestUserIntentHint is authoritative for explicit dialogue intent. blockedRoutes
are hard exclusions. latestAnswerMeaningHint and turnFlags are conservative facts;
no flag chooses questionPurpose. latestSafetyHint is only a candidate whose meaning
must be confirmed from the original text and current context.
</context>

<state>
Interpret answers by meaning, never by the previous questionPurpose alone.

- evidenceFor: exact substantive excerpts directly supporting automaticThought.
- evidenceAgainst: exact substantive excerpts showing contradiction, missing
  certainty, absent expected evidence, or another reason for lower confidence.
- alternativeViews: exact substantive excerpts containing another plausible or
  balanced interpretation.
- acknowledgement: an exact excerpt where the user distinguishes fact from
  inference or meaningfully reassesses certainty.
- coveredPurposes: purposes actually asked, regardless of answer quality.
- askedRoutes: semanticRouteType values already asked.
- blockedRoutes: semanticRouteType values rejected as irrelevant or repetitive.

Use answerDisposition: DIALOGUE_CONTROL, UNCLEAR, and SKIPPED are not CBT evidence.
NO_DIRECT_EVIDENCE is never evidenceFor; store it as evidenceAgainst only when it
meaningfully reduces certainty and treat direct-support search as resolved.
When turnFlags.directEvidenceRouteResolved is true, do not seek the same direct
evidence again; choose the next direction from the whole semantic state.

For RELEVANCE_FEEDBACK or REPETITION_FEEDBACK:
- do not store the latest answer as evidence or acknowledgement;
- set lastUserIntent from latestUserIntentHint when supplied;
- add the rejected semantic route to blockedRoutes;
- do not reuse its target, comparison, assumed cause, evidence source, or inference
  through paraphrasing or a different questionPurpose;
- do not use OTHER_SPECIFIC to disguise a known blocked route;
- plan a genuinely different information route.

For REQUEST_EXAMPLE, REQUEST_EXPLANATION, or DIFFICULTY_FEEDBACK, do not store the
request as evidence. Use prefaceGoal to answer, explain, or simplify briefly.
</state>

<plan>
Separate:
- observed facts,
- the core claim in automaticThought,
- the unresolved inference needed to connect them.

Choose the single unresolved point with the highest information value. A detail is
relevant only if its answer could change understanding or confidence. Do not follow
a word from the latest answer merely because it was mentioned.

Purpose meanings:
- SITUATION_REFLECTION: an observable fact needed to separate event from inference.
- EMOTION_REFLECTION: the user's own emotion or trigger only when still unclear.
- EVIDENCE_FOR: direct words, actions, outcomes, or facts supporting the core claim.
- EVIDENCE_AGAINST: contradiction, missing certainty, lack of expected support, or
  another fact weakening the core claim.
- ALTERNATIVE_VIEW: another interpretation consistent with established facts.
- BALANCED_THOUGHT: a fair conclusion containing both evidence and uncertainty.
- FREE_REFLECTION: only when no specific purpose fits and the user must choose what
  matters or where to continue.

Do not ask for another person's hidden motive, demand a positive counterexample,
repeat an answered route, or continue seeking support after the user reports none.
The same questionPurpose may remain after feedback if the new information route is
genuinely different.

For ask_question:
- questionPurpose, semanticRouteType, and questionGoal must express the same
  direction. Purpose describes why; semanticRouteType describes what information
  route is used.
- questionGoal is a short Korean plan, not the final question:
  "확인된 사실: ...; 핵심 주장: ...; 미해결 간극: ...; 질문 목표: ..."
- include only substantive groundingQuestionCodes.
- put answered, irrelevant, repeated, and blocked routes in avoidTopics.
- use prefaceGoal only for a necessary reply or transition.
</plan>

<confirmation>
confirmationAllowed is necessary but not sufficient. Call request_confirmation only
when saved answers meaningfully contain:
- evidence for,
- evidence against or uncertainty,
- the user's own alternative or balanced view,
- and acknowledgement of fact versus inference or reassessed certainty.

The user need not declare automaticThought false. "Possible but not certain from
current facts" can be a valid outcome. Use exact saved excerpts, supplied distortion
definitions, and tentative language. Never use feedback as coverage, invent scores,
or re-propose a rejected distortion.
confirmationCoverage must point to evidence actually present in the matching state
lists, and acknowledgement must match state.acknowledgement.
</confirmation>

<tools>
- safety_stop: only a plausible harm-or-danger candidate for independent verification.
- request_confirmation: only when the semantic completion conditions are satisfied.
- ask_question: update state and plan exactly one new direction; never write the
  final user-facing question.

Call exactly one available tool. Never return COMPLETE.
</tools>

<example>
If the user says "그딴 게 무슨 상관인데?" after being asked about situations where
a manager smiled at other people, treat it as RELEVANCE_FEEDBACK. Do not save it as
evidence. Block the route about when or why the manager smiled at others. You may
keep EVIDENCE_FOR, but use a new route such as whether any direct words or actions,
apart from the facial expression, connected the manager's reaction to the user or
the report.
</example>
""".strip()


SAFETY_VERIFIER_PROMPT = """
<role>Verify a proposed Mindot safety stop.</role>
<confirm>Only an explicit or genuinely plausible reference to self-harm, suicide,
harm to others, or immediate physical danger. CRISIS requires current or imminent
danger; otherwise use REVIEW.</confirm>
<reject>Anger, profanity, frustration, refusal, difficulty, anxiety, sadness, or
conflict alone are not safety signals.</reject>
<evidence>If confirmed, copy one exact supporting excerpt. Otherwise return
confirmed=false, risk NONE, and evidence=null. Never infer from tone or invent text.</evidence>
""".strip()


@dataclass
class AgentIdempotencyEntry:
    fingerprint: str
    response: CbtTurnResponse


@dataclass
class AgentSessionRuntime:
    session_id: int
    state: CbtSessionState
    history: list[QuestionAnswer] = field(default_factory=list)
    pending_question: GeneratedQuestion | None = None
    expires_at: float = 0.0
    request_cache: OrderedDict[UUID, AgentIdempotencyEntry] = field(
        default_factory=OrderedDict
    )
    fingerprint_cache: OrderedDict[str, AgentIdempotencyEntry] = field(
        default_factory=OrderedDict
    )
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
_safety_verifier_model: Any | None = None


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

    request_entry = runtime.request_cache.get(request.request_id)
    if request_entry is not None:
        if request_entry.fingerprint != fingerprint:
            raise CbtAgentIdempotencyError(
                "The same requestId cannot be reused with a different CBT payload"
            )
        runtime.request_cache.move_to_end(request.request_id)
        runtime.fingerprint_cache.move_to_end(fingerprint)
        return request_entry.response.model_copy(
            update={"request_id": request.request_id}
        )

    fingerprint_entry = runtime.fingerprint_cache.get(fingerprint)
    if fingerprint_entry is not None:
        runtime.fingerprint_cache.move_to_end(fingerprint)
        runtime.request_cache[request.request_id] = fingerprint_entry
        _trim_idempotency_cache(runtime)
        return fingerprint_entry.response.model_copy(
            update={"request_id": request.request_id}
        )
    return None


def _remember_agent_response(
    runtime: AgentSessionRuntime,
    request: CbtRequest,
    fingerprint: str,
    response: CbtTurnResponse,
) -> None:
    # 세션별 bounded 캐시입니다. 재시작·멀티프로세스 멱등성은 Spring 또는
    # 외부 저장소가 담당해야 합니다.
    entry = AgentIdempotencyEntry(
        fingerprint=fingerprint,
        response=response,
    )
    runtime.request_cache[request.request_id] = entry
    runtime.fingerprint_cache[fingerprint] = entry
    _trim_idempotency_cache(runtime)


def _trim_idempotency_cache(runtime: AgentSessionRuntime) -> None:
    while len(runtime.request_cache) > AGENT_IDEMPOTENCY_CACHE_MAX_ENTRIES:
        runtime.request_cache.popitem(last=False)
    while len(runtime.fingerprint_cache) > AGENT_IDEMPOTENCY_CACHE_MAX_ENTRIES:
        _, expired_entry = runtime.fingerprint_cache.popitem(last=False)
        expired_request_ids = [
            request_id
            for request_id, entry in runtime.request_cache.items()
            if entry is expired_entry
        ]
        for request_id in expired_request_ids:
            runtime.request_cache.pop(request_id, None)


def _empty_session_state() -> CbtSessionState:
    return CbtSessionState(
        situation_summary=None,
        emotion_summary=None,
        evidence_for=[],
        evidence_against=[],
        alternative_views=[],
        covered_purposes=[],
        asked_routes=[],
        blocked_routes=[],
        acknowledgement=None,
        last_user_intent=LatestUserIntent.START,
        turn_count=0,
    )


def _get_agent_model(
    confirmation_allowed: bool,
    safety_allowed: bool = True,
) -> Any:
    cache_key = (confirmation_allowed, safety_allowed)
    model = _agent_models.get(cache_key)
    if model is None:
        tools = list(AGENT_TOOLS)
        if not confirmation_allowed:
            tools.remove(REQUEST_CONFIRMATION_TOOL)
        if not safety_allowed:
            tools.remove(SAFETY_STOP_TOOL)
        model = _get_llm().bind_tools(
            tools,
            tool_choice="required",
            strict=True,
            parallel_tool_calls=False,
        )
        _agent_models[cache_key] = model
    return model


def _get_safety_verifier_model() -> Any:
    global _safety_verifier_model
    if _safety_verifier_model is None:
        _safety_verifier_model = _get_llm().with_structured_output(
            SafetyVerification,
            method="json_schema",
            strict=True,
        )
    return _safety_verifier_model


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
        "latestSafetyHint": (
            latest_safety_hint.value if latest_safety_hint is not None else None
        ),
        "latestUserIntentHint": (
            detected_feedback.value if detected_feedback is not None else None
        ),
        "latestAnswerMeaningHint": latest_answer_meaning_hint,
        "turnFlags": turn_flags,
        "blockedRoutes": blocked_routes,
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
    mode: str,
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

    detected_intent = _explicit_feedback_intent(request)
    if history and _is_explicit_dialogue_refusal(history[-1].answer):
        detected_intent = LatestUserIntent.UNCLEAR

    feedback_routes = [
        item.semantic_route_type
        for item in history
        if item.semantic_route_type is not None
        if _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
    ]
    legacy_feedback_count = sum(
        item.semantic_route_type is None
        and _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
        for item in history
    )
    restored_legacy_routes = (
        [
            route
            for route in state.blocked_routes
            if route not in previous.blocked_routes
            and route not in feedback_routes
            and route != SemanticRouteType.OTHER_SPECIFIC
        ][:legacy_feedback_count]
        if mode == "REHYDRATE"
        else []
    )
    if (
        mode == "REHYDRATE"
        and legacy_feedback_count > 0
        and not restored_legacy_routes
    ):
        raise CbtDraftValidationError(
            "REHYDRATE must restore a specific semanticRouteType for legacy "
            "rejected questions; OTHER_SPECIFIC is not sufficient"
        )
    blocked_routes = list(
        dict.fromkeys(
            [
                *previous.blocked_routes,
                *restored_legacy_routes,
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
            "covered_purposes": list(
                dict.fromkeys(item.question_purpose for item in history)
            ),
            "asked_routes": list(
                dict.fromkeys(
                    [*previous.asked_routes, *asked_routes]
                )
            )[-24:],
            "blocked_routes": list(dict.fromkeys(blocked_routes))[-16:],
            "acknowledgement": acknowledgement,
            "last_user_intent": detected_intent or state.last_user_intent,
            "turn_count": len(history),
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


def _validate_safety_verification(
    verification: SafetyVerification,
    request: CbtRequest,
) -> None:
    if not verification.confirmed:
        return
    assert verification.evidence is not None
    if not any(
        verification.evidence in source
        for source in _safety_source_texts(request)
    ):
        raise CbtDraftValidationError(
            "Safety evidence must be an exact excerpt from supplied user text"
        )


async def _verify_safety_candidate(
    request: CbtRequest,
    candidate: SafetyStopAction,
    *,
    safety_model: Any | None = None,
) -> SafetyVerification:
    payload = {
        "suspectedReason": candidate.suspected_reason.value,
        "record": {
            "situation": request.record.situation,
            "automaticThought": request.record.automatic_thought,
        },
        "userAnswers": [
            {
                "questionCode": item.question_code,
                "answer": item.answer,
            }
            for item in _history_for(request)
        ],
    }
    verification = await _invoke_structured(
        model=safety_model or _get_safety_verifier_model(),
        schema=SafetyVerification,
        system_prompt=SAFETY_VERIFIER_PROMPT,
        payload=payload,
        validate=lambda result: _validate_safety_verification(
            result,
            request,
        ),
        stage="Agent safety verification",
        retry_guidance=(
            "Require exact harm-or-danger evidence; frustration or refusal alone "
            "must be rejected."
        ),
    )
    if CBT_DEBUG_LOG_ANALYSIS:
        logger.info(
            "CBT Agent safety verification: requestId=%s sessionId=%s "
            "confirmed=%s risk=%s",
            request.request_id,
            request.session_id,
            verification.confirmed,
            verification.risk.level.value,
        )
    return verification


def _validate_agent_action(
    action: AgentAction,
    request: CbtRequest,
) -> CbtAnalysisDraft:
    def representative(
        items: list[SavedAnswerEvidence],
    ) -> SavedAnswerEvidence | None:
        return items[-1] if items else None

    if isinstance(action, RequestConfirmationAction):
        coverage = action.confirmation.confirmation_coverage
        acknowledgement = SavedAnswerEvidence(
            source_question_code=(
                action.confirmation.acknowledgement_source_question_code
            ),
            excerpt=action.confirmation.acknowledgement_evidence,
        )
        progress = CbtSemanticProgress(
            evidence_for=coverage.evidence_for,
            evidence_against=coverage.evidence_against,
            alternative_view=coverage.alternative_view,
            acknowledgement=acknowledgement,
        )
    else:
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
            question_plan=action.question_plan.to_cbt_plan(),
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
        coverage = action.confirmation.confirmation_coverage
        if coverage.evidence_for not in action.state.evidence_for:
            raise CbtDraftValidationError(
                "confirmationCoverage.evidenceFor is not in state.evidenceFor"
            )
        if coverage.evidence_against not in action.state.evidence_against:
            raise CbtDraftValidationError(
                "confirmationCoverage.evidenceAgainst is not in state.evidenceAgainst"
            )
        if coverage.alternative_view not in action.state.alternative_views:
            raise CbtDraftValidationError(
                "confirmationCoverage.alternativeView is not in state.alternativeViews"
            )
        if acknowledgement != action.state.acknowledgement:
            raise CbtDraftValidationError(
                "confirmation acknowledgement does not match state.acknowledgement"
            )
        draft = CbtAnalysisDraft(
            result_type=CbtResultType.CONFIRMATION_REQUIRED,
            semantic_progress=progress,
            question_plan=None,
            confirmation=action.confirmation,
            risk=none_risk,
        )
    else:
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
    safety_model: Any | None = None,
) -> tuple[AgentAction, CbtAnalysisDraft]:
    payload = _build_agent_payload(request, runtime, mode)
    confirmation_allowed = _confirmation_candidate_available(request)
    safety_allowed = True
    model = agent_model or _get_agent_model(
        confirmation_allowed,
        safety_allowed,
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
                mode,
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
            if isinstance(action, SafetyStopAction):
                verification = await _verify_safety_candidate(
                    request,
                    action,
                    safety_model=safety_model,
                )
                if not verification.confirmed:
                    feedback = (
                        "The independent safety verifier rejected safety_stop. "
                        "Treat tone, frustration, or refusal as conversation "
                        "feedback and choose a non-safety action."
                    )
                    feedbacks.append(feedback)
                    safety_allowed = False
                    if agent_model is None:
                        model = _get_agent_model(
                            confirmation_allowed,
                            safety_allowed,
                        )
                    if CBT_DEBUG_LOG_ANALYSIS:
                        logger.info(
                            "CBT Agent first-stage retry: requestId=%s "
                            "sessionId=%s mode=%s attempt=%s reason=%s",
                            request.request_id,
                            request.session_id,
                            mode,
                            attempt + 1,
                            feedback,
                        )
                    continue

                draft = CbtAnalysisDraft(
                    result_type=CbtResultType.SAFETY_STOP,
                    semantic_progress=CbtSemanticProgress(
                        evidence_for=(
                            action.state.evidence_for[-1]
                            if action.state.evidence_for
                            else None
                        ),
                        evidence_against=(
                            action.state.evidence_against[-1]
                            if action.state.evidence_against
                            else None
                        ),
                        alternative_view=(
                            action.state.alternative_views[-1]
                            if action.state.alternative_views
                            else None
                        ),
                        acknowledgement=action.state.acknowledgement,
                    ),
                    question_plan=None,
                    confirmation=None,
                    risk=verification.risk,
                )
                _validate_analysis_draft(draft, request)

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

    raise RuntimeError(
        "CBT session Agent remained invalid after corrective retries: "
        f"{' | '.join(feedbacks)}"
    ) from None


def _build_agent_writer_payload(
    request: CbtRequest,
    plan: CbtQuestionPlan,
) -> dict[str, Any]:
    history = _history_for(request)
    grounding_codes = set(plan.grounding_question_codes)
    return {
        "record": {
            "situation": request.record.situation,
            "automaticThought": request.record.automatic_thought,
            "primaryEmotionCode": request.record.primary_emotion_code,
        },
        "latestInteraction": _question_dump(history[-1]) if history else None,
        "groundingAnswers": [
            _question_dump(item)
            for item in history
            if item.question_code in grounding_codes
        ],
        "previousQuestions": [
            {
                "questionPurpose": item.question_purpose.value,
                "semanticRouteType": (
                    item.semantic_route_type.value
                    if item.semantic_route_type is not None
                    else None
                ),
                "question": item.question,
            }
            for item in history[
                max(
                    0,
                    len(history) - WRITER_PREVIOUS_QUESTION_LIMIT - 1,
                ):-1
            ]
        ],
        "prefaceRequired": plan.preface_goal is not None,
        "plan": plan.model_dump(by_alias=True, mode="json"),
    }


def _validate_agent_wording(
    wording: QuestionWordingDraft,
    plan: CbtQuestionPlan,
    request: CbtRequest,
) -> None:
    _validate_wording_draft(wording, plan, request)
    question = wording.question
    if any(
        marker in question
        for marker in ("긍정적인 반응", "좋은 반응", "잘했다는 반응")
    ):
        raise CbtDraftValidationError(
            "Writer must not replace the Agent plan with a positive counterexample"
        )
async def _write_agent_question(
    request: CbtRequest,
    plan: CbtQuestionPlan,
    *,
    writer_model: Any | None = None,
) -> QuestionWordingDraft:
    return await _invoke_structured(
        model=writer_model or _get_writer_model(),
        schema=QuestionWordingDraft,
        system_prompt=WRITER_PROMPT,
        payload=_build_agent_writer_payload(request, plan),
        validate=lambda wording: _validate_agent_wording(
            wording,
            plan,
            request,
        ),
        stage="Agent question wording",
        retry_guidance=(
            "Keep the Agent plan, avoid forbidden topics, and do not repeat a "
            "previous question."
        ),
    )


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
    safety_model: Any | None = None,
    writer_model: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    action, draft = await _select_agent_action(
        request,
        runtime,
        mode,
        agent_model=agent_model,
        safety_model=safety_model,
    )
    wording: QuestionWordingDraft | None = None
    if isinstance(action, AskQuestionAction):
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
            blocked_routes = action.state.blocked_routes
            if (
                explicit_intent in CONVERSATION_FEEDBACK_INTENTS
                and latest.semantic_route_type is not None
            ):
                blocked_routes = list(
                    dict.fromkeys(
                        [*blocked_routes, latest.semantic_route_type]
                    )
                )[-16:]
            updated_state = action.state.model_copy(
                update={
                    "evidence_for": [
                        item
                        for item in action.state.evidence_for
                        if item.source_question_code != latest.question_code
                    ],
                    "evidence_against": [
                        item
                        for item in action.state.evidence_against
                        if item.source_question_code != latest.question_code
                    ],
                    "alternative_views": [
                        item
                        for item in action.state.alternative_views
                        if item.source_question_code != latest.question_code
                    ],
                    "blocked_routes": blocked_routes,
                    "acknowledgement": (
                        None
                        if action.state.acknowledgement is not None
                        and action.state.acknowledgement.source_question_code
                        == latest.question_code
                        else action.state.acknowledgement
                    ),
                    "last_user_intent": (
                        explicit_intent or LatestUserIntent.UNCLEAR
                    ),
                }
            )
            action = action.model_copy(update={"state": updated_state})
        wording = await _write_agent_question(
            request,
            question_plan,
            writer_model=writer_model,
        )
    response = _with_agent_meta(_to_response(draft, request, wording))

    if response.status == CbtApiStatus.CONTINUE:
        assert response.next_question is not None
        runtime.state = action.state
        runtime.history = list(_history_for(request))
        runtime.pending_question = response.next_question
    else:
        # 마지막 요청의 HTTP 재시도에도 동일 응답을 돌려줄 수 있도록 TTL까지
        # runtime을 유지합니다. 명시적 중단은 close endpoint가 즉시 제거합니다.
        runtime.state = action.state
        runtime.history = list(_history_for(request))
        runtime.pending_question = None
    await registry.get(runtime.session_id)
    return response


async def generate_agent_cbt_start(
    request: CbtStartRequest,
    *,
    agent_model: Any | None = None,
    safety_model: Any | None = None,
    writer_model: Any | None = None,
    registry: CbtAgentSessionRegistry = _registry,
) -> CbtTurnResponse:
    """새 Agent 세션을 만들고 첫 질문 또는 안전 중단을 반환합니다."""

    runtime, _ = await registry.get_or_create(request.session_id)
    fingerprint = _request_fingerprint(request)
    async with runtime.lock:
        cached = _cached_agent_response(runtime, request, fingerprint)
        if cached is not None:
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
            safety_model=safety_model,
            writer_model=writer_model,
            registry=registry,
        )
        _remember_agent_response(runtime, request, fingerprint, response)
        return response


async def generate_agent_cbt_turn(
    request: CbtTurnRequest,
    *,
    agent_model: Any | None = None,
    safety_model: Any | None = None,
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
            safety_model=safety_model,
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
