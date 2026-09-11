"""Pattern explanations synthesize retrieved CBT cases without count claims."""
import json
import unittest

from PatternExplainLLM import PATTERN_MODEL, SYSTEM_PROMPT, PatternRequest, explain


def case(
    sid,
    *,
    codes=None,
    situation="업무에서 실수했다",
    thought="나는 무능하다.",
    after="실수 하나로 능력 전체를 판단할 수 없다.",
    score=4,
    version="cbt-insight-1",
):
    return dict(
        reflectionSessionId=sid,
        situationText=situation,
        automaticThought=thought,
        alternativeThoughtText=after,
        helpfulnessScore=score,
        confirmedDistortionCodes=codes or [],
        resultFormatVersion=version,
        confirmedResult=(
            dict(userConfirmed=True, afterText=after, reviews=[])
            if version == "cbt-insight-1"
            else None
        ),
    )


class FakeAgent:
    def __init__(self, response):
        self.response = response
        self.inputs = []

    async def ainvoke(self, value):
        self.inputs.append(value)
        return {"structured_response": self.response}


class ConfirmedAfterPattern(unittest.IsolatedAsyncioTestCase):
    def request(self, cases):
        return PatternRequest(
            emotionRecordId=7,
            situationText="친구들과 모임을 마친 뒤 불안했다",
            automaticThought="내가 분위기를 망쳤다고 생각할 것 같다",
            primaryEmotionCode="ANXIETY",
            similarCases=cases,
        )

    async def test_semantic_evidence_is_sent_without_count_statistics(self):
        cases = [
            case(
                1,
                codes=["MIND_READING"],
                situation="회의가 끝난 뒤 동료의 표정이 떠올랐다",
                thought="동료가 나를 부족하다고 생각할 것 같다",
                after="표정만으로 동료의 생각을 알 수는 없다.",
                score=5,
            ),
            case(
                2,
                codes=["MIND_READING"],
                situation="친구들과 저녁 모임을 마치고 집에 왔다",
                thought="친구들이 내 말을 이상하게 여겼을 것 같다",
                after="친구들의 생각을 확인하기 전에는 여러 가능성이 있다.",
                score=3,
            ),
        ]
        agent = FakeAgent(
            {
                "patternSummary": (
                    "사람들과 함께한 자리를 마친 뒤, 상대가 자신을 부정적으로 "
                    "봤을 것이라고 짐작하며 불안해지는 흐름이 이어졌어요."
                ),
                "repeatedDistortionCodes": ["MIND_READING"],
                "helpfulCaseIndex": 2,
                "recommendation": (
                    "지금 떠오른 생각도 확인된 사실인지 천천히 살펴보면 어떨까요?"
                ),
            }
        )

        result = await explain(self.request(cases), agent=agent)

        self.assertNotIn("건 중", result.patternSummary)
        self.assertEqual(result.repeatedDistortionCodes, ["MIND_READING"])
        self.assertEqual(
            result.helpfulAlternativeThought,
            "확인한 수정 생각: 친구들의 생각을 확인하기 전에는 여러 가능성이 있다.",
        )

        payload = json.loads(agent.inputs[0]["messages"][0]["content"])
        self.assertNotIn("statistics", payload)
        self.assertNotIn("dominantPattern", payload)
        self.assertEqual(
            payload["similarCases"][1]["confirmedAfterText"],
            "친구들의 생각을 확인하기 전에는 여러 가능성이 있다.",
        )
        self.assertEqual(
            payload["availableConfirmedDistortions"][0]["code"],
            "MIND_READING",
        )
        self.assertNotIn("count", json.dumps(payload, ensure_ascii=False).lower())

    async def test_ratio_is_removed_and_unprovided_code_is_discarded(self):
        agent = FakeAgent(
            {
                "patternSummary": (
                    "실수 뒤 자신 전체를 부정적으로 평가하는 흐름이 있었어요. "
                    "(7건 중 5건)"
                ),
                "repeatedDistortionCodes": ["LABELING", "UNKNOWN_CODE"],
                "helpfulCaseIndex": None,
                "recommendation": "이번에도 한 사건을 자신 전체의 평가로 넓힌 건 아닌지 살펴볼까요?",
            }
        )
        cases = [
            case(1, codes=["LABELING"]),
            case(2, codes=["LABELING"]),
        ]

        result = await explain(self.request(cases), agent=agent)

        self.assertNotIn("건 중", result.patternSummary)
        self.assertEqual(result.repeatedDistortionCodes, ["LABELING"])

    async def test_model_selects_contextually_relevant_legacy_alternative(self):
        agent = FakeAgent(
            {
                "patternSummary": "비슷한 관계 상황에서 상대의 반응을 미리 짐작하는 흐름이 있었어요.",
                "repeatedDistortionCodes": [],
                "helpfulCaseIndex": 2,
                "recommendation": "지금은 어떤 다른 가능성도 있는지 살펴보면 어떨까요?",
            }
        )
        cases = [
            case(1, score=5, after="업무상 실수는 누구에게나 생길 수 있다."),
            case(
                2,
                situation="친구에게 답장이 오지 않았다",
                thought="친구가 나를 싫어할 것 같다",
                after="답장이 늦는 데에는 내가 모르는 사정도 있을 수 있다.",
                score=3,
                version="legacy",
            ),
        ]

        result = await explain(self.request(cases), agent=agent)

        self.assertEqual(
            result.helpfulAlternativeThought,
            "기존 성찰의 대안적 사고: 답장이 늦는 데에는 내가 모르는 사정도 있을 수 있다.",
        )

    def test_prompt_does_not_discuss_rejected_distortions(self):
        self.assertNotIn("거절", SYSTEM_PROMPT)
        self.assertNotIn("REJECTED", SYSTEM_PROMPT)

    def test_requested_model_is_fixed(self):
        self.assertEqual(PATTERN_MODEL, "gpt-4o-mini")
