# Python 3.10에서도 `SomeType | None` 같은 타입 표기를 안정적으로 사용하게 합니다.
from __future__ import annotations

import logging
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Path as ApiPath, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import Field, field_validator

# 실행 디렉터리와 무관하게 import 전에 OpenAI 설정을 준비합니다. 키 값은
# 애플리케이션 로그나 응답에 노출하지 않습니다.
load_dotenv(Path(__file__).resolve().parent.parent / "infra" / ".env.local")

# records_agent에서 정의한 스키마와 실제 분석 함수를 가져옵니다.
# 현재 import는 `mindot_ai` 디렉터리에서 `uvicorn app:app`을 실행하는 구성을 기준으로 합니다.

from records_agent import (
    ApiModel,
    RecordAnalysis,
    analyze_record
)
from cbt_agent import (
    CBT_DEBUG_LOG_ANALYSIS,
    CbtStartRequest,
    CbtTurnRequest,
    CbtTurnResponse,
    generate_cbt_start,
    generate_cbt_turn,
)
from cbt_session_agent import (
    close_agent_cbt_session,
    generate_agent_cbt_start,
    generate_agent_cbt_turn,
)


logger = logging.getLogger(__name__)


class AnalyzeRecordRequest(ApiModel):
    """Spring Boot가 최초 초안 구조화를 위해 보내는 요청 본문."""

    # alias는 JSON의 camelCase와 Python의 snake_case를 연결합니다.
    # min/max_length는 FastAPI가 받을 수 있는 원문 크기 범위를 제한합니다.
    raw_text: str = Field(alias="rawText", min_length=1, max_length=10_000)

    @field_validator("raw_text")
    @classmethod
    def reject_blank_text(cls, value: str) -> str:
        """공백만 있는 원문을 제거하고 FastAPI가 422를 반환하게 합니다."""

        value = value.strip()
        if not value:
            raise ValueError("rawText must not be blank")
        return value


# FastAPI 애플리케이션 객체입니다. uvicorn이 이 `app` 변수를 찾아 서버를 실행합니다.
app = FastAPI(
    title="Mindot AI Service",
    description="Internal AI APIs called only by the Mindot Spring Boot service.",
    version="1.0.0",
)

# 로컬 개발 편의를 위한 CORS 설정입니다.
# 운영 환경에서는 FastAPI가 외부에 직접 노출되지 않고 Spring Boot만 호출해야 합니다.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
def root() -> dict[str, str]:
    """서버 기본 정보와 Swagger 문서 위치를 반환합니다."""

    return {
        "message": "Mindot AI service",
        "swagger": "/docs",
    }


@app.get("/internal/ai/health")
def health() -> dict[str, str]:
    """컨테이너나 Spring Boot가 FastAPI 기동 여부를 확인하는 엔드포인트."""

    return {"status": "ready"}


async def _run_analysis(
    *,
    raw_text: str,
) -> RecordAnalysis:
    """에이전트 호출과 공통 오류 변환을 한곳에서 처리합니다."""

    try:
        return await analyze_record(
            raw_text=raw_text,
        )
    except Exception as exc:
        # 감정 원문과 구조화 결과는 민감정보이므로 로그에 남기지 않습니다.
        logger.exception("Record analysis failed")
        # 외부 AI 호출 실패를 Spring에 502 Bad Gateway로 알려 줍니다.
        raise HTTPException(
            status_code=502,
            detail="The AI record analysis request failed.",
        ) from exc


async def _run_cbt_start(request: CbtStartRequest) -> CbtTurnResponse:
    """CBT 시작 호출의 민감정보 비노출 오류 처리를 담당합니다."""

    try:
        return await generate_cbt_start(request)
    except Exception as exc:
        # 검증 예외에는 모델 출력이 포함될 수 있으므로 traceback도 남기지 않습니다.
        logger.error(
            "CBT start generation failed: requestId=%s error=%s",
            request.request_id,
            type(exc).__name__,
        )
        raise HTTPException(
            status_code=502,
            detail="The AI CBT start request failed.",
        ) from exc


async def _run_cbt_turn(request: CbtTurnRequest) -> CbtTurnResponse:
    """CBT 다음 턴 호출의 민감정보 비노출 오류 처리를 담당합니다."""

    try:
        return await generate_cbt_turn(request)
    except Exception as exc:
        logger.error(
            "CBT turn generation failed: requestId=%s error=%s",
            request.request_id,
            type(exc).__name__,
        )
        raise HTTPException(
            status_code=502,
            detail="The AI CBT turn request failed.",
        ) from exc


async def _run_agent_cbt_start(request: CbtStartRequest) -> CbtTurnResponse:
    """비교 실험용 세션 Agent의 시작 오류를 공통 HTTP 오류로 변환합니다."""

    try:
        return await generate_agent_cbt_start(request)
    except Exception as exc:
        if CBT_DEBUG_LOG_ANALYSIS:
            logger.exception(
                "CBT Agent start failed: requestId=%s error=%s",
                request.request_id,
                type(exc).__name__,
            )
        else:
            logger.error(
                "CBT Agent start failed: requestId=%s error=%s",
                request.request_id,
                type(exc).__name__,
            )
        raise HTTPException(
            status_code=502,
            detail="The AI CBT Agent start request failed.",
        ) from exc


async def _run_agent_cbt_turn(request: CbtTurnRequest) -> CbtTurnResponse:
    """비교 실험용 세션 Agent의 다음 턴 오류를 변환합니다."""

    try:
        return await generate_agent_cbt_turn(request)
    except Exception as exc:
        if CBT_DEBUG_LOG_ANALYSIS:
            logger.exception(
                "CBT Agent turn failed: requestId=%s error=%s",
                request.request_id,
                type(exc).__name__,
            )
        else:
            logger.error(
                "CBT Agent turn failed: requestId=%s error=%s",
                request.request_id,
                type(exc).__name__,
            )
        raise HTTPException(
            status_code=502,
            detail="The AI CBT Agent turn request failed.",
        ) from exc


@app.post(
    "/internal/ai/records",
    # 반환 객체도 RecordAnalysis 스키마로 검증하고 OpenAPI 문서에 반영합니다.
    response_model=RecordAnalysis,
    # 응답 JSON은 automatic_thought가 아닌 automaticThought 같은 alias를 사용합니다.
    response_model_by_alias=True,
)
async def run_records_agent(request: AnalyzeRecordRequest) -> RecordAnalysis:
    """Spring Boot가 처음 저장한 감정 원문을 AI 초안으로 구조화합니다."""

    # FastAPI가 요청 JSON을 AnalyzeRecordRequest로 검증한 뒤 여기로 전달합니다.
    return await _run_analysis(
        raw_text=request.raw_text,
    )


@app.post(
    "/internal/ai/reflections/start",
    response_model=CbtTurnResponse,
    response_model_by_alias=True,
)
async def run_cbt_start(request: CbtStartRequest) -> CbtTurnResponse:
    """Spring이 생성한 reflection_sessions의 첫 CBT 질문을 만듭니다."""

    return await _run_cbt_start(request)


@app.post(
    "/internal/ai/reflections/turn",
    response_model=CbtTurnResponse,
    response_model_by_alias=True,
)
async def run_cbt_turn(request: CbtTurnRequest) -> CbtTurnResponse:
    """누적 답변으로 다음 질문 또는 사용자 확인용 유형 제안을 만듭니다."""

    return await _run_cbt_turn(request)


@app.post(
    "/internal/ai/reflections/agent/start",
    response_model=CbtTurnResponse,
    response_model_by_alias=True,
)
async def run_agent_cbt_start(request: CbtStartRequest) -> CbtTurnResponse:
    """비교 실험용 인메모리 CBT Agent를 시작합니다."""

    return await _run_agent_cbt_start(request)


@app.post(
    "/internal/ai/reflections/agent/turn",
    response_model=CbtTurnResponse,
    response_model_by_alias=True,
)
async def run_agent_cbt_turn(request: CbtTurnRequest) -> CbtTurnResponse:
    """살아 있는 Agent를 진행하거나 전체 이력으로 다시 수화합니다."""

    return await _run_agent_cbt_turn(request)


@app.delete(
    "/internal/ai/reflections/agent/{session_id}",
    status_code=204,
)
async def stop_agent_cbt_session(
    session_id: int = ApiPath(gt=0),
) -> Response:
    """사용자가 CBT를 중단했을 때 인메모리 Agent만 종료합니다."""

    await close_agent_cbt_session(session_id)
    return Response(status_code=204)


if __name__ == "__main__":
    # `python app.py`로 직접 실행했을 때만 개발 서버를 시작합니다.
    # `uvicorn app:app`으로 실행하면 이 블록은 실행되지 않습니다.
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8000,
    )
