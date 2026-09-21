from uuid import UUID
import pytest
from conftest import START, TURN, ANSWER, new_request, turn_request, receipt, question


def assessment(candidate, accept=True):
    return [receipt(tool="assess_completion", args={"fallbackQuestion": "다른 근거도 살펴볼까요?"}),
            receipt(candidate), receipt({"accept": accept, "reason": "fixture"})]


class Test_FA_FUNC_013:
    @pytest.mark.parametrize("case,status,outcome", [
        ("forged_quote", 200, "UNRESOLVED"), ("assistant_quote", 200, "UNRESOLVED"),
        ("blank_after", 200, "UNRESOLVED"), ("invalid_code", 502, None),
        ("mismatched_shape", 200, "UNRESOLVED"), ("missing_field", 502, None)])
    def test_invalid_candidate_never_becomes_proposal(self, client, cbt, candidate, case, status, outcome):
        if case == "forged_quote": candidate["afterEvidence"][0]["quote"] = "사용자가 말하지 않은 문장"
        elif case == "assistant_quote": candidate["afterEvidence"][0].update(messageNumber=1, quote="판단")
        elif case == "blank_after": candidate["afterText"] = "   "
        elif case == "invalid_code":
            candidate.update(assessmentType="DISTORTION_SUGGESTED", suggestions=[{"code": "INVALID", "explanation": "fixture"}])
        elif case == "mismatched_shape": candidate["assessmentType"] = "DISTORTION_SUGGESTED"
        else: candidate.pop("afterText")
        cbt.endpoint.queue.append(question())
        assert client.post(START, json=new_request()).status_code == 200
        cbt.endpoint.queue.extend(assessment(candidate)[:2])  # Review must not run.
        response = client.post(TURN, json=turn_request())
        assert response.status_code == status, response.text
        if status == 200:
            assert response.json()["outcome"] == outcome
            assert response.json()["currentProposal"] is None
        else:
            assert response.json() == {"detail": {"code": "GENERATION_FAILED"}}
        assert cbt.registry.sessions[101].snapshot["currentProposal"] is None
        assert len(cbt.endpoint.calls) == 3  # NEW + SELECT + ASSESSOR, no review.


class Test_FA_FUNC_014:
    def test_proposal_server_fields_explanation_and_withdrawal(self, client, cbt, candidate):
        cbt.endpoint.queue.extend([question(), *assessment(candidate),
            receipt(tool="offer_help", args={"text": "실수 하나와 전체 능력을 구분하는 뜻이에요."}), question()])
        assert client.post(START, json=new_request()).status_code == 200
        response = client.post(TURN, json=turn_request())
        assert response.status_code == 200, response.text
        body = response.json()
        proposal = body["currentProposal"]
        assert body["outcome"] == "PROPOSAL" and body["phase"] == "PROPOSAL_REVIEW"
        assert str(UUID(proposal["proposalId"])) == proposal["proposalId"]
        assert proposal["basedOnRevision"] == 2
        assert proposal["resultFormatVersion"] == "cbt-insight-1"
        assert proposal["beforeText"] == proposal["originalBeforeText"] == new_request()["record"]["automaticThought"]
        assert proposal["afterText"] == ANSWER and "changeStatus" not in proposal
        assert set(body) == {"sessionId", "requestId", "attemptNo", "inputRevision", "revision", "outcome", "phase", "assistantMessage", "currentProposal", "issue"}
        assert len(cbt.endpoint.calls) == 4  # NEW + SELECT + ASSESSOR + review
        explained = client.post(TURN, json=turn_request(3, 4, "explain", "설명해 주세요.")).json()
        assert explained["outcome"] == "EXPLAIN_PROPOSAL"
        assert explained["currentProposal"] == proposal
        withdrawn = client.post(TURN, json=turn_request(5, 6, "withdraw", "제 뜻과 달라서 철회할게요.")).json()
        assert withdrawn["outcome"] == "QUESTION" and withdrawn["currentProposal"] is None
        assert cbt.registry.sessions[101].snapshot["currentProposal"] is None
        assert len(cbt.endpoint.calls) == 6

    def test_internal_question_purpose_is_not_in_response(self, client, cbt):
        cbt.endpoint.queue.append(receipt(tool="check_current_thought"))
        response = client.post(START, json=new_request())
        assert response.status_code == 200
        assert response.json()["outcome"] == "QUESTION"
        assert cbt.registry.sessions[101].pending_question_purpose == "CURRENT_THOUGHT_CHECK"
        assert "purpose" not in response.text.lower()

    def test_rejected_review_returns_fallback(self, client, cbt, candidate):
        cbt.endpoint.queue.extend([question(), *assessment(candidate, accept=False)])
        assert client.post(START, json=new_request()).status_code == 200
        response = client.post(TURN, json=turn_request())
        assert response.status_code == 200
        assert response.json()["outcome"] == "QUESTION"
        assert response.json()["currentProposal"] is None
        assert response.json()["assistantMessage"]["content"] == "다른 근거도 살펴볼까요?"
