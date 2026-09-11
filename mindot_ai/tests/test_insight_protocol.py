"""Prepared for post-ChatGPT review only; no provider/network/server fixture."""
import unittest
from datetime import datetime
from copy import deepcopy
from unittest.mock import patch
from langchain_core.messages import AIMessage
from cbt_session_agent.contracts import Start, Turn, ProtocolError
from cbt_session_agent.state import Registry
from cbt_session_agent import service
from cbt_session_agent.diagnostics import Diagnostics
from cbt_session_agent.safety import detector

STAMP = '2026-09-09T00:00:00Z'
RECORD = dict(recordId=1, situation='숫자 한 곳을 수정했다.', automaticThought='나는 일을 전혀 못한다.')
ANSWER = '실수 하나로 능력 전체를 판단한 게 성급했네요. 이번 실수만 고치면 되겠어요.'
FALLBACK = '그렇게 생각한 근거와 다르게 볼 수 있는 근거를 함께 살펴보면 지금 생각은 어떤가요?'


class FakeBudget:
    def reserve_path(self, requests):
        assert [p for p, _ in requests] == ['ASSESSOR', 'ASSESSMENT_REVIEW']


class FakeProvider:
    """Scripted SDK-independent boundary; actual LangGraph/tool nodes still run."""
    calls = []
    selection = ('ask_question', dict(text='그 판단을 뒷받침하는 구체적인 사실은 무엇인가요?'))
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
        raise AssertionError('unexpected_structured_phase')
    async def review(self, messages):
        self.calls.append('ASSESSMENT_REVIEW')
        return AIMessage(content=''), dict(accept=True, reason='fixture')
    async def close(self):
        pass


class InsightProtocol(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.registry = Registry()
        FakeProvider.calls = []
        FakeProvider.selection = ('ask_question', dict(text='그 판단을 뒷받침하는 구체적인 사실은 무엇인가요?'))
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
        restored = deepcopy(self.registry.sessions[1].snapshot)
        # Pydantic serializes UTC as Z; compare instants while preserving every
        # other snapshot/message field, including content, role and sequence.
        self.assertEqual(len(restored['messages']), len(snapshot['messages']))
        for before, after in zip(snapshot['messages'], restored['messages']):
            self.assertEqual(datetime.fromisoformat(before['createdAt']), datetime.fromisoformat(after['createdAt']))
            after['createdAt'] = before['createdAt']
        self.assertEqual(restored, snapshot)

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
        FakeProvider.selection = ('assess_completion', dict(fallbackQuestion=FALLBACK))
        FakeProvider.candidate = dict(changeStatus='ESTABLISHED', beforeCorrection=None,
            afterText='실수 하나가 내 능력 전체의 증거는 아니다.',
            afterEvidence=[dict(messageNumber=2, quote=ANSWER)], assessmentType='UNDETERMINED', suggestions=[],
            comparisonExplanation='능력 전체에 대한 단정을 수정했다.', evidenceForText=None, evidenceAgainstText=None)
        result = await service.turn(self.delta(), registry=self.registry)
        self.assertEqual(FakeProvider.calls[-3:], ['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])
        self.assertEqual(result.outcome, 'PROPOSAL')
        proposal = result.currentProposal
        FakeProvider.selection = ('offer_help', dict(text='처음에는 한 번의 실수를 능력 전체의 증거로 보았고, 바뀐 생각은 그 실수 하나와 전체 능력을 나누어 본다는 차이예요. 이 설명은 제안을 바꾸거나 승인한다는 뜻이 아니에요.'))
        explanation = Turn(sessionId=1, requestId='explain', attemptNo=1, baseRevision=3, inputRevision=4,
            userMessage=dict(messageNumber=4, role='USER', content='방금 제안에서 제 처음 생각과 바뀐 생각이 어떻게 다른지 조금 쉽게 설명해 주세요.', createdAt=STAMP))
        result = await service.turn(explanation, registry=self.registry)
        self.assertEqual(result.currentProposal, proposal)
        self.assertEqual(result.phase, 'PROPOSAL_REVIEW')
        self.assertEqual(result.outcome, 'EXPLAIN_PROPOSAL')
        self.assertEqual(FakeProvider.calls[-1:], ['SELECT'])
        snapshot = deepcopy(self.registry.sessions[1].snapshot)
        FakeProvider.calls.clear()
        self.registry.remove(1)
        restored = await service.start(Start(**snapshot), registry=self.registry)
        self.assertEqual(restored.currentProposal, proposal)
        self.assertEqual(FakeProvider.calls, [])
        self.assertEqual(self.registry.sessions[1].snapshot['currentProposal'], proposal)
        FakeProvider.selection = ('ask_question', dict(text='지금도 그 실수가 능력 부족의 증거라고 느끼게 하는 구체적인 사실은 무엇인가요?'))
        withdrawal = Turn(sessionId=1, requestId='withdraw', attemptNo=1, baseRevision=5, inputRevision=6,
            userMessage=dict(messageNumber=6, role='USER', content='제가 앞에서 바뀐 것처럼 말했지만 그건 제 뜻이 아니었어요. 지금도 그 실수가 제 능력이 없다는 증거라고 믿고 있어요.', createdAt=STAMP))
        result = await service.turn(withdrawal, registry=self.registry)
        self.assertIsNone(result.currentProposal)
        self.assertEqual((result.outcome,result.phase),('QUESTION','DIALOGUE'))

    async def test_revision_mismatch_requests_resync_without_append_or_model(self):
        await self.new()
        before=deepcopy(self.registry.sessions[1].snapshot)
        calls=list(FakeProvider.calls)
        mismatch=self.delta().model_copy(update={'baseRevision':9,'inputRevision':10})
        with self.assertRaises(ProtocolError) as error:
            await service.turn(mismatch,registry=self.registry)
        self.assertEqual(error.exception.code,'RESYNC_REQUIRED')
        self.assertEqual(FakeProvider.calls,calls)
        self.assertEqual(self.registry.sessions[1].snapshot,before)

    async def test_no_change_and_denied_risk_continue_without_proposal(self):
        await self.new()
        self.assertEqual(FakeProvider.calls, ['MODERATION','SELECT'])
        unchanged='질문 뜻은 이해했지만 아직 제 생각은 그대로예요.'
        FakeProvider.selection=('ask_question',dict(text='그 생각을 그대로 유지하게 하는 가장 구체적인 사실은 무엇인가요?'))
        result=await service.turn(self.delta(unchanged),registry=self.registry)
        self.assertEqual((result.outcome,result.phase,result.currentProposal),('QUESTION','DIALOGUE',None))
        self.assertNotIn('ASSESSOR',FakeProvider.calls)
        denied=Turn(sessionId=1,requestId='denied-risk',attemptNo=1,baseRevision=3,inputRevision=4,
            userMessage=dict(messageNumber=4,role='USER',content='자해하고 싶은 건 아니에요. 실수 때문에 지쳐서 그렇게 들릴까 봐 말하는 거예요.',createdAt=STAMP))
        FakeProvider.selection=('ask_question',dict(text='지쳤다는 느낌과 능력이 없다는 판단을 구분하게 해 주는 사실이 있나요?'))
        result=await service.turn(denied,registry=self.registry)
        self.assertEqual((result.outcome,result.phase,result.currentProposal),('QUESTION','DIALOGUE',None))
        self.assertNotIn(result.outcome,('SAFETY_CLARIFY','SAFETY_STOP','PROPOSAL'))

    async def test_help_example_is_assistant_content_not_user_fact(self):
        await self.new()
        FakeProvider.selection=('offer_help',dict(text='가상의 예로, “회의에서 한 번 답을 놓쳤지만 다른 질문에는 답했다”처럼 한 장면과 전체 능력을 나누어 적을 수 있어요.'))
        result=await service.turn(self.delta('답하는 형식을 가상 예시로 보여 주세요.'),registry=self.registry)
        self.assertEqual((result.outcome,result.phase,result.currentProposal),('HELP','DIALOGUE',None))
        messages=self.registry.sessions[1].snapshot['messages']
        self.assertEqual([m['role'] for m in messages],['ASSISTANT','USER','ASSISTANT'])
        self.assertEqual(messages[1]['content'],'답하는 형식을 가상 예시로 보여 주세요.')
        self.assertIn('가상의 예로',messages[2]['content'])

    async def test_control_keeps_later_and_complete_stop_choices_distinct(self):
        FakeProvider.selection=('respond_control',{})
        result=await self.new()
        self.assertEqual((result.outcome,result.phase,result.currentProposal),('CONTROL','DIALOGUE',None))
        self.assertIn('나중에 이어하기',result.assistantMessage.content)
        self.assertIn('성찰 완전히 중단',result.assistantMessage.content)

    async def test_clear_current_risk_stops_but_past_hypothetical_and_quotes_do_not(self):
        self.assertIsNotNone(detector('지금 칼로 손목을 긋고 있어요.'))
        self.assertIsNone(detector('과거에는 칼로 손목을 긋고 있었지만 지금은 아니에요.'))
        self.assertIsNone(detector('만약 지금 칼로 손목을 긋고 있다면 도움을 요청해야 해요.'))
        self.assertIsNone(detector('소설에서 “지금 칼로 손목을 긋고 있어”라는 문장을 읽었어요.'))
        await self.new()
        calls=list(FakeProvider.calls)
        danger=Turn(sessionId=1,requestId='danger',attemptNo=1,baseRevision=1,inputRevision=2,
            userMessage=dict(messageNumber=2,role='USER',content='지금 칼로 손목을 긋고 있어요.',createdAt=STAMP))
        result=await service.turn(danger,registry=self.registry)
        self.assertEqual((result.outcome,result.phase,result.currentProposal),('SAFETY_STOP','DIALOGUE',None))
        self.assertEqual(FakeProvider.calls,calls)

    async def test_diagnostics_sink_failure_does_not_block_commit(self):
        def broken_sink(_):raise RuntimeError('offline fixture sink failure')
        diagnostics=Diagnostics(sink=broken_sink)
        result=await service.start(Start(mode='NEW',sessionId=2,revision=0,record=RECORD,
            pendingJob=dict(requestId='new',attemptNo=1,inputRevision=0)),registry=self.registry,
            diagnostics=diagnostics)
        self.assertEqual(result.outcome,'QUESTION')
        self.assertEqual(self.registry.sessions[2].snapshot['revision'],1)
        self.assertGreater(diagnostics.counters.get('audit_sink_failure',0),0)

    async def test_null_after_returns_same_fallback_without_review_or_technical_retry(self):
        await self.new()
        FakeProvider.selection = ('assess_completion', dict(fallbackQuestion=FALLBACK))
        FakeProvider.candidate = dict(changeStatus='NOT_ESTABLISHED', beforeCorrection=None,
            afterText=None, afterEvidence=[],
            assessmentType='UNDETERMINED', suggestions=[], comparisonExplanation='변화 근거 없음',
            evidenceForText=None, evidenceAgainstText=None)
        result = await service.turn(self.delta(), registry=self.registry)
        self.assertEqual((result.outcome,result.phase),('QUESTION','DIALOGUE'))
        self.assertEqual(result.assistantMessage.content,FALLBACK)
        self.assertIsNone(result.currentProposal)
        self.assertIsNone(result.issue)
        self.assertEqual(FakeProvider.calls[-2:],['SELECT','ASSESSOR'])
        self.assertNotIn('ASSESSMENT_REVIEW', FakeProvider.calls)
