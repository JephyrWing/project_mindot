"""Prepared Q14 contract tests; not executed during this source-fix stage."""
import json
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


def candidate(status='NOT_ESTABLISHED', *, message_number=2, quote=ANSWER):
    value = dict(changeStatus=status, beforeCorrection=None, afterText=None, afterEvidence=[],
        assessmentType='UNDETERMINED', suggestions=[], comparisonExplanation='현재 생각 변화가 충분히 성립하지 않았다.',
        evidenceForText=None, evidenceAgainstText=None)
    if status == 'ESTABLISHED':
        value.update(afterText='한 번의 실수로 능력 전체를 판단한 것은 지나쳤다.',
            afterEvidence=[dict(messageNumber=message_number, quote=quote)],
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

    def turn(self, request_id, base_revision, message_number, content):
        return Turn(sessionId=14,requestId=request_id,attemptNo=1,
            baseRevision=base_revision,inputRevision=base_revision+1,
            userMessage=dict(messageNumber=message_number,role='USER',content=content,createdAt=STAMP))

    def answer_turn(self):
        return self.turn('answer',1,2,ANSWER)

    def test_agent_contract_recovers_only_help_interleaved_unanswered_check(self):
        agent=wire.PROMPTS['SELECT']
        self.assertTrue(all(concept in agent for concept in (
            '전체 시간순 대화를 뒤에서부터',
            '가장 최신의 아직 답변되지 않은 현재 생각 확인 질문',
            '도움·설명·예시 응답으로 이루어진 교환만',
            '직전 ASSISTANT 메시지일 필요는 없다',
        )))
        self.assertTrue(all(blocker in agent for blocker in (
            '현재 안전 문제','명시적인 일시 중단·완전 종료',
            '포기·철회·전환','일반 CBT 질문','proposal 전이',
        )))
        self.assertIn('ESTABLISHED 또는 NOT_ESTABLISHED는 Assessor만 판단한다',agent)

    async def request_help(self):
        help_text='처음 판단과 지금 판단이 어떻게 다른지를 편한 말로 답하면 돼요.'
        self.provider.selection=('offer_help',dict(text=help_text))
        result=await service.turn(self.turn('help',1,2,'어떻게 답해야 할지 예를 들어 주세요.'),registry=self.registry)
        self.assertEqual((result.outcome,result.assistantMessage.content),('HELP',help_text))
        return result

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

    async def test_help_exchange_then_actual_answer_reaches_assessor_live(self):
        await self.start_check()
        await self.request_help()
        self.assertEqual(self.registry.sessions[14].pending_question_purpose,graph.CURRENT_THOUGHT_CHECK)
        self.provider.selection=('assess_completion',dict(fallbackQuestion=FALLBACK))
        self.provider.candidate=candidate()
        result=await service.turn(self.turn('answer-after-help',3,4,ANSWER),registry=self.registry)
        assessed=self.provider.assessor_payloads[-1]['snapshot']
        self.assertEqual(assessed['pendingQuestionPurpose'],graph.CURRENT_THOUGHT_CHECK)
        self.assertEqual([m['role'] for m in assessed['messages']],['ASSISTANT','USER','ASSISTANT','USER'])
        self.assertEqual((result.outcome,result.assistantMessage.content),('QUESTION',FALLBACK))
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)

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
        self.assertEqual(result.currentProposal['resultFormatVersion'],'cbt-insight-1')
        self.assertEqual(result.currentProposal['basedOnRevision'],2)
        self.assertTrue(result.currentProposal['proposalId'])
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)

    async def test_restore_after_help_uses_real_chronological_history_for_assessor_routing(self):
        await self.start_check()
        await self.request_help()
        snapshot=deepcopy(self.registry.sessions[14].snapshot)
        self.registry.remove(14)
        restored=await service.start(Start(**snapshot),registry=self.registry)
        runtime=self.registry.sessions[14]
        self.assertEqual(restored.outcome,'RESTORED')
        self.assertIsNone(runtime.pending_question_purpose)
        self.provider.selection=('assess_completion',dict(fallbackQuestion=FALLBACK))
        self.provider.candidate=candidate()
        result=await service.turn(self.turn('restored-answer',3,4,ANSWER),registry=self.registry)
        selected_snapshot=self.provider.select_payloads[-1]
        self.assertIsNone(selected_snapshot['pendingQuestionPurpose'])
        chronological=[json.loads(message.content) for message in
            wire.messages('SELECT',dict(snapshot=selected_snapshot))[2:]]
        self.assertEqual([(row['speaker'],row['content']) for row in chronological],[
            ('ASSISTANT',CHECK),
            ('USER','어떻게 답해야 할지 예를 들어 주세요.'),
            ('ASSISTANT','처음 판단과 지금 판단이 어떻게 다른지를 편한 말로 답하면 돼요.'),
            ('USER',ANSWER),
        ])
        self.assertEqual(self.provider.calls[-2:],['SELECT','ASSESSOR'])
        self.assertEqual((result.outcome,result.assistantMessage.content),('QUESTION',FALLBACK))

    async def test_safety_clears_pending_continuation(self):
        await self.start_check()
        before=list(self.provider.calls)
        safety=await service.turn(self.turn('safety',1,2,'지금 칼로 손목을 긋고 있어요.'),registry=self.registry)
        self.assertEqual(safety.outcome,'SAFETY_STOP')
        self.assertEqual(self.provider.calls,before)
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)

    async def test_explicit_control_clears_pending_continuation(self):
        await self.start_check()
        self.provider.selection=('respond_control',{})
        control=await service.turn(self.turn('control',1,2,'이 기능은 여기서 멈출게요.'),registry=self.registry)
        self.assertEqual(control.outcome,'CONTROL')
        self.assertNotIn('ASSESSOR',self.provider.calls)
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)

    async def test_topic_abandonment_is_not_restored_as_pending_check(self):
        await self.start_check()
        ordinary='그 주제는 그만하고 이번 회의에서 실제로 확인된 사실부터 볼까요?'
        self.provider.selection=('ask_question',dict(text=ordinary))
        shifted=await service.turn(self.turn('shift',1,2,'그 생각 얘기는 그만하고 회의 상황을 다시 볼래요.'),registry=self.registry)
        self.assertEqual((shifted.outcome,shifted.assistantMessage.content),('QUESTION',ordinary))
        self.assertIsNone(self.registry.sessions[14].pending_question_purpose)
        snapshot=deepcopy(self.registry.sessions[14].snapshot)
        self.registry.remove(14)
        await service.start(Start(**snapshot),registry=self.registry)
        self.provider.selection=('ask_question',dict(text='회의에서 직접 들은 말은 무엇이었나요?'))
        result=await service.turn(self.turn('ordinary-answer',3,4,'팀장이 숫자 한 곳만 수정해 달라고 했어요.'),registry=self.registry)
        self.assertEqual(result.outcome,'QUESTION')
        self.assertNotIn('ASSESSOR',self.provider.calls)


if __name__ == '__main__':
    unittest.main()
