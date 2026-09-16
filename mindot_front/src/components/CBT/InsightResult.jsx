import { distortionLabels } from './distortionLabels.js'

export default function InsightResult({ result, reviews = {}, onReview, disabled = false }) {
  if (!result) return null

  return <section className="cbt-distortion-reviews cbt-insight-result" aria-label="성찰 결과와 생각 패턴">
    {result.beforeCorrection && <aside className="cbt-insight-original">
      <span>처음 기록한 원문</span>
      <p>{result.originalBeforeText}</p>
    </aside>}

    <div className="cbt-insight-thoughts">
      <article>
        <span>처음 생각</span>
        <p>{result.beforeText}</p>
      </article>
      <article className="is-reframed">
        <span>새롭게 바라본 생각</span>
        <p>{result.afterText}</p>
      </article>
    </div>

    {result.comparisonExplanation && <div className="cbt-insight-comparison">
      <strong>생각의 변화</strong>
      <p>{result.comparisonExplanation}</p>
    </div>}

    <section className="cbt-insight-patterns">
      <h3>알아차린 생각 패턴</h3>
      <p className="cbt-insight-pattern-description">성찰 과정에서 살펴본 생각의 경향입니다.</p>
      {result.assessmentType === 'NO_CLEAR_DISTORTION' && <p className="cbt-insight-empty">뚜렷하게 확인된 생각 패턴은 없습니다.</p>}
      {result.assessmentType === 'UNDETERMINED' && <p className="cbt-insight-empty">생각의 변화는 확인했지만, 현재 내용만으로 특정 패턴을 판단하기는 어렵습니다.</p>}
      {(result.suggestions ?? []).map((suggestion) => {
        const reviewStatus = result.reviews?.find((review) => review.code === suggestion.code)?.reviewStatus
        return <article className="cbt-insight-pattern" key={suggestion.code}>
          <strong>{distortionLabels[suggestion.code] ?? suggestion.code}</strong>
          <p>{suggestion.explanation}</p>
          {onReview ? <label>
            <span>이 설명이 내 생각과 맞나요?</span>
            <select required disabled={disabled} value={reviews[suggestion.code] ?? ''} onChange={(event) => onReview(suggestion.code, event.target.value)}>
              <option value="">선택해 주세요</option>
              <option value="CONFIRMED">맞아요</option>
              <option value="REJECTED">맞지 않아요</option>
            </select>
          </label> : reviewStatus && <span className={`cbt-insight-review is-${reviewStatus.toLowerCase()}`}>
            {reviewStatus === 'CONFIRMED' ? '내가 확인한 패턴' : '동의하지 않은 제안'}
          </span>}
        </article>
      })}
    </section>

    {(result.evidenceForText || result.evidenceAgainstText) && <div className="cbt-insight-evidence">
      {result.evidenceForText && <article><span>처음 생각을 뒷받침한 내용</span><p>{result.evidenceForText}</p></article>}
      {result.evidenceAgainstText && <article><span>다르게 볼 수 있었던 내용</span><p>{result.evidenceAgainstText}</p></article>}
    </div>}
  </section>
}
