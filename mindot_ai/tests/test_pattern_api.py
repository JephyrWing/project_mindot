import pytest
from conftest import PATTERNS, PRIVATE

class Test_FA_FUNC_019:
    def test_allowed_codes_deduplicated_and_recommendation_formatted(self, client, pattern_request, pattern_draft, pattern_mock):
        pattern_draft.update(repeatedDistortionCodes=["MIND_READING", "INVALID", "MIND_READING"],
                             recommendation="과거와 비슷한 흐름인지 살펴볼까요.")
        response = client.post(PATTERNS, json=pattern_request)
        assert response.status_code == 200
        body = response.json()
        assert set(body) == {"patternSummary", "repeatedDistortionCodes", "helpfulAlternativeThought", "recommendation"}
        assert body["repeatedDistortionCodes"] == ["MIND_READING"]
        assert body["recommendation"] == "과거와 비슷한 흐름인지 살펴볼까요?"
        pattern_mock.assert_awaited_once()

    @pytest.mark.parametrize("index", [None, 0, -1, 2])
    def test_invalid_candidate_index_is_null(self, client, pattern_request, pattern_draft, pattern_mock, index):
        pattern_draft["helpfulCaseIndex"] = index
        response = client.post(PATTERNS, json=pattern_request)
        assert response.status_code == 200
        assert response.json()["helpfulAlternativeThought"] is None

    @pytest.mark.parametrize("field", ["patternSummary", "recommendation"])
    def test_statistics_only_is_unusable(self, client, pattern_request, pattern_draft, pattern_mock, field):
        pattern_draft[field] = "3건 중 2건"
        response = client.post(PATTERNS, json=pattern_request)
        assert response.status_code == 502
        assert response.json() == {"detail": "The AI pattern explanation request failed."}

    @pytest.mark.parametrize("case", ["exception", "timeout", "missing_output", "invalid_output"])
    def test_failure_is_generic_without_retry(self, client, pattern_request, pattern_mock, case):
        if case == "exception": pattern_mock.side_effect = RuntimeError(PRIVATE)
        elif case == "timeout": pattern_mock.side_effect = TimeoutError(PRIVATE)
        elif case == "missing_output": pattern_mock.return_value = {}
        else: pattern_mock.return_value = {"structured_response": {"invalid": PRIVATE}}
        response = client.post(PATTERNS, json=pattern_request)
        assert response.status_code == 502
        assert response.json() == {"detail": "The AI pattern explanation request failed."}
        assert PRIVATE not in response.text
        pattern_mock.assert_awaited_once()
