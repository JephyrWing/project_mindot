import asyncio
from copy import deepcopy
import httpx
from conftest import app_module, START, TURN, PRIVATE, new_request, turn_request, question


class Test_FA_FUNC_011:
    def test_success_replay_and_conflicting_payload(self, client, cbt):
        cbt.endpoint.queue.extend([question(), question()])
        initial = client.post(START, json=new_request())
        assert initial.status_code == 200
        assert client.post(START, json=new_request()).json() == initial.json()
        changed_start = new_request()
        changed_start["record"]["automaticThought"] = "다른 생각"
        assert client.post(START, json=changed_start).status_code == 409
        body = turn_request()
        first = client.post(TURN, json=body)
        assert first.status_code == 200
        snapshot = deepcopy(cbt.registry.sessions[101].snapshot)
        assert client.post(TURN, json=body).json() == first.json()
        changed = turn_request(answer="다른 답변")
        conflict = client.post(TURN, json=changed)
        assert conflict.status_code == 409
        assert conflict.json() == {"detail": {"code": "REQUEST_CONFLICT"}}
        assert cbt.registry.sessions[101].snapshot == snapshot
        assert len(cbt.endpoint.calls) == 2

    def test_failed_attempt_requires_explicit_increment(self, client, cbt):
        cbt.endpoint.queue.extend([question(), TimeoutError(PRIVATE), question()])
        assert client.post(START, json=new_request()).status_code == 200
        body = turn_request()
        for _ in range(2):
            response = client.post(TURN, json=body)
            assert response.status_code == 502
            assert response.json() == {"detail": {"code": "GENERATION_FAILED"}}
            assert PRIVATE not in response.text
        assert len(cbt.endpoint.calls) == 2
        snap = cbt.registry.sessions[101].snapshot
        assert [m["role"] for m in snap["messages"]] == ["ASSISTANT", "USER"]
        body["attemptNo"] = 2
        response = client.post(TURN, json=body)
        assert response.status_code == 200
        assert response.json()["attemptNo"] == 2
        assert len(cbt.endpoint.calls) == 3
        assert [m["messageNumber"] for m in cbt.registry.sessions[101].snapshot["messages"]] == [1, 2, 3]


class Test_FA_FUNC_012:
    def test_in_progress_and_delete_prevent_late_commit(self, cbt):
        # All requests share one event loop. Events create deterministic overlap,
        # not sleeps or paid-provider latency.
        async def scenario():
            entered, release = asyncio.Event(), asyncio.Event()
            async def delayed():
                entered.set()
                await release.wait()
                return question()
            cbt.endpoint.queue.extend([question(), delayed])
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app_module.app), base_url="http://test") as client:
                assert (await client.post(START, json=new_request())).status_code == 200
                runtime = cbt.registry.sessions[101]
                pending = asyncio.create_task(client.post(TURN, json=turn_request()))
                try:
                    await asyncio.wait_for(entered.wait(), 5)
                    second = await client.post(TURN, json=turn_request(request_id="concurrent"))
                    assert second.status_code == 409
                    assert second.json() == {"detail": {"code": "IN_PROGRESS"}}
                    before = deepcopy(runtime.snapshot)
                    for _ in range(2):
                        deleted = await client.delete("/internal/ai/reflections/101")
                        assert deleted.status_code == 204 and not deleted.content
                    release.set()
                    response = await asyncio.wait_for(pending, 5)
                    assert response.status_code == 502
                    assert runtime.closed and 101 not in cbt.registry.sessions
                    assert runtime.snapshot == before
                    assert "turn-101" not in runtime.successes
                    assert len(cbt.endpoint.calls) == 2
                finally:
                    release.set()
                    if not pending.done(): pending.cancel()
                    await asyncio.gather(pending, return_exceptions=True)
        asyncio.run(scenario())
