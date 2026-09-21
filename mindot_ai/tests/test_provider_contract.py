from copy import deepcopy
import pytest
from cbt_session_agent import provider
from cbt_session_agent.contracts import CompletionTechnicalError
from cbt_session_agent.diagnostics import Diagnostics
from conftest import START, TURN, new_request, turn_request, question, receipt


class Test_FA_FUNC_017:
    @pytest.mark.parametrize("case", ["choices", "tools", "refusal", "truncated", "array", "duplicate_key", "unknown_tool"])
    def test_malformed_selection_is_not_committed_or_retried(self, client, cbt, case):
        raw = question()
        choice = raw["choices"][0]
        msg = choice["message"]
        if case == "choices": raw["choices"].append(deepcopy(choice))
        elif case == "tools": msg["tool_calls"].append(deepcopy(msg["tool_calls"][0]))
        elif case == "refusal": msg["refusal"] = "fixture refusal"
        elif case == "truncated": choice["finish_reason"] = "length"
        elif case == "array": msg["tool_calls"][0]["function"]["arguments"] = "[]"
        elif case == "duplicate_key": msg["tool_calls"][0]["function"]["arguments"] = '{"text":"first","text":"second"}'
        else: msg["tool_calls"][0]["function"]["name"] = "unknown_tool"
        cbt.endpoint.queue.append(raw)
        response = client.post(START, json=new_request())
        assert response.status_code == 502
        assert response.json() == {"detail": {"code": "GENERATION_FAILED"}}
        runtime = cbt.registry.sessions[101]
        assert runtime.snapshot["messages"] == [] and not runtime.successes
        assert len(cbt.endpoint.calls) == 1
        assert client.post(START, json=new_request()).status_code == 502
        assert len(cbt.endpoint.calls) == 1

    @pytest.mark.parametrize("case", ["choices", "refusal", "truncated", "array", "duplicate_key"])
    def test_malformed_assessor_receipt_has_no_review_or_commit(self, client, cbt, candidate, case):
        cbt.endpoint.queue.append(question())
        assert client.post(START, json=new_request()).status_code == 200
        raw = receipt(candidate)
        if case == "choices": raw["choices"] *= 2
        elif case == "refusal": raw["choices"][0]["message"]["refusal"] = "fixture refusal"
        elif case == "truncated": raw["choices"][0]["finish_reason"] = "length"
        elif case == "array": raw["choices"][0]["message"]["content"] = "[]"
        else: raw["choices"][0]["message"]["content"] = '{"a":1,"a":2}'
        cbt.endpoint.queue.extend([receipt(tool="assess_completion", args={"fallbackQuestion": "다른 근거는 무엇인가요?"}), raw])
        response = client.post(TURN, json=turn_request())
        assert response.status_code == 502
        runtime = cbt.registry.sessions[101]
        assert len(runtime.snapshot["messages"]) == 2
        assert runtime.snapshot["currentProposal"] is None
        assert "turn-101" not in runtime.successes
        assert len(cbt.endpoint.calls) == 3

    @pytest.mark.parametrize("field,limit", [("inputEstimate", provider.INPUT_TOKEN_LIMIT), ("requestBytes", provider.REQUEST_BYTE_LIMIT)])
    @pytest.mark.parametrize("offset", [-1, 0, 1])
    def test_capacity_boundaries_before_transport(self, client, cbt, monkeypatch, field, limit, offset):
        # Exact capacity comparison is isolated from tokenizer variability.
        size = dict(inputEstimate=100, requestBytes=100, reservation=1000)
        size[field] = limit + offset
        monkeypatch.setattr(provider, "measure", lambda wire: size)
        cbt.endpoint.queue.append(question())
        response = client.post(START, json=new_request())
        assert response.status_code == (502 if offset > 0 else 200)
        assert len(cbt.endpoint.calls) == (0 if offset > 0 else 1)
        if offset > 0:
            assert response.json() == {"detail": {"code": "GENERATION_FAILED"}}
            assert not cbt.registry.sessions[101].successes

    def test_actual_oversized_input_is_rejected(self, client, cbt):
        body = new_request()
        body["record"]["automaticThought"] = "긴 테스트 입력 " * 25000
        response = client.post(START, json=body)
        assert response.status_code == 502
        assert not cbt.endpoint.calls
        assert not cbt.registry.sessions[101].successes

    def test_budget_allows_only_select_assessor_review_once(self):
        budget = provider.Budget(Diagnostics(), lambda: True)
        for phase in ("SELECT", "ASSESSOR", "ASSESSMENT_REVIEW"):
            budget.admit(phase, {"max_completion_tokens": provider.PHASES[phase]})
        with pytest.raises(CompletionTechnicalError, match="generation_budget_or_phase_order"):
            budget.admit("SELECT", {"max_completion_tokens": 8192})
        assert len(budget.ledger) == 3

    def test_measure_counts_utf8_bytes(self):
        import json
        wire = {"text": "한글", "max_completion_tokens": 8192}
        measured = provider.measure(wire)
        assert measured["requestBytes"] == len(json.dumps(wire, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        assert measured["inputEstimate"] > 64
