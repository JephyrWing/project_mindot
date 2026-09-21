from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

import app as app_module
import records_agent


client = TestClient(app_module.app)
URL = "/internal/ai/records"


@pytest.fixture
def provider_result():
    """LLM이 반환했다고 가정할 데이터. 테스트마다 새로 생성된다."""
    return {
        "record": {
            "situation": "회의에서 질문을 받았다.",
            "automaticThought": "내가 일을 못한다고 생각할까 걱정됐다.",
            "emotions": [
                {"code": " social anxiety ", "intensity": 7}
            ],
            "bodyReaction": None,
            "behavior": None,
            "contextCategory": "SOCIAL_EVALUATION",
            "relatedPersonType": "COLLEAGUE",
        },
        "risk": {
            "level": "NONE",
            "reason": None,
        },
    }


@pytest.fixture
def mock_provider(monkeypatch, provider_result):
    """실제 LLM 호출 대신 준비한 데이터를 반환한다."""
    invoke = AsyncMock(
        return_value={"structured_response": provider_result}
    )

    monkeypatch.setattr(
        records_agent,
        "agent",
        SimpleNamespace(ainvoke=invoke),
    )

    return invoke


PRIVATE = "TEST_ONLY_PRIVATE_PROVIDER_DETAIL"


class Test_FA_FUNC_004:
    """TC-FA-FUNC-004: provider 결과의 Spring 응답 계약 정규화."""

    def test_record_response_contract(self, provider_result, mock_provider):
        """감정 코드 정규화, camelCase 필드, metadata를 검증한다."""
        response = client.post(
            URL,
            json={"rawText": "회의에서 질문을 받아 불안했다."},
        )

        assert response.status_code == 200, response.text
        body = response.json()
        record = body["record"]

        # 앞뒤 공백 제거 → 대문자 → 중간 공백을 밑줄로 변경
        assert record["emotions"] == [
            {"code": "SOCIAL_ANXIETY", "intensity": 7}
        ]

        # 실제 JSON 응답의 필드명은 camelCase여야 한다.
        assert set(record) == {
            "situation",
            "automaticThought",
            "emotions",
            "bodyReaction",
            "behavior",
            "contextCategory",
            "relatedPersonType",
        }

        # 정규화 대상이 아닌 값은 유지된다.
        for field in (
            "situation",
            "automaticThought",
            "bodyReaction",
            "behavior",
            "contextCategory",
            "relatedPersonType",
        ):
            assert record[field] == provider_result["record"][field]

        # metadata는 서버 설정을 이용해 추가된다.
        assert body["meta"] == {
            "model": records_agent.RECORDS_MODEL,
            "promptVersion": records_agent.RECORDS_PROMPT_VERSION,
        }

        mock_provider.assert_awaited_once()

    @pytest.mark.parametrize(
        "situation, category, expected_category",
        [
            (None, "WORK", None),
            ("회의에서 질문을 받았다.", None, "OTHER"),
            ("회의에서 질문을 받았다.", "WORK", "WORK"),
        ],
    )
    def test_context_category_normalization(self,
        provider_result,
        mock_provider,
        situation,
        category,
        expected_category,
    ):
        provider_result["record"]["situation"] = situation
        provider_result["record"]["contextCategory"] = category

        response = client.post(URL, json={"rawText": "테스트 기록"})

        assert response.status_code == 200, response.text
        record = response.json()["record"]

        assert record["situation"] == situation
        assert record["contextCategory"] == expected_category

    def test_none_risk_clears_reason(self, provider_result, mock_provider):
        # NONE인데 reason이 붙어 있는 provider 응답을 의도적으로 만든다.
        provider_result["risk"] = {
            "level": "NONE",
            "reason": "제거되어야 하는 테스트 사유",
        }

        response = client.post(URL, json={"rawText": "테스트 기록"})

        assert response.status_code == 200, response.text
        assert response.json()["risk"] == {
            "level": "NONE",
            "reason": None,
        }

    @pytest.mark.parametrize(
        "intensity, expected_status",
        [
            (0, 200),
            (10, 200),
            (None, 200),
            (-1, 502),
            (11, 502),
        ],
    )
    def test_intensity_contract(self,
        provider_result,
        mock_provider,
        intensity,
        expected_status,
    ):
        provider_result["record"]["emotions"][0]["intensity"] = intensity

        response = client.post(URL, json={"rawText": "테스트 기록"})

        assert response.status_code == expected_status, response.text

        if expected_status == 200:
            emotion = response.json()["record"]["emotions"][0]
            assert emotion["intensity"] == intensity
        else:
            assert response.json() == {
                "detail": "The AI record analysis request failed."
            }


class Test_FA_FUNC_005:
    """TC-FA-FUNC-005: provider timeout·예외·잘못된 출력 처리."""

    @pytest.mark.parametrize("error", [TimeoutError(PRIVATE), ConnectionError(PRIVATE), RuntimeError(PRIVATE)])
    def test_provider_failure_is_bounded(self, mock_provider, error):
        mock_provider.side_effect = error
        response = client.post(URL, json={"rawText": "테스트 기록"})
        assert response.status_code == 502
        assert response.json() == {"detail": "The AI record analysis request failed."}
        assert PRIVATE not in response.text
        mock_provider.assert_awaited_once()  # Service boundary; SDK retries are separate.

    @pytest.mark.parametrize("result", [{}, {"structured_response": None}, {"structured_response": {"invalid": PRIVATE}}])
    def test_malformed_structured_output(self, mock_provider, result):
        mock_provider.return_value = result
        response = client.post(URL, json={"rawText": "테스트 기록"})
        assert response.status_code == 502
        assert PRIVATE not in response.text
        mock_provider.assert_awaited_once()
