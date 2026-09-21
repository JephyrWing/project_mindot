"""Offline fixtures for the workbook's FastAPI requirements, not live LLM quality."""
from pathlib import Path
from collections import deque
from copy import deepcopy
import json
import os
import re
import socket
import httpx
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

# App imports construct clients. Do not read local credentials or enable tracing.
with patch.dict(os.environ, {
    "PYTHON_DOTENV_DISABLED": "1", "OPENAI_API_KEY": "offline-fixture",
    "LANGSMITH_TRACING": "false", "LANGCHAIN_TRACING_V2": "false",
}):
    import app as app_module
    import records_agent
    import PatternExplainLLM as patterns
from fastapi.testclient import TestClient
from openai.types.chat import ChatCompletion
from cbt_session_agent import service
from cbt_session_agent.state import Registry

STAMP = "2026-09-17T00:00:00Z"
ANSWER = "실수 하나가 내 능력 전체의 증거는 아니에요."
PRIVATE = "TEST_ONLY_PRIVATE_PROVIDER_DETAIL"
START = "/internal/ai/reflections/start"
TURN = "/internal/ai/reflections/turn"
RECORDS = "/internal/ai/records"
PATTERNS = "/internal/ai/patterns/explain"


def message(number, role, content):
    return dict(messageNumber=number, role=role, content=content, createdAt=STAMP)


def new_request():
    return dict(mode="NEW", sessionId=101, revision=0,
                record=dict(recordId=1, situation="숫자 한 곳을 수정했다.",
                            automaticThought="나는 일을 전혀 못한다."),
                pendingJob=dict(requestId="start-101", attemptNo=1, inputRevision=0))


def turn_request(base=1, number=2, request_id="turn-101", answer=ANSWER):
    return dict(sessionId=101, requestId=request_id, attemptNo=1,
                baseRevision=base, inputRevision=base + 1,
                userMessage=message(number, "USER", answer))


def receipt(content=None, *, tool=None, args=None):
    msg = dict(role="assistant", content=json.dumps(content, ensure_ascii=False) if tool is None else None)
    if tool:
        msg["tool_calls"] = [dict(id="call-fixture", type="function",
            function=dict(name=tool, arguments=json.dumps(args or {}, ensure_ascii=False)))]
    return dict(id="chatcmpl-fixture", object="chat.completion", created=1,
                model="gpt-4o-mini", choices=[dict(index=0, message=msg,
                finish_reason="tool_calls" if tool else "stop")])


def question():
    return receipt(tool="ask_question", args={"text": "그 판단을 뒷받침하는 사실은 무엇인가요?"})


class ScriptedEndpoint:
    """Replace only the SDK transport; real Provider/Budget/parser/graph run."""
    def __init__(self):
        self.queue = deque()
        self.calls = []
        self.with_raw_response = SimpleNamespace(create=self.raw_create)

    async def create(self, **kwargs):
        self.calls.append(deepcopy(kwargs))
        if not self.queue:
            pytest.fail("Unexpected provider call: no scripted receipt remains")
        value = self.queue.popleft()
        if isinstance(value, BaseException):
            raise value
        if callable(value):
            value = await value()
        return ChatCompletion.model_validate(deepcopy(value))

    async def raw_create(self, **kwargs):
        result = await self.create(**kwargs)
        return SimpleNamespace(parse=lambda: result, headers={})


@pytest.fixture(autouse=True)
def offline(monkeypatch, request):
    # Do not change the existing cbt_test subfolder fixtures.
    if request.node.path.parent != Path(__file__).parent:
        return
    for key, value in {"OPENAI_API_KEY": "offline-fixture", "PYTHON_DOTENV_DISABLED": "1",
                       "LANGSMITH_TRACING": "false", "LANGCHAIN_TRACING_V2": "false"}.items():
        monkeypatch.setenv(key, value)
    def denied(*args, **kwargs):
        pytest.fail("External network access is forbidden in requirement tests")
    original_connect = socket.socket.connect
    original_connect_ex = socket.socket.connect_ex
    def guarded_connect(sock, address):
        # Windows asyncio implements its internal wakeup pipe with loopback TCP.
        if isinstance(address, tuple) and address[0] in ("127.0.0.1", "::1"):
            return original_connect(sock, address)
        return denied()
    def guarded_connect_ex(sock, address):
        if isinstance(address, tuple) and address[0] in ("127.0.0.1", "::1"):
            return original_connect_ex(sock, address)
        return denied()
    monkeypatch.setattr(socket.socket, "connect", guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", guarded_connect_ex)
    # Block SDK HTTP even on loopback; ASGI/TestClient have their own transports.
    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", denied)
    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", denied)
    monkeypatch.setattr(records_agent, "agent", SimpleNamespace(ainvoke=AsyncMock(side_effect=AssertionError("unmocked records"))))
    monkeypatch.setattr(patterns, "_get_pattern_agent", lambda: SimpleNamespace(ainvoke=AsyncMock(side_effect=AssertionError("unmocked patterns"))))
    match = re.search(r"Test_(?:TC_)?FA_FUNC_(\d{3})", request.node.nodeid)
    if match:
        req_id = "FA-FUNC-" + match.group(1)
        request.node.user_properties.extend([("requirement_id", req_id), ("tc_id", "TC-" + req_id), ("layer", "FastAPI")])


@pytest.fixture
def cbt(monkeypatch):
    registry = Registry()
    endpoint = ScriptedEndpoint()
    moderation = AsyncMock(return_value={"results": [{"flagged": False}]})
    injected = dict(registry=registry, agent_model=endpoint, assessor_model=endpoint,
                    moderation_client=SimpleNamespace(moderations=SimpleNamespace(create=moderation)))
    async def start(req):
        return await service.start(req, **injected)
    async def turn(req):
        return await service.turn(req, **injected)
    async def close(sid):
        return await service.close(sid, registry=registry)
    monkeypatch.setattr(app_module, "generate_agent_cbt_start", start)
    monkeypatch.setattr(app_module, "generate_agent_cbt_turn", turn)
    monkeypatch.setattr(app_module, "close_agent_cbt_session", close)
    return SimpleNamespace(registry=registry, endpoint=endpoint, moderation=moderation,
                           injected=injected)


@pytest.fixture
def client(cbt):
    with TestClient(app_module.app) as client:
        yield client


@pytest.fixture
def candidate():
    return dict(changeStatus="ESTABLISHED", beforeCorrection=None, afterText=ANSWER,
                afterEvidence=[dict(messageNumber=2, quote=ANSWER)],
                assessmentType="UNDETERMINED", suggestions=[],
                comparisonExplanation="한 번의 실수와 전체 능력을 구분했다.",
                evidenceForText=None, evidenceAgainstText=None)


@pytest.fixture
def record_mock():
    """Validation tests only: inspect the fail-closed agent from offline()."""
    return records_agent.agent.ainvoke


@pytest.fixture
def pattern_request():
    return dict(emotionRecordId=1, situationText="회의에서 질문을 받았다.",
                automaticThought="평가가 걱정됐다.", primaryEmotionCode="ANXIETY",
                similarCases=[dict(reflectionSessionId=10, situationText="발표에서 질문을 받았다.",
                    automaticThought="모두 나를 못한다고 생각할 것이다.",
                    alternativeThoughtText="기존 성찰의 생각", helpfulnessScore=3,
                    confirmedDistortionCodes=["MIND_READING"], resultFormatVersion="cbt-insight-1",
                    confirmedResult={"afterText": "질문만으로 상대의 평가를 알 수 없다."})])


@pytest.fixture
def pattern_draft():
    return dict(patternSummary="타인의 평가를 부정적으로 짐작했던 흐름이 있었어요.",
                repeatedDistortionCodes=["MIND_READING"], helpfulCaseIndex=1,
                recommendation="과거와 비슷한 흐름인지 살펴볼까요?")


@pytest.fixture
def pattern_mock(monkeypatch, pattern_draft):
    invoke = AsyncMock(return_value={"structured_response": pattern_draft})
    monkeypatch.setattr(patterns, "_get_pattern_agent", lambda: SimpleNamespace(ainvoke=invoke))
    return invoke
