from copy import deepcopy
import pytest
from cbt_session_agent.contracts import Start, Message
from conftest import START, TURN, new_request, turn_request, message, question


class Test_FA_FUNC_006:
    @pytest.mark.parametrize("case", ["history", "proposal", "missing_job", "job_revision", "restore_order", "restore_revision", "restore_phase"])
    def test_invalid_snapshot_does_not_create_runtime(self, client, cbt, case):
        body = new_request()
        if case == "history": body["messages"] = [message(1, "USER", "답변")]
        elif case == "proposal": body["currentProposal"] = {"proposalId": "invalid"}
        elif case == "missing_job": body.pop("pendingJob")
        elif case == "job_revision": body["pendingJob"]["inputRevision"] = 1
        else:
            body.update(mode="RESTORE", pendingJob=None)
            if case == "restore_order": body["messages"] = [message(2, "ASSISTANT", "질문")]
            elif case == "restore_revision": body["revision"] = -1
            elif case == "restore_phase": body["phase"] = "PROPOSAL_REVIEW"
        response = client.post(START, json=body)
        assert response.status_code == 422
        assert not cbt.registry.sessions and not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()


class Test_FA_FUNC_007:
    def test_new_generates_one_question(self, client, cbt):
        cbt.endpoint.queue.append(question())
        response = client.post(START, json=new_request())
        assert response.status_code == 200, response.text
        result = response.json()
        assert result["outcome"] == "QUESTION"
        assert (result["inputRevision"], result["revision"]) == (0, 1)
        assert result["assistantMessage"]["messageNumber"] == 1
        assert result["assistantMessage"]["role"] == "ASSISTANT"
        assert result["currentProposal"] is None
        assert len(cbt.endpoint.calls) == 1
        assert len(cbt.registry.sessions[101].snapshot["messages"]) == 1


class Test_FA_FUNC_008:
    @pytest.mark.parametrize("phase", ["DIALOGUE", "PROPOSAL_REVIEW"])
    def test_restore_preserves_snapshot_without_external_calls(self, client, cbt, phase):
        body = new_request()
        body.update(mode="RESTORE", revision=3, pendingJob=None, phase=phase,
                    messages=[message(1, "ASSISTANT", "처음 질문"), message(2, "USER", "답변"), message(3, "ASSISTANT", "다음 질문")],
                    currentProposal={"proposalId": "saved", "afterText": "저장한 생각"} if phase == "PROPOSAL_REVIEW" else None,
                    historicalTypeReviews=[{"code": "MIND_READING", "accepted": False}])
        response = client.post(START, json=body)
        assert response.status_code == 200
        result = response.json()
        assert result["outcome"] == "RESTORED"
        assert result["revision"] == result["inputRevision"] == 3
        assert result["phase"] == phase
        assert result["currentProposal"] == body["currentProposal"]
        assert result["assistantMessage"] is None
        assert cbt.registry.sessions[101].snapshot == Start.model_validate(body).model_dump(mode="json")
        assert not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()


class Test_FA_FUNC_009:
    @pytest.mark.parametrize("case", ["revision", "role", "blank"])
    def test_invalid_turn(self, client, cbt, case):
        body = turn_request()
        if case == "revision": body["inputRevision"] = 9
        elif case == "role": body["userMessage"]["role"] = "ASSISTANT"
        else: body["userMessage"]["content"] = " \t "
        assert client.post(TURN, json=body).status_code == 422
        assert not cbt.registry.sessions and not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()

    @pytest.mark.parametrize("case", ["missing", "stale", "message_number"])
    def test_runtime_mismatch_is_resync(self, client, cbt, case):
        body = turn_request()
        if case != "missing":
            cbt.endpoint.queue.append(question())
            assert client.post(START, json=new_request()).status_code == 200
            cbt.endpoint.calls.clear()
            cbt.moderation.reset_mock()
            if case == "stale": body.update(baseRevision=3, inputRevision=4)
            else: body["userMessage"]["messageNumber"] = 9
        response = client.post(TURN, json=body)
        assert response.status_code == 409
        assert response.json() == {"detail": {"code": "RESYNC_REQUIRED"}}
        assert not cbt.endpoint.calls
        cbt.moderation.assert_not_awaited()


class Test_FA_FUNC_010:
    def test_turn_appends_one_user_and_one_assistant(self, client, cbt):
        cbt.endpoint.queue.extend([question(), question()])
        assert client.post(START, json=new_request()).status_code == 200
        first = deepcopy(cbt.registry.sessions[101].snapshot["messages"][0])
        response = client.post(TURN, json=turn_request())
        assert response.status_code == 200, response.text
        result = response.json()
        snap = cbt.registry.sessions[101].snapshot
        assert result["outcome"] == "QUESTION"
        assert (result["inputRevision"], result["revision"], snap["revision"]) == (2, 3, 3)
        assert [m["role"] for m in snap["messages"]] == ["ASSISTANT", "USER", "ASSISTANT"]
        assert [m["messageNumber"] for m in snap["messages"]] == [1, 2, 3]
        assert snap["messages"][0] == first
        # UTC '+00:00' and 'Z' encode the same timestamp.
        assert Message.model_validate(snap["messages"][-1]) == Message.model_validate(result["assistantMessage"])
        assert snap["pendingJob"] is None
        assert len(cbt.endpoint.calls) == 2
