"""Prepared for post-ChatGPT review only; no provider/network/server fixture."""
import unittest
from copy import deepcopy
from unittest.mock import patch
from langchain_core.messages import AIMessage
from cbt_simple.contracts import Start, Turn, ProtocolError
from cbt_simple.state import Registry
from cbt_simple import service

STAMP = '2026-09-09T00:00:00Z'
RECORD = dict(recordId=1, situation='숫자 한 곳을 수정했다.', automaticThought='나는 일을 전혀 못한다.')
ANSWER = '실수 하나로 능력 전체를 판단한 게 성급했네요. 이번 실수만 고치면 되겠어요.'


class FakeBudget:
    def reserve_path(self, requests):
        assert [p for p, _ in requests] == ['ASSESSOR', 'ASSESSMENT_REVIEW']


class FakeProvider:
    """Scripted SDK-independent boundary; actual LangGraph/tool nodes still run."""
    calls = []
    selection = ('write_turn', dict(mode='QUESTION', goal='처음 생각의 근거'))
    candidate = None
    def __init__(self, budget, *args):
        self.budget = FakeBudget()
        self.client = self
        self.moderations = self
    async def create(self, **kwargs):
        self.calls.append('MODERATION')
        return {'results': [{'flagged': False}]}
    def messages(self, phase, payload, pair=()):
        return (phase, deepcopy(payload), pair)
    def wire(self, phase, payload):
        return {'phase': phase}
    async def choose(self, messages):
        self.calls.append('SELECT')
        name, args = self.selection
        call = dict(name=name, args=args, id='fixture-call')
        return AIMessage(content='', tool_calls=[call]), call
    async def structured(self, phase, payload):
        self.calls.append(phase)
        if phase == 'ASSESSOR':
            return deepcopy(self.candidate)
        return {'text': '그때 확인한 사실을 알려 주실 수 있나요?'}
    async def review(self, messages):
        self.calls.append('ASSESSMENT_REVIEW')
        return AIMessage(content=''), dict(accept=True, reason='fixture')
    async def close(self):
        pass


class InsightProtocol(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.registry = Registry()
        FakeProvider.calls = []
        FakeProvider.selection = ('write_turn', dict(mode='QUESTION', goal='처음 생각의 근거'))
        self.patch = patch.object(service, 'Provider', FakeProvider)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    async def new(self):
        return await service.start(Start(mode='NEW', sessionId=1, revision=0, record=RECORD,
            pendingJob=dict(requestId='new', attemptNo=1, inputRevision=0)), registry=self.registry)

    def delta(self, answer=ANSWER, attempt=1):
        return Turn(sessionId=1, requestId='answer', attemptNo=attempt, baseRevision=1, inputRevision=2,
            userMessage=dict(messageNumber=2, role='USER', content=answer, createdAt=STAMP))

    async def test_restore_uses_no_provider_and_preserves_unanswered_assistant(self):
        await self.new()
        snapshot = deepcopy(self.registry.sessions[1].snapshot)
        FakeProvider.calls.clear()
        self.registry.remove(1)
        result = await service.start(Start(**snapshot), registry=self.registry)
        self.assertEqual(result.outcome, 'RESTORED')
        self.assertEqual(FakeProvider.calls, [])
        self.assertEqual(self.registry.sessions[1].snapshot['messages'], snapshot['messages'])

    async def test_cache_replay_rebinds_attempt_without_regeneration_or_append(self):
        await self.new()
        first = await service.turn(self.delta(), registry=self.registry)
        calls = list(FakeProvider.calls)
        retry = await service.turn(self.delta(attempt=2), registry=self.registry)
        self.assertEqual(retry.assistantMessage, first.assistantMessage)
        self.assertEqual(retry.attemptNo, 2)
        self.assertEqual(FakeProvider.calls, calls)
        self.assertEqual(len(self.registry.sessions[1].snapshot['messages']), 3)

    async def test_memory_loss_requests_resync_before_any_model(self):
        with self.assertRaises(ProtocolError) as error:
            await service.turn(self.delta(), registry=self.registry)
        self.assertEqual(error.exception.code, 'RESYNC_REQUIRED')
        self.assertEqual(FakeProvider.calls, [])

    async def test_restored_pending_user_is_not_appended_twice(self):
        await self.new()
        snapshot = deepcopy(self.registry.sessions[1].snapshot)
        delta = self.delta()
        snapshot.update(revision=2, pendingJob=dict(requestId='answer', attemptNo=1, inputRevision=2, userMessageNumber=2))
        snapshot['messages'].append(delta.userMessage.model_dump(mode='json'))
        self.registry.remove(1)
        await service.start(Start(**snapshot), registry=self.registry)
        await service.turn(delta, registry=self.registry)
        self.assertEqual([m['role'] for m in self.registry.sessions[1].snapshot['messages']], ['ASSISTANT','USER','ASSISTANT'])

    async def test_same_request_different_text_is_a_conflict(self):
        await self.new()
        await service.turn(self.delta(), registry=self.registry)
        with self.assertRaises(ProtocolError) as error:
            await service.turn(self.delta('서로 다른 내용'), registry=self.registry)
        self.assertEqual(error.exception.code, 'REQUEST_CONFLICT')

    async def test_assessment_and_same_agent_review_then_explanation_and_withdrawal(self):
        await self.new()
        FakeProvider.selection = ('assess_completion', {})
        FakeProvider.candidate = dict(beforeCorrection=None, afterText='실수 하나가 내 능력 전체의 증거는 아니다.',
            afterEvidence=[dict(messageNumber=2, quote=ANSWER)], assessmentType='UNDETERMINED', suggestions=[],
            comparisonExplanation='능력 전체에 대한 단정을 수정했다.', evidenceForText=None, evidenceAgainstText=None)
        result = await service.turn(self.delta(), registry=self.registry)
        self.assertEqual(FakeProvider.calls[-3:], ['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])
        self.assertEqual(result.outcome, 'PROPOSAL')
        proposal = result.currentProposal
        FakeProvider.selection = ('write_turn', dict(mode='EXPLAIN_PROPOSAL', goal='같은 제안을 설명'))
        explanation = Turn(sessionId=1, requestId='explain', attemptNo=1, baseRevision=3, inputRevision=4,
            userMessage=dict(messageNumber=4, role='USER', content='쉽게 설명해 주세요.', createdAt=STAMP))
        result = await service.turn(explanation, registry=self.registry)
        self.assertEqual(result.currentProposal, proposal)
        FakeProvider.selection = ('write_turn', dict(mode='QUESTION', goal='사용자의 철회를 반영'))
        withdrawal = Turn(sessionId=1, requestId='withdraw', attemptNo=1, baseRevision=5, inputRevision=6,
            userMessage=dict(messageNumber=6, role='USER', content='생각이 바뀐 것은 아니에요.', createdAt=STAMP))
        result = await service.turn(withdrawal, registry=self.registry)
        self.assertIsNone(result.currentProposal)

    async def test_null_after_is_unresolved_not_approvable_or_technical_retry(self):
        await self.new()
        FakeProvider.selection = ('assess_completion', {})
        FakeProvider.candidate = dict(beforeCorrection=None, afterText=None, afterEvidence=[],
            assessmentType='UNDETERMINED', suggestions=[], comparisonExplanation='변화 근거 없음',
            evidenceForText=None, evidenceAgainstText=None)
        result = await service.turn(self.delta(), registry=self.registry)
        self.assertEqual(result.outcome, 'UNRESOLVED')
        self.assertIsNone(result.currentProposal)
        self.assertNotIn('ASSESSMENT_REVIEW', FakeProvider.calls)
