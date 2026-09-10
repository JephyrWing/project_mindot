"""Render retrieved, user-confirmed evidence without inventing a new assessment.

Retrieval/ownership/completion filtering belongs to Spring. This endpoint does
not classify the current record or turn legacy alternatives into new insights.
"""
from collections import Counter
from pydantic import BaseModel, ConfigDict, Field


class SimilarCase(BaseModel):
    model_config = ConfigDict(extra='forbid')
    reflectionSessionId: int
    situationText: str | None
    automaticThought: str | None
    alternativeThoughtText: str | None
    helpfulnessScore: int | None
    confirmedDistortionCodes: list[str]
    resultFormatVersion: str
    confirmedResult: dict | None


class PatternRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    emotionRecordId: int
    situationText: str | None
    automaticThought: str | None
    primaryEmotionCode: str | None
    similarCases: list[SimilarCase] = Field(max_length=100)


def explain(request: PatternRequest) -> dict:
    counts = Counter()
    choices = []
    for case in request.similarCases:
        if case.resultFormatVersion == 'cbt-insight-1':
            confirmed = case.confirmedResult or {}
            if confirmed.get('userConfirmed') is not True:
                continue
            codes = {r['code'] for r in confirmed.get('reviews', []) if r.get('reviewStatus') == 'CONFIRMED'}
            text = confirmed.get('afterText')
            label = '확인한 수정 생각'
        else:
            codes = set(case.confirmedDistortionCodes)
            text = case.alternativeThoughtText
            label = '기존 성찰의 대안적 사고'
        counts.update(codes)
        if text and (case.helpfulnessScore or 0) >= 3:
            choices.append((case.helpfulnessScore, f'{label}: {text}'))
    repeated = sorted(c for c, count in counts.items() if count >= 2)
    summary = ('유사한 완료 성찰에서 두 번 이상 수락한 유형: ' + ', '.join(repeated)
               if repeated else '유사한 완료 성찰에서 두 번 이상 수락한 인지왜곡 유형은 없습니다.')
    return dict(patternSummary=summary, repeatedDistortionCodes=repeated,
                helpfulAlternativeThought=max(choices, key=lambda item: item[0])[1] if choices else None,
                recommendation='과거에 확인한 생각이 이번 상황에도 맞는지 직접 살펴보세요. 이 기록만으로 현재 생각의 유형을 판단하지 않습니다.')
