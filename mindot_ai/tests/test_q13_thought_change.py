"""Prepared Q13 regressions; scripted fixtures only, never model-quality grades."""
import unittest
from copy import deepcopy

from langchain_core.messages import AIMessage

from cbt_session_agent import graph, schema, wire
from cbt_session_agent.contracts import CompletionTechnicalError, Result
from cbt_session_agent.diagnostics import Diagnostics


STAMP = '2026-09-11T00:00:00Z'
FALLBACK = '그 가능성을 고려한 지금도 처음 판단을 어느 정도 사실이라고 느끼시나요?'


def message(number, role, content):
    return dict(messageNumber=number, role=role, content=content, createdAt=STAMP)


def snapshot(*contents, automatic_thought='나는 한 번 실수했으니 일을 전혀 못한다.'):
    rows=[]
    for number, (role, content) in enumerate(contents, 1):
        rows.append(message(number, role, content))
    return dict(mode='RESTORE', sessionId=13, revision=len(rows),
        record=dict(recordId=13, situation='보고서 숫자 하나를 수정했다.',
            automaticThought=automatic_thought, primaryEmotionCode='ANXIETY',
            primaryIntensity=7, beforeBeliefStrength=75, contextCategory='WORK'),
        messages=rows, phase='DIALOGUE', currentProposal=None,
        historicalTypeReviews=[], pendingJob=None)


def established(after, evidence):
    return dict(changeStatus='ESTABLISHED', beforeCorrection=None, afterText=after,
        afterEvidence=evidence, assessmentType='DISTORTION_SUGGESTED',
        suggestions=[dict(code='OVERGENERALIZATION',
            explanation='한 번의 실수를 능력 전체로 넓혀 판단했다.')],
        comparisonExplanation='한 번의 실수와 전체 능력을 구분하도록 생각을 수정했다.',
        evidenceForText=None, evidenceAgainstText=None)


def not_established(reason):
    return dict(changeStatus='NOT_ESTABLISHED', beforeCorrection=None, afterText=None,
        afterEvidence=[], assessmentType='UNDETERMINED', suggestions=[],
        comparisonExplanation=reason, evidenceForText=None, evidenceAgainstText=None)


class FakeBudget:
    def reserve_path(self, requests):
        assert [phase for phase, _ in requests] == ['ASSESSOR', 'ASSESSMENT_REVIEW']


class Q13Provider:
    def __init__(self, candidate, *, accept=True, selection=None):
        self.budget=FakeBudget();self.candidate=deepcopy(candidate);self.accept=accept
        self.selection=selection or ('assess_completion',dict(fallbackQuestion=FALLBACK))
        self.calls=[];self.review_input=None
    def messages(self, phase, payload, pair=()):
        return phase,deepcopy(payload),pair
    def wire(self, phase, payload):
        return dict(phase=phase)
    async def choose(self, messages):
        self.calls.append('SELECT')
        name,args=self.selection
        call=dict(name=name,args=deepcopy(args),id='q13-call')
        return AIMessage(content='',tool_calls=[call]),call
    async def structured(self, phase, payload):
        self.calls.append(phase)
        return deepcopy(self.candidate)
    async def review(self, messages):
        self.calls.append('ASSESSMENT_REVIEW');self.review_input=messages
        return AIMessage(content=''),dict(accept=self.accept,reason='scripted semantic decision')


class Q13Contract(unittest.TestCase):
    def test_fallback_and_change_status_are_strict_internal_contracts(self):
        assess=schema.select_schemas()['assess_completion']
        self.assertEqual(assess['required'],['fallbackQuestion'])
        self.assertEqual(assess['properties']['fallbackQuestion'],{'type':'string'})
        change=schema.assessor_schema()['properties']['changeStatus']
        self.assertEqual(change['enum'],['ESTABLISHED','NOT_ESTABLISHED'])
        outcomes=Result.model_json_schema()['properties']['outcome']['enum']
        self.assertEqual(outcomes,['RESTORED','QUESTION','HELP','EXPLAIN_PROPOSAL','PROPOSAL',
            'CONTROL','SAFETY_CLARIFY','SAFETY_STOP','UNRESOLVED'])
        self.assertEqual((wire.INPUT_TOKEN_LIMIT,wire.REQUEST_BYTE_LIMIT),(48000,196608))

    def test_prompts_preserve_q14_semantic_roles_without_server_keyword_policy(self):
        agent=wire.PROMPTS['SELECT'];assessor=wire.PROMPTS['ASSESSOR'];review=wire.PROMPTS['ASSESSMENT_REVIEW']
        self.assertIn('check_current_thought',schema.select_schemas())
        self.assertTrue(all(concept in agent for concept in
            ('BEFORE','현재 생각 확인','pendingQuestionPurpose','assess_completion','fallbackQuestion')))
        priorities=[agent.index(concept) for concept in
            ('현재 사용자의 명확한 즉시 위험','전체 중단 의사','현재 도움·예시 요청','현재 생각 확인','일반 CBT 질문')]
        self.assertEqual(priorities,sorted(priorities))
        self.assertTrue(all(concept in assessor for concept in
            ('유일한 Assessor','ESTABLISHED','NOT_ESTABLISHED','실제 USER 발화','다른 가능성')))
        self.assertTrue(all(concept in review for concept in
            ('다시 수행하거나 뒤집지 않는다','원문 충실도','왜곡 근거','유형 적합성')))


class Q13Transitions(unittest.IsolatedAsyncioTestCase):
    async def run_case(self, snap, candidate, *, accept=True, selection=None):
        provider=Q13Provider(candidate,accept=accept,selection=selection)
        result=await graph.execute(deepcopy(snap),provider,Diagnostics())
        return result,provider

    async def test_explicit_and_implicit_user_revision_can_be_established(self):
        cases=[
            ('실수 하나로 능력 전체를 판단한 게 성급했어요. 이번 실수만 고치면 돼요.',),
            ('처음엔 완전히 무능하다고 느꼈어요.','지금은 그 확신이 많이 줄었고 이번 일만의 문제로 보여요.'),
        ]
        for contents in cases:
            with self.subTest(contents=contents):
                turns=[]
                for index,content in enumerate(contents):
                    if index:turns.append(('ASSISTANT','지금 생각은 처음과 비교해 어떤가요?'))
                    turns.append(('USER',content))
                snap=snapshot(*turns)
                evidence=[dict(messageNumber=m['messageNumber'],quote=m['content'])
                    for m in snap['messages'] if m['role']=='USER']
                candidate=established('이번 실수와 능력 전체는 구분해서 볼 수 있다.',evidence)
                result,provider=await self.run_case(snap,candidate)
                self.assertEqual((result['outcome'],result['phase']),('PROPOSAL','PROPOSAL_REVIEW'))
                self.assertEqual(provider.calls,['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])
                self.assertNotIn('changeStatus',result['currentProposal'])

    async def test_only_possibility_counterevidence_insufficient_information_factual_before_and_ai_echo_fall_back(self):
        cases=[
            ('팀장이 바빴을 가능성도 있어요.','다른 가능성만 언급'),
            ('직접 불이익을 준 적은 없어요.','반대 근거만 언급'),
            ('확인할 정보가 부족해요.','현재 생각 불분명'),
            ('마감일은 오늘이었어요.','사실적이고 비례적인 최초 생각'),
            ('가상 예시처럼 이번 실수만 고치면 된다고 말하면 되나요?','AI 예시 반복'),
        ]
        for content,reason in cases:
            with self.subTest(reason=reason):
                thought='마감일은 오늘이다.' if '사실적' in reason else '팀장이 나 때문에 화가 났다.'
                result,provider=await self.run_case(snapshot(('USER',content),automatic_thought=thought),
                    not_established(reason))
                self.assertEqual((result['outcome'],result['phase'],result['currentProposal']),
                    ('QUESTION','DIALOGUE',None))
                self.assertEqual(result['text'],FALLBACK)
                self.assertIsNone(result['issue'])
                self.assertEqual(provider.calls,['SELECT','ASSESSOR'])

    async def test_multiple_user_turns_can_jointly_support_real_revision(self):
        snap=snapshot(('USER','그 판단은 표정 하나만 보고 내린 추측이었어요.'),
            ('ASSISTANT','그 점을 반영하면 지금 생각은 어떤가요?'),
            ('USER','지금은 나 때문이라고 확정할 수 없다고 생각해요.'),
            automatic_thought='팀장이 나 때문에 화가 났다.')
        evidence=[dict(messageNumber=1,quote=snap['messages'][0]['content']),
            dict(messageNumber=3,quote=snap['messages'][2]['content'])]
        result,provider=await self.run_case(snap,
            established('팀장이 나 때문에 화났다고 확정할 수 없다.',evidence))
        self.assertEqual(result['outcome'],'PROPOSAL')
        self.assertEqual(provider.calls,['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])

    async def test_not_established_and_review_rejection_reuse_same_fallback_without_extra_call(self):
        snap=snapshot(('USER','다른 이유도 있을 수는 있어요.'))
        result,provider=await self.run_case(snap,not_established('현재 생각이 불분명'))
        self.assertEqual((result['outcome'],result['phase'],result['text'],result['currentProposal'],result['issue']),
            ('QUESTION','DIALOGUE',FALLBACK,None,None))
        self.assertEqual(provider.calls,['SELECT','ASSESSOR'])
        user=snap['messages'][0]
        candidate=established('한 번의 실수와 능력 전체는 다르다.',
            [dict(messageNumber=1,quote=user['content'])])
        result,provider=await self.run_case(snap,candidate,accept=False)
        self.assertEqual((result['outcome'],result['phase'],result['text'],result['currentProposal'],result['issue']),
            ('QUESTION','DIALOGUE',FALLBACK,None,None))
        self.assertEqual(provider.calls,['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])

    async def test_malformed_contradictory_and_invalid_quote_are_not_normal_non_establishment(self):
        snap=snapshot(('USER','실수 하나로 전체를 판단한 게 성급했어요.'))
        valid=established('이번 실수와 능력 전체는 다르다.',
            [dict(messageNumber=1,quote=snap['messages'][0]['content'])])
        malformed=deepcopy(valid);malformed.pop('changeStatus')
        with self.assertRaises(CompletionTechnicalError):
            await self.run_case(snap,malformed)
        contradictory=not_established('모순 후보');contradictory['afterText']='바뀐 생각'
        result,_=await self.run_case(snap,contradictory)
        self.assertEqual((result['outcome'],result['issue']),('UNRESOLVED','INVALID_CANDIDATE'))
        invalid=deepcopy(valid);invalid['afterEvidence'][0]['quote']='USER가 말하지 않은 문장'
        result,_=await self.run_case(snap,invalid)
        self.assertEqual((result['outcome'],result['issue']),('UNRESOLVED','INVALID_CANDIDATE'))

    async def test_fallback_uses_existing_display_length_boundary(self):
        snap=snapshot(('USER','다른 가능성도 있어요.'))
        selection=('assess_completion',dict(fallbackQuestion='가'*501))
        with self.assertRaises(CompletionTechnicalError):
            await self.run_case(snap,not_established('불분명'),selection=selection)


if __name__ == '__main__':
    unittest.main()
