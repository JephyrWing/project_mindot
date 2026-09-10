"""Prepared for code review; scripted structure checks, not model quality grades."""
import json
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

from cbt_simple import graph, service, wire, schema
from cbt_simple.contracts import Start, Turn, ProtocolError
from cbt_simple.provider import WriterFormatError
from cbt_simple.state import Registry
from cbt_q11.diagnostics import Diagnostics
from test_insight_protocol import FakeProvider, RECORD, ANSWER, STAMP

FIXTURE = json.loads((Path(__file__).parent/'fixtures'/'canary_noop_correction.json').read_text(encoding='utf-8'))


class WriterInputs(unittest.TestCase):
    def test_definitions_follow_role_and_mode_without_losing_context(self):
        snapshot = deepcopy(FIXTURE['snapshot'])
        snapshot['phase'] = 'PROPOSAL_REVIEW'
        original = deepcopy(snapshot)
        for suggestions in ([{'code': 'OVERGENERALIZATION'}], []):
            snapshot['currentProposal'] = {'suggestions': suggestions, 'afterText': '실수 하나로 전체를 판단할 수 없다.'}
            for phase in ('WRITER', 'WRITER_REPAIR'):
                for mode in ('QUESTION', 'HELP', 'EXPLAIN_PROPOSAL'):
                    with self.subTest(phase=phase, mode=mode, suggestions=suggestions):
                        payload = dict(snapshot=deepcopy(snapshot), mode=mode, goal='한 번의 실수와 전체 능력을 연결한 근거')
                        if phase=='WRITER_REPAIR':payload['formatError']='display_text_format'
                        before = deepcopy(payload)
                        request = wire.wire(phase, payload)
                        context = json.loads(request['messages'][1]['content'])
                        if mode=='EXPLAIN_PROPOSAL':
                            self.assertEqual(context['distortionDefinitions'], [d for d in schema.DEFINITIONS if d['code'] in {s['code'] for s in suggestions}])
                        else:self.assertNotIn('distortionDefinitions', context)
                        for key in ('record', 'currentProposal'):
                            self.assertEqual(context[key], snapshot[key])
                        self.assertEqual(context['goal'], payload['goal'])
                        self.assertEqual(context['mode'], mode)
                        self.assertEqual(payload, before)
                        for row, message in zip(snapshot['messages'], request['messages'][2:]):
                            self.assertEqual(message['role'], row['role'].lower())
                            self.assertEqual(json.loads(message['content']), dict(messageNumber=row['messageNumber'], speaker=row['role'], content=row['content']))
                        self.assertEqual(len(request['messages'])-2, len(snapshot['messages']))
        for phase in ('SELECT', 'ASSESSOR', 'ASSESSMENT_REVIEW'):
            context=json.loads(wire.messages(phase,dict(snapshot=snapshot))[1].content)
            self.assertEqual(context['distortionDefinitions'],schema.DEFINITIONS)
        self.assertEqual(snapshot['messages'],original['messages'])


class Probe(FakeProvider):
    def __init__(self, candidate, *, accept=True, failure=None):
        super().__init__(None)
        self.calls=[];self.candidate=deepcopy(candidate);self.accept=accept
        self.failure=failure;self.review_input=None;self.writer_plans=[]
        self.selection=('assess_completion', {})

    async def structured(self, phase, payload):
        self.writer_plans.append((phase,deepcopy(payload)))
        if self.failure:raise self.failure
        return await super().structured(phase,payload)

    async def review(self, messages):
        self.review_input=messages
        message,_=await super().review(messages)
        return message,dict(accept=self.accept,reason='scripted decision')


class ProposalProcessing(unittest.IsolatedAsyncioTestCase):
    async def run_candidate(self,candidate,snapshot=None,accept=True):
        snapshot=deepcopy(snapshot or FIXTURE['snapshot'])
        provider=Probe(candidate,accept=accept);diagnostics=Diagnostics()
        result=await graph.execute(snapshot,provider,diagnostics)
        return result,provider,diagnostics

    async def test_actual_failed_candidate_keeps_raw_and_uses_one_processed_value(self):
        raw=deepcopy(FIXTURE['candidate']);before=deepcopy(raw)
        with patch.object(graph,'validate',wraps=schema.validate) as validate:
            result,provider,d=await self.run_candidate(raw)
        self.assertEqual(validate.call_count,1)
        self.assertEqual(raw,before)
        self.assertEqual(next(e['candidate'] for e in d.events if e['event']=='candidate'),raw)
        processed=deepcopy(raw);processed['beforeCorrection']=None
        tool=provider.review_input[2][1]
        selection=provider.review_input[2][0]
        self.assertEqual(tool.tool_call_id,selection.tool_calls[0]['id'])
        self.assertEqual(json.loads(tool.content),processed)
        actual_review_messages=wire.messages('ASSESSMENT_REVIEW',provider.review_input[1],provider.review_input[2])
        self.assertIs(actual_review_messages[-1],tool)
        self.assertIs(actual_review_messages[-2],selection)
        self.assertEqual(next(e['result'] for e in d.events if e['event']=='tool_result'),processed)
        self.assertEqual([e['normalizedFields'] for e in d.events if e['event']=='candidate_normalized'],[['beforeCorrection']])
        self.assertEqual(provider.calls,['SELECT','ASSESSOR','ASSESSMENT_REVIEW'])
        self.assertEqual(result['outcome'],'PROPOSAL')
        for k,v in processed.items():self.assertEqual(result['currentProposal'][k],v)
        self.assertEqual(result['currentProposal']['beforeText'],FIXTURE['snapshot']['record']['automaticThought'])
        self.assertIn(processed['afterText'],result['text'])

    async def test_real_correction_survives_but_other_ai_quotes_and_invalid_after_do_not(self):
        snapshot=deepcopy(FIXTURE['snapshot'])
        correction='처음 기록은 내가 이번 보고서를 잘못 썼다는 뜻이었어요.'
        snapshot['messages'].append(dict(messageNumber=5,role='USER',content=correction,createdAt=STAMP))
        valid=deepcopy(FIXTURE['candidate'])
        valid['beforeCorrection']=dict(text=correction,evidence=dict(messageNumber=5,quote=correction))
        result,_,d=await self.run_candidate(valid,snapshot)
        self.assertEqual(result['currentProposal']['beforeCorrection'],valid['beforeCorrection'])
        self.assertEqual(result['currentProposal']['beforeText'],correction)
        self.assertEqual(result['currentProposal']['originalBeforeText'],snapshot['record']['automaticThought'])
        self.assertNotIn('candidate_normalized',[e['event'] for e in d.events])
        for change in ('different_correction','space_only_difference','invalid_after'):
            with self.subTest(change=change):
                raw=deepcopy(FIXTURE['candidate'])
                if change=='different_correction':raw['beforeCorrection']['text']='다른 처음 생각'
                elif change=='space_only_difference':raw['beforeCorrection']['text']+=' '
                else:raw['afterEvidence']=[dict(messageNumber=1,quote=raw['beforeCorrection']['text'])]
                result,provider,_=await self.run_candidate(raw)
                self.assertEqual(result['issue'],'INVALID_CANDIDATE')
                self.assertIsNone(result['currentProposal'])
                self.assertIsNone(provider.review_input)

    async def test_three_unresolved_causes_clear_old_proposal_and_do_not_regenerate(self):
        null=deepcopy(FIXTURE['candidate']);null.update(beforeCorrection=None,afterText=None,afterEvidence=[],suggestions=[],assessmentType='UNDETERMINED')
        invalid=deepcopy(FIXTURE['candidate']);invalid['afterEvidence'][0]['messageNumber']=1
        seen=[]
        for candidate,accept,issue,count in [(null,True,'AFTER_NOT_ESTABLISHED',2),(invalid,True,'INVALID_CANDIDATE',2),(FIXTURE['candidate'],False,'CANDIDATE_REJECTED',3)]:
            snapshot=deepcopy(FIXTURE['snapshot']);snapshot.update(phase='PROPOSAL_REVIEW',currentProposal={'proposalId':'old'})
            result,provider,_=await self.run_candidate(candidate,snapshot,accept)
            self.assertEqual((result['outcome'],result['phase'],result['issue']),('UNRESOLVED','DIALOGUE',issue))
            self.assertIsNone(result['currentProposal']);self.assertEqual(len(provider.calls),count)
            self.assertEqual(result['text'],getattr(graph,issue));seen.append(result['text'])
        self.assertEqual(len(set(seen)),3)

    async def test_malformed_schema_and_provider_errors_remain_technical(self):
        malformed=deepcopy(FIXTURE['candidate']);malformed['beforeCorrection']={'text':FIXTURE['snapshot']['record']['automaticThought']}
        with self.assertRaises(WriterFormatError):await self.run_candidate(malformed)
        for error in (WriterFormatError('json_structure'),TimeoutError('provider timeout')):
            provider=Probe(FIXTURE['candidate'],failure=error)
            with self.assertRaises(type(error)):
                await graph.execute(deepcopy(FIXTURE['snapshot']),provider,Diagnostics())
            self.assertIsNone(provider.review_input)

    async def test_writer_repair_keeps_original_mode_and_payload(self):
        provider=Probe(None);provider.selection=('write_turn',dict(mode='HELP',goal='실제 앞 질문의 뜻 설명'))
        async def structured(phase,payload):
            provider.calls.append(phase);provider.writer_plans.append((phase,deepcopy(payload)))
            if phase=='WRITER':raise WriterFormatError('display_text_format')
            return {'text':'앞 질문의 의미를 설명합니다.'}
        provider.structured=structured
        result=await graph.execute(deepcopy(FIXTURE['snapshot']),provider,Diagnostics())
        first=provider.writer_plans[0][1];repair=provider.writer_plans[1][1]
        self.assertEqual({k:v for k,v in repair.items() if k!='formatError'},first)
        self.assertEqual(provider.calls,['SELECT','WRITER','WRITER_REPAIR'])
        self.assertEqual(result['outcome'],'HELP')

    async def test_failed_turn_preserves_input_and_retry_never_duplicates_delta(self):
        registry=Registry();provider=Probe(None)
        provider.selection=('write_turn',dict(mode='QUESTION',goal='처음 생각의 근거'))
        with patch.object(service,'Provider',return_value=provider):
            await service.start(Start(mode='NEW',sessionId=77,revision=0,record=RECORD,pendingJob=dict(requestId='new',attemptNo=1,inputRevision=0)),registry=registry)
            delta=Turn(sessionId=77,requestId='turn',attemptNo=1,baseRevision=1,inputRevision=2,userMessage=dict(messageNumber=2,role='USER',content=ANSWER,createdAt=STAMP))
            provider.failure=TimeoutError('provider timeout')
            with self.assertRaises(TimeoutError):await service.turn(delta,registry=registry)
            before=list(provider.calls)
            with self.assertRaises(ProtocolError):await service.turn(delta,registry=registry)
            self.assertEqual(provider.calls,before)
            provider.failure=None
            await service.turn(delta.model_copy(update={'attemptNo':2}),registry=registry)
        self.assertEqual([m['role'] for m in registry.sessions[77].snapshot['messages']],['ASSISTANT','USER','ASSISTANT'])
        self.assertEqual(registry.sessions[77].snapshot['messages'][1]['content'],ANSWER)

    async def test_restore_does_not_normalize_preexisting_proposal(self):
        snapshot=deepcopy(FIXTURE['snapshot'])
        proposal=dict(deepcopy(FIXTURE['candidate']),proposalId='saved-proposal')
        snapshot.update(mode='RESTORE',phase='PROPOSAL_REVIEW',currentProposal=proposal,pendingJob=None)
        request=Start(**snapshot);registry=Registry()
        with patch.object(service,'Provider',side_effect=AssertionError('RESTORE must not instantiate Provider')):
            result=await service.start(request,registry=registry)
        self.assertEqual(result.currentProposal,proposal)
        self.assertEqual(registry.sessions[request.sessionId].snapshot,request.model_dump(mode='json'))
