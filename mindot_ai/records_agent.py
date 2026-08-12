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
RECORDS_PROMPT_VERSION = "analyze-record-v1"


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
    interpretation: str | None = Field(
        description="Meaning the user assigned to the situation, kept separate from facts.",
    )
    automatic_thought: str | None = Field(
        # Python 안에서는 automatic_thought, JSON에서는 automaticThought를 사용합니다.
        alias="automaticThought",
        description="The user's immediate automatic thought, without adding new claims.",
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
    context_category: str | None = Field(
        alias="contextCategory",
        description="Broad context code such as WORK, FAMILY, RELATIONSHIP, or OTHER.",
    )
    related_person_type: str | None = Field(
        alias="relatedPersonType",
        description="Broad relationship code such as COLLEAGUE, FRIEND, FAMILY, or OTHER.",
    )


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
You are Mindot's record-structuring agent. Mindot is a self-observation aid, not a
medical diagnosis, treatment, or counseling service.

Convert one new short Korean or English emotion record into the required schema.

Rules:
1. Preserve the distinction between observable facts (situation) and the user's
   meaning or assumption (interpretation). situation must exclude subjective phrases
   such as "것 같다", "느꼈다", "생각했다", inferred motives, and judgments.
   Example: for "아무도 답하지 않아 무시당한 것 같았다", situation is
   "아무도 답하지 않았다" and interpretation is "무시당했다고 생각했다".
   Never present an assumption as fact.
2. Extract only information supported by rawText. Do not invent
   motives, childhood experiences, personality traits, diagnoses, or treatment advice.
3. Keep the user's wording concise. Use null or an empty list when information is
   absent. The user may fill blank fields directly in the application later.
4. Emotion codes must be concise uppercase codes. Prefer ANXIETY, FEAR, ANGER,
   FRUSTRATION, SADNESS, DISAPPOINTMENT, SHAME, GUILT, LONELINESS, JOY, RELIEF,
   ACHIEVEMENT, CALM, GRATITUDE, EXCITEMENT, or OTHER. Do not infer an intensity.
5. risk.level is REVIEW for ambiguous but meaningful self-harm, suicide, harm-to-
   others, or immediate-danger language, and CRISIS for explicit or imminent danger.
   Otherwise it is NONE. Use a normalized reason such as
   SELF_HARM_EXPLICIT, SUICIDE_EXPLICIT, HARM_TO_OTHERS_EXPLICIT,
   IMMEDIATE_DANGER, or AMBIGUOUS_SAFETY_SIGNAL. Never quote sensitive text in reason.
6. Do not generate follow-up questions or instructions for filling blank fields.
7. Do not create embeddings. Spring AI owns embedding generation and persistence.
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
