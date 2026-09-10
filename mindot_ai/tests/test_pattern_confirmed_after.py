"""Prepared source only: confirmed thoughts and accepted-type counts are separate."""
import unittest
from pattern_explanation import PatternRequest, explain


def case(sid, reviews, after='실수 하나로 능력 전체를 판단할 수 없다.', score=4):
    return dict(reflectionSessionId=sid, situationText='업무 실수', automaticThought='나는 무능하다.',
        alternativeThoughtText=after, helpfulnessScore=score, confirmedDistortionCodes=[],
        resultFormatVersion='cbt-insight-1', confirmedResult=dict(userConfirmed=True, afterText=after, reviews=reviews))


class ConfirmedAfterPattern(unittest.TestCase):
    def result(self, cases):
        return explain(PatternRequest(emotionRecordId=7, situationText='업무', automaticThought='생각',
            primaryEmotionCode='ANXIETY', similarCases=cases))

    def test_all_rejected_after_is_reused_without_restoring_rejected_labels(self):
        rejected=[dict(code='LABELING',reviewStatus='REJECTED')]
        result=self.result([case(1,rejected),case(2,rejected)])
        self.assertEqual(result['repeatedDistortionCodes'],[])
        self.assertIn('수정 생각',result['helpfulAlternativeThought'])
        self.assertIn('없습니다',result['patternSummary'])

    def test_only_accepted_codes_repeat_in_mixed_cases(self):
        accepted=dict(code='LABELING',reviewStatus='CONFIRMED')
        rejected=dict(code='MIND_READING',reviewStatus='REJECTED')
        result=self.result([case(1,[accepted,rejected]),case(2,[accepted]),case(3,[rejected])])
        self.assertEqual(result['repeatedDistortionCodes'],['LABELING'])

    def test_helpfulness_threshold_and_legacy_label_are_preserved(self):
        legacy=case(2,[])
        legacy.update(resultFormatVersion='legacy',confirmedResult=None,confirmedDistortionCodes=['LABELING'])
        result=self.result([case(1,[],score=2),legacy])
        self.assertTrue(result['helpfulAlternativeThought'].startswith('기존 성찰의 대안적 사고:'))
