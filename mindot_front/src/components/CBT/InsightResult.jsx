import { useState } from 'react'
import {
  classifyDistortionChanges,
  getAfterDistortionCodes,
  getBeforeDistortionCodes,
} from '../../utils/reflections/distortionComparison.js'
import { distortionLabels } from './distortionLabels.js'

const distortionOptions = Object.entries(distortionLabels)
const labelText = (code) => distortionLabels[code] ?? code

function DistortionBadges({ codes, emptyText, phase, onRemove }) {
  if (!codes.length) return <p className="cbt-distortion-empty">{emptyText}</p>

  return (
    <ul className="cbt-distortion-badges">
      {codes.map((code) => (
        <li key={code}>
          <span>{labelText(code)}</span>
          {onRemove && (
            <button
              type="button"
              onClick={() => onRemove(code)}
              aria-label={`${phase} ${labelText(code)} 라벨 제거`}
            >
              제거
            </button>
          )}
        </li>
      ))}
    </ul>
  )
}

function DistortionLabelEditor({ phase, title, description, codes, onChange, disabled }) {
  const [selectedCode, setSelectedCode] = useState('')
  const selectId = `cbt-${phase.toLowerCase()}-distortion-add`
  const availableOptions = distortionOptions.filter(([code]) => !codes.includes(code))
  const availableCodes = new Set(availableOptions.map(([code]) => code))
  const activeSelectedCode = availableCodes.has(selectedCode) ? selectedCode : ''

  const addLabel = () => {
    if (!activeSelectedCode) return
    onChange([...codes, activeSelectedCode])
    setSelectedCode('')
  }

  return (
    <section className="cbt-distortion-phase" aria-label={`${phase} 인지왜곡 라벨`}>
      <header>
        <span>{phase}</span>
        <div>
          <h4>{title}</h4>
          <p>{description}</p>
        </div>
      </header>

      <DistortionBadges
        codes={codes}
        phase={phase}
        emptyText="선택한 라벨이 없습니다."
        onRemove={disabled ? null : (code) => onChange(codes.filter((item) => item !== code))}
      />

      {!disabled && (
        <div className="cbt-distortion-add">
          <label htmlFor={selectId}>{phase} 라벨 선택</label>
          <div>
            <select
              id={selectId}
              value={activeSelectedCode}
              onChange={(event) => setSelectedCode(event.target.value)}
              disabled={!availableOptions.length}
            >
              <option value="">추가할 라벨 선택</option>
              {availableOptions.map(([code, label]) => (
                <option key={code} value={code}>{label}</option>
              ))}
            </select>
            <button type="button" disabled={!activeSelectedCode} onClick={addLabel}>
              {phase} 라벨 추가
            </button>
          </div>
        </div>
      )}
    </section>
  )
}

function DistortionComparison({ beforeCodes, afterCodes, onBeforeChange, onAfterChange, disabled }) {
  const changes = classifyDistortionChanges(beforeCodes, afterCodes)
  const editable = Boolean(onBeforeChange && onAfterChange)
  const changeGroups = [
    { key: 'removed', title: '제거', description: '성찰 후에는 선택되지 않은 라벨', codes: changes.removed },
    { key: 'persisted', title: '지속', description: '성찰 전후에 모두 남은 라벨', codes: changes.persisted },
    { key: 'new', title: '신규', description: '성찰 후 새롭게 확인한 라벨', codes: changes.new },
  ]

  return (
    <section className="cbt-distortion-comparison" aria-label="BEFORE/AFTER 인지왜곡 비교">
      <header>
        <h3>인지왜곡 라벨 전후 비교</h3>
        <p>라벨은 진단이 아니라, 생각을 돌아보기 위한 분류입니다.</p>
      </header>

      <div className="cbt-distortion-phases">
        {editable ? (
          <>
            <DistortionLabelEditor
              phase="BEFORE"
              title="성찰 전 생각"
              description="처음 생각에서 확인한 라벨"
              codes={beforeCodes}
              onChange={onBeforeChange}
              disabled={disabled}
            />
            <DistortionLabelEditor
              phase="AFTER"
              title="성찰 후 생각"
              description="새롭게 바라본 생각에서 확인한 라벨"
              codes={afterCodes}
              onChange={onAfterChange}
              disabled={disabled}
            />
          </>
        ) : (
          <>
            <section className="cbt-distortion-phase" aria-label="BEFORE 인지왜곡 라벨">
              <header><span>BEFORE</span><div><h4>성찰 전 생각</h4><p>처음 생각에서 확인한 라벨</p></div></header>
              <DistortionBadges codes={beforeCodes} emptyText="확인된 라벨이 없습니다." />
            </section>
            <section className="cbt-distortion-phase" aria-label="AFTER 인지왜곡 라벨">
              <header><span>AFTER</span><div><h4>성찰 후 생각</h4><p>새롭게 바라본 생각에서 확인한 라벨</p></div></header>
              <DistortionBadges codes={afterCodes} emptyText="확인된 라벨이 없습니다." />
            </section>
          </>
        )}
      </div>

      <div className="cbt-distortion-changes" aria-label="인지왜곡 라벨 변화">
        {changeGroups.map((group) => (
          <article className={`is-${group.key}`} key={group.key}>
            <header><strong>{group.title}</strong><span>{group.codes.length}개</span></header>
            <p>{group.description}</p>
            <DistortionBadges codes={group.codes} emptyText="해당 라벨 없음" />
          </article>
        ))}
      </div>
    </section>
  )
}

export default function InsightResult({
  result,
  reviews = {},
  onReview,
  disabled = false,
  beforeCodes,
  afterCodes,
  onBeforeChange,
  onAfterChange,
}) {
  if (!result) return null

  const resolvedBeforeCodes = beforeCodes ?? getBeforeDistortionCodes(result)
  const resolvedAfterCodes = afterCodes ?? getAfterDistortionCodes(result)
  const hasStoredComparison = Array.isArray(result.beforeDistortions)
    || Array.isArray(result.afterDistortions)

  return <section className="cbt-distortion-reviews cbt-insight-result" aria-label="성찰 결과와 생각 패턴">
    {result.beforeCorrection && <aside className="cbt-insight-original">
      <span>처음 기록한 원문</span>
      <p>{result.originalBeforeText}</p>
    </aside>}

    <div className="cbt-insight-thoughts">
      <article><span>처음 생각</span><p>{result.beforeText}</p></article>
      <article className="is-reframed"><span>새롭게 바라본 생각</span><p>{result.afterText}</p></article>
    </div>

    {result.comparisonExplanation && <div className="cbt-insight-comparison">
      <strong>생각의 변화</strong>
      <p>{result.comparisonExplanation}</p>
    </div>}

    <section className="cbt-insight-patterns">
      <h3>AI가 제안한 성찰 전 생각 패턴</h3>
      <p className="cbt-insight-pattern-description">제안이 내 생각과 맞는지 확인하거나 거절할 수 있습니다.</p>
      {result.assessmentType === 'NO_CLEAR_DISTORTION' && <p className="cbt-insight-empty">뚜렷하게 확인된 생각 패턴은 없습니다.</p>}
      {result.assessmentType === 'UNDETERMINED' && <p className="cbt-insight-empty">생각의 변화는 확인했지만, 현재 내용만으로 특정 패턴을 판단하기는 어렵습니다.</p>}
      {(result.suggestions ?? []).map((suggestion) => {
        const reviewStatus = result.reviews?.find((review) => review.code === suggestion.code)?.reviewStatus
        return <article className="cbt-insight-pattern" key={suggestion.code}>
          <strong>{labelText(suggestion.code)}</strong>
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

    {(onBeforeChange && onAfterChange) || hasStoredComparison ? (
      <DistortionComparison
        beforeCodes={resolvedBeforeCodes}
        afterCodes={resolvedAfterCodes}
        onBeforeChange={onBeforeChange}
        onAfterChange={onAfterChange}
        disabled={disabled}
      />
    ) : null}

    {(result.evidenceForText || result.evidenceAgainstText) && <div className="cbt-insight-evidence">
      {result.evidenceForText && <article><span>처음 생각을 뒷받침한 내용</span><p>{result.evidenceForText}</p></article>}
      {result.evidenceAgainstText && <article><span>다르게 볼 수 있었던 내용</span><p>{result.evidenceAgainstText}</p></article>}
    </div>}
  </section>
}
