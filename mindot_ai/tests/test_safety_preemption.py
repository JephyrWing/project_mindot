import json
import pytest
from conftest import START, TURN, PRIVATE, new_request, turn_request, question


class Test_FA_FUNC_015:
    # FA-FUNC-015: 자살 징후 또는 타인 위해 징조를 검증합니다.
    @pytest.mark.parametrize("text", [
        "죽으려고 지금 약을 많이 삼켰어요.",
        "당장 옥상에서 뛰어내리려 해요.",
        "지금 다른 사람에게 총을 쏘려 해요.",
    ])
    def test_current_emergency_preempts_calls(self, client, cbt, text):
        # If preemption regresses, return a valid question so the assertion shows
        # the wrong outcome instead of a missing-fixture/provider error.
        cbt.endpoint.queue.append(question())
        body = new_request()
        body["record"]["automaticThought"] = text
        response = client.post(START, json=body)
        assert response.status_code == 200
        assert response.json()["outcome"] == "SAFETY_STOP"
        assert response.json()["currentProposal"] is None
        assert not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()

    @pytest.mark.parametrize("text", ["예전에 죽으려고 약을 많이 삼켰어요.",
        "만약 죽으려고 지금 약을 많이 삼켰다면 어떻게 될까요?", '"죽으려고 지금 약을 많이 삼켰어요"라는 문장을 읽었어요.',
        "친구가 죽으려고 지금 약을 많이 삼켰어요.", "죽으려고 지금 약을 많이 삼킨 것은 아니에요."])
    def test_noncurrent_minimal_pairs_are_not_deterministically_stopped(self, client, cbt, text):
        cbt.endpoint.queue.append(question())
        body = new_request()
        body["record"]["automaticThought"] = text
        response = client.post(START, json=body)
        assert response.status_code == 200
        assert response.json()["outcome"] == "QUESTION"
        assert len(cbt.endpoint.calls) == 1
        cbt.moderation.assert_awaited_once()

    def test_turn_emergency_preempts_new_calls(self, client, cbt):
        cbt.endpoint.queue.append(question())
        assert client.post(START, json=new_request()).status_code == 200
        cbt.endpoint.calls.clear()
        cbt.moderation.reset_mock()
        response = client.post(TURN, json=turn_request(answer="죽으려고 지금 약을 많이 삼켰어요."))
        assert response.status_code == 200
        assert response.json()["outcome"] == "SAFETY_STOP"
        assert not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()


class Test_FA_FUNC_016:
    @pytest.mark.parametrize("case", ["flagged", "timeout", "exception"])
    def test_moderation_is_advisory_and_failure_is_available_to_agent(self, client, cbt, case):
        if case == "flagged": cbt.moderation.return_value = {"results": [{"flagged": True}]}
        else: cbt.moderation.side_effect = TimeoutError(PRIVATE) if case == "timeout" else RuntimeError(PRIVATE)
        cbt.endpoint.queue.append(question())
        response = client.post(START, json=new_request())
        assert response.status_code == 200
        assert response.json()["outcome"] == "QUESTION"
        assert len(cbt.endpoint.calls) == 1
        cbt.moderation.assert_awaited_once()
        context = json.loads(cbt.endpoint.calls[0]["messages"][1]["content"])
        assert context["moderation"] == {"available": case == "flagged", "flagged": True if case == "flagged" else None}
        assert PRIVATE not in response.text
