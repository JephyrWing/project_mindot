from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

import app as app_module
from cbt_session_agent.contracts import ProtocolError
from conftest import START, TURN, RECORDS, PATTERNS, PRIVATE, new_request, turn_request


client = TestClient(app_module.app)


class Test_FA_FUNC_001:
    """TC-FA-FUNC-001: 서비스 상태와 활성 내부 API 조회."""

    def test_api_contract(self):
        response = client.get("/")
        assert response.status_code == 200
        assert response.json() == {
          "message": "Mindot AI service",
          "swagger": "/docs",
        }

    def test_health_check(self):
        response = client.get("/internal/ai/health")
        assert response.status_code == 200
        assert response.json() == {"status": "ready"}

    def test_active_openapi_without_provider(self, client, cbt):
        response = client.get("/openapi.json")
        assert response.status_code == 200
        paths = response.json()["paths"]
        for path in (RECORDS, START, TURN, PATTERNS):
            assert "post" in paths[path]
        assert "delete" in paths["/internal/ai/reflections/{session_id}"]
        assert not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()


class Test_FA_FUNC_002:
    """TC-FA-FUNC-002: 잘못된 요청과 내부 장애의 HTTP 계약."""

    @pytest.mark.parametrize(
        "body",
        [
            {},
            {"rawText": ""},
            {"rawText": "   "},
        ],
    )
    def test_record_invalid_input(self, monkeypatch, body):
        # 교체 대상인 원래 분석함수가 await가 붙기에 여기에 async를 단다.
        async def should_not_run(*args, **kwargs):
            pytest.fail("잘못된 입력에서 분석 함수가 호출됐습니다.")

        monkeypatch.setattr(
            app_module, "analyze_record", should_not_run
        )

        response = client.post(
            "/internal/ai/records",
            json=body,
        )

        assert response.status_code == 422

    def test_record_analysis_failure(self, monkeypatch):
        private_detail = "TEST_ONLY_PRIVATE_PROVIDER_DETAIL"
        received = []

        async def fake_analyze_record(*, raw_text):
            received.append(raw_text)
            raise RuntimeError(private_detail)

        monkeypatch.setattr(
            app_module, "analyze_record", fake_analyze_record
        )

        response = client.post(
            "/internal/ai/records",
            json={"rawText": "회의에서 질문을 받아 불안했다."},
        )

        # 입력이 분석 함수까지 전달됐는지 확인
        assert received == ["회의에서 질문을 받아 불안했다."]

        # 내부 오류를 정해진 HTTP 응답으로 바꾸는지 확인
        assert response.status_code == 502
        assert response.json() == {
            "detail": "The AI record analysis request failed."
        }
        assert private_detail not in response.text

    @pytest.mark.parametrize("body", [{"rawText": "x" * 10001}])
    def test_oversized_record_input(self, client, record_mock, body):
        assert client.post(RECORDS, json=body).status_code == 422
        record_mock.assert_not_awaited()

    @pytest.mark.parametrize("path", [RECORDS, START, TURN, PATTERNS])
    def test_extra_field_rejected_before_dispatch(self, client, cbt, pattern_request, record_mock, pattern_mock, path):
        body = {RECORDS: {"rawText": "테스트 기록"}, START: new_request(),
                TURN: turn_request(), PATTERNS: pattern_request}[path]
        body["unexpected"] = "forbidden"
        assert client.post(path, json=body).status_code == 422
        assert not cbt.registry.sessions and not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()
        record_mock.assert_not_awaited()
        pattern_mock.assert_not_awaited()

    @pytest.mark.parametrize("path,attribute", [(START, "generate_agent_cbt_start"), (TURN, "generate_agent_cbt_turn")])
    @pytest.mark.parametrize("code,status", [("RESYNC_REQUIRED", 409), ("REQUEST_CONFLICT", 409),
        ("IN_PROGRESS", 409), ("GENERATION_FAILED", 502), ("CANCELLED", 502)])
    def test_protocol_error_mapping(self, client, monkeypatch, path, attribute, code, status):
        mock = AsyncMock(side_effect=ProtocolError(code))
        monkeypatch.setattr(app_module, attribute, mock)
        response = client.post(path, json=new_request() if path == START else turn_request())
        assert response.status_code == status
        assert response.json() == {"detail": {"code": code}}
        mock.assert_awaited_once()

    @pytest.mark.parametrize("path,attribute,detail", [
        (START, "generate_agent_cbt_start", {"code": "GENERATION_FAILED"}),
        (TURN, "generate_agent_cbt_turn", {"code": "GENERATION_FAILED"}),
        (PATTERNS, "explain_pattern", "The AI pattern explanation request failed.")])
    def test_private_exception_hidden(self, client, monkeypatch, pattern_request, path, attribute, detail):
        mock = AsyncMock(side_effect=RuntimeError(PRIVATE))
        monkeypatch.setattr(app_module, attribute, mock)
        body = {RECORDS: {"rawText": "테스트 기록"}, START: new_request(), TURN: turn_request(), PATTERNS: pattern_request}[path]
        response = client.post(path, json=body)
        assert response.status_code == 502
        assert response.json() == {"detail": detail}
        assert PRIVATE not in response.text
        mock.assert_awaited_once()
