import { useEffect, useMemo, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import { emotionLabel } from '../../utils/records/emotions.js'
import {
  getEmotionPatternDetail,
  getEmotionPatterns,
  submitEmotionPatternFeedback,
} from '../../utils/patterns/patternsApi.js'
import './PatternInsights.css'

const weekdayLabels = {
  MONDAY: '월요일',
  TUESDAY: '화요일',
  WEDNESDAY: '수요일',
  THURSDAY: '목요일',
  FRIDAY: '금요일',
  SATURDAY: '토요일',
  SUNDAY: '일요일',
}

const timeBucketLabels = {
  DAWN: '새벽',
  MORNING: '아침',
  AFTERNOON: '오후',
  EVENING: '저녁',
  NIGHT: '밤',
}

const patternLevelContent = {
  RECENT: {
    label: '최근 반복',
    description: '같은 주 안에서 여러 번 관찰된 패턴입니다.',
  },
  REPEATED: {
    label: '반복',
    description: '연속된 주에 다시 관찰된 패턴입니다.',
  },
  SUSTAINED: {
    label: '지속',
    description: '여러 주에 걸쳐 이어지고 있는 패턴입니다.',
  },
  LONG_TERM: {
    label: '장기',
    description: '최근 8주 중 여러 주에 걸쳐 관찰된 패턴입니다.',
  },
}

const patternFilters = [
  { value: 'ALL', label: '전체 패턴' },
  ...Object.entries(patternLevelContent).map(([value, content]) => ({
    value,
    label: content.label,
  })),
]

const patternFeedbackContent = {
  HELPFUL: '도움됐어요',
  NOT_HELPFUL: '도움되지 않았어요',
}

const formatDate = (dateValue, includeTime = false) => {
  if (!dateValue) return '날짜 정보 없음'

  const date = includeTime
    ? new Date(dateValue)
    : new Date(`${dateValue}T00:00:00`)

  if (Number.isNaN(date.getTime())) return '날짜 정보 없음'

  return new Intl.DateTimeFormat('ko-KR', includeTime
    ? {
      year: 'numeric', month: 'long', day: 'numeric', weekday: 'short',
      hour: '2-digit', minute: '2-digit',
    }
    : { year: 'numeric', month: 'long', day: 'numeric' }).format(date)
}

const patternTitle = (pattern) => {
  const emotion = emotionLabel(pattern?.emotionCode, '감정 정보 없음')
  const weekday = pattern?.weekday
    ? `${weekdayLabels[pattern.weekday] ?? pattern.weekday} `
    : ''
  const time = timeBucketLabels[pattern?.timeBucket]
    ?? pattern?.timeBucket
    ?? '특정 시간대'

  return `${weekday}${time}에 ${emotion} 감정이 반복됐어요`
}

const patternObservation = (pattern) => {
  const weekday = pattern?.weekday
    ? weekdayLabels[pattern.weekday] ?? pattern.weekday
    : '여러 요일'
  const time = timeBucketLabels[pattern?.timeBucket]
    ?? pattern?.timeBucket
    ?? '특정 시간대'
  const emotion = emotionLabel(pattern?.emotionCode, '해당 감정')

  return `최근 8주 동안 ${weekday} ${time}에 ${emotion} 감정이 ${Number(pattern?.occurrenceCount ?? 0)}회 기록되었습니다.`
}

const getPatternErrorMessage = (error, isDetail = false) => {
  if (!error?.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (isDetail && error.response.status === 404) {
    return '선택한 반복 패턴을 찾을 수 없습니다.'
  }

  return error.response.data?.message
    ?? error.response.data?.detail
    ?? '반복 패턴을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function PatternSummary({ pattern }) {
  const level = patternLevelContent[pattern.patternLevel]
    ?? { label: pattern.patternLevel ?? '패턴', description: '기록에서 반복된 흐름입니다.' }

  return (
    <>
      <div className="pattern-insights-card__heading">
        <div>
          <span className={`pattern-insights-level is-${String(pattern.patternLevel).toLowerCase()}`}>
            {level.label}
          </span>
          <h2>{patternTitle(pattern)}</h2>
        </div>
        {pattern.feedback && (
          <span className="pattern-insights-feedback-done">
            피드백 완료
          </span>
        )}
      </div>
      <p className="pattern-insights-observation">{patternObservation(pattern)}</p>
      <p className="pattern-insights-level-description">{level.description}</p>
      <dl className="pattern-insights-stats">
        <div>
          <dt>기록 횟수</dt>
          <dd>{Number(pattern.occurrenceCount ?? 0)}회</dd>
        </div>
        <div>
          <dt>기록한 날짜</dt>
          <dd>{Number(pattern.distinctDateCount ?? 0)}일</dd>
        </div>
        <div>
          <dt>관찰된 주</dt>
          <dd>{Number(pattern.observedWeekCount ?? 0)}주</dd>
        </div>
      </dl>
    </>
  )
}

function PatternList({ onPatternDetail }) {
  const [patterns, setPatterns] = useState([])
  const [selectedLevel, setSelectedLevel] = useState('ALL')
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    let isActive = true

    const loadPatterns = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const response = await getEmotionPatterns()
        if (isActive) setPatterns(Array.isArray(response) ? response : [])
      } catch (error) {
        if (isActive) {
          setPatterns([])
          setLoadError(getPatternErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadPatterns()
    return () => {
      isActive = false
    }
  }, [reloadCount])

  const displayedPatterns = useMemo(() => selectedLevel === 'ALL'
    ? patterns
    : patterns.filter((pattern) => pattern.patternLevel === selectedLevel),
  [patterns, selectedLevel])

  return (
    <>
      <header className="pattern-insights-header">
        <div>
          <span>패턴 분석</span>
          <h1>반복 패턴</h1>
          <p>최근 8주의 확정 감정 기록에서 반복된 시간과 감정의 흐름을 확인합니다.</p>
        </div>
        {!isLoading && !loadError && (
          <strong>확인된 패턴 {patterns.length}개</strong>
        )}
      </header>

      {!isLoading && !loadError && patterns.length > 0 && (
        <div className="pattern-insights-filter">
          <label htmlFor="pattern-level-filter">반복 정도</label>
          <select
            id="pattern-level-filter"
            value={selectedLevel}
            onChange={(event) => setSelectedLevel(event.target.value)}
          >
            {patternFilters.map((filter) => (
              <option value={filter.value} key={filter.value}>{filter.label}</option>
            ))}
          </select>
        </div>
      )}

      {isLoading ? (
        <div className="pattern-insights-state" role="status">
          반복 패턴을 확인하는 중입니다.
        </div>
      ) : loadError ? (
        <div className="pattern-insights-state is-error" role="alert">
          <p>{loadError}</p>
          <button type="button" onClick={() => setReloadCount((count) => count + 1)}>
            다시 시도
          </button>
        </div>
      ) : patterns.length === 0 ? (
        <div className="pattern-insights-state">
          <strong>아직 확인된 반복 패턴이 없습니다.</strong>
          <p>감정 기록이 쌓이면 같은 감정이 반복되는 시간과 요일을 이곳에서 확인할 수 있습니다.</p>
        </div>
      ) : displayedPatterns.length === 0 ? (
        <div className="pattern-insights-state">
          선택한 반복 정도에 해당하는 패턴이 없습니다.
        </div>
      ) : (
        <div className="pattern-insights-list">
          {displayedPatterns.map((pattern) => (
            <article className="pattern-insights-card" key={pattern.patternId}>
              <PatternSummary pattern={pattern} />
              <div className="pattern-insights-card__footer">
                <span>
                  {formatDate(pattern.windowStart)} - {formatDate(pattern.windowEnd)}
                </span>
                <button
                  type="button"
                  onClick={() => onPatternDetail(pattern.patternId)}
                  aria-label={`${patternTitle(pattern)} 상세 보기`}
                >
                  근거 기록 보기
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
    </>
  )
}

function PatternDetail({ patternId, onBack }) {
  const [pattern, setPattern] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)
  const [isSubmittingFeedback, setIsSubmittingFeedback] = useState(false)
  const [feedbackError, setFeedbackError] = useState('')
  const [feedbackMessage, setFeedbackMessage] = useState('')

  useEffect(() => {
    let isActive = true

    const loadPattern = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const response = await getEmotionPatternDetail(patternId)
        if (isActive) setPattern(response)
      } catch (error) {
        if (isActive) {
          setPattern(null)
          setLoadError(getPatternErrorMessage(error, true))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadPattern()
    return () => {
      isActive = false
    }
  }, [patternId, reloadCount])

  const handleFeedback = async (feedback) => {
    setIsSubmittingFeedback(true)
    setFeedbackError('')
    setFeedbackMessage('')

    try {
      const response = await submitEmotionPatternFeedback(patternId, feedback)
      setPattern((current) => ({
        ...current,
        feedback: response.feedback,
        feedbackAt: response.feedbackAt,
      }))
      setFeedbackMessage('피드백을 저장했습니다.')
    } catch (error) {
      setFeedbackError(getPatternErrorMessage(error, true))
    } finally {
      setIsSubmittingFeedback(false)
    }
  }

  if (isLoading) {
    return <div className="pattern-insights-state" role="status">패턴 근거를 불러오는 중입니다.</div>
  }

  if (loadError) {
    return (
      <div className="pattern-insights-state is-error" role="alert">
        <p>{loadError}</p>
        <div>
          <button type="button" onClick={onBack}>목록으로</button>
          <button type="button" onClick={() => setReloadCount((count) => count + 1)}>다시 시도</button>
        </div>
      </div>
    )
  }

  const evidenceRecords = pattern?.evidenceRecords ?? []

  return (
    <>
      <button className="pattern-insights-back" type="button" onClick={onBack}>
        반복 패턴 목록으로
      </button>

      <article className="pattern-insights-detail">
        <header>
          <span>패턴 상세</span>
          <h1>{patternTitle(pattern)}</h1>
          <p>{patternObservation(pattern)}</p>
        </header>

        <section aria-labelledby="pattern-detail-summary-title">
          <h2 id="pattern-detail-summary-title">관찰된 흐름</h2>
          <PatternSummary pattern={pattern} />
          <p className="pattern-insights-caution">
            이 내용은 기록에서 함께 나타난 흐름을 보여 주며, 원인이나 진단을 의미하지 않습니다.
          </p>
        </section>

        <section aria-labelledby="pattern-evidence-title">
          <div className="pattern-insights-section-heading">
            <div>
              <h2 id="pattern-evidence-title">근거 기록</h2>
              <p>이 패턴을 확인하는 데 사용된 확정 기록입니다.</p>
            </div>
            <strong>{evidenceRecords.length}건</strong>
          </div>

          {evidenceRecords.length === 0 ? (
            <div className="pattern-insights-evidence-empty">현재 확인할 수 있는 근거 기록이 없습니다.</div>
          ) : (
            <div className="pattern-insights-evidence-list">
              {evidenceRecords.map((record) => (
                <article key={record.emotionRecordId}>
                  <header>
                    <div>
                      <strong>기록 #{record.emotionRecordId}</strong>
                      <span>
                        {emotionLabel(record.primaryEmotionCode)}
                        {Number.isFinite(record.primaryIntensity)
                          ? ` · 강도 ${record.primaryIntensity}/10`
                          : ''}
                      </span>
                    </div>
                    <time dateTime={record.occurredAt}>{formatDate(record.occurredAt, true)}</time>
                  </header>
                  <dl>
                    <div>
                      <dt>상황</dt>
                      <dd>{record.situationText || '상황 정보가 없습니다.'}</dd>
                    </div>
                    <div>
                      <dt>기록 내용</dt>
                      <dd>{record.rawText || '기록 내용이 없습니다.'}</dd>
                    </div>
                  </dl>
                </article>
              ))}
            </div>
          )}
        </section>

        <section className="pattern-insights-feedback" aria-labelledby="pattern-feedback-title">
          <h2 id="pattern-feedback-title">이 패턴과 관점이 도움이 되었나요?</h2>
          <p>선택한 답변은 서버에 저장되며 언제든 다시 바꿀 수 있습니다.</p>
          <div>
            {Object.entries(patternFeedbackContent).map(([value, label]) => (
              <button
                type="button"
                key={value}
                aria-pressed={pattern.feedback === value}
                className={pattern.feedback === value ? 'is-selected' : ''}
                disabled={isSubmittingFeedback}
                onClick={() => handleFeedback(value)}
              >
                {label}
              </button>
            ))}
          </div>
          {isSubmittingFeedback && <p role="status">피드백을 저장하는 중입니다.</p>}
          {feedbackMessage && <p className="is-success" role="status">{feedbackMessage}</p>}
          {feedbackError && <p className="is-error" role="alert">{feedbackError}</p>}
        </section>
      </article>
    </>
  )
}

function PatternInsights({
  patternId = null,
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onPatternDetail,
  onBack,
  onHome,
}) {
  return (
    <main className="pattern-insights-page">
      <Navbar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onDailyCare={onDailyCare}
        onHome={onHome}
      />

      <div className="pattern-insights-content">
        {patternId
          ? <PatternDetail patternId={patternId} onBack={onBack} />
          : <PatternList onPatternDetail={onPatternDetail} />}
      </div>
    </main>
  )
}

export default PatternInsights
