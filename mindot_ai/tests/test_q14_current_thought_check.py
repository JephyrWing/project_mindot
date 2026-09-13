"""Prepared Q14 contract tests; not executed during the implementation-only stage."""
import unittest
from copy import deepcopy
from unittest.mock import patch

from cbt_session_agent import graph, service, wire
from cbt_session_agent.contracts import Start, Turn
from cbt_session_agent.state import Registry
from test_insight_protocol import FakeProvider, RECORD, STAMP


CHECK = '처음의 “나는 일을 전혀 못한다”는 판단을 지금은 어떻게 보고 있나요?'
ANSWER = '숫자 하나를 틀린 건 맞지만, 그 일 하나로 능력 전체를 판단하는 건 지나쳤다고 지금은 생각해요.'
FALLBACK = '그 판단을 강하게 만들었던 구체적인 장면과 그렇지 않았던 장면을 하나씩 떠올려 볼까요?'


def candidate(status='NOT_ESTABLISHED'):
    value = dict(changeStatus=status, beforeCorrection=None, afterText=None, afterEvidence=[],
        assessmentType='UNDETERMINED', suggestions=[], comparisonExplanation='현재 생각 변화가 충분히 성립하지 않았다.',
        evidenceForText=None, evidenceAgainstText=None)
    if status == 'ESTABLISHED':
        value.update(afterText='한 번의 실수로 능력 전체를 판단한 것은 지나쳤다.',
            afterEvidence=[dict(messageNumber=2, quote=ANSWER)],
            comparisonExplanation='한 번의 실수를 능력 전체로 넓힌 최초 판단의 범위를 줄였다.')
    return value


class Q14Provider(FakeProvider):
    def __init__(self):
        super().__init__(None)
        self.calls=[]
        self.selection=('check_current_thought',dict(text=CHECK))
        self.candidate=candidate()
        self.select_payloads=[]
        self.assessor_payloads=[]

    async def choose(self, messages):
        self.select_payloads.append(deepcopy(messages[1]['snapshot']))
        return await super().choose(messages)

    async def structured(self, phase, payload):
        if phase == 'ASSESSOR':
            self.assessor_payloads.append(deepcopy(payload))
        return await super().structured(phase,payload)


class CurrentThoughtCheck(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.registry=Registry()
        self.provider=Q14Provider()
        self.patch=patch.object(service,'Provider',return_value=self.provider)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    async def start_check(self):
        return await service.start(Start(mode='NEW',sessionId=14,revision=0,record=RECORD,
            pendingJob=dict(requestId='new',attemptNo=1,inputRevision=0)),registry=self.registry)

    def answer_turn(self):
        return Turn(sessionId=14,requestId='answer',attemptNo=1,baseRevision=1,inputRevision=2,
            userMessage=dict(messageNumber=2,role='USER',content=ANSWER,createdAt=STAMP))

    async def test_check_uses_existing_question_contract_and_internal_purpose_only(self):
        result=await self.start_check()
        runtime=self.registry.sessions[14]
        self.assertEqual((result.outcome,result.phase,result.assistantMessage.content),('QUESTION','DIALOGUE',CHECK))
        self.assertNotIn('_questionPurpose',result.model_dump(mode='json'))
        self.assertNotIn('pendingQuestionPurpose',runtime.snapshot)
        self.assertEqual(runtime.pending_question_purpose,graph.CURRENT_THOUGHT_CHECK)
        self.assertEqual(self.provider.calls,['MODERATION','SELECT'])

    async def test_immediate_answer_reaches_assessor_and_not_established_does_not_repeat_check(self):
        await self.start_check()
        self.provider.selection=('assess_completion',dict(fallbackQuestion=FALLBACK))
        self.provider.candidate=candidate()
        result=await service.turn(self.answer_turn(),registry=self.registry)
        self.assertEqual(self.provider.assessor_payloads[-1]['snapshot']['pendingQuestionPurpose'],graph.CURRENT_THOUGHT_CHECK)
        self.assertEqual(self.provider.calls[-2:],['SELECT','ASSESSOR'])
        self.assertEqual((result.outcome,result.phase,result.assistantMessage.content),('QUESTION','DIALOGUE',FALLBACK))
        self.assertNotEqual(result.assistantMessage.content,CHECK)
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)

    async def test_help_request_keeps_current_thought_question_pending(self):
        await self.start_check()
        help_text='처음 판단과 지금 판단이 어떻게 다른지를 편한 말로 답하면 돼요.'
        self.provider.selection=('offer_help',dict(text=help_text))
        base=self.answer_turn()
        help_turn=base.model_copy(update=dict(requestId='help',
            userMessage=base.userMessage.model_copy(update={'content':'어떻게 답해야 할지 예를 들어 주세요.'})))
        result=await service.turn(help_turn,registry=self.registry)
        self.assertEqual((result.outcome,result.assistantMessage.content),('HELP',help_text))
        self.assertEqual(self.registry.sessions[14].pending_question_purpose,graph.CURRENT_THOUGHT_CHECK)

    async def test_established_answer_keeps_three_call_ceiling_and_returns_proposal(self):
        await self.start_check()
        self.provider.selection=('assess_completion',dict(fallbackQuestion=FALLBACK))
        self.provider.candidate=candidate('ESTABLISHED')
        before=len(self.provider.calls)
        result=await service.turn(self.answer_turn(),registry=self.registry)
        generated=[name for name in self.provider.calls[before:] if name != 'MODERATION']
        self.assertEqual(generated,['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])
        self.assertEqual((result.outcome,result.phase),('PROPOSAL','PROPOSAL_REVIEW'))
        self.assertEqual(result.currentProposal['afterText'],candidate('ESTABLISHED')['afterText'])
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)

    async def test_restore_clears_volatile_marker_but_preserves_chronological_history(self):
        await self.start_check()
        snapshot=deepcopy(self.registry.sessions[14].snapshot)
        self.registry.remove(14)
        restored=await service.start(Start(**snapshot),registry=self.registry)
        runtime=self.registry.sessions[14]
        self.assertEqual(restored.outcome,'RESTORED')
        self.assertIsNone(runtime.pending_question_purpose)
        self.assertEqual([m['content'] for m in runtime.snapshot['messages']],[CHECK])
        context=wire.messages('SELECT',dict(snapshot=runtime.snapshot))[1].content
        self.assertIn(CHECK,context)


if __name__ == '__main__':
    unittest.main()
