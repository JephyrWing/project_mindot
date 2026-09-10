# 함수 반환형 등에 아직 정의되지 않은 클래스를 적어도 오류가 나지 않게 합니다.
# 예: `def normalize(...) -> RiskAssessment`처럼 클래스 내부에서 자기 타입을 참조할 수 있습니다.
from __future__ import annotations

import json
import os
from enum import Enum
from typing import Any

from dotenv import load_dotenv
from langchain.agents import create_agent
from langchain.agents.structured_output import ProviderStrategy
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

load_dotenv("../infra/.env.local")

RECORDS_MODEL = os.getenv("OPENAI_RECORDS_MODEL", "gpt-4o-mini")
# 어떤 프롬프트 규칙으로 결과가 생성됐는지 추적하기 위한 버전입니다.
RECORDS_PROMPT_VERSION = "analyze-record-dev"


class ApiModel(BaseModel):
    """내부 AI API의 모든 요청·응답 모델이 공통으로 상속하는 기반 클래스."""

    # populate_by_name=True:
    #   Python 필드명(raw_text)과 API 별칭(rawText)을 모두 입력으로 받을 수 있습니다.
    # extra="forbid":
    #   스키마에 없는 필드가 들어오면 조용히 무시하지 않고 검증 오류를 냅니다.
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class RiskLevel(str, Enum):
    """감정 기록에서 발견한 안전 신호의 단계."""

    NONE = "NONE"
    REVIEW = "REVIEW"
    CRISIS = "CRISIS"


class ContextCategory(str, Enum):
    """상황에 등장한 사람이 아니라 사건 자체의 유형을 나타냅니다."""

    SOCIAL_EVALUATION = "SOCIAL_EVALUATION"
    PERFORMANCE = "PERFORMANCE"
    PROMISE = "PROMISE"
    MISTAKE = "MISTAKE"
    CONFLICT = "CONFLICT"
    REJECTION = "REJECTION"
    WORK = "WORK"
    STUDY = "STUDY"
    HEALTH = "HEALTH"
    DAILY_LIFE = "DAILY_LIFE"
    OTHER = "OTHER"


class EmotionItem(ApiModel):
    """하나의 감정 코드와 선택적인 강도를 표현합니다."""

    code: str = Field(
        # 예: ANXIETY, JOY, SOCIAL_ANXIETY처럼 대문자 코드만 허용합니다.
        min_length=2,
        max_length=50,
        pattern=r"^[A-Z][A-Z0-9_]*$",
        description="Stable uppercase emotion code such as ANXIETY or JOY.",
    )
    intensity: int | None = Field(
        # 값은 0~10 또는 null입니다. 사용자가 말하지 않은 강도는 추측하지 않습니다.
        # OpenAI strict Structured Output에서는 nullable이어도 키 자체는 필요합니다.
        ge=0,
        le=10,
        description="Emotion intensity explicitly stated by the user, from 0 to 10.",
    )

    @field_validator("code", mode="before")
    @classmethod
    def normalize_code(cls, value: Any) -> Any:
        """정식 검증 전에 감정 코드를 대문자 스네이크 표기로 정리합니다."""

        if isinstance(value, str):
            return value.strip().upper().replace(" ", "_")
        return value


class StructuredRecord(ApiModel):
    """AI가 원문에서 추출해야 하는 구조화 감정 기록입니다."""

    situation: str | None = Field(
        description="Only the observable event or context stated by the user.",
    )
    automatic_thought: str | None = Field(
        alias="automaticThought",
        description=(
            "An immediate thought, assumption, prediction, self-evaluation, or "
            "subjective appraisal explicitly expressed in rawText. Extract it "
            "using the user's original meaning and wording. Never infer a plausible "
            "thought from a situation, emotion, body reaction, or behavior. "
            "Return null when no thought content is explicitly supported."
        ),
    )
    emotions: list[EmotionItem] = Field(
        description="Zero or more emotions explicitly supported by the input.",
    )
    body_reaction: str | None = Field(
        alias="bodyReaction",
        description="Physical reaction explicitly stated by the user.",
    )
    behavior: str | None = Field(
        description="Action or response explicitly stated by the user.",
    )
    context_category: ContextCategory | None = Field(
        alias="contextCategory",
        description=(
            "Classify the event itself, never the other person's relationship. "
            "Must be non-null whenever situation is non-null and null when situation "
            "is null. Use SOCIAL_EVALUATION for being watched, judged, criticized, "
            "or mocked; PERFORMANCE for presentations, tests, interviews, or task "
            "performance; PROMISE for appointments, commitments, lateness, or "
            "cancellation; MISTAKE for errors, omissions, or accidental wrongdoing; "
            "CONFLICT for arguments, disagreement, or boundary violations; REJECTION "
            "for refusal, exclusion, unanswered contact, or disconnection; WORK for "
            "other work situations; STUDY for other study situations; HEALTH for "
            "physical health situations; DAILY_LIFE for ordinary daily activities; "
            "and OTHER when no category fits. Prefer a specific event category over "
            "a broad domain category."
        ),
    )
    related_person_type: str | None = Field(
        alias="relatedPersonType",
        description=(
            "Relationship type explicitly supported by rawText. "
            "Return null when no person or identifiable relationship is provided. "
            "Do not use OTHER merely because the relationship is unknown."
        ),
    )

    @model_validator(mode="after")
    def normalize_context_category(self) -> "StructuredRecord":
        if self.situation is None:
            self.context_category = None
        elif self.context_category is None:
            self.context_category = ContextCategory.OTHER

        return self


class RiskAssessment(ApiModel):
    """AI가 판단한 안전 신호와 민감 원문을 포함하지 않는 사유 코드."""

    level: RiskLevel
    reason: str | None = Field(
        description="A short normalized reason code; never copy sensitive source text.",
    )

    @model_validator(mode="after")
    def normalize_none_reason(self) -> RiskAssessment:
        """위험 없음(NONE)인데 사유가 남아 있는 모순된 결과를 정리합니다."""

        if self.level == RiskLevel.NONE:
            self.reason = None
        return self


class RecordAnalysisDraft(ApiModel):
    """OpenAI가 최초 원문에서 생성하는 구조화 초안 응답입니다."""

    record: StructuredRecord
    risk: RiskAssessment


class AnalysisMeta(ApiModel):
    """서버가 직접 붙이는 모델·프롬프트 추적 정보."""

    model: str
    prompt_version: str = Field(alias="promptVersion")


class RecordAnalysis(RecordAnalysisDraft):
    """클라이언트에 최종 반환하는 전체 분석 결과."""

    meta: AnalysisMeta


# 사용자의 감정 원문은 이 문자열에 섞지 않고 아래 analyze_record에서 별도 메시지로 보냅니다.
SYSTEM_PROMPT = """
You are Mindot's record-structuring agent.

Mindot is a self-observation aid, not a medical diagnosis, treatment, or
counseling service.

Convert one short Korean or English emotion record into the required schema.
Extract only information directly supported by rawText. Never invent facts,
thoughts, motives, relationships, diagnoses, or advice. When information is
absent or uncertain, return null or an empty list.

Field rules:

1. situation

Extract only observable context or events presented by the user as having
occurred. Do not include assumptions, predictions, judgments, inferred motives,
or thought content.

Never turn uncertainty into fact:
- "비웃는 것 같았다" must not become "비웃었다".
- "일부러 제외한 것 같았다" must not become "일부러 제외했다".

For expressions such as "X 것 같다", "X라는 생각이 들었다",
"혹시 X 아닐까", or "X인가 보다", the entire proposition X is thought
content and must be excluded from situation.


2. automaticThought

Extract only a thought, assumption, prediction, self-evaluation, worry, or
subjective appraisal explicitly expressed in rawText.

automaticThought is an extraction field, not an inference field. A thought does
not need to contain the word "생각", but its content must be directly supported
by the input.

Keep the user's uncertainty:
- "사람들이 나를 비웃는 것 같았다" must remain uncertain.
- Do not rewrite it as "사람들이 나를 비웃었다".

Never create a plausible thought merely because it would explain an emotion or
situation. For example, do not infer "나는 무능하다" from a mistake or
"사람들이 나를 싫어한다" from anxiety.

If no thought content is explicitly supported, return null.


3. emotions

Extract only emotions supported by rawText.

Prefer these uppercase codes:
ANXIETY, FEAR, ANGER, FRUSTRATION, SADNESS, DISAPPOINTMENT,
SHAME, GUILT, LONELINESS, JOY, RELIEF, ACHIEVEMENT,
CALM, GRATITUDE, EXCITEMENT, OTHER.

Do not infer intensity. Return null unless the user explicitly gives a value
from 0 to 10.


4. bodyReaction and behavior

bodyReaction is an involuntary physiological response:
- 심장이 빠르게 뛰었다
- 손이 떨렸다
- 식은땀이 났다
- 숨이 가빠졌다
- 눈물이 났다

behavior is an action performed by the user:
- 방문을 세게 닫았다
- 자리를 피했다
- 답장하지 않았다
- 손으로 얼굴을 가렸다
- 울었다

Do not classify an action as bodyReaction merely because it was caused by an
emotion.

Use this distinction:
- A physiological response that happened to the user -> bodyReaction
- An action performed by the user -> behavior

Extract both separately when both are present.


5. contextCategory

contextCategory classifies what kind of event occurred. It must not describe
who was involved; relatedPersonType handles the person's relationship.

Use the most specific supported event category:
- SOCIAL_EVALUATION: being watched, judged, criticized, embarrassed, or mocked
- PERFORMANCE: presentations, tests, interviews, or task performance
- PROMISE: appointments, commitments, lateness, cancellation, or broken plans
- MISTAKE: errors, omissions, forgotten responsibilities, or accidental actions
- CONFLICT: arguments, disagreement, boundary violations, or unwanted behavior
- REJECTION: refusal, exclusion, unanswered contact, or disconnection
- WORK: work situations not covered by a more specific category
- STUDY: study situations not covered by a more specific category
- HEALTH: illness, pain, or physical health situations
- DAILY_LIFE: transportation, shopping, chores, or ordinary daily activities
- OTHER: situations that do not fit another category

A specific event category takes priority over a broad domain category. For
example, being late to a promise is PROMISE rather than MISTAKE, making a
mistake during a presentation is PERFORMANCE, and a sibling using the user's
belongings without permission is CONFLICT rather than a family category.

The following consistency rule is mandatory:
- If situation is null, contextCategory must be null.
- If situation is not null, contextCategory must not be null.

If situation exists but no specific category applies, use OTHER. Never choose
contextCategory from the identity or relationship of another person.


6. relatedPersonType

relatedPersonType must be one of COLLEAGUE, FRIEND, FAMILY, OTHER, or null.

Use it only when the relationship is supported by rawText. Return null for
vague references such as "사람들", "누군가", or "다들". Do not guess the
relationship.


7. risk

Use CRISIS for explicit or imminent self-harm, suicide, harm to others, or
immediate danger. Use REVIEW for ambiguous but meaningful safety language.
Otherwise use NONE.

Use only normalized reason codes such as SELF_HARM_EXPLICIT,
SUICIDE_EXPLICIT, HARM_TO_OTHERS_EXPLICIT, IMMEDIATE_DANGER, or
AMBIGUOUS_SAFETY_SIGNAL. When level is NONE, reason must be null. Never copy
sensitive rawText into reason.


Examples:

Input:
"길가다 사람들이 내 옷을 보고 비웃는 것 같은 생각이 들어서 불안했어"

Expected:
- situation: "길을 가고 있었다"
- automaticThought: "사람들이 내 옷을 보고 비웃는 것 같았다"
- emotions: [{"code": "ANXIETY", "intensity": null}]
- bodyReaction: null
- behavior: null
- contextCategory: "SOCIAL_EVALUATION"
- relatedPersonType: null


Input:
"동생이 허락 없이 내 물건을 써서 화가 났고 방문을 세게 닫았어"

Expected:
- situation: "동생이 허락 없이 내 물건을 사용했다"
- automaticThought: null
- emotions: [{"code": "ANGER", "intensity": null}]
- bodyReaction: null
- behavior: "방문을 세게 닫았다"
- contextCategory: "CONFLICT"
- relatedPersonType: "FAMILY"


Input:
"그냥 이유 없이 불안했어"

Expected:
- situation: null
- automaticThought: null
- emotions: [{"code": "ANXIETY", "intensity": null}]
- bodyReaction: null
- behavior: null
- contextCategory: null
- relatedPersonType: null

Do not generate follow-up questions, diagnoses, advice, or embeddings.
""".strip()


# 실제 OpenAI 모델 연결 객체입니다. 생성 시점에는 API를 호출하지 않습니다.
llm = ChatOpenAI(
    model=RECORDS_MODEL,
    # 구조화 작업의 결과 변동을 줄이기 위해 temperature를 0으로 둡니다.
    temperature=0.0,
    # 한 번의 요청이 무기한 기다리지 않도록 제한하고, 일시적 실패는 최대 2번 재시도합니다.
    timeout=30.0,
    max_retries=2,
    # Chat Completions 대신 OpenAI Responses API를 사용합니다.
    use_responses_api=True,
)

# LangChain 에이전트를 구성합니다.
# tools=[]이므로 검색이나 DB 작업 없이 모델 호출 한 번으로 구조화만 수행합니다.
agent = create_agent(
    model=llm,
    tools=[],
    system_prompt=SYSTEM_PROMPT,
    # ProviderStrategy는 OpenAI의 Structured Outputs 기능으로 Pydantic 형식을 강제합니다.
    # strict=True이면 스키마와 다른 키·타입의 응답을 허용하지 않습니다.
    response_format=ProviderStrategy(RecordAnalysisDraft, strict=True),
)


async def analyze_record(
    *,
    raw_text: str,
) -> RecordAnalysis:
    """최초 감정 원문을 구조화하고 서버 관리 메타데이터를 붙입니다."""

    payload = {
        "rawText": raw_text,
    }

    # ensure_ascii=False를 사용해야 한글이 \uXXXX 형태로 불필요하게 이스케이프되지 않습니다.
    result = await agent.ainvoke(
        {
            "messages": [
                {
                    "role": "user",
                    "content": json.dumps(payload, ensure_ascii=False),
                }
            ]
        }
    )

    # create_agent의 구조화 결과는 최종 상태의 structured_response 키에 저장됩니다.
    structured_response = result.get("structured_response")
    if structured_response is None:
        raise RuntimeError("The records agent returned no structured response.")

    # 모델 결과를 한 번 더 Pydantic으로 검증합니다.
    draft = RecordAnalysisDraft.model_validate(structured_response)

    # model/promptVersion은 모델이 임의 작성하지 못하도록 서버 상수에서 붙입니다.
    return RecordAnalysis(
        **draft.model_dump(),
        meta=AnalysisMeta(
            model=RECORDS_MODEL,
            prompt_version=RECORDS_PROMPT_VERSION,
        ),
    )
