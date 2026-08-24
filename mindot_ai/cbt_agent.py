"""저장된 CBT 문맥을 분석한 뒤 다음 성찰 질문 또는 왜곡 제안을 생성합니다.

Spring이 매 요청에 전체 ordered question_answers를 전달합니다. FastAPI는 별도
Agent 메모리나 도구 없이 먼저 진행 방향을 분석하고, 일반 질문일 때만 두 번째
Structured Output 호출로 문장을 작성합니다. 세션 완료는 사용자 확인 후 Spring이
처리합니다.
"""

from __future__ import annotations

import json
import logging
import os
import re
from collections.abc import Callable
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Annotated, Any, Literal, TypeVar
from uuid import UUID

from dotenv import load_dotenv
from langchain_core.exceptions import OutputParserException
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator


load_dotenv(Path(__file__).resolve().parent.parent / "infra" / ".env.local")

CBT_MODEL = os.getenv("OPENAI_CBT_MODEL", "gpt-4o-mini")
CBT_PROMPT_VERSION = "cbt-reflection-dev"
CBT_MODEL_OUTPUT_ATTEMPTS = 3
WRITER_PREVIOUS_QUESTION_LIMIT = 4
CBT_DEBUG_LOG_ANALYSIS = os.getenv(
    "CBT_DEBUG_LOG_ANALYSIS",
    "true",
).lower() in {"1", "true", "yes", "on"}


# uvicorn의 기본 INFO handler를 사용해 로컬 서버 콘솔에서 바로 확인합니다.
logger = logging.getLogger("uvicorn.error")


class CbtDraftValidationError(RuntimeError):
    """모델 출력이 서버의 CBT 의미 규칙을 위반했음을 나타냅니다."""


class ApiModel(BaseModel):
    model_config = ConfigDict(
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


ModelT = TypeVar("ModelT", bound=ApiModel)
QuestionCode = Annotated[
    str,
    Field(min_length=2, max_length=50, pattern=r"^[A-Z][A-Z0-9_]*$"),
]


class DistortionCode(str, Enum):
    """distortion_types 초기 데이터와 동일한 12개 안정 코드입니다."""

    ALL_OR_NOTHING_THINKING = "ALL_OR_NOTHING_THINKING"
    CATASTROPHIZING_FORTUNE_TELLING = "CATASTROPHIZING_FORTUNE_TELLING"
    DISQUALIFYING_DISCOUNTING_POSITIVE = "DISQUALIFYING_DISCOUNTING_POSITIVE"
    EMOTIONAL_REASONING = "EMOTIONAL_REASONING"
    LABELING = "LABELING"
    MAGNIFICATION_MINIMIZATION = "MAGNIFICATION_MINIMIZATION"
    MENTAL_FILTER_SELECTIVE_ABSTRACTION = "MENTAL_FILTER_SELECTIVE_ABSTRACTION"
    MIND_READING = "MIND_READING"
    OVERGENERALIZATION = "OVERGENERALIZATION"
    PERSONALIZATION = "PERSONALIZATION"
    SHOULD_MUST_STATEMENTS = "SHOULD_MUST_STATEMENTS"
    TUNNEL_VISION = "TUNNEL_VISION"


DISTORTION_DEFINITIONS: dict[DistortionCode, dict[str, str]] = {
    DistortionCode.ALL_OR_NOTHING_THINKING: {
        "nameKo": "흑백논리",
        "description": "상황을 연속선이 아닌 두 극단 중 하나로 판단함",
    },
    DistortionCode.CATASTROPHIZING_FORTUNE_TELLING: {
        "nameKo": "파국화·미래예측",
        "description": "미래의 부정적 결과를 실제보다 크게 또는 확실하게 예상함",
    },
    DistortionCode.DISQUALIFYING_DISCOUNTING_POSITIVE: {
        "nameKo": "긍정적인 면 무시",
        "description": "긍정적 정보의 의미를 축소하거나 예외로 처리함",
    },
    DistortionCode.EMOTIONAL_REASONING: {
        "nameKo": "감정적 추론",
        "description": "그렇게 느낀다는 이유로 외부 사실도 그렇다고 판단함",
    },
    DistortionCode.LABELING: {
        "nameKo": "낙인찍기",
        "description": "한 사건이나 행동을 사람 전체에 대한 고정된 평가로 확장함",
    },
    DistortionCode.MAGNIFICATION_MINIMIZATION: {
        "nameKo": "과장·축소",
        "description": "부정적 요소는 과장하고 긍정적 요소는 축소함",
    },
    DistortionCode.MENTAL_FILTER_SELECTIVE_ABSTRACTION: {
        "nameKo": "정신적 여과",
        "description": "전체 맥락보다 일부 부정적 정보에만 주의를 고정함",
    },
    DistortionCode.MIND_READING: {
        "nameKo": "독심술",
        "description": "충분한 근거 없이 타인의 생각이나 의도를 단정함",
    },
    DistortionCode.OVERGENERALIZATION: {
        "nameKo": "과잉일반화",
        "description": "한두 사건에서 광범위하고 반복적인 결론을 내림",
    },
    DistortionCode.PERSONALIZATION: {
        "nameKo": "개인화",
        "description": "여러 원인이 있는 결과를 자신과 과도하게 연결함",
    },
    DistortionCode.SHOULD_MUST_STATEMENTS: {
        "nameKo": "당위적 사고",
        "description": "자신·타인·상황에 경직된 반드시 또는 해야 한다 규칙을 적용함",
    },
    DistortionCode.TUNNEL_VISION: {
        "nameKo": "터널 시야",
        "description": "한 관점이나 측면만 보고 다른 관련 정보를 배제함",
    },
}


class DistortionReviewStatus(str, Enum):
    PROPOSED = "PROPOSED"
    CONFIRMED = "CONFIRMED"
    REJECTED = "REJECTED"


class RiskLevel(str, Enum):
    NONE = "NONE"
    REVIEW = "REVIEW"
    CRISIS = "CRISIS"


class RiskReasonCode(str, Enum):
    """Spring과 공유하는 안전 중단 사유의 안정 코드입니다."""

    SELF_HARM = "SELF_HARM"
    SUICIDE = "SUICIDE"
    HARM_TO_OTHERS = "HARM_TO_OTHERS"
    IMMEDIATE_DANGER = "IMMEDIATE_DANGER"
    AMBIGUOUS_SAFETY_SIGNAL = "AMBIGUOUS_SAFETY_SIGNAL"


class CbtResultType(str, Enum):
    QUESTION = "QUESTION"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
    SAFETY_STOP = "SAFETY_STOP"


class CbtApiStatus(str, Enum):
    CONTINUE = "CONTINUE"
    CONFIRM_REQUIRED = "CONFIRM_REQUIRED"
    SAFETY_STOP = "SAFETY_STOP"


class QuestionPurpose(str, Enum):
    SITUATION_REFLECTION = "SITUATION_REFLECTION"
    EMOTION_REFLECTION = "EMOTION_REFLECTION"
    EVIDENCE_FOR = "EVIDENCE_FOR"
    EVIDENCE_AGAINST = "EVIDENCE_AGAINST"
    ALTERNATIVE_VIEW = "ALTERNATIVE_VIEW"
    BALANCED_THOUGHT = "BALANCED_THOUGHT"
    FREE_REFLECTION = "FREE_REFLECTION"


class AnswerDisposition(str, Enum):
    """저장 답변이 CBT 의미 근거로 쓰일 수 있는지를 나타냅니다."""

    SUBSTANTIVE = "SUBSTANTIVE"
    NO_DIRECT_EVIDENCE = "NO_DIRECT_EVIDENCE"
    DIALOGUE_CONTROL = "DIALOGUE_CONTROL"
    UNCLEAR = "UNCLEAR"
    SKIPPED = "SKIPPED"


class SemanticRouteType(str, Enum):
    """질문 목적과 별개인, 질문이 정보를 탐색하는 의미 경로입니다."""

    OBSERVABLE_EVENT_DETAIL = "OBSERVABLE_EVENT_DETAIL"
    DIRECT_WORD_OR_ACTION = "DIRECT_WORD_OR_ACTION"
    EXPECTED_SIGNAL_ABSENCE = "EXPECTED_SIGNAL_ABSENCE"
    CONTRADICTORY_FACT = "CONTRADICTORY_FACT"
    OTHER_PEOPLE_COMPARISON = "OTHER_PEOPLE_COMPARISON"
    CERTAINTY_REASSESSMENT = "CERTAINTY_REASSESSMENT"
    ALTERNATIVE_EXPLANATION = "ALTERNATIVE_EXPLANATION"
    BALANCED_CONCLUSION = "BALANCED_CONCLUSION"
    EMOTION_OR_TRIGGER = "EMOTION_OR_TRIGGER"
    USER_SELECTED_DIRECTION = "USER_SELECTED_DIRECTION"
    OTHER_SPECIFIC = "OTHER_SPECIFIC"


class LatestUserIntent(str, Enum):
    START = "START"
    CBT_ANSWER = "CBT_ANSWER"
    REQUEST_EXAMPLE = "REQUEST_EXAMPLE"
    REQUEST_EXPLANATION = "REQUEST_EXPLANATION"
    RELEVANCE_FEEDBACK = "RELEVANCE_FEEDBACK"
    DIFFICULTY_FEEDBACK = "DIFFICULTY_FEEDBACK"
    REPETITION_FEEDBACK = "REPETITION_FEEDBACK"
    UNCLEAR = "UNCLEAR"


ALTERNATIVE_PURPOSES = {
    QuestionPurpose.ALTERNATIVE_VIEW,
    QuestionPurpose.BALANCED_THOUGHT,
}
CONFIRMATION_REQUIRED_FIELDS = (
    "evidenceForText",
    "evidenceAgainstText",
    "alternativeThoughtText",
    "afterBeliefStrength",
    "finalEmotionIntensity",
    "helpfulnessScore",
    "beforeDistortionReviews",
    "afterDistortionReviews",
)


class CbtRecordContext(ApiModel):
    """Spring이 emotion_records에서 조회해 전달하는 CBT 문맥입니다."""

    record_id: int = Field(alias="recordId", gt=0)
    situation: str | None = Field(default=None, max_length=4_000)
    automatic_thought: str = Field(
        alias="automaticThought",
        min_length=1,
        max_length=4_000,
    )
    primary_emotion_code: str | None = Field(
        default=None,
        alias="primaryEmotionCode",
        max_length=50,
        pattern=r"^[A-Z][A-Z0-9_]*$",
    )
    primary_intensity: int | None = Field(
        default=None,
        alias="primaryIntensity",
        ge=0,
        le=10,
    )
    before_belief_strength: int | None = Field(
        default=None,
        alias="beforeBeliefStrength",
        ge=0,
        le=100,
    )
    context_category: str | None = Field(
        default=None,
        alias="contextCategory",
        max_length=50,
        pattern=r"^[A-Z][A-Z0-9_]*$",
    )


class QuestionAnswer(ApiModel):
    """reflection_sessions.question_answers의 ordered JSONB 원소입니다."""

    question_code: QuestionCode = Field(alias="questionCode")
    question_purpose: QuestionPurpose = Field(alias="questionPurpose")
    semantic_route_type: SemanticRouteType | None = Field(
        default=None,
        alias="semanticRouteType",
    )
    question: str = Field(min_length=1, max_length=1_000)
    answer: str | None = Field(default=None, min_length=1, max_length=4_000)
    asked_at: datetime = Field(alias="askedAt")
    answered_at: datetime | None = Field(default=None, alias="answeredAt")

    @model_validator(mode="after")
    def validate_answer_timestamp_pair(self) -> "QuestionAnswer":
        if (self.answer is None) != (self.answered_at is None):
            raise ValueError(
                "answer and answeredAt must both be present or both be null"
            )
        return self


class ReviewedDistortion(ApiModel):
    """이전 제안에 대한 사용자 검토 상태입니다."""

    code: DistortionCode
    review_status: DistortionReviewStatus = Field(alias="reviewStatus")
    classifier_confidence: float | None = Field(
        default=None,
        alias="classifierConfidence",
        ge=0,
        le=1,
    )


class CbtStartRequest(ApiModel):
    """POST /internal/ai/reflections/start 요청입니다."""

    request_id: UUID = Field(alias="requestId")
    session_id: int = Field(alias="sessionId", gt=0)
    record: CbtRecordContext


class CbtTurnRequest(ApiModel):
    """Spring이 현재 답변을 먼저 저장한 뒤 보내는 다음 턴 요청입니다."""

    request_id: UUID = Field(alias="requestId")
    session_id: int = Field(alias="sessionId", gt=0)
    current_step: QuestionCode = Field(
        alias="currentStep",
        description="마지막으로 답변이 저장된 questionCode",
    )
    record: CbtRecordContext
    question_answers: list[QuestionAnswer] = Field(
        alias="questionAnswers",
        min_length=1,
    )
    before_distortions: list[ReviewedDistortion] = Field(
        default_factory=list,
        alias="beforeDistortions",
        max_length=12,
    )

    @model_validator(mode="after")
    def validate_saved_current_answer(self) -> "CbtTurnRequest":
        if any(item.answer is None for item in self.question_answers):
            raise ValueError(
                "Spring must save the current answer before requesting the next CBT turn"
            )

        question_codes = [item.question_code for item in self.question_answers]
        if len(question_codes) != len(set(question_codes)):
            raise ValueError("questionCode must be unique within a CBT session")

        distortion_codes = [item.code for item in self.before_distortions]
        if len(distortion_codes) != len(set(distortion_codes)):
            raise ValueError("beforeDistortions must not contain duplicate codes")

        if self.current_step != self.question_answers[-1].question_code:
            raise ValueError("currentStep must match the last answered questionCode")

        return self


CbtRequest = CbtStartRequest | CbtTurnRequest


class DistortionProposal(ApiModel):
    code: DistortionCode
    classifier_confidence: float = Field(
        alias="classifierConfidence",
        ge=0,
        le=1,
    )


class SavedAnswerEvidence(ApiModel):
    """완료 조건의 한 의미 영역을 뒷받침하는 저장 답변 근거입니다."""

    source_question_code: QuestionCode = Field(alias="sourceQuestionCode")
    excerpt: str = Field(min_length=1, max_length=300)


class CbtSemanticProgress(ApiModel):
    """1차 분석기가 저장 답변에서 선택한 의미별 대표 근거입니다."""

    evidence_for: SavedAnswerEvidence | None = Field(alias="evidenceFor")
    evidence_against: SavedAnswerEvidence | None = Field(alias="evidenceAgainst")
    alternative_view: SavedAnswerEvidence | None = Field(alias="alternativeView")
    acknowledgement: SavedAnswerEvidence | None


class ConfirmationCoverage(ApiModel):
    """확인 제안 전에 의미 있게 다뤄졌어야 하는 세 가지 영역입니다."""

    evidence_for: SavedAnswerEvidence = Field(alias="evidenceFor")
    evidence_against: SavedAnswerEvidence = Field(alias="evidenceAgainst")
    alternative_view: SavedAnswerEvidence = Field(alias="alternativeView")


class RiskAssessment(ApiModel):
    level: RiskLevel
    reason_code: RiskReasonCode | None = Field(
        alias="reasonCode",
    )

    @model_validator(mode="after")
    def validate_reason_code(self) -> "RiskAssessment":
        if self.level == RiskLevel.NONE and self.reason_code is not None:
            raise ValueError("reasonCode must be null when risk level is NONE")
        if self.level != RiskLevel.NONE and self.reason_code is None:
            raise ValueError("reasonCode is required when risk is REVIEW or CRISIS")
        if (
            self.level == RiskLevel.CRISIS
            and self.reason_code == RiskReasonCode.AMBIGUOUS_SAFETY_SIGNAL
        ):
            raise ValueError(
                "CRISIS requires a specific safety reasonCode"
            )
        if (
            self.level == RiskLevel.REVIEW
            and self.reason_code == RiskReasonCode.IMMEDIATE_DANGER
        ):
            raise ValueError(
                "IMMEDIATE_DANGER requires risk level CRISIS"
            )
        return self


class GeneratedQuestion(ApiModel):
    question_code: QuestionCode = Field(alias="questionCode")
    question_purpose: QuestionPurpose = Field(alias="questionPurpose")
    semantic_route_type: SemanticRouteType = Field(alias="semanticRouteType")
    question: str = Field(min_length=1, max_length=500)


class CbtQuestionPlan(ApiModel):
    """1차 분석 모델이 정하는 다음 질문의 의미와 금지 경로입니다."""

    question_purpose: QuestionPurpose = Field(alias="questionPurpose")
    semantic_route_type: SemanticRouteType = Field(alias="semanticRouteType")
    latest_user_intent: LatestUserIntent = Field(
        alias="latestUserIntent",
        description="Must equal latestUserIntentHint when that hint is present.",
    )
    question_goal: str = Field(
        alias="questionGoal",
        min_length=1,
        max_length=300,
        description=(
            "A four-line plan using 확인된 사실, 핵심 주장, 미해결 간극, "
            "질문 목표. It must target one unresolved inference and match "
            "questionPurpose."
        ),
    )
    preface_goal: str | None = Field(alias="prefaceGoal", max_length=300)
    grounding_question_codes: list[str] = Field(
        alias="groundingQuestionCodes",
        max_length=5,
    )
    avoid_topics: list[str] = Field(alias="avoidTopics", max_length=8)

    @model_validator(mode="after")
    def validate_plan(self) -> "CbtQuestionPlan":
        # 1차 모델이 계획의 마지막에 습관적으로 붙이는 물음표는 의미를
        # 바꾸지 않으므로 제거합니다. 이 형식 문제만으로 502를 만들지 않습니다.
        self.question_goal = self.question_goal.replace("?", "").strip()
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


class QuestionWordingDraft(ApiModel):
    """2차 작성 모델이 생성하는 선택적 머리말과 실제 질문입니다."""

    preface: str | None = Field(min_length=1, max_length=140)
    question: str = Field(min_length=1, max_length=350)

    @model_validator(mode="after")
    def validate_preface_and_question(self) -> "QuestionWordingDraft":
        if self.preface is not None and "?" in self.preface:
            raise ValueError("preface must be a statement, not a question")
        if self.question.count("?") != 1:
            raise ValueError("question must contain exactly one question mark")
        return self

    def rendered_message(self) -> str:
        if self.preface is None:
            return self.question
        return f"{self.preface} {self.question}"


class ReflectionOutcomeDraft(ApiModel):
    """사용자가 확인·수정할 CBT 질문 후 결과 초안입니다."""

    evidence_for_text: str | None = Field(
        alias="evidenceForText",
        max_length=4_000,
    )
    evidence_against_text: str | None = Field(
        alias="evidenceAgainstText",
        max_length=4_000,
    )
    alternative_thought_text: str = Field(
        alias="alternativeThoughtText",
        min_length=1,
        max_length=4_000,
    )
    after_distortions: list[DistortionProposal] = Field(
        alias="afterDistortions",
        max_length=12,
    )

    @model_validator(mode="after")
    def validate_unique_after_distortions(self) -> "ReflectionOutcomeDraft":
        codes = [item.code for item in self.after_distortions]
        if len(codes) != len(set(codes)):
            raise ValueError("afterDistortions must not contain duplicate codes")
        return self


class CbtConfirmationDraft(ApiModel):
    """사용자에게 제안할 왜곡 유형과 성찰 결과의 근거 묶음입니다."""

    before_distortions: list[DistortionProposal] = Field(
        alias="beforeDistortions",
        min_length=1,
        max_length=12,
    )
    outcome_draft: ReflectionOutcomeDraft = Field(alias="outcomeDraft")
    acknowledgement_evidence: str = Field(
        alias="acknowledgementEvidence",
        min_length=1,
        max_length=300,
    )
    acknowledgement_source_question_code: QuestionCode = Field(
        alias="acknowledgementSourceQuestionCode",
    )
    confirmation_coverage: ConfirmationCoverage = Field(
        alias="confirmationCoverage"
    )
    proposal_message: str = Field(
        alias="proposalMessage",
        min_length=1,
        max_length=1_000,
    )

    @model_validator(mode="after")
    def validate_unique_before_distortions(self) -> "CbtConfirmationDraft":
        codes = [item.code for item in self.before_distortions]
        if len(codes) != len(set(codes)):
            raise ValueError("beforeDistortions must not contain duplicate codes")
        return self


class CbtAnalysisDraft(ApiModel):
    """1차 분석 모델이 strict Structured Output으로 반환하는 한 턴 계획입니다."""

    result_type: CbtResultType = Field(alias="resultType")
    semantic_progress: CbtSemanticProgress = Field(alias="semanticProgress")
    question_plan: CbtQuestionPlan | None = Field(alias="questionPlan")
    confirmation: CbtConfirmationDraft | None
    risk: RiskAssessment

    @model_validator(mode="after")
    def validate_result_shape(self) -> "CbtAnalysisDraft":
        if self.result_type == CbtResultType.QUESTION:
            if self.question_plan is None:
                raise ValueError("QUESTION requires questionPlan")
            if self.confirmation is not None:
                raise ValueError("QUESTION must not include confirmation")
            if self.risk.level != RiskLevel.NONE:
                raise ValueError("QUESTION is not allowed when a safety signal exists")

        elif self.result_type == CbtResultType.CONFIRMATION_REQUIRED:
            if self.question_plan is not None:
                raise ValueError(
                    "CONFIRMATION_REQUIRED must not include questionPlan"
                )
            if self.confirmation is None:
                raise ValueError("CONFIRMATION_REQUIRED requires confirmation")
            if self.risk.level != RiskLevel.NONE:
                raise ValueError(
                    "CONFIRMATION_REQUIRED is not allowed when a safety signal exists"
                )

        else:
            if self.question_plan is not None or self.confirmation is not None:
                raise ValueError("SAFETY_STOP must not include CBT output")
            if self.risk.level == RiskLevel.NONE:
                raise ValueError("SAFETY_STOP requires REVIEW or CRISIS risk")

        return self


class CbtQuestionOrSafetyDraft(ApiModel):
    """확인 조건이 부족할 때 confirmation을 표현할 수 없는 분석 결과입니다."""

    result_type: Literal[
        CbtResultType.QUESTION,
        CbtResultType.SAFETY_STOP,
    ] = Field(alias="resultType")
    semantic_progress: CbtSemanticProgress = Field(alias="semanticProgress")
    question_plan: CbtQuestionPlan | None = Field(alias="questionPlan")
    risk: RiskAssessment

    @model_validator(mode="after")
    def validate_result_shape(self) -> "CbtQuestionOrSafetyDraft":
        if self.result_type == CbtResultType.QUESTION:
            if self.question_plan is None:
                raise ValueError("QUESTION requires questionPlan")
            if self.risk.level != RiskLevel.NONE:
                raise ValueError(
                    "QUESTION is not allowed when a safety signal exists"
                )
        else:
            if self.question_plan is not None:
                raise ValueError("SAFETY_STOP must not include questionPlan")
            if self.risk.level == RiskLevel.NONE:
                raise ValueError(
                    "SAFETY_STOP requires REVIEW or CRISIS risk"
                )
        return self

    def to_analysis_draft(self) -> CbtAnalysisDraft:
        return CbtAnalysisDraft(
            result_type=self.result_type,
            semantic_progress=self.semantic_progress,
            question_plan=self.question_plan,
            confirmation=None,
            risk=self.risk,
        )


class AnalysisMeta(ApiModel):
    model: str
    prompt_version: str = Field(alias="promptVersion")


class CbtTurnResponse(ApiModel):
    """FastAPI가 Spring에 반환하는 내부 API 응답입니다."""

    request_id: UUID = Field(alias="requestId")
    status: CbtApiStatus
    next_question: GeneratedQuestion | None = Field(alias="nextQuestion")
    before_distortions: list[DistortionProposal] = Field(alias="beforeDistortions")
    outcome_draft: ReflectionOutcomeDraft | None = Field(alias="outcomeDraft")
    confirmation_required_fields: list[str] = Field(
        alias="confirmationRequiredFields"
    )
    acknowledgement_evidence: str | None = Field(alias="acknowledgementEvidence")
    acknowledgement_source_question_code: str | None = Field(
        alias="acknowledgementSourceQuestionCode"
    )
    proposal_message: str | None = Field(alias="proposalMessage")
    risk: RiskAssessment
    meta: AnalysisMeta


ANALYSIS_PROMPT = """
<role>
Plan one Korean CBT self-reflection turn. Decide safety, meaningful completion, or
one next question direction. Do not diagnose, write the final question, or try to
prove automaticThought false.
</role>

<input>
Read latestInteraction first, then the ordered questionAnswers.

latestUserIntentHint is authoritative when it identifies an explicit dialogue
request or feedback. latestAnswerMeaningHint and turnFlags are conservative
server-derived facts. blockedRoutes are hard semantic exclusions.

latestSafetyHint is only a candidate. Confirm its meaning from the original user
text. Do not stop for a negated, quoted, historical, hypothetical, or otherwise
non-current harm statement.
</input>

<interpret>
A previous questionPurpose records what was asked, not what the answer established.
Interpret every answer by its actual meaning and answerDisposition.

DIALOGUE_CONTROL, UNCLEAR, and SKIPPED fill no semantic domain.
NO_DIRECT_EVIDENCE is never evidenceFor; it resolves direct-support search and may
be evidenceAgainst when it meaningfully reduces certainty.

Fill semanticProgress with at most one exact saved-answer excerpt per domain:
evidenceFor, evidenceAgainst, alternativeView, and acknowledgement. The last means
the user distinguished fact from inference or meaningfully reassessed certainty.
Do not copy one excerpt across unrelated domains. questionPurpose does not decide
which domain an answer can fill.

Separate:
- observed facts,
- the core claim in automaticThought,
- the unresolved inference connecting them,
- and routes already answered, rejected, or blocked.
</interpret>

<feedback>
For RELEVANCE_FEEDBACK or REPETITION_FEEDBACK:
- treat the latest answer only as dialogue feedback;
- do not use its questionCode as grounding;
- block the rejected route's target, comparison, inferred cause, evidence source,
  and assumed causal link;
- choose a genuinely different information route.

Changing only questionPurpose or wording is not a new route. The same purpose may
remain when a different semanticRouteType serves it. A semanticRouteType in
blockedRoutes is forbidden. Do not label a known route OTHER_SPECIFIC to evade a
block.

For REQUEST_EXPLANATION, REQUEST_EXAMPLE, or DIFFICULTY_FEEDBACK, use prefaceGoal
to reply briefly, then continue with one relevant and answerable point.
</feedback>

<progress>
Choose the single unresolved point with the highest information value. The answer
must be capable of changing understanding, confidence, or the next decision.

Purpose meanings:
- SITUATION_REFLECTION: observable fact needed to separate event from inference.
- EMOTION_REFLECTION: the user's emotion or trigger only when genuinely unclear.
- EVIDENCE_FOR: direct fact making the core claim more likely.
- EVIDENCE_AGAINST: contradiction, missing certainty, absent expected support, or
  another fact making the core claim less certain.
- ALTERNATIVE_VIEW: another interpretation consistent with established facts.
- BALANCED_THOUGHT: a fair conclusion containing both evidence and uncertainty.
- FREE_REFLECTION: only when no specific purpose fits and the user must choose what
  matters or where to continue.

There is no fixed order. Do not:
- follow an adjacent detail merely because it was mentioned;
- ask for another person's hidden thoughts or motives;
- repeat or paraphrase an answered or blocked route;
- seek another direct signal after the user meaningfully reported none;
- demand a positive example or force optimism;
- assume automaticThought is completely false.
</progress>

<question_plan>
For QUESTION, questionGoal must contain exactly one new semantic target and use:

확인된 사실: ...
핵심 주장: ...
미해결 간극: ...
질문 목표: ...

It is a Korean plan of at most 300 characters, not question wording.
questionPurpose and semanticRouteType must both match 질문 목표. semanticRouteType
names the information route, independently of questionPurpose.

groundingQuestionCodes may reference only substantive saved answers that directly
support the plan. Put answered, irrelevant, repeated, and blocked routes in
avoidTopics. Use prefaceGoal only for a necessary reply or transition.
</question_plan>

<decision>
Return SAFETY_STOP only for a contextually plausible current self-harm, suicide,
harm to others, or immediate danger signal. Profanity, sadness, frustration,
refusal, difficulty, and dialogue criticism alone are not safety signals.

Return CONFIRMATION_REQUIRED only when confirmationAllowed and semanticProgress
contains exact saved-answer evidence that semantically provides:
- support for automaticThought,
- contrary evidence or uncertainty,
- the user's own alternative or balanced view,
- and a distinction between fact and inference or a meaningful reassessment of
  confidence.

The user need not declare the original thought false. "Possible but not certain
from current facts" can qualify. Use only exact saved excerpts and supplied
distortionDefinitions. Never use dialogue feedback as coverage, invent scores,
re-propose a rejected distortion, or describe a diagnosis.

Otherwise return QUESTION. Never return COMPLETE.
</decision>

<example>
Situation: the manager frowned during the user's report but smiled at others.
automaticThought: "The manager is upset because of me."
The user rejects a question about when or why the manager smiled at others as
irrelevant.

Mark RELEVANCE_FEEDBACK and block that comparison-and-motive route. EVIDENCE_FOR
may remain only through a genuinely new route, such as whether any direct words or
actions linked dissatisfaction to the user or report. If the user then reports
there were none, do not ask for another signal; choose the next unresolved issue
from uncertainty, a grounded alternative, or a balanced conclusion.
</example>
""".strip()


WRITER_PROMPT = """
<role>
Express the supplied CBT plan as one natural Korean response. Do not re-plan,
classify, diagnose, add evidence, or change questionPurpose or semanticRouteType.
</role>

<preface>
If prefaceRequired is false, return preface=null.
If true, write exactly one brief statement that fulfills prefaceGoal before the
question. It may acknowledge a dialogue problem, explain relevance, simplify the
task, or give a few neutral examples when requested.

Do not use a question mark, generic repeated empathy, or introduce a new topic in
the preface.
</preface>

<question>
Ask only the single semantic point in questionGoal. Respect questionPurpose,
semanticRouteType, groundingAnswers, avoidTopics, and previousQuestions.

Use simple, natural Korean honorifics and exactly one "?". Do not use "당신",
clinical or sharp language, multiple questions, another person's hidden motive,
unsupported assumptions, forced positivity, or a repeated question.
</question>
""".strip()


_llm: ChatOpenAI | None = None
_analysis_models: dict[bool, Any] = {}
_writer_model: Any | None = None


def _get_llm() -> ChatOpenAI:
    global _llm
    if _llm is None:
        _llm = ChatOpenAI(
            model=CBT_MODEL,
            temperature=0.0,
            timeout=30.0,
            max_retries=2,
            use_responses_api=True,
        )
    return _llm


def _get_analysis_model(confirmation_allowed: bool) -> Any:
    model = _analysis_models.get(confirmation_allowed)
    if model is None:
        schema = (
            CbtAnalysisDraft
            if confirmation_allowed
            else CbtQuestionOrSafetyDraft
        )
        model = _get_llm().with_structured_output(
            schema,
            method="json_schema",
            strict=True,
        )
        _analysis_models[confirmation_allowed] = model
    return model


def _confirmation_candidate_available(request: CbtRequest) -> bool:
    """완료 출력 스키마를 노출할지 정하는 거친 선행 조건입니다.

    실제 완료 가능 여부는 ``semanticProgress``의 저장 답변 의미를 별도로
    검증합니다. 질문 목적을 완료 근거로 사용하지 않습니다.
    """

    if not isinstance(request, CbtTurnRequest):
        return False
    purposes = {item.question_purpose for item in request.question_answers}
    return (
        QuestionPurpose.EVIDENCE_FOR in purposes
        and QuestionPurpose.EVIDENCE_AGAINST in purposes
        and bool(ALTERNATIVE_PURPOSES & purposes)
    )


def _get_writer_model() -> Any:
    global _writer_model
    if _writer_model is None:
        _writer_model = _get_llm().with_structured_output(
            QuestionWordingDraft,
            method="json_schema",
            strict=True,
        )
    return _writer_model


def _classify_explicit_user_intent(
    answer: str | None,
) -> LatestUserIntent | None:
    """문장 표면에 명시된 대화 제어 의도만 보수적으로 판별합니다."""

    normalized = " ".join((answer or "").lower().split())
    repetition_markers = (
        "같은 질문",
        "똑같은 질문",
        "계속 물어",
        "또 물어",
        "반복",
        "빙빙",
        "진전이 없",
        "아까 물어",
    )
    relevance_markers = (
        "무슨 상관",
        "뭔 상관",
        "왜 중요",
        "중요한 게 아니",
        "중요한게 아니",
        "그게 중요해",
        "그딴게 중요",
        "그딴 게 중요",
        "상관없",
    )
    example_request_markers = (
        "예를 들면 어떤",
        "예를 들어 어떤",
        "예시를 들어",
        "예시 좀",
        "예를 좀",
    )
    explanation_request_markers = (
        "왜 물어",
        "왜 묻",
        "이 질문을 왜",
        "그걸 왜",
        "이걸 왜",
    )
    difficulty_markers = (
        "질문이 어려",
        "질문이 너무 어려",
        "질문 너무 어려",
        "너무 복잡",
        "어떻게 답",
        "무슨 말인지 모르",
        "이해가 안 돼",
        "이해가 안돼",
    )

    marker_groups = (
        (LatestUserIntent.REPETITION_FEEDBACK, repetition_markers),
        (LatestUserIntent.RELEVANCE_FEEDBACK, relevance_markers),
        (LatestUserIntent.REQUEST_EXAMPLE, example_request_markers),
        (LatestUserIntent.REQUEST_EXPLANATION, explanation_request_markers),
        (LatestUserIntent.DIFFICULTY_FEEDBACK, difficulty_markers),
    )
    for intent, markers in marker_groups:
        if any(marker in normalized for marker in markers):
            return intent
    return None


def _blocked_route_for(item: QuestionAnswer) -> dict[str, Any]:
    return {
        "sourceQuestionCode": item.question_code,
        "questionPurpose": item.question_purpose.value,
        "semanticRouteType": (
            item.semantic_route_type.value
            if item.semantic_route_type is not None
            else None
        ),
        "rejectedQuestion": item.question,
        "blockedSemantics": [
            "target",
            "comparison",
            "inferredCause",
            "evidenceSource",
            "assumedCausalLink",
        ],
    }


def _blocked_routes_from_history(
    question_answers: list[QuestionAnswer],
) -> list[dict[str, Any]]:
    return [
        _blocked_route_for(item)
        for item in question_answers
        if _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
    ]


def _blocked_semantic_route_types(
    question_answers: list[QuestionAnswer],
) -> set[SemanticRouteType]:
    return {
        item.semantic_route_type
        for item in question_answers
        if item.semantic_route_type is not None
        and _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
    }


def _is_explicit_no_direct_evidence_answer(answer: str | None) -> bool:
    """직접 근거가 없다는 명시적 답변만 보수적으로 감지합니다."""

    normalized = " ".join((answer or "").lower().split())
    double_negation_patterns = (
        r"없(?:는|었던)?\s*건\s*아니",
        r"없(?:는|었던)?\s*것은\s*아니",
        r"없지(?:는|\s*)\s*않",
        r"없진\s*않",
    )
    if any(re.search(pattern, normalized) for pattern in double_negation_patterns):
        return False

    absence = r"(?:없어|없다|없었|없음|없는\s*것\s*같|딱히\s*없|전혀\s*없)"
    patterns = (
        rf"(?:직접(?:적인)?\s*)?(?:증거|근거|신호).{{0,15}}{absence}",
        rf"표정\s*외(?:에|에는|엔)?.{{0,35}}{absence}",
        rf"말(?:하거나|이나)?.{{0,10}}행동.{{0,20}}{absence}",
        rf"(?:다른|추가).{{0,10}}(?:증거|근거|신호).{{0,15}}{absence}",
    )
    return any(re.search(pattern, normalized) for pattern in patterns)


def _has_explicit_no_direct_evidence(item: QuestionAnswer) -> bool:
    if _is_explicit_no_direct_evidence_answer(item.answer):
        return True
    direct_route = item.semantic_route_type in {
        SemanticRouteType.DIRECT_WORD_OR_ACTION,
        SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
    }
    if item.question_purpose != QuestionPurpose.EVIDENCE_FOR and not direct_route:
        return False
    normalized = " ".join((item.answer or "").lower().split())
    return bool(
        re.fullmatch(
            r"(?:아니[,\s]*)?(?:음[,\s]*)?(?:딱히\s*)?"
            r"(?:없어|없어요|없다|없었어|없었어요|없는\s*것\s*같아)"
            r"[.!\s]*",
            normalized,
        )
    )


def _is_explicit_dialogue_refusal(answer: str | None) -> bool:
    """짧고 명시적인 답변 거부·좌절 표현만 보수적으로 감지합니다."""

    normalized = " ".join((answer or "").lower().split())
    refusal_markers = (
        "하기 싫",
        "답하기 싫",
        "대답하기 싫",
        "질문 싫",
        "그만 물",
        "그만해",
        "짜증나",
        "피곤하게",
        "귀찮",
    )
    if any(marker in normalized for marker in refusal_markers):
        return True

    profanity_markers = ("씨발", "시발", "ㅅㅂ", "존나", "좆", "병신")
    reporting_markers = ("라고", "말했", "욕했", "들었", "불렀")
    return (
        len(normalized) <= 40
        and any(marker in normalized for marker in profanity_markers)
        and not any(marker in normalized for marker in reporting_markers)
    )


def _classify_answer_disposition(
    value: QuestionAnswer | str | None,
) -> AnswerDisposition:
    """추론하지 않고 명시적인 비실질 답변만 보수적으로 분리합니다."""

    item = value if isinstance(value, QuestionAnswer) else None
    answer = item.answer if item is not None else value
    normalized = " ".join((answer or "").lower().split()).strip()
    compact = re.sub(r"[\s.!?~…ㅠㅜㅋㅎ]+", "", normalized)

    skip_markers = (
        "넘어갈래",
        "넘어가자",
        "패스할게",
        "패스하자",
        "답하지 않을",
        "대답하지 않을",
        "답 안 할",
        "대답 안 할",
    )
    if not normalized or any(marker in normalized for marker in skip_markers):
        return AnswerDisposition.SKIPPED

    if (
        _classify_explicit_user_intent(normalized) in DIALOGUE_CONTROL_INTENTS
        or _is_explicit_dialogue_refusal(normalized)
    ):
        return AnswerDisposition.DIALOGUE_CONTROL

    if (
        _has_explicit_no_direct_evidence(item)
        if item is not None
        else _is_explicit_no_direct_evidence_answer(normalized)
    ):
        return AnswerDisposition.NO_DIRECT_EVIDENCE

    unclear_forms = {
        "모르겠어",
        "모르겠어요",
        "잘모르겠어",
        "잘모르겠어요",
        "글쎄",
        "글쎄요",
        "생각이안나",
        "생각이안나요",
        "잘생각이안나",
        "잘생각이안나요",
    }
    if compact in unclear_forms:
        return AnswerDisposition.UNCLEAR

    return AnswerDisposition.SUBSTANTIVE


def _explicit_safety_hint(request: CbtRequest) -> RiskReasonCode | None:
    """최신 답변의 명백한 위험 표현만 분석 전에 힌트로 제공합니다."""

    if not isinstance(request, CbtTurnRequest):
        return None
    answer = " ".join((request.question_answers[-1].answer or "").lower().split())
    marker_groups = (
        (
            RiskReasonCode.SUICIDE,
            ("자살", "죽고 싶", "죽어 버리고", "죽어버리고", "목숨을 끊"),
        ),
        (
            RiskReasonCode.SELF_HARM,
            (
                "자해",
                "나를 해치",
                "나를 해칠",
                "내 몸을 해치",
                "내 몸을 해칠",
                "스스로를 해치",
                "스스로를 해칠",
            ),
        ),
        (
            RiskReasonCode.HARM_TO_OTHERS,
            (
                "죽이고 싶",
                "죽이겠",
                "누군가를 죽일",
                "사람을 죽일",
                "너를 죽일",
                "해치고 싶",
                "해치겠",
                "누군가를 해칠",
                "다른 사람을 해칠",
                "너를 해칠",
            ),
        ),
    )
    for reason, markers in marker_groups:
        if any(marker in answer for marker in markers):
            return reason
    return None


def _is_clearly_non_current_user_safety_reference(request: CbtRequest) -> bool:
    """명백한 부정·제3자 인용·과거 종료·가정 표현만 보수적으로 감지합니다."""

    if not isinstance(request, CbtTurnRequest):
        return False
    normalized = " ".join((request.question_answers[-1].answer or "").lower().split())
    harm = r"(?:죽고\s*싶|자살|자해|죽이|해치)"

    # 이중 부정은 안전하다고 확정할 수 없으므로 분석 모델의 문맥 판단에 둡니다.
    if re.search(r"(?:없|않)(?:는|은|었던)?\s*건\s*아니", normalized):
        return False

    negated = re.search(
        rf"{harm}.{{0,45}}(?:전혀\s*|하나도\s*)?(?:없|아니)",
        normalized,
    )
    historical_and_resolved = re.search(
        rf"(?:예전|과거|전에는|한때).{{0,60}}{harm}.{{0,60}}"
        r"(?:지금|현재|이제).{0,25}(?:없|아니)",
        normalized,
    )
    hypothetical = re.search(
        rf"(?:만약|가령|예를\s*들|예시).{{0,45}}{harm}",
        normalized,
    )

    third_party = re.search(
        rf"(?:친구|가족|지인|동료|팀장|그\s*사람|상대|부모|형제|자매)"
        rf"(?:가|이|은|는).{{0,45}}{harm}.{{0,30}}"
        r"(?:말|얘기|이야기|문자|연락)",
        normalized,
    )
    self_harm_statement = re.search(
        rf"(?:나도|저도|나는|저는|내가|제가).{{0,35}}{harm}",
        normalized,
    )
    quoted_only = third_party is not None and self_harm_statement is None
    return any((negated, historical_and_resolved, hypothetical, quoted_only))


def _contextual_safety_hint(request: CbtRequest) -> RiskReasonCode | None:
    """명백한 비현재·제3자 문맥을 제외한 위험 후보만 반환합니다."""

    hint = _explicit_safety_hint(request)
    if hint is not None and _is_clearly_non_current_user_safety_reference(request):
        return None
    return hint


def _analysis_turn_flags(
    request: CbtRequest,
    intent: LatestUserIntent | None,
) -> tuple[str | None, dict[str, bool]]:
    if not isinstance(request, CbtTurnRequest):
        return None, {
            "latestAnswerIsDialogueControl": False,
            "directEvidenceRouteResolved": False,
        }

    latest = request.question_answers[-1]
    direct_evidence_route_resolved = _has_explicit_no_direct_evidence(latest)
    latest_answer_is_dialogue_control = (
        intent in DIALOGUE_CONTROL_INTENTS
        or _is_explicit_dialogue_refusal(latest.answer)
    )
    answer_meaning_hint = None
    if direct_evidence_route_resolved:
        answer_meaning_hint = "NO_ADDITIONAL_DIRECT_EVIDENCE"
    elif _is_explicit_dialogue_refusal(latest.answer):
        answer_meaning_hint = "DIALOGUE_REFUSAL_OR_FRUSTRATION"
    elif _is_clearly_non_current_user_safety_reference(request):
        answer_meaning_hint = "NON_CURRENT_OR_THIRD_PARTY_SAFETY_REFERENCE"

    return answer_meaning_hint, {
        "latestAnswerIsDialogueControl": latest_answer_is_dialogue_control,
        "directEvidenceRouteResolved": direct_evidence_route_resolved,
    }


def _build_analysis_payload(
    request: CbtRequest,
) -> dict[str, Any]:
    question_answers: list[dict[str, Any]] = []
    before_distortions: list[dict[str, Any]] = []

    if isinstance(request, CbtTurnRequest):
        question_answers = [
            {
                **item.model_dump(by_alias=True, mode="json"),
                "answerDisposition": _classify_answer_disposition(item).value,
            }
            for item in request.question_answers
        ]
        before_distortions = [
            item.model_dump(by_alias=True, mode="json")
            for item in request.before_distortions
        ]

    latest_user_intent_hint = _explicit_feedback_intent(request)
    latest_safety_hint = _contextual_safety_hint(request)
    latest_answer_meaning_hint, turn_flags = _analysis_turn_flags(
        request,
        latest_user_intent_hint,
    )
    confirmation_allowed = _confirmation_candidate_available(request)
    blocked_routes = (
        _blocked_routes_from_history(request.question_answers)
        if isinstance(request, CbtTurnRequest)
        else []
    )

    return {
        "requestType": (
            "TURN" if isinstance(request, CbtTurnRequest) else "START"
        ),
        "confirmationAllowed": confirmation_allowed,
        "record": request.record.model_dump(by_alias=True, mode="json"),
        "latestInteraction": (
            question_answers[-1] if question_answers else None
        ),
        "latestSafetyHint": (
            latest_safety_hint.value if latest_safety_hint is not None else None
        ),
        "latestUserIntentHint": (
            latest_user_intent_hint.value
            if latest_user_intent_hint is not None
            else None
        ),
        "latestAnswerMeaningHint": latest_answer_meaning_hint,
        "turnFlags": turn_flags,
        "blockedRoutes": blocked_routes,
        "questionAnswers": question_answers,
        "beforeDistortions": before_distortions,
        "distortionDefinitions": (
            [
                {
                    "code": code.value,
                    **DISTORTION_DEFINITIONS[code],
                }
                for code in DistortionCode
            ]
            if confirmation_allowed
            else []
        ),
    }


def _build_writer_payload(
    request: CbtRequest,
    plan: CbtQuestionPlan,
) -> dict[str, Any]:
    previous_questions: list[dict[str, Any]] = []
    grounding_answers: list[dict[str, Any]] = []
    latest_interaction: dict[str, Any] | None = None

    if isinstance(request, CbtTurnRequest):
        previous_questions = [
            {
                "questionPurpose": item.question_purpose.value,
                "semanticRouteType": (
                    item.semantic_route_type.value
                    if item.semantic_route_type is not None
                    else None
                ),
                "question": item.question,
            }
            for item in request.question_answers[
                max(
                    0,
                    len(request.question_answers)
                    - WRITER_PREVIOUS_QUESTION_LIMIT
                    - 1,
                ):-1
            ]
        ]
        grounding_codes = set(plan.grounding_question_codes)
        grounding_answers = [
            {
                "questionCode": item.question_code,
                "questionPurpose": item.question_purpose.value,
                "semanticRouteType": (
                    item.semantic_route_type.value
                    if item.semantic_route_type is not None
                    else None
                ),
                "question": item.question,
                "answer": item.answer,
            }
            for item in request.question_answers
            if item.question_code in grounding_codes
        ]
        latest = request.question_answers[-1]
        latest_interaction = {
            "questionPurpose": latest.question_purpose.value,
            "semanticRouteType": (
                latest.semantic_route_type.value
                if latest.semantic_route_type is not None
                else None
            ),
            "question": latest.question,
            "answer": latest.answer,
        }

    return {
        "record": {
            "situation": request.record.situation,
            "automaticThought": request.record.automatic_thought,
            "primaryEmotionCode": request.record.primary_emotion_code,
        },
        "latestInteraction": latest_interaction,
        "groundingAnswers": grounding_answers,
        "previousQuestions": previous_questions,
        "prefaceRequired": plan.preface_goal is not None,
        "plan": plan.model_dump(by_alias=True, mode="json"),
    }


def _explicit_feedback_intent(
    request: CbtRequest,
) -> LatestUserIntent | None:
    if not isinstance(request, CbtTurnRequest):
        return None
    return _classify_explicit_user_intent(
        request.question_answers[-1].answer
    )


CONVERSATION_FEEDBACK_INTENTS = {
    LatestUserIntent.RELEVANCE_FEEDBACK,
    LatestUserIntent.REPETITION_FEEDBACK,
}

DIALOGUE_CONTROL_INTENTS = {
    *CONVERSATION_FEEDBACK_INTENTS,
    LatestUserIntent.DIFFICULTY_FEEDBACK,
    LatestUserIntent.REQUEST_EXAMPLE,
    LatestUserIntent.REQUEST_EXPLANATION,
}

QUESTION_GOAL_LABELS = (
    "확인된 사실:",
    "핵심 주장:",
    "미해결 간극:",
    "질문 목표:",
)


def _repeats_resolved_direct_evidence(text: str) -> bool:
    """해결된 직접 근거 탐색을 명백히 다시 여는 표현만 감지합니다."""

    normalized = " ".join(text.lower().split())
    question_target = normalized.split("질문 목표:", 1)[-1]
    if any(
        marker in question_target
        for marker in (
            "확신",
            "확실",
            "불확실",
            "가능성",
            "대안",
            "다른 해석",
            "균형",
            "결론",
        )
    ):
        return False

    evidence_terms = r"(?:말|행동|발언|언급|표현|지목|신호|증거|근거|반응)"
    repeat_patterns = (
        rf"(?:다른|추가|또\s*다른).{{0,20}}{evidence_terms}.{{0,20}}"
        r"(?:있|했|보였|찾|확인)",
        rf"{evidence_terms}.{{0,15}}(?:더|또|추가로).{{0,10}}"
        r"(?:있|했|보였|찾|확인)",
        rf"(?:직접|명시적).{{0,12}}{evidence_terms}.{{0,15}}"
        r"(?:있|했|보였|찾|확인)",
    )
    return any(re.search(pattern, question_target) for pattern in repeat_patterns)


def _semantic_route_terms(text: str) -> set[str]:
    """거부 경로의 명백한 재사용만 찾기 위한 보수적 한국어 어간 집합입니다."""

    stopwords = {
        "무엇",
        "어떤",
        "어떻게",
        "다른",
        "있",
        "없",
        "생각",
        "확인",
        "질문",
        "목표",
        "사실",
        "주장",
        "간극",
    }
    suffixes = (
        "에게서는",
        "에게는",
        "에서는",
        "으로는",
        "이라고",
        "이라는",
        "에게",
        "에서",
        "으로",
        "한테",
        "들은",
        "였던",
        "했던",
        "았던",
        "었던",
        "나요",
        "까요",
        "인가요",
        "은",
        "는",
        "이",
        "가",
        "을",
        "를",
        "의",
        "도",
    )
    terms: set[str] = set()
    for raw in re.findall(r"[가-힣]{2,}", text.lower()):
        term = raw
        for suffix in suffixes:
            if term.endswith(suffix) and len(term) - len(suffix) >= 1:
                term = term[: -len(suffix)]
                break
        if term not in stopwords and len(term) >= 1:
            terms.add(term)
    return terms


def _reuses_latest_rejected_route(
    text: str,
    request: CbtRequest,
) -> bool:
    """관련성·반복 피드백 직전 질문과 명백히 같은 경로인지 판별합니다."""

    if not isinstance(request, CbtTurnRequest):
        return False
    if _explicit_feedback_intent(request) not in CONVERSATION_FEEDBACK_INTENTS:
        return False

    # 신규 기록은 구조화된 semanticRouteType으로 검사합니다. 단어 겹침은
    # 경로 메타데이터가 없는 과거 JSONB 기록에서만 사용하는 fallback입니다.
    if request.question_answers[-1].semantic_route_type is not None:
        return False

    target = text.split("질문 목표:", 1)[-1]
    rejected = request.question_answers[-1].question
    overlap = _semantic_route_terms(target) & _semantic_route_terms(rejected)
    return len(overlap) >= 2


def _resolved_feedback_intent(
    request: CbtRequest,
    plan: CbtQuestionPlan,
) -> LatestUserIntent | None:
    explicit = _explicit_feedback_intent(request)
    if explicit in CONVERSATION_FEEDBACK_INTENTS:
        return explicit
    if plan.latest_user_intent in CONVERSATION_FEEDBACK_INTENTS:
        return plan.latest_user_intent
    return None


def _apply_feedback_constraints(
    request: CbtRequest,
    plan: CbtQuestionPlan,
) -> CbtQuestionPlan:
    """명시적 대화 의도를 고정하고, 거부된 의미 경로를 방어적으로 차단합니다."""

    if not isinstance(request, CbtTurnRequest):
        return plan

    explicit_intent = _explicit_feedback_intent(request)
    if explicit_intent is None:
        return plan

    preface_goals = {
        LatestUserIntent.RELEVANCE_FEEDBACK: (
            "Write this natural transition or a close equivalent: "
            "'핵심에서 벗어난 질문이었으니 다른 방향으로 살펴볼게요.'"
        ),
        LatestUserIntent.REPETITION_FEEDBACK: (
            "Write a short natural transition equivalent to: "
            "'같은 내용을 되물었네요. 이번에는 다른 방향으로 살펴볼게요.'"
        ),
        LatestUserIntent.DIFFICULTY_FEEDBACK: (
            "Briefly acknowledge the difficulty and introduce a simpler next step."
        ),
        LatestUserIntent.REQUEST_EXAMPLE: (
            "Give a few brief neutral examples relevant to the new question goal."
        ),
        LatestUserIntent.REQUEST_EXPLANATION: (
            "Briefly explain how the new question helps distinguish observed facts "
            "from automaticThought."
        ),
    }
    updates: dict[str, Any] = {
        "latest_user_intent": explicit_intent,
        "preface_goal": preface_goals[explicit_intent],
    }

    feedback_intent = _resolved_feedback_intent(request, plan)
    if feedback_intent is None:
        return plan.model_copy(update=updates)

    rejected_item = request.question_answers[-1]
    rejected_question = rejected_item.question
    rejected_route = (
        "Rejected semantic route; do not reuse its target, comparison, inferred "
        "cause, evidence source, or assumed causal link: "
        f"{rejected_question}"
    )
    avoid_topics = list(dict.fromkeys([*plan.avoid_topics, rejected_route]))[-8:]
    updates["latest_user_intent"] = feedback_intent
    updates["avoid_topics"] = avoid_topics
    return plan.model_copy(update=updates)


def _validate_saved_answer_evidence(
    evidence: SavedAnswerEvidence,
    question_answers: list[QuestionAnswer],
    label: str,
    *,
    allow_no_direct_evidence: bool = True,
) -> QuestionAnswer:
    source = next(
        (
            item
            for item in question_answers
            if item.question_code == evidence.source_question_code
        ),
        None,
    )
    if source is None:
        raise CbtDraftValidationError(
            f"{label} must reference a saved user answer"
        )
    if source.answer is None or evidence.excerpt not in source.answer:
        raise CbtDraftValidationError(
            f"{label} must be an exact saved-answer excerpt"
        )
    disposition = _classify_answer_disposition(source)
    if disposition in {
        AnswerDisposition.DIALOGUE_CONTROL,
        AnswerDisposition.UNCLEAR,
        AnswerDisposition.SKIPPED,
    }:
        raise CbtDraftValidationError(
            f"{label} cannot use a {disposition.value} answer as CBT evidence"
        )
    if (
        disposition == AnswerDisposition.NO_DIRECT_EVIDENCE
        and not allow_no_direct_evidence
    ):
        raise CbtDraftValidationError(
            f"{label} cannot use NO_DIRECT_EVIDENCE as evidence for the thought"
        )
    return source


def _evidence_key(evidence: SavedAnswerEvidence) -> tuple[str, str]:
    return evidence.source_question_code, evidence.excerpt


def _validate_semantic_progress(
    progress: CbtSemanticProgress,
    question_answers: list[QuestionAnswer],
) -> None:
    selected = (
        ("Semantic evidence-for", progress.evidence_for, False),
        ("Semantic evidence-against", progress.evidence_against, True),
        ("Semantic alternative-view", progress.alternative_view, True),
        ("Semantic acknowledgement", progress.acknowledgement, True),
    )
    for label, evidence, allow_no_direct in selected:
        if evidence is not None:
            _validate_saved_answer_evidence(
                evidence,
                question_answers,
                label,
                allow_no_direct_evidence=allow_no_direct,
            )

    complete = [evidence for _, evidence, _ in selected if evidence is not None]
    if len(complete) == 4:
        keys = [_evidence_key(evidence) for evidence in complete]
        if keys[0] == keys[1] or len(set(keys)) < 3:
            raise CbtDraftValidationError(
                "semanticProgress cannot copy one saved excerpt across distinct "
                "meaning domains without independent evidence"
            )


def _semantic_progress_complete(progress: CbtSemanticProgress) -> bool:
    return all(
        evidence is not None
        for evidence in (
            progress.evidence_for,
            progress.evidence_against,
            progress.alternative_view,
            progress.acknowledgement,
        )
    )


def _validate_analysis_draft(
    draft: CbtAnalysisDraft,
    request: CbtRequest,
) -> None:
    history = (
        request.question_answers
        if isinstance(request, CbtTurnRequest)
        else []
    )
    _validate_semantic_progress(draft.semantic_progress, history)

    if (
        draft.result_type == CbtResultType.SAFETY_STOP
        and _is_clearly_non_current_user_safety_reference(request)
    ):
        raise CbtDraftValidationError(
            "SAFETY_STOP is invalid because the harm wording is clearly negated, "
            "resolved historical context, hypothetical, or attributed only to a "
            "third party. Re-read the original answer and continue without treating "
            "it as the user's current safety signal"
        )

    if (
        isinstance(request, CbtStartRequest)
        and draft.result_type == CbtResultType.CONFIRMATION_REQUIRED
    ):
        raise CbtDraftValidationError(
            "START cannot require confirmation before any user answer"
        )

    if draft.result_type == CbtResultType.QUESTION:
        assert draft.question_plan is not None
        plan = draft.question_plan
        explicit_intent = _explicit_feedback_intent(request)
        if (
            isinstance(request, CbtTurnRequest)
            and plan.latest_user_intent == LatestUserIntent.START
        ):
            _, turn_flags = _analysis_turn_flags(request, explicit_intent)
            plan.latest_user_intent = (
                LatestUserIntent.UNCLEAR
                if turn_flags["latestAnswerIsDialogueControl"]
                else LatestUserIntent.CBT_ANSWER
            )
        elif isinstance(request, CbtStartRequest):
            plan.latest_user_intent = LatestUserIntent.START

        if explicit_intent in CONVERSATION_FEEDBACK_INTENTS:
            # 이 두 의도는 명시적 문구로 서버가 고신뢰 판별합니다. 작은 모델이
            # REQUEST_EXPLANATION 등으로 흔들려 502를 만들지 않도록 라벨만
            # 정규화하고, 잘못 형성된 목표는 아래 거부 경로 검증에서 차단합니다.
            plan.latest_user_intent = explicit_intent
        elif (
            explicit_intent is not None
            and plan.latest_user_intent != explicit_intent
        ):
            raise CbtDraftValidationError(
                "questionPlan.latestUserIntent must match latestUserIntentHint"
            )

        answers_by_code = {item.question_code: item for item in history}
        for code in plan.grounding_question_codes:
            source = answers_by_code.get(code)
            if source is None:
                raise CbtDraftValidationError(
                    "groundingQuestionCodes must reference saved answers"
                )
            if _classify_answer_disposition(source) in {
                AnswerDisposition.DIALOGUE_CONTROL,
                AnswerDisposition.UNCLEAR,
                AnswerDisposition.SKIPPED,
            }:
                raise CbtDraftValidationError(
                    "groundingQuestionCodes must reference substantive saved answers"
                )

        if (
            isinstance(request, CbtTurnRequest)
            and explicit_intent in CONVERSATION_FEEDBACK_INTENTS
            and request.question_answers[-1].question_code
            in plan.grounding_question_codes
        ):
            raise CbtDraftValidationError(
                "relevance or repetition feedback cannot ground the next question"
            )

        if any(
            plan.question_goal.count(label) != 1
            for label in QUESTION_GOAL_LABELS
        ):
            raise CbtDraftValidationError(
                "questionGoal must contain each required label exactly once"
            )
        if "?" in plan.question_goal:
            raise CbtDraftValidationError(
                "questionGoal must be a plan, not question wording"
            )
        blocked_route_types = _blocked_semantic_route_types(history)
        if plan.semantic_route_type in blocked_route_types:
            raise CbtDraftValidationError(
                "questionPlan.semanticRouteType reuses a route the user rejected"
            )
        if (
            blocked_route_types
            and plan.semantic_route_type == SemanticRouteType.OTHER_SPECIFIC
        ):
            raise CbtDraftValidationError(
                "OTHER_SPECIFIC cannot hide a known rejected semantic route; "
                "choose the precise new semanticRouteType"
            )
        if _reuses_latest_rejected_route(plan.question_goal, request):
            raise CbtDraftValidationError(
                "questionGoal reuses the semantic route the user just rejected as "
                "irrelevant or repetitive. Do not revisit that target, comparison, "
                "or inferred cause; choose a genuinely different information route"
            )

        _, turn_flags = _analysis_turn_flags(request, explicit_intent)
        if (
            turn_flags["directEvidenceRouteResolved"]
            and _repeats_resolved_direct_evidence(plan.question_goal)
        ):
            raise CbtDraftValidationError(
                "questionGoal repeats a resolved direct-evidence route. The user "
                "already reported no additional direct words, actions, evidence, "
                "or signals. Do not seek another direct signal; choose the next "
                "unresolved direction from uncertainty, a grounded alternative, or "
                "a balanced conclusion without forcing any one questionPurpose"
            )
        return

    if draft.result_type == CbtResultType.CONFIRMATION_REQUIRED:
        if not isinstance(request, CbtTurnRequest):
            raise CbtDraftValidationError(
                "Confirmation requires at least one saved user answer"
            )

        if not _semantic_progress_complete(draft.semantic_progress):
            raise CbtDraftValidationError(
                "Confirmation requires semanticProgress evidence-for, "
                "evidence-against, alternative-view, and acknowledgement"
            )

        assert draft.confirmation is not None
        confirmation = draft.confirmation
        acknowledgement = SavedAnswerEvidence(
            source_question_code=(
                confirmation.acknowledgement_source_question_code
            ),
            excerpt=confirmation.acknowledgement_evidence,
        )
        _validate_saved_answer_evidence(
            acknowledgement,
            request.question_answers,
            "Acknowledgement evidence",
        )

        _validate_saved_answer_evidence(
            confirmation.confirmation_coverage.evidence_for,
            request.question_answers,
            "Evidence-for coverage",
            allow_no_direct_evidence=False,
        )
        _validate_saved_answer_evidence(
            confirmation.confirmation_coverage.evidence_against,
            request.question_answers,
            "Evidence-against coverage",
        )
        _validate_saved_answer_evidence(
            confirmation.confirmation_coverage.alternative_view,
            request.question_answers,
            "Alternative-view coverage",
        )

        progress = draft.semantic_progress
        assert progress.evidence_for is not None
        assert progress.evidence_against is not None
        assert progress.alternative_view is not None
        assert progress.acknowledgement is not None
        if (
            confirmation.confirmation_coverage.evidence_for
            != progress.evidence_for
            or confirmation.confirmation_coverage.evidence_against
            != progress.evidence_against
            or confirmation.confirmation_coverage.alternative_view
            != progress.alternative_view
            or acknowledgement != progress.acknowledgement
        ):
            raise CbtDraftValidationError(
                "confirmation evidence must exactly match semanticProgress"
            )

        rejected_codes = {
            item.code
            for item in request.before_distortions
            if item.review_status == DistortionReviewStatus.REJECTED
        }
        if any(
            item.code in rejected_codes
            for item in confirmation.before_distortions
        ):
            raise CbtDraftValidationError(
                "The CBT model re-proposed a rejected distortion"
            )


def _validate_wording_draft(
    wording: QuestionWordingDraft,
    plan: CbtQuestionPlan,
    request: CbtRequest,
) -> None:
    if plan.preface_goal is None and wording.preface is not None:
        raise CbtDraftValidationError(
            "Writer preface must be null when prefaceGoal is null"
        )
    if plan.preface_goal is not None and wording.preface is None:
        raise CbtDraftValidationError(
            "Writer preface is required when prefaceGoal is present"
        )
    if _reuses_latest_rejected_route(wording.question, request):
        raise CbtDraftValidationError(
            "Writer question reuses the semantic route the user just rejected. "
            "Express only the plan's genuinely different target"
        )
    explicit_intent = _explicit_feedback_intent(request)
    _, turn_flags = _analysis_turn_flags(request, explicit_intent)
    if (
        turn_flags["directEvidenceRouteResolved"]
        and _repeats_resolved_direct_evidence(wording.question)
    ):
        raise CbtDraftValidationError(
            "Writer question reopens the resolved direct-evidence route. The user "
            "already reported no additional direct words, actions, mentions, or "
            "signals; express only the plan's next unresolved direction"
        )


def _next_question_code(
    request: CbtRequest,
    purpose: QuestionPurpose,
) -> str:
    existing_codes = (
        {item.question_code for item in request.question_answers}
        if isinstance(request, CbtTurnRequest)
        else set()
    )
    ordinal = len(existing_codes) + 1
    while True:
        candidate = f"{purpose.value}_{ordinal:03d}"
        if candidate not in existing_codes:
            return candidate
        ordinal += 1


def _to_response(
    draft: CbtAnalysisDraft,
    request: CbtRequest,
    wording: QuestionWordingDraft | None = None,
) -> CbtTurnResponse:
    status_by_result = {
        CbtResultType.QUESTION: CbtApiStatus.CONTINUE,
        CbtResultType.CONFIRMATION_REQUIRED: CbtApiStatus.CONFIRM_REQUIRED,
        CbtResultType.SAFETY_STOP: CbtApiStatus.SAFETY_STOP,
    }
    next_question: GeneratedQuestion | None = None
    if draft.result_type == CbtResultType.QUESTION:
        assert draft.question_plan is not None
        assert wording is not None
        next_question = GeneratedQuestion(
            question_code=_next_question_code(
                request,
                draft.question_plan.question_purpose,
            ),
            question_purpose=draft.question_plan.question_purpose,
            semantic_route_type=draft.question_plan.semantic_route_type,
            question=wording.rendered_message(),
        )
    else:
        assert wording is None
    confirmation = draft.confirmation
    return CbtTurnResponse(
        request_id=request.request_id,
        status=status_by_result[draft.result_type],
        next_question=next_question,
        before_distortions=(
            confirmation.before_distortions if confirmation is not None else []
        ),
        outcome_draft=(
            confirmation.outcome_draft if confirmation is not None else None
        ),
        confirmation_required_fields=(
            list(CONFIRMATION_REQUIRED_FIELDS)
            if draft.result_type == CbtResultType.CONFIRMATION_REQUIRED
            else []
        ),
        acknowledgement_evidence=(
            confirmation.acknowledgement_evidence
            if confirmation is not None
            else None
        ),
        acknowledgement_source_question_code=(
            confirmation.acknowledgement_source_question_code
            if confirmation is not None
            else None
        ),
        proposal_message=(
            confirmation.proposal_message if confirmation is not None else None
        ),
        risk=draft.risk,
        meta=AnalysisMeta(model=CBT_MODEL, prompt_version=CBT_PROMPT_VERSION),
    )


async def _invoke_structured(
    *,
    model: Any,
    schema: type[ModelT],
    system_prompt: str,
    payload: dict[str, Any],
    validate: Callable[[ModelT], None],
    stage: str,
    retry_guidance: str,
) -> ModelT:
    validation_feedback: str | None = None
    validation_feedbacks: list[str] = []

    for attempt in range(CBT_MODEL_OUTPUT_ATTEMPTS):
        messages = [SystemMessage(content=system_prompt)]
        if validation_feedbacks:
            messages.append(
                SystemMessage(
                    content=(
                        f"Previous {stage} outputs failed these validations:\n- "
                        + "\n- ".join(validation_feedbacks)
                        + "\nUse the same payload and return a corrected result that "
                        f"satisfies all prior feedback. {retry_guidance}"
                    )
                )
            )
        messages.append(
            HumanMessage(content=json.dumps(payload, ensure_ascii=False))
        )

        try:
            result = await model.ainvoke(messages)
            parsed = schema.model_validate(result)
            validate(parsed)
        except ValidationError as exc:
            validation_feedback = ", ".join(
                f"{'.'.join(str(part) for part in error['loc'])}: {error['msg']}"
                for error in exc.errors(include_input=False)
            )[:1_000]
        except OutputParserException:
            # 원본 출력은 민감한 사용자 문맥을 포함할 수 있어 재사용하지 않습니다.
            validation_feedback = (
                "The previous response could not be parsed as the required schema"
            )
        except CbtDraftValidationError as exc:
            validation_feedback = str(exc)[:1_000]
        else:
            return parsed

        if (
            validation_feedback is not None
            and validation_feedback not in validation_feedbacks
        ):
            validation_feedbacks.append(validation_feedback)

        if CBT_DEBUG_LOG_ANALYSIS:
            logger.info(
                "CBT structured stage retry: stage=%s attempt=%s reason=%s",
                stage,
                attempt + 1,
                validation_feedback,
            )

    raise RuntimeError(
        f"CBT {stage} remained invalid after corrective retries: "
        f"{' | '.join(validation_feedbacks)}"
    ) from None


async def _analyze_turn(
    request: CbtRequest,
    *,
    analysis_model: Any | None = None,
) -> CbtAnalysisDraft:
    confirmation_allowed = _confirmation_candidate_available(request)
    schema = (
        CbtAnalysisDraft
        if confirmation_allowed
        else CbtQuestionOrSafetyDraft
    )
    result = await _invoke_structured(
        model=analysis_model or _get_analysis_model(confirmation_allowed),
        schema=schema,
        system_prompt=ANALYSIS_PROMPT,
        payload=_build_analysis_payload(request),
        validate=lambda draft: _validate_analysis_draft(
            (
                draft
                if isinstance(draft, CbtAnalysisDraft)
                else draft.to_analysis_draft()
            ),
            request,
        ),
        stage="analysis",
        retry_guidance=(
            "Re-check progress, saved-answer references, the selected result "
            "container, and rejected distortions."
        ),
    )
    if isinstance(result, CbtAnalysisDraft):
        return result
    return result.to_analysis_draft()


async def _write_question(
    request: CbtRequest,
    plan: CbtQuestionPlan,
    *,
    writer_model: Any | None = None,
) -> QuestionWordingDraft:
    return await _invoke_structured(
        model=writer_model or _get_writer_model(),
        schema=QuestionWordingDraft,
        system_prompt=WRITER_PROMPT,
        payload=_build_writer_payload(request, plan),
        validate=lambda wording: _validate_wording_draft(
            wording,
            plan,
            request,
        ),
        stage="question wording",
        retry_guidance=(
            "Keep the same plan, avoid forbidden topics, and do not repeat a "
            "previous question."
        ),
    )


async def _generate(
    request: CbtRequest,
    *,
    analysis_model: Any | None = None,
    writer_model: Any | None = None,
) -> CbtTurnResponse:
    analysis = await _analyze_turn(
        request,
        analysis_model=analysis_model,
    )
    if CBT_DEBUG_LOG_ANALYSIS:
        logger.info(
            "CBT first-stage analysis: requestId=%s sessionId=%s draft=%s",
            request.request_id,
            request.session_id,
            analysis.model_dump_json(by_alias=True),
        )
    if analysis.result_type != CbtResultType.QUESTION:
        return _to_response(analysis, request)

    assert analysis.question_plan is not None
    question_plan = _apply_feedback_constraints(
        request,
        analysis.question_plan,
    )
    if question_plan is not analysis.question_plan:
        analysis = analysis.model_copy(
            update={"question_plan": question_plan}
        )

    wording = await _write_question(
        request,
        question_plan,
        writer_model=writer_model,
    )
    return _to_response(analysis, request, wording)


async def generate_cbt_start(
    request: CbtStartRequest,
    *,
    analysis_model: Any | None = None,
    writer_model: Any | None = None,
) -> CbtTurnResponse:
    """상황·감정·자동적 사고에 근거한 첫 성찰 질문을 생성합니다."""

    return await _generate(
        request,
        analysis_model=analysis_model,
        writer_model=writer_model,
    )


async def generate_cbt_turn(
    request: CbtTurnRequest,
    *,
    analysis_model: Any | None = None,
    writer_model: Any | None = None,
) -> CbtTurnResponse:
    """누적 답변을 읽고 다음 질문 또는 사용자 확인용 유형 제안을 생성합니다."""

    return await _generate(
        request,
        analysis_model=analysis_model,
        writer_model=writer_model,
    )
