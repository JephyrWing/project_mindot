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
from types import MappingProxyType
from typing import Annotated, Any, Literal, TypeVar
from uuid import UUID

from dotenv import load_dotenv
from langchain_core.exceptions import OutputParserException
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator


load_dotenv(Path(__file__).resolve().parent.parent / "infra" / ".env.local")

CBT_MODEL = os.getenv("OPENAI_CBT_MODEL", "gpt-4o-mini")
CBT_PROMPT_VERSION = "cbt-reflection-quality-v2"
CBT_WRITER_TEMPERATURE = float(
    os.getenv("OPENAI_CBT_WRITER_TEMPERATURE", "0.3")
)
CBT_MODEL_OUTPUT_ATTEMPTS = 2
WRITER_PREVIOUS_QUESTION_LIMIT = 4
CBT_DEBUG_LOG_ANALYSIS = os.getenv(
    "CBT_DEBUG_LOG_ANALYSIS",
    "true",
).lower() in {"1", "true", "yes", "on"}


# uvicorn의 기본 INFO handler를 사용해 로컬 서버 콘솔에서 바로 확인합니다.
logger = logging.getLogger("uvicorn.error")


class CbtDraftValidationError(RuntimeError):
    """모델 출력이 서버의 CBT 의미 규칙을 위반했음을 나타냅니다."""


class CbtModelOutputExhaustedError(RuntimeError):
    """모델 출력의 검증·파싱 재시도가 소진됐음을 나타냅니다."""

    def __init__(self, stage: str, feedbacks: list[str]) -> None:
        self.stage = stage
        self.feedbacks = tuple(feedbacks)
        super().__init__(
            f"CBT {stage} remained invalid after corrective retries: "
            f"{' | '.join(feedbacks)}"
        )


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


class SemanticRouteFamily(str, Enum):
    """외부 계약에 노출하지 않는 CBT 질문 경로의 상위 의미 계열입니다."""

    CONTEXT_OBSERVATION = "CONTEXT_OBSERVATION"
    DIRECT_SUPPORT = "DIRECT_SUPPORT"
    COUNTEREVIDENCE = "COUNTEREVIDENCE"
    CERTAINTY = "CERTAINTY"
    ALTERNATIVE = "ALTERNATIVE"
    SYNTHESIS = "SYNTHESIS"
    EMOTION = "EMOTION"
    USER_CHOICE = "USER_CHOICE"
    OTHER = "OTHER"


SEMANTIC_ROUTE_FAMILY_BY_TYPE = {
    SemanticRouteType.OBSERVABLE_EVENT_DETAIL: (
        SemanticRouteFamily.CONTEXT_OBSERVATION
    ),
    SemanticRouteType.OTHER_PEOPLE_COMPARISON: (
        SemanticRouteFamily.CONTEXT_OBSERVATION
    ),
    SemanticRouteType.DIRECT_WORD_OR_ACTION: SemanticRouteFamily.DIRECT_SUPPORT,
    SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: SemanticRouteFamily.DIRECT_SUPPORT,
    SemanticRouteType.CONTRADICTORY_FACT: SemanticRouteFamily.COUNTEREVIDENCE,
    SemanticRouteType.CERTAINTY_REASSESSMENT: SemanticRouteFamily.CERTAINTY,
    SemanticRouteType.ALTERNATIVE_EXPLANATION: SemanticRouteFamily.ALTERNATIVE,
    SemanticRouteType.BALANCED_CONCLUSION: SemanticRouteFamily.SYNTHESIS,
    SemanticRouteType.EMOTION_OR_TRIGGER: SemanticRouteFamily.EMOTION,
    SemanticRouteType.USER_SELECTED_DIRECTION: SemanticRouteFamily.USER_CHOICE,
    SemanticRouteType.OTHER_SPECIFIC: SemanticRouteFamily.OTHER,
}


SEMANTIC_ROUTE_DEFINITIONS = MappingProxyType(
    {
        SemanticRouteType.OBSERVABLE_EVENT_DETAIL: MappingProxyType(
            {
                "meaning": "사용자가 직접 관찰한 구체적인 사건·행동·상황을 확인",
                "informationSource": "USER_OBSERVATION",
                "forbiddenDirections": (
                    "그 사건의 의미, 이유, 제3자의 감정·생각·의도 추측",
                ),
            }
        ),
        SemanticRouteType.DIRECT_WORD_OR_ACTION: MappingProxyType(
            {
                "meaning": "핵심 생각을 직접 뒷받침하는 실제 말이나 행동 확인",
                "informationSource": "USER_OBSERVATION",
                "forbiddenDirections": (
                    "제3자의 숨은 동기·감정·원인 추측",
                ),
            }
        ),
        SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: MappingProxyType(
            {
                "meaning": (
                    "핵심 생각이 맞다면 예상되지만 실제로는 없었던 "
                    "관찰 가능한 신호 확인"
                ),
                "informationSource": "USER_OBSERVATION",
                "forbiddenDirections": (
                    "왜 그 신호가 없었는지, 상대의 의도나 속마음 질문",
                ),
            }
        ),
        SemanticRouteType.CONTRADICTORY_FACT: MappingProxyType(
            {
                "meaning": "핵심 생각의 확실성을 낮추는 구체적으로 알려진 사실 확인",
                "informationSource": "USER_OBSERVATION_OR_KNOWN_FACT",
                "forbiddenDirections": (
                    "다른 가능성, 숨은 원인, 제3자의 감정 질문으로 변환",
                ),
            }
        ),
        SemanticRouteType.OTHER_PEOPLE_COMPARISON: MappingProxyType(
            {
                "meaning": (
                    "핵심 주장과 직접 관련된 경우에만 관찰 가능한 행동 차이를 비교"
                ),
                "informationSource": "USER_OBSERVATION",
                "forbiddenDirections": (
                    "다른 사람이 무엇을 생각·느낌·인지했는지 질문",
                ),
            }
        ),
        SemanticRouteType.CERTAINTY_REASSESSMENT: MappingProxyType(
            {
                "meaning": (
                    "사용자가 사실과 추론을 구별하거나 현재 확신 정도를 다시 판단"
                ),
                "informationSource": "USER_JUDGMENT",
                "forbiddenDirections": (
                    "제3자의 이유·동기·감정·의도 질문",
                ),
            }
        ),
        SemanticRouteType.ALTERNATIVE_EXPLANATION: MappingProxyType(
            {
                "meaning": (
                    "확인되지 않은 실제 원인을 맞히지 않고 가능한 설명을 가설로 검토"
                ),
                "informationSource": "USER_HYPOTHESIS",
                "forbiddenDirections": (
                    "제3자의 실제 이유나 진짜 의도를 단정하거나 맞히게 하기",
                ),
            }
        ),
        SemanticRouteType.BALANCED_CONCLUSION: MappingProxyType(
            {
                "meaning": (
                    "확인된 근거와 불확실성을 함께 반영한 균형 잡힌 결론 구성"
                ),
                "informationSource": "USER_SYNTHESIS",
                "forbiddenDirections": (
                    "새로운 원인·근거·제3자 내면 질문",
                ),
            }
        ),
        SemanticRouteType.EMOTION_OR_TRIGGER: MappingProxyType(
            {
                "meaning": (
                    "상황에서 사용자가 느낀 감정이나 그 감정을 촉발한 지점 확인"
                ),
                "informationSource": "USER_EXPERIENCE",
                "forbiddenDirections": (
                    "제3자의 감정·생각·의도 질문",
                ),
            }
        ),
        SemanticRouteType.USER_SELECTED_DIRECTION: MappingProxyType(
            {
                "meaning": (
                    "사용자가 자신의 경험 중 어느 부분을 이어서 살펴볼지 선택"
                ),
                "informationSource": "USER_CHOICE",
                "forbiddenDirections": (
                    "제3자 숨은 상태 추측, 이미 거부된 방향 반복",
                ),
            }
        ),
        SemanticRouteType.OTHER_SPECIFIC: MappingProxyType(
            {
                "meaning": "과거 데이터 복원 전용 legacy route",
                "informationSource": "LEGACY_ONLY",
                "forbiddenDirections": ("새 질문 계획에서 선택",),
            }
        ),
    }
)


ALLOWED_QUESTION_PURPOSES_BY_ROUTE = MappingProxyType(
    {
        SemanticRouteType.OBSERVABLE_EVENT_DETAIL: frozenset(
            {QuestionPurpose.SITUATION_REFLECTION}
        ),
        SemanticRouteType.DIRECT_WORD_OR_ACTION: frozenset(
            {QuestionPurpose.EVIDENCE_FOR}
        ),
        SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: frozenset(
            {QuestionPurpose.EVIDENCE_AGAINST}
        ),
        SemanticRouteType.CONTRADICTORY_FACT: frozenset(
            {QuestionPurpose.EVIDENCE_AGAINST}
        ),
        SemanticRouteType.OTHER_PEOPLE_COMPARISON: frozenset(
            {
                QuestionPurpose.SITUATION_REFLECTION,
                QuestionPurpose.EVIDENCE_FOR,
                QuestionPurpose.EVIDENCE_AGAINST,
            }
        ),
        SemanticRouteType.CERTAINTY_REASSESSMENT: frozenset(
            {QuestionPurpose.BALANCED_THOUGHT}
        ),
        SemanticRouteType.ALTERNATIVE_EXPLANATION: frozenset(
            {QuestionPurpose.ALTERNATIVE_VIEW}
        ),
        SemanticRouteType.BALANCED_CONCLUSION: frozenset(
            {QuestionPurpose.BALANCED_THOUGHT}
        ),
        SemanticRouteType.EMOTION_OR_TRIGGER: frozenset(
            {QuestionPurpose.EMOTION_REFLECTION}
        ),
        SemanticRouteType.USER_SELECTED_DIRECTION: frozenset(
            {QuestionPurpose.FREE_REFLECTION}
        ),
        SemanticRouteType.OTHER_SPECIFIC: frozenset(),
    }
)


class LatestUserIntent(str, Enum):
    START = "START"
    CBT_ANSWER = "CBT_ANSWER"
    REQUEST_EXAMPLE = "REQUEST_EXAMPLE"
    REQUEST_EXPLANATION = "REQUEST_EXPLANATION"
    RELEVANCE_FEEDBACK = "RELEVANCE_FEEDBACK"
    DIFFICULTY_FEEDBACK = "DIFFICULTY_FEEDBACK"
    REPETITION_FEEDBACK = "REPETITION_FEEDBACK"
    UNCLEAR = "UNCLEAR"


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
            "A short Korean plan targeting one unresolved inference owned by the "
            "USER and matching questionPurpose and semanticRouteType."
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
Plan one Korean CBT reflection turn. Select SAFETY_STOP,
CONFIRMATION_REQUIRED, or one QUESTION direction. The Writer creates final
question wording. Do not diagnose, invent facts, or try to prove
automaticThought false.
</role>

<order>
1. Check a plausible current harm or immediate-danger signal.
2. Interpret latestInteraction and authoritative user feedback.
3. Review completionCandidates and rebuild semanticProgress from the actual
   saved-answer meanings and exact excerpts.
4. If confirmationAllowed and all four domains are valid, return
   CONFIRMATION_REQUIRED instead of asking another question.
5. Otherwise select one new, answerable route.
</order>

<context>
The reflection subject and respondent are always the USER. A person mentioned
in the situation is a third party, not the respondent.

blockedRoutes and blockedRouteFamilies are hard exclusions.
resolvedButIrrelevantTopics are closed and cannot be reused or grounded.
semanticRouteDefinitions are authoritative. Select only an allowed
questionPurpose and never select OTHER_SPECIFIC.
</context>

<evidence>
Interpret answers by meaning and answerDisposition, never by questionPurpose
alone. completionCandidates are attention hints, not proof.

DIALOGUE_CONTROL, UNCLEAR, and SKIPPED fill no CBT domain.
NO_DIRECT_EVIDENCE is never evidenceFor and may lower certainty.

semanticProgress uses exact saved-answer excerpts:
- evidenceFor supports the core claim;
- evidenceAgainst contradicts it or lowers certainty;
- alternativeView contains another plausible or balanced interpretation;
- acknowledgement separates fact from inference or reassesses certainty.
</evidence>

<feedback>
RELEVANCE_FEEDBACK and REPETITION_FEEDBACK are dialogue control, not evidence.
Do not reuse the rejected route, family, topic, comparison, cause, evidence
source, or causal link.

For REQUEST_EXAMPLE, give neutral possibilities in prefaceGoal, then ask which,
if any, seems plausible. Do not ask for the third party's actual hidden reason.

For REQUEST_EXPLANATION or DIFFICULTY_FEEDBACK, use one brief prefaceGoal and
one simpler relevant direction.
</feedback>

<question>
Start from the core claim, not an adjacent detail. Choose one unresolved point
whose answer could most change the user's understanding or certainty.

The user may answer from their own observation, experience, judgment,
hypothesis, or synthesis. Never ask the user to know a third party's actual
emotion, thought, motive, intention, or cause.

Follow the selected semanticRouteDefinition exactly. A route label with a
different question meaning is invalid.

questionGoal is a short Korean plan, not final wording:
"확인된 사실: ...; 핵심 주장: ...; 미해결 간극: ...; 사용자에게 물을 목표: ..."

groundingQuestionCodes may contain only substantive saved answers.
Do not repeat a closed meaning, force optimism, or assume the original thought
is entirely false.
</question>

<confirmation>
Confirmation requires valid evidenceFor, evidenceAgainst, alternativeView, and
acknowledgement. Candidate coverage alone is insufficient; verify answer
meaning and exact excerpts.

Use tentative distortion language and concrete saved evidence.
proposalMessage should briefly state the observed pattern and why the proposed
distortion may apply. Do not use "당신", invent scores, use feedback as
evidence, or re-propose a rejected distortion.
</confirmation>

<safety>
SAFETY_STOP only for a contextually plausible current self-harm, suicide,
harm-to-others, or immediate-danger signal. Profanity, sadness, anxiety,
frustration, refusal, and criticism alone are not safety signals.
</safety>
""".strip()


WRITER_PROMPT = """
<role>
Write one natural Korean response from the supplied plan. Do not change
questionPurpose or semanticRouteType, add evidence, classify, or diagnose.
</role>

<route>
selectedRouteDefinition is authoritative. Express exactly its meaning using
the grounded topic. Do not turn CONTRADICTORY_FACT into another-possibility
question or EMOTION_OR_TRIGGER into a third-party emotion question.

For ALTERNATIVE_EXPLANATION, ask which possibility may fit, not what the third
party's actual hidden reason was.
</route>

<subject>
questionSubject is USER. Ask what the user observed, experienced, inferred,
judged, or can consider. Never ask for a third party's actual emotion, thought,
motive, intention, or cause. Never use "당신".
</subject>

<preface>
If prefaceRequired is false, return preface=null. If true, fulfill
prefaceGoal in one brief statement without adding a new topic.

For REQUEST_EXAMPLE, give brief neutral possibilities and then ask whether any
of them seems plausible. Do not repeat the same open-ended hidden-reason
question.
</preface>

<question>
Ask one simple, answerable Korean question. Respect groundingAnswers,
blockedRoutes, blockedRouteFamilies, resolvedButIrrelevantTopics, avoidTopics,
latestInteraction, and previousQuestions.

Do not repeat a closed meaning, ask multiple questions, force optimism, or
invent facts.
</question>
""".strip()


_llm: ChatOpenAI | None = None
_writer_llm: ChatOpenAI | None = None
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


def _get_writer_llm() -> ChatOpenAI:
    global _writer_llm
    if _writer_llm is None:
        _writer_llm = ChatOpenAI(
            model=CBT_MODEL,
            temperature=CBT_WRITER_TEMPERATURE,
            timeout=30.0,
            max_retries=2,
            use_responses_api=True,
        )
    return _writer_llm


def _confirmation_candidate_available(request: CbtRequest) -> bool:
    """실질 답변 수로 완료 출력 스키마 노출만 결정합니다.

    질문 목적은 사용하지 않습니다. 실제 완료 가능 여부는
    ``semanticProgress``의 저장 답변 의미를 별도로 검증합니다.
    """

    if not isinstance(request, CbtTurnRequest):
        return False
    candidate_dispositions = {
        AnswerDisposition.SUBSTANTIVE,
        AnswerDisposition.NO_DIRECT_EVIDENCE,
    }
    return sum(
        _classify_answer_disposition(item) in candidate_dispositions
        for item in request.question_answers
    ) >= 2


def _get_writer_model() -> Any:
    global _writer_model
    if _writer_model is None:
        _writer_model = _get_writer_llm().with_structured_output(
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


def _semantic_route_family(
    route_type: SemanticRouteType,
) -> SemanticRouteFamily:
    return SEMANTIC_ROUTE_FAMILY_BY_TYPE[route_type]


def _semantic_route_definition_payload(
    route_type: SemanticRouteType,
) -> dict[str, Any]:
    definition = SEMANTIC_ROUTE_DEFINITIONS[route_type]
    return {
        "semanticRouteType": route_type.value,
        "semanticRouteFamily": _semantic_route_family(route_type).value,
        "meaning": definition["meaning"],
        "informationSource": definition["informationSource"],
        "forbiddenDirections": list(definition["forbiddenDirections"]),
        "allowedQuestionPurposes": sorted(
            purpose.value
            for purpose in ALLOWED_QUESTION_PURPOSES_BY_ROUTE[route_type]
        ),
    }


def _semantic_route_definitions_payload() -> list[dict[str, Any]]:
    return [
        _semantic_route_definition_payload(route_type)
        for route_type in SemanticRouteType
    ]


def _validate_question_plan_route(plan: CbtQuestionPlan) -> None:
    if plan.semantic_route_type == SemanticRouteType.OTHER_SPECIFIC:
        raise CbtDraftValidationError(
            "OTHER_SPECIFIC is legacy-only and cannot be selected for a new question"
        )
    allowed_purposes = ALLOWED_QUESTION_PURPOSES_BY_ROUTE[
        plan.semantic_route_type
    ]
    if plan.question_purpose not in allowed_purposes:
        raise CbtDraftValidationError(
            "questionPurpose is not allowed for the selected semanticRouteType"
        )


def _feedback_blocked_route_families(
    question_answers: list[QuestionAnswer],
) -> set[SemanticRouteFamily]:
    return {
        _semantic_route_family(item.semantic_route_type)
        for item in question_answers
        if item.semantic_route_type is not None
        and _classify_explicit_user_intent(item.answer)
        in CONVERSATION_FEEDBACK_INTENTS
    }


def _direct_support_resolved(
    question_answers: list[QuestionAnswer],
) -> bool:
    return any(
        _has_explicit_no_direct_evidence(item)
        for item in question_answers
    )


def _hard_blocked_route_families(
    question_answers: list[QuestionAnswer],
) -> set[SemanticRouteFamily]:
    blocked = _feedback_blocked_route_families(question_answers)
    if _direct_support_resolved(question_answers):
        blocked.add(SemanticRouteFamily.DIRECT_SUPPORT)
    return blocked


def _resolved_but_irrelevant_topics(
    question_answers: list[QuestionAnswer],
) -> list[dict[str, Any]]:
    topics: list[dict[str, Any]] = []
    for item in question_answers:
        if (
            item.semantic_route_type is None
            or _classify_explicit_user_intent(item.answer)
            not in CONVERSATION_FEEDBACK_INTENTS
        ):
            continue
        topics.append(
            {
                "sourceQuestionCode": item.question_code,
                "questionPurpose": item.question_purpose.value,
                "semanticRouteType": item.semantic_route_type.value,
                "semanticRouteFamily": _semantic_route_family(
                    item.semantic_route_type
                ).value,
                "rejectedQuestion": item.question,
            }
        )
    return topics


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
    unclear_text = re.sub(
        r"[\s,.!?~…ㅠㅜㅋㅎ]+",
        " ",
        normalized,
    ).strip()

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

    hesitation = r"(?:(?:음+|어+|아+)\s+)*"
    modifier = r"(?:(?:아직(?:은)?|지금은|솔직히)\s+)*"
    uncertainty = (
        r"(?:"
        r"(?:잘\s+)?모르겠(?:어|어요|네요|는데요|는데|다|습니다)?"
        r"|글쎄(?:요)?"
        r"|(?:생각이\s+)?(?:잘\s+)?안\s+나(?:요|네요|는데요|는데)?"
        r")"
    )
    if re.fullmatch(hesitation + modifier + uncertainty, unclear_text):
        return AnswerDisposition.UNCLEAR

    return AnswerDisposition.SUBSTANTIVE


def _is_clearly_non_current_user_safety_text(text: str | None) -> bool:
    """명백한 부정·제3자 인용·종료된 과거·가정 표현을 감지합니다."""

    normalized = " ".join((text or "").lower().split())
    if not normalized:
        return False
    harm = r"(?:죽고\s*싶|자살|자해|죽이|해치|위해를\s*가하)"

    # 이중 부정은 안전하다고 확정할 수 없으므로 모델의 문맥 판단에 둡니다.
    if re.search(r"(?:없|않)(?:는|은|었던)?\s*건\s*아니", normalized):
        return False

    negated = re.search(
        rf"{harm}.{{0,45}}(?:전혀\s*|하나도\s*)?"
        r"(?:지\s*않|생각(?:은|이)?\s*없|의도(?:는|가)?\s*없|없|아니)",
        normalized,
    )
    historical_and_resolved = re.search(
        rf"(?:예전|과거|전에는|한때).{{0,60}}{harm}.{{0,60}}"
        r"(?:지금|현재|이제).{0,25}(?:없|아니|괜찮)",
        normalized,
    )
    hypothetical = re.search(
        rf"(?:만약|가령|예를\s*들|예시).{{0,45}}{harm}",
        normalized,
    )
    third_party = re.search(
        rf"(?:친구|가족|지인|동료|팀장|그\s*사람|상대|부모|형제|자매)"
        rf"(?:가|이|은|는).{{0,45}}{harm}.{{0,30}}"
        r"(?:말|얘기|이야기|문자|연락|라고|다고\s*했)",
        normalized,
    )
    self_harm_statement = re.search(
        rf"(?:나도|저도|나는|저는|내가|제가).{{0,35}}{harm}",
        normalized,
    )
    quoted_only = third_party is not None and self_harm_statement is None
    return any((negated, historical_and_resolved, hypothetical, quoted_only))


def _explicit_safety_reason_from_text(
    text: str | None,
) -> RiskReasonCode | None:
    """문자열에 있는 명시적 현재 위해 표현을 보수적으로 분류합니다."""

    normalized = " ".join((text or "").lower().split())
    if not normalized or _is_clearly_non_current_user_safety_text(normalized):
        return None
    marker_groups = (
        (
            RiskReasonCode.HARM_TO_OTHERS,
            (
                "다른 사람을 해치",
                "누군가를 해치",
                "사람을 해치",
                "너를 해치",
                "죽이고 싶",
                "죽이겠",
                "누군가를 죽일",
                "사람을 죽일",
                "너를 죽일",
            ),
        ),
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
            RiskReasonCode.IMMEDIATE_DANGER,
            (
                "지금 당장 위험",
                "당장 위험해",
                "위험에 처해",
                "살려 줘",
                "살려줘",
                "누가 나를 죽이",
                "공격받고 있어",
            ),
        ),
        (
            RiskReasonCode.AMBIGUOUS_SAFETY_SIGNAL,
            ("해치고 싶", "해치겠", "위해를 가할", "다치게 할 것 같"),
        ),
    )
    for reason, markers in marker_groups:
        if any(marker in normalized for marker in markers):
            return reason
    return None


def _explicit_safety_hint(request: CbtRequest) -> RiskReasonCode | None:
    """현재 입력의 명백한 위험 표현만 분석 전에 힌트로 제공합니다."""

    if isinstance(request, CbtTurnRequest):
        texts = [request.question_answers[-1].answer]
    else:
        texts = [request.record.automatic_thought, request.record.situation]
    for text in texts:
        reason = _explicit_safety_reason_from_text(text)
        if reason is not None:
            return reason
    return None


def _is_clearly_non_current_user_safety_reference(request: CbtRequest) -> bool:
    """명백한 부정·제3자 인용·과거 종료·가정 표현만 보수적으로 감지합니다."""

    if isinstance(request, CbtTurnRequest):
        return _is_clearly_non_current_user_safety_text(
            request.question_answers[-1].answer
        )
    texts = (request.record.automatic_thought, request.record.situation)
    return _explicit_safety_hint(request) is None and any(
        _is_clearly_non_current_user_safety_text(text)
        for text in texts
    )


def _contextual_safety_hint(request: CbtRequest) -> RiskReasonCode | None:
    """명백한 비현재·제3자 문맥을 제외한 위험 후보만 반환합니다."""

    return _explicit_safety_hint(request)


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
    direct_evidence_route_resolved = _direct_support_resolved(
        request.question_answers
    )
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


def _completion_candidates(
    question_answers: list[QuestionAnswer],
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, bool]]:
    candidates: dict[str, list[dict[str, Any]]] = {
        "evidenceFor": [],
        "evidenceAgainst": [],
        "alternativeView": [],
        "acknowledgement": [],
    }
    excluded = {
        AnswerDisposition.DIALOGUE_CONTROL,
        AnswerDisposition.UNCLEAR,
        AnswerDisposition.SKIPPED,
    }

    for order, item in enumerate(question_answers):
        if item.answer is None or not item.answer.strip():
            continue
        disposition = _classify_answer_disposition(item)
        if disposition in excluded:
            continue
        candidate = {
            "questionCode": item.question_code,
            "answer": item.answer,
            "answerDisposition": disposition.value,
            "questionPurpose": item.question_purpose.value,
            "semanticRouteType": (
                item.semantic_route_type.value
                if item.semantic_route_type is not None
                else None
            ),
            "answeredAt": (
                item.answered_at.isoformat()
                if item.answered_at is not None
                else None
            ),
            "order": order,
        }
        if (
            disposition != AnswerDisposition.NO_DIRECT_EVIDENCE
            and (
                item.question_purpose == QuestionPurpose.EVIDENCE_FOR
                or item.semantic_route_type
                == SemanticRouteType.DIRECT_WORD_OR_ACTION
            )
        ):
            candidates["evidenceFor"].append(candidate)
        if (
            item.question_purpose == QuestionPurpose.EVIDENCE_AGAINST
            or item.semantic_route_type
            in {
                SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
                SemanticRouteType.CONTRADICTORY_FACT,
            }
            or disposition == AnswerDisposition.NO_DIRECT_EVIDENCE
        ):
            candidates["evidenceAgainst"].append(candidate)
        if (
            item.question_purpose == QuestionPurpose.ALTERNATIVE_VIEW
            or item.semantic_route_type
            == SemanticRouteType.ALTERNATIVE_EXPLANATION
        ):
            candidates["alternativeView"].append(candidate)
        if (
            item.question_purpose == QuestionPurpose.BALANCED_THOUGHT
            or item.semantic_route_type
            in {
                SemanticRouteType.CERTAINTY_REASSESSMENT,
                SemanticRouteType.BALANCED_CONCLUSION,
            }
        ):
            candidates["acknowledgement"].append(candidate)

    candidates = {
        domain: items[-4:]
        for domain, items in candidates.items()
    }
    coverage = {
        domain: bool(items)
        for domain, items in candidates.items()
    }
    return candidates, coverage


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
    history = (
        request.question_answers
        if isinstance(request, CbtTurnRequest)
        else []
    )
    completion_candidates, completion_coverage = _completion_candidates(history)

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
        "questionSubject": "USER",
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
        "questionSubject": "USER",
        "record": {
            "situation": request.record.situation,
            "automaticThought": request.record.automatic_thought,
            "primaryEmotionCode": request.record.primary_emotion_code,
        },
        "latestInteraction": latest_interaction,
        "groundingAnswers": grounding_answers,
        "previousQuestions": previous_questions,
        "blockedRoutes": (
            _blocked_routes_from_history(request.question_answers)
            if isinstance(request, CbtTurnRequest)
            else []
        ),
        "blockedRouteFamilies": sorted(
            family.value
            for family in _hard_blocked_route_families(
                request.question_answers
                if isinstance(request, CbtTurnRequest)
                else []
            )
        ),
        "resolvedButIrrelevantTopics": _resolved_but_irrelevant_topics(
            request.question_answers
            if isinstance(request, CbtTurnRequest)
            else []
        ),
        "selectedRouteDefinition": _semantic_route_definition_payload(
            plan.semantic_route_type
        ),
        "selectedRouteFamily": _semantic_route_family(
            plan.semantic_route_type
        ).value,
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

DIRECT_EVIDENCE_ROUTES = {
    SemanticRouteType.DIRECT_WORD_OR_ACTION,
    SemanticRouteType.EXPECTED_SIGNAL_ABSENCE,
}


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

    latest = request.question_answers[-1]
    rejected_route = latest.semantic_route_type
    rejected_question = latest.question
    if len(rejected_question) > 180:
        rejected_question = f"{rejected_question[:177].rstrip()}..."
    avoid_topics = [
        *plan.avoid_topics,
        f"거부된 질문 소재: {rejected_question}",
    ]
    if rejected_route is not None:
        rejected_family = _semantic_route_family(rejected_route)
        avoid_topics.append(f"차단된 의미 계열: {rejected_family.value}")
    avoid_topics = list(dict.fromkeys(avoid_topics))[-8:]
    updates["latest_user_intent"] = feedback_intent
    updates["avoid_topics"] = avoid_topics
    return plan.model_copy(update=updates)


def _valid_saved_answer_evidence(
    evidence: SavedAnswerEvidence,
    question_answers: list[QuestionAnswer],
    *,
    allow_no_direct_evidence: bool = True,
) -> bool:
    source = next(
        (
            item
            for item in question_answers
            if item.question_code == evidence.source_question_code
        ),
        None,
    )
    if source is None:
        return False
    if source.answer is None or evidence.excerpt not in source.answer:
        return False
    disposition = _classify_answer_disposition(source)
    if disposition in {
        AnswerDisposition.DIALOGUE_CONTROL,
        AnswerDisposition.UNCLEAR,
        AnswerDisposition.SKIPPED,
    }:
        return False
    if (
        disposition == AnswerDisposition.NO_DIRECT_EVIDENCE
        and not allow_no_direct_evidence
    ):
        return False
    return True


def _normalize_semantic_progress(
    progress: CbtSemanticProgress,
    question_answers: list[QuestionAnswer],
) -> CbtSemanticProgress:
    def keep(
        evidence: SavedAnswerEvidence | None,
        *,
        allow_no_direct_evidence: bool = True,
    ) -> SavedAnswerEvidence | None:
        if evidence is None:
            return None
        if not _valid_saved_answer_evidence(
            evidence,
            question_answers,
            allow_no_direct_evidence=allow_no_direct_evidence,
        ):
            return None
        return evidence

    return CbtSemanticProgress(
        evidence_for=keep(
            progress.evidence_for,
            allow_no_direct_evidence=False,
        ),
        evidence_against=keep(progress.evidence_against),
        alternative_view=keep(progress.alternative_view),
        acknowledgement=keep(progress.acknowledgement),
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


def _validate_generated_user_visible_text(*texts: str | None) -> None:
    if any(text is not None and "당신" in text for text in texts):
        raise CbtDraftValidationError(
            "Generated user-visible text must not use the cold second-person "
            "expression '당신'"
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
    draft.semantic_progress = _normalize_semantic_progress(
        draft.semantic_progress,
        history,
    )

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
        _validate_question_plan_route(plan)
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

        if explicit_intent is not None:
            plan.latest_user_intent = explicit_intent

        answers_by_code = {item.question_code: item for item in history}
        blocked_grounding_code = (
            request.question_answers[-1].question_code
            if isinstance(request, CbtTurnRequest)
            and explicit_intent in CONVERSATION_FEEDBACK_INTENTS
            else None
        )
        plan.grounding_question_codes = [
            code
            for code in plan.grounding_question_codes
            if (source := answers_by_code.get(code)) is not None
            and _classify_answer_disposition(source)
            not in {
                AnswerDisposition.DIALOGUE_CONTROL,
                AnswerDisposition.UNCLEAR,
                AnswerDisposition.SKIPPED,
            }
            and code != blocked_grounding_code
        ]
        blocked_route_types = _blocked_semantic_route_types(history)
        hard_blocked_families = _hard_blocked_route_families(history)
        selected_family = _semantic_route_family(plan.semantic_route_type)
        if plan.semantic_route_type in blocked_route_types:
            raise CbtDraftValidationError(
                "questionPlan.semanticRouteType reuses a route the user rejected"
            )
        if selected_family in hard_blocked_families:
            raise CbtDraftValidationError(
                "questionPlan.semanticRouteType belongs to a hard-blocked route "
                "family"
            )
        if (
            hard_blocked_families
            and plan.semantic_route_type == SemanticRouteType.OTHER_SPECIFIC
        ):
            raise CbtDraftValidationError(
                "OTHER_SPECIFIC cannot hide a hard-blocked semantic route family; "
                "choose the precise new semanticRouteType"
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
        _validate_generated_user_visible_text(
            confirmation.proposal_message,
            confirmation.outcome_draft.alternative_thought_text,
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
    acknowledgement = (
        draft.semantic_progress.acknowledgement
        if confirmation is not None
        else None
    )
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
            acknowledgement.excerpt if acknowledgement is not None else None
        ),
        acknowledgement_source_question_code=(
            acknowledgement.source_question_code
            if acknowledgement is not None
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

    raise CbtModelOutputExhaustedError(stage, validation_feedbacks) from None


FALLBACK_QUESTION_CANDIDATES = (
    (
        QuestionPurpose.EVIDENCE_FOR,
        SemanticRouteType.DIRECT_WORD_OR_ACTION,
        "그 생각이 사실이라고 느끼게 한 직접적인 말이나 행동이 있었나요?",
    ),
    (
        QuestionPurpose.EVIDENCE_AGAINST,
        SemanticRouteType.CONTRADICTORY_FACT,
        "그 생각이 확실하다고 보기 어렵게 만드는 사실도 있었나요?",
    ),
    (
        QuestionPurpose.ALTERNATIVE_VIEW,
        SemanticRouteType.ALTERNATIVE_EXPLANATION,
        "같은 상황을 설명할 수 있는 다른 가능성이 있다면 무엇일까요?",
    ),
    (
        QuestionPurpose.BALANCED_THOUGHT,
        SemanticRouteType.CERTAINTY_REASSESSMENT,
        "지금까지 확인한 내용을 보면, 처음 생각을 어느 정도 확실한 사실이라고 느끼시나요?",
    ),
    (
        QuestionPurpose.BALANCED_THOUGHT,
        SemanticRouteType.BALANCED_CONCLUSION,
        "확인된 사실과 아직 확실하지 않은 부분을 함께 담으면, 지금 어떤 생각으로 정리할 수 있을까요?",
    ),
    (
        QuestionPurpose.FREE_REFLECTION,
        SemanticRouteType.USER_SELECTED_DIRECTION,
        "지금 이 생각에서 어떤 부분부터 살펴보는 것이 가장 도움이 될까요?",
    ),
)


FALLBACK_QUESTION_BY_ROUTE = {
    SemanticRouteType.OBSERVABLE_EVENT_DETAIL: (
        "그 상황에서 직접 보고 들은 사실은 무엇이었나요?"
    ),
    SemanticRouteType.DIRECT_WORD_OR_ACTION: FALLBACK_QUESTION_CANDIDATES[0][2],
    SemanticRouteType.EXPECTED_SIGNAL_ABSENCE: (
        "그 생각이 맞다면 예상할 만한 말이나 행동 중 실제로 없었던 것도 있었나요?"
    ),
    SemanticRouteType.CONTRADICTORY_FACT: FALLBACK_QUESTION_CANDIDATES[1][2],
    SemanticRouteType.OTHER_PEOPLE_COMPARISON: (
        "그 상황에서 다른 사람과 비교해 직접 확인한 사실은 무엇이었나요?"
    ),
    SemanticRouteType.CERTAINTY_REASSESSMENT: FALLBACK_QUESTION_CANDIDATES[3][2],
    SemanticRouteType.ALTERNATIVE_EXPLANATION: FALLBACK_QUESTION_CANDIDATES[2][2],
    SemanticRouteType.BALANCED_CONCLUSION: FALLBACK_QUESTION_CANDIDATES[4][2],
    SemanticRouteType.EMOTION_OR_TRIGGER: "그 상황에서 어떤 감정을 느꼈나요?",
    SemanticRouteType.USER_SELECTED_DIRECTION: FALLBACK_QUESTION_CANDIDATES[5][2],
    SemanticRouteType.OTHER_SPECIFIC: (
        "지금 이 생각에서 아직 확인하지 못한 한 가지는 무엇인가요?"
    ),
}


FALLBACK_PREFACE_BY_INTENT = {
    LatestUserIntent.RELEVANCE_FEEDBACK: (
        "핵심에서 벗어난 질문이었네요. 다른 방향으로 살펴볼게요."
    ),
    LatestUserIntent.REPETITION_FEEDBACK: (
        "같은 내용을 되물었네요. 이번에는 다른 방향으로 살펴볼게요."
    ),
    LatestUserIntent.DIFFICULTY_FEEDBACK: (
        "질문을 조금 더 간단히 바꿔볼게요."
    ),
    LatestUserIntent.REQUEST_EXPLANATION: (
        "이 질문은 확인된 사실과 추측을 나누어 보기 위한 거예요."
    ),
    LatestUserIntent.REQUEST_EXAMPLE: (
        "예를 들면 직접 들은 말, 반복된 행동, 반대되는 사례 같은 것이 있어요."
    ),
}


def _empty_semantic_progress() -> CbtSemanticProgress:
    return CbtSemanticProgress(
        evidence_for=None,
        evidence_against=None,
        alternative_view=None,
        acknowledgement=None,
    )


def _fallback_latest_user_intent(request: CbtRequest) -> LatestUserIntent:
    explicit = _explicit_feedback_intent(request)
    if explicit is not None:
        return explicit
    if isinstance(request, CbtStartRequest):
        return LatestUserIntent.START
    if _is_explicit_dialogue_refusal(request.question_answers[-1].answer):
        return LatestUserIntent.UNCLEAR
    return LatestUserIntent.CBT_ANSWER


def _deterministic_fallback_wording(
    request: CbtRequest,
    plan: CbtQuestionPlan,
) -> QuestionWordingDraft:
    intent = _fallback_latest_user_intent(request)
    return QuestionWordingDraft(
        preface=FALLBACK_PREFACE_BY_INTENT.get(intent),
        question=FALLBACK_QUESTION_BY_ROUTE[plan.semantic_route_type],
    )


def _build_deterministic_fallback(
    request: CbtRequest,
    *,
    semantic_progress: CbtSemanticProgress | None = None,
) -> tuple[CbtAnalysisDraft, QuestionWordingDraft | None]:
    history = (
        request.question_answers
        if isinstance(request, CbtTurnRequest)
        else []
    )
    progress = _normalize_semantic_progress(
        semantic_progress or _empty_semantic_progress(),
        history,
    )
    safety_reason = _contextual_safety_hint(request)
    if safety_reason is not None:
        level = (
            RiskLevel.CRISIS
            if safety_reason == RiskReasonCode.IMMEDIATE_DANGER
            else RiskLevel.REVIEW
        )
        return (
            CbtAnalysisDraft(
                result_type=CbtResultType.SAFETY_STOP,
                semantic_progress=progress,
                question_plan=None,
                confirmation=None,
                risk=RiskAssessment(
                    level=level,
                    reason_code=safety_reason,
                ),
            ),
            None,
        )

    used_routes = {
        item.semantic_route_type
        for item in history
        if item.semantic_route_type is not None
    }
    blocked_routes = _blocked_semantic_route_types(history)
    blocked_families = _hard_blocked_route_families(history)
    selected: tuple[QuestionPurpose, SemanticRouteType, str] | None = None
    for candidate in FALLBACK_QUESTION_CANDIDATES:
        _, route, _ = candidate
        if route in used_routes or route in blocked_routes:
            continue
        if _semantic_route_family(route) in blocked_families:
            continue
        selected = candidate
        break
    if selected is None:
        selected = FALLBACK_QUESTION_CANDIDATES[-1]

    purpose, route, question = selected
    plan = CbtQuestionPlan(
        question_purpose=purpose,
        semantic_route_type=route,
        latest_user_intent=_fallback_latest_user_intent(request),
        question_goal=f"사용자에게 물을 목표: {question}",
        preface_goal=None,
        grounding_question_codes=[],
        avoid_topics=[],
    )
    plan = _apply_feedback_constraints(request, plan)
    draft = CbtAnalysisDraft(
        result_type=CbtResultType.QUESTION,
        semantic_progress=progress,
        question_plan=plan,
        confirmation=None,
        risk=RiskAssessment(level=RiskLevel.NONE, reason_code=None),
    )
    return draft, _deterministic_fallback_wording(request, plan)


def _log_fallback_usage(
    request: CbtRequest,
    *,
    architecture: str,
    failed_stage: str,
    draft: CbtAnalysisDraft,
) -> None:
    selected_route = (
        draft.question_plan.semantic_route_type.value
        if draft.question_plan is not None
        else f"SAFETY_STOP:{draft.risk.reason_code.value}"
    )
    logger.info(
        "CBT deterministic fallback: requestId=%s sessionId=%s "
        "architecture=%s failedStage=%s selectedFallbackRoute=%s",
        request.request_id,
        request.session_id,
        architecture,
        failed_stage,
        selected_route,
    )


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
        validate=lambda draft: _validate_generated_user_visible_text(
            draft.preface,
            draft.question,
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
    try:
        analysis = await _analyze_turn(
            request,
            analysis_model=analysis_model,
        )
    except CbtModelOutputExhaustedError as exc:
        analysis, wording = _build_deterministic_fallback(request)
        _log_fallback_usage(
            request,
            architecture="dual-llm",
            failed_stage=exc.stage,
            draft=analysis,
        )
        return _to_response(analysis, request, wording)
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

    try:
        wording = await _write_question(
            request,
            question_plan,
            writer_model=writer_model,
        )
    except CbtModelOutputExhaustedError as exc:
        wording = _deterministic_fallback_wording(request, question_plan)
        _log_fallback_usage(
            request,
            architecture="dual-llm",
            failed_stage=exc.stage,
            draft=analysis,
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
