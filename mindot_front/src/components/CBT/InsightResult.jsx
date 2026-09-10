export const distortionLabels = {
  ALL_OR_NOTHING_THINKING: '흑백논리', CATASTROPHIZING_FORTUNE_TELLING: '파국화·미래예측',
  DISQUALIFYING_DISCOUNTING_POSITIVE: '긍정적인 면 무시', EMOTIONAL_REASONING: '감정적 추론',
  LABELING: '낙인찍기', MAGNIFICATION_MINIMIZATION: '과장·축소', MENTAL_FILTER_SELECTIVE_ABSTRACTION: '정신적 여과',
  MIND_READING: '독심술', OVERGENERALIZATION: '과잉일반화', PERSONALIZATION: '개인화',
  SHOULD_MUST_STATEMENTS: '당위적 사고', TUNNEL_VISION: '터널 시야',
}
export default function InsightResult({ result, reviews, onReview, disabled = false }) {
  if (!result) return null
  return <section className="cbt-distortion-reviews" aria-label="생각과 인지왜곡 제안">
    {result.beforeCorrection && <p>처음 기록한 원문: {result.originalBeforeText}</p>}
    <h2>처음 생각</h2><p>{result.beforeText}</p>
    <h2>알아차리고 수정한 생각</h2><p>{result.afterText}</p>
    <p>{result.comparisonExplanation}</p>
    <h3>인지왜곡 제안</h3>
    {result.assessmentType === 'NO_CLEAR_DISTORTION' && <p>뚜렷한 인지왜곡 유형을 제안하지 않았습니다.</p>}
    {result.assessmentType === 'UNDETERMINED' && <p>수정한 생각은 확인됐지만, 특정 인지왜곡 유형은 현재 근거로 판단하기 어렵습니다.</p>}
    {(result.suggestions ?? []).map((suggestion) => <div key={suggestion.code}>
      <strong>{distortionLabels[suggestion.code] ?? suggestion.code}</strong><p>{suggestion.explanation}</p>
      {onReview ? <label>이 제안에 대한 내 판단
        <select required disabled={disabled} value={reviews[suggestion.code] ?? ''} onChange={(e) => onReview(suggestion.code, e.target.value)}>
          <option value="">선택해 주세요</option><option value="CONFIRMED">수락</option><option value="REJECTED">거부</option>
        </select>
      </label> : <p>{result.reviews?.find((r) => r.code === suggestion.code)?.reviewStatus === 'CONFIRMED' ? '사용자 수락' : '사용자 거부'}</p>}
    </div>)}
    {result.evidenceForText && <p>처음 생각을 뒷받침한 내용: {result.evidenceForText}</p>}
    {result.evidenceAgainstText && <p>처음 생각과 다른 내용: {result.evidenceAgainstText}</p>}
  </section>
}
