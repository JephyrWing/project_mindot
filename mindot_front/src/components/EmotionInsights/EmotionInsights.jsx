import { useEffect, useMemo, useState } from 'react'
import Navbar from '../Navbar/Navbar.jsx'
import { emotionLabel } from '../../utils/records/emotions.js'
import { getEmotionInsights } from '../../utils/insights/emotionInsightsApi.js'
import './EmotionInsights.css'

// 백엔드 분류 코드를 사용자에게 표시할 이름과 화면 설명으로 변환하는 설정.
const insightSections = [
  {
    groupBy: 'time',
    title: '시간대별 감정 분포',
    description: '하루 중 어느 시간대에 어떤 감정을 주로 기록했는지 보여 줍니다.',
    labels: {
      DAWN: '새벽',
      MORNING: '아침',
      AFTERNOON: '오후',
      EVENING: '저녁',
      NIGHT: '밤',
      UNSPECIFIED: '시간대 정보 없음',
    },
  },
  {
    groupBy: 'situation',
    title: '상황별 감정 분포',
    description: '감정이 나타난 상황별로 감정 종류와 기록 수를 비교합니다.',
    labels: {
      SOCIAL_EVALUATION: '사회적 평가',
      PERFORMANCE: '발표·시험',
      PROMISE: '약속',
      MISTAKE: '실수',
      CONFLICT: '갈등',
      REJECTION: '거절·소외',
      WORK: '업무',
      STUDY: '학업',
      HEALTH: '건강',
      DAILY_LIFE: '일상',
      OTHER: '기타',
      UNSPECIFIED: '상황 미분류',
    },
  },
  {
    groupBy: 'relationship',
    title: '관계별 감정 분포',
    description: '상황과 관련된 사람의 관계에 따라 반복되는 감정을 살펴봅니다.',
    labels: {
      COLLEAGUE: '직장 동료',
      FRIEND: '친구',
      FAMILY: '가족',
      OTHER: '기타',
      UNSPECIFIED: '관계 정보 없음',
    },
  },
]

// 감정별 분포 구간을 일관된 색으로 구분하기 위한 팔레트 설정.
const emotionColors = {
  ANXIETY: '#376fd0',
  FEAR: '#7658b5',
  ANGER: '#c64f4f',
  FRUSTRATION: '#c57835',
  SADNESS: '#6482a9',
  DISAPPOINTMENT: '#89718d',
  SHAME: '#a15f7a',
  GUILT: '#8a6a54',
  LONELINESS: '#65748b',
  JOY: '#df9c22',
  RELIEF: '#42978a',
  ACHIEVEMENT: '#2780a8',
  CALM: '#4e9a60',
  GRATITUDE: '#88752a',
  EXCITEMENT: '#d36b38',
  OTHER: '#718096',
}
const fallbackEmotionColors = ['#5079b8', '#8a68ad', '#b56d55', '#5c8c7a']

// 사용자 정의 감정도 새로고침 후 같은 색을 사용하도록 문자열 기반 색상 선택.
const getEmotionColor = (emotionCode) => {
  if (emotionColors[emotionCode]) return emotionColors[emotionCode]

  const hash = [...emotionCode].reduce(
    (total, character) => total + character.codePointAt(0),
    0,
  )
  return fallbackEmotionColors[hash % fallbackEmotionColors.length]
}

// API 오류 상태에 맞는 인사이트 조회 안내 문구 반환.
const getInsightErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  return error.response.data?.message
    ?? error.response.data?.detail
    ?? '감정 인사이트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 백엔드의 감정 개수 객체를 화면에서 반복 가능한 항목으로 변환.
const createEmotionItems = (emotionCounts) => Object.entries(emotionCounts ?? {})
  .map(([code, count]) => ({
    code,
    label: emotionLabel(code),
    count: Number(count),
  }))
  .filter((item) => Number.isFinite(item.count) && item.count > 0)
  .sort((firstItem, secondItem) => (
    secondItem.count - firstItem.count
    || firstItem.label.localeCompare(secondItem.label, 'ko-KR')
  ))

// 한 분류 기준의 그룹별 감정 비율과 표본 수를 표시하는 영역.
function DistributionSection({ definition, insight }) {
  const groups = insight?.groups ?? []

  return (
    <section
      className="emotion-insights-section"
      aria-labelledby={`emotion-insights-${definition.groupBy}`}
    >
      <div className="emotion-insights-section__heading">
        <div>
          <h2 id={`emotion-insights-${definition.groupBy}`}>
            {definition.title}
          </h2>
          <p>{definition.description}</p>
        </div>
        <span>{Number(insight?.totalSampleCount ?? 0)}개 기록 기준</span>
      </div>

      {groups.length === 0 ? (
        <div className="emotion-insights-empty">
          표시할 확정 기록이 없습니다.
        </div>
      ) : (
        <div className="emotion-insights-groups">
          {groups.map((group) => {
            const sampleCount = Number(group.sampleCount) || 0
            const emotionItems = createEmotionItems(group.emotionCounts)
            const groupLabel = definition.labels[group.groupCode] ?? group.groupCode

            return (
              <article className="emotion-insights-group" key={group.groupCode}>
                <div className="emotion-insights-group__title">
                  <h3>{groupLabel}</h3>
                  <span>표본 {sampleCount}개</span>
                </div>

                <div
                  className="emotion-insights-bar"
                  role="img"
                  aria-label={`${groupLabel}: ${emotionItems.map((item) => `${item.label} ${item.count}개`).join(', ')}`}
                >
                  {emotionItems.map((item) => (
                    <span
                      key={item.code}
                      style={{
                        width: `${sampleCount > 0 ? (item.count / sampleCount) * 100 : 0}%`,
                        backgroundColor: getEmotionColor(item.code),
                      }}
                      title={`${item.label} ${item.count}개`}
                    />
                  ))}
                </div>

                <ul className="emotion-insights-legend" aria-label={`${groupLabel} 감정별 표본`}>
                  {emotionItems.map((item) => (
                    <li key={item.code}>
                      <i style={{ backgroundColor: getEmotionColor(item.code) }} />
                      <span>{item.label}</span>
                      <strong>{item.count}개</strong>
                    </li>
                  ))}
                </ul>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}

// 확정한 감정 기록의 시간대·상황·관계별 분포를 한 화면에 제공하는 컴포넌트.
function EmotionInsights({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onBack,
  onHome,
}) {
  const [insights, setInsights] = useState({})
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reloadCount, setReloadCount] = useState(0)

  // 세 분류의 표본 기준이 같도록 화면 진입 시 모든 인사이트를 함께 조회.
  useEffect(() => {
    let isActive = true

    const loadInsights = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const responses = await Promise.all(
          insightSections.map((section) => getEmotionInsights(section.groupBy)),
        )

        if (!isActive) return

        setInsights(Object.fromEntries(
          responses.map((response) => [response.groupBy, response]),
        ))
      } catch (error) {
        if (isActive) {
          setInsights({})
          setLoadError(getInsightErrorMessage(error))
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadInsights()

    return () => {
      isActive = false
    }
  }, [reloadCount])

  const totalSampleCount = useMemo(() => Math.max(
    0,
    ...Object.values(insights).map(
      (insight) => Number(insight.totalSampleCount) || 0,
    ),
  ), [insights])

  return (
    <main className="emotion-insights-page">
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

      <div className="emotion-insights-content">
        <header className="emotion-insights-header">
          <div>
            <span className="emotion-insights-eyebrow">기록·감정 분포</span>
            <h1>감정 인사이트</h1>
            <p>확정한 감정 기록을 시간대, 상황, 관계별로 비교해 보세요.</p>
          </div>
          <button type="button" onClick={onBack}>감정 기록 목록으로</button>
        </header>

        {isLoading ? (
          <div className="emotion-insights-state" role="status">
            감정 분포를 불러오는 중입니다.
          </div>
        ) : loadError ? (
          <div className="emotion-insights-state is-error" role="alert">
            <p>{loadError}</p>
            <button type="button" onClick={() => setReloadCount((count) => count + 1)}>
              다시 시도
            </button>
          </div>
        ) : (
          <>
            <div className="emotion-insights-summary" aria-label="감정 인사이트 표본 안내">
              <strong>총 {totalSampleCount}개의 확정 기록</strong>
              <p>각 분포의 수치는 확정된 대표 감정을 기준으로 계산합니다. 표본이 적을 때는 단정하기보다 기록의 흐름을 살펴보세요.</p>
            </div>

            <div className="emotion-insights-sections">
              {insightSections.map((definition) => (
                <DistributionSection
                  key={definition.groupBy}
                  definition={definition}
                  insight={insights[definition.groupBy]}
                />
              ))}
            </div>
          </>
        )}
      </div>
    </main>
  )
}

export default EmotionInsights
