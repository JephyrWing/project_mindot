import { useEffect, useMemo, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  generateWeeklyReport,
  getWeeklyReport,
} from '../../utils/reports/reportsApi.js'
import './WeeklyReportGraph.css'

// 사용자 시간대의 날짜를 백엔드 요청용 연월일 형식으로 변환.
const toLocalDateValue = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')

  return `${year}-${month}-${day}`
}

// 선택한 주의 월요일과 일요일을 사용자 표시 기간으로 계산.
const getWeekRange = (weekOffset) => {
  const selectedDate = new Date()
  selectedDate.setHours(12, 0, 0, 0)
  selectedDate.setDate(selectedDate.getDate() + weekOffset * 7)

  const dayOfWeek = selectedDate.getDay()
  const mondayOffset = dayOfWeek === 0 ? -6 : 1 - dayOfWeek
  const weekStart = new Date(selectedDate)
  weekStart.setDate(selectedDate.getDate() + mondayOffset)

  const weekEnd = new Date(weekStart)
  weekEnd.setDate(weekStart.getDate() + 6)

  const dateFormatter = new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })

  return {
    weekStart: toLocalDateValue(weekStart),
    label: `${dateFormatter.format(weekStart)} ~ ${dateFormatter.format(weekEnd)}`,
  }
}

// 월요일부터 일요일까지 그래프에 표시할 요일 순서 설정.
const graphWeekdays = [
  { day: '월', dayIndex: 1 },
  { day: '화', dayIndex: 2 },
  { day: '수', dayIndex: 3 },
  { day: '목', dayIndex: 4 },
  { day: '금', dayIndex: 5 },
  { day: '토', dayIndex: 6 },
  { day: '일', dayIndex: 0 },
]

// 백엔드 감정 코드를 그래프 상세 정보에 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const emotionCodeLabels = {
  ANXIETY: '불안',
  FEAR: '두려움',
  ANGER: '분노',
  FRUSTRATION: '답답함',
  SADNESS: '슬픔',
  DISAPPOINTMENT: '실망',
  SHAME: '수치심',
  GUILT: '죄책감',
  LONELINESS: '외로움',
  JOY: '기쁨',
  RELIEF: '안도',
  ACHIEVEMENT: '성취감',
  CALM: '평온',
  GRATITUDE: '감사',
  EXCITEMENT: '설렘',
  OTHER: '기타',
}

// 감정 기록 근거 목록을 요일별로 묶고 평균 강도와 대표 감정 계산.
const createWeeklyGraphItems = (emotionRecordEvidences = []) => {
  const recordsByDay = Array.from({ length: 7 }, () => [])

  emotionRecordEvidences.forEach((record) => {
    const occurredDate = new Date(record.occurredAt)

    if (Number.isNaN(occurredDate.getTime())) return

    recordsByDay[occurredDate.getDay()].push(record)
  })

  return graphWeekdays.map(({ day, dayIndex }) => {
    const records = recordsByDay[dayIndex]
    const intensities = records
      .map((record) => record.primaryIntensity)
      .filter((intensity) => intensity !== null
        && intensity !== undefined
        && intensity !== '')
      .map((intensity) => Number(intensity))
      .filter((intensity) => Number.isFinite(intensity)
        && intensity >= 0
        && intensity <= 10)
    const emotionCounts = records.reduce((counts, record) => {
      const emotionCode = record.primaryEmotionCode

      if (emotionCode) counts[emotionCode] = (counts[emotionCode] ?? 0) + 1
      return counts
    }, {})
    const representativeEmotionCode = Object.entries(emotionCounts)
      .sort(([firstCode, firstCount], [secondCode, secondCount]) => (
        secondCount - firstCount || firstCode.localeCompare(secondCode)
      ))[0]?.[0] ?? null

    const averageIntensity = intensities.length > 0
      ? intensities.reduce(
        (totalIntensity, currentIntensity) => totalIntensity + currentIntensity,
        0,
      ) / intensities.length
      : null

    return {
      day,
      value: averageIntensity === null ? null : Number(averageIntensity.toFixed(1)),
      recordCount: records.length,
      representativeEmotion: representativeEmotionCode
        ? emotionCodeLabels[representativeEmotionCode] ?? representativeEmotionCode
        : '분석 전',
    }
  })
}

// 리포트 API 오류를 서버 연결 여부에 맞는 사용자 안내 문구로 변환.
const getReportErrorMessage = (error) => (
  error.response
    ? '주간 리포트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
    : '서버에 연결할 수 없습니다. 서버 실행 상태를 확인한 뒤 다시 시도해 주세요.'
)

// 주간 감정 그래프 기능을 단계적으로 추가하기 위한 기본 화면 정의.
function WeeklyReportGraph({
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
  // 현재 주를 기준으로 사용자가 이동한 주간 위치 상태 관리.
  const [weekOffset, setWeekOffset] = useState(0)
  // 사용자가 상세 정보를 확인할 요일 상태 관리.
  const [selectedDay, setSelectedDay] = useState('')
  // 백엔드에서 조회하거나 생성한 선택 주의 리포트 상태 관리.
  const [report, setReport] = useState(null)
  // 선택 주의 리포트 API 호출 진행 여부 상태 관리.
  const [isLoading, setIsLoading] = useState(true)
  // 선택 주의 리포트 API 호출 실패 안내 상태 관리.
  const [loadError, setLoadError] = useState('')
  // 기록이 없는 선택 주에 표시할 빈 화면 안내 상태 관리.
  const [emptyMessage, setEmptyMessage] = useState('')
  // 인증 갱신까지 실패한 로그인 만료 상태 관리.
  const [isAuthExpired, setIsAuthExpired] = useState(false)
  // 서버 오류 발생 후 같은 주를 다시 조회하기 위한 요청 횟수 상태 관리.
  const [reloadCount, setReloadCount] = useState(0)
  // 최신 감정 기록을 반영하는 리포트 갱신 요청 상태 관리.
  const [isRefreshing, setIsRefreshing] = useState(false)
  // 리포트 갱신 결과를 사용자에게 안내할 문구 상태 관리.
  const [refreshMessage, setRefreshMessage] = useState('')
  // 리포트 갱신 실패 안내 문구 상태 관리.
  const [refreshError, setRefreshError] = useState('')
  // 선택한 주의 월요일부터 일요일까지 표시할 기간 계산.
  const selectedWeek = getWeekRange(weekOffset)
  // API 응답의 감정 기록 근거를 요일별 평균 강도 그래프 항목으로 변환.
  const weeklyGraphItems = useMemo(
    () => createWeeklyGraphItems(report?.emotionRecordEvidences),
    [report],
  )
  // 선택한 요일에 해당하는 평균 강도와 대표 감정 정보 탐색.
  const selectedGraphItem = weeklyGraphItems.find((item) => item.day === selectedDay)

  // 주간 이동 시 선택 요일을 초기화하고 이동 범위를 현재 주까지로 제한하는 처리.
  const handleWeekMove = (offsetChange) => {
    setSelectedDay('')
    setWeekOffset((currentOffset) => Math.min(currentOffset + offsetChange, 0))
  }

  // 선택 주 변경 시 저장된 리포트 조회와 미생성 리포트 생성 요청 처리.
  useEffect(() => {
    let isActive = true

    const loadWeeklyReport = async () => {
      setIsLoading(true)
      setLoadError('')
      setEmptyMessage('')
      setIsAuthExpired(false)
      setRefreshMessage('')
      setRefreshError('')
      setReport(null)

      try {
        const savedReport = await getWeeklyReport(selectedWeek.weekStart)

        if (isActive) setReport(savedReport)
      } catch (getError) {
        if (!isActive) return

        if (getError.response?.status === 401) {
          setIsAuthExpired(true)
          return
        }

        if (getError.response?.status !== 404) {
          setLoadError(getReportErrorMessage(getError))
          return
        }

        try {
          const generatedReport = await generateWeeklyReport(selectedWeek.weekStart)

          if (isActive) setReport(generatedReport)
        } catch (generateError) {
          if (!isActive) return

          if (generateError.response?.status === 401) {
            setIsAuthExpired(true)
          } else if (generateError.response?.status === 409) {
            setEmptyMessage('선택한 주에 작성한 감정 기록이 없습니다.')
          } else {
            setLoadError(getReportErrorMessage(generateError))
          }
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadWeeklyReport()

    return () => {
      isActive = false
    }
  }, [reloadCount, selectedWeek.weekStart])

  // 선택한 주의 최신 감정 기록으로 주간 리포트를 다시 생성하는 처리.
  const handleReportRefresh = async () => {
    if (isLoading || isRefreshing) return

    setIsRefreshing(true)
    setRefreshMessage('')
    setRefreshError('')
    setEmptyMessage('')

    try {
      const refreshedReport = await generateWeeklyReport(selectedWeek.weekStart)

      setReport(refreshedReport)
      setLoadError('')
      setIsAuthExpired(false)
      setRefreshMessage('최신 감정 기록으로 그래프를 갱신했습니다.')
    } catch (error) {
      if (error.response?.status === 401) {
        setIsAuthExpired(true)
        setRefreshError('로그인 정보가 만료되었습니다. 다시 로그인해 주세요.')
      } else if (error.response?.status === 409) {
        setReport(null)
        setEmptyMessage('선택한 주에 작성한 감정 기록이 없습니다.')
      } else {
        setRefreshError(getReportErrorMessage(error))
      }
    } finally {
      setIsRefreshing(false)
    }
  }

  // 공통 네비게이션과 그래프 표시 예정 영역으로 구성한 기본 화면 반환.
  return (
    <main className="weekly-report-graph-page">
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

      <div className="weekly-report-graph-content">
        <section className="weekly-report-graph-card" aria-labelledby="weekly-graph-title">
          <BrandLogo className="weekly-report-graph-logo" onClick={onHome} />
          <h1 id="weekly-graph-title">주간 감정 그래프</h1>
          <p className="weekly-report-graph-description">
            한 주 동안의 감정 변화를 그래프로 확인하는 공간입니다.
          </p>

          {/* 선택한 주의 기간과 이전 및 다음 주 이동 기능 배치. */}
          <div className="weekly-report-graph-period">
            <span>선택 기간</span>
            <strong>{selectedWeek.label}</strong>
          </div>
          <div
            className="weekly-report-graph-navigation"
            aria-label="주간 그래프 기간 선택"
          >
            <button
              type="button"
              onClick={() => handleWeekMove(-1)}
            >
              ← 이전 주
            </button>
            <button
              type="button"
              onClick={() => handleWeekMove(1)}
              disabled={weekOffset === 0}
            >
              다음 주 →
            </button>
          </div>

          {/* 선택한 주의 최신 감정 기록을 그래프에 다시 반영하는 버튼 배치. */}
          <button
            className="weekly-report-graph-refresh"
            type="button"
            onClick={handleReportRefresh}
            disabled={isLoading || isRefreshing || isAuthExpired}
          >
            {isRefreshing ? '갱신 중' : '최신 기록으로 갱신'}
          </button>
          {refreshMessage && (
            <p className="weekly-report-graph-refresh-message" role="status">
              {refreshMessage}
            </p>
          )}
          {refreshError && (
            <p className="weekly-report-graph-refresh-error" role="alert">
              {refreshError}
            </p>
          )}

          {/* 선택한 주의 리포트 API 호출 결과 상태 표시. */}
          <section
            className="weekly-report-graph-chart"
            aria-labelledby="weekly-report-graph-chart-title"
          >
            <header className="weekly-report-graph-chart-heading">
              <h2 id="weekly-report-graph-chart-title">요일별 감정 강도</h2>
              <span>0~10점</span>
            </header>

            {isLoading && (
              <p className="weekly-report-graph-note" role="status">
                주간 리포트를 불러오고 있습니다.
              </p>
            )}
            {isAuthExpired && (
              <div className="weekly-report-graph-state" role="alert">
                <h3>로그인이 필요합니다</h3>
                <p>로그인 정보가 만료되었습니다. 다시 로그인해 주세요.</p>
                <button type="button" onClick={onLogin}>로그인 화면으로 이동</button>
              </div>
            )}
            {loadError && !isAuthExpired && (
              <div className="weekly-report-graph-state" role="alert">
                <h3>리포트를 불러오지 못했습니다</h3>
                <p>{loadError}</p>
                <button
                  type="button"
                  onClick={() => setReloadCount((currentCount) => currentCount + 1)}
                >
                  다시 불러오기
                </button>
              </div>
            )}
            {emptyMessage && !isLoading && !loadError && !isAuthExpired && (
              <div className="weekly-report-graph-state" role="status">
                <h3>기록이 없습니다</h3>
                <p>{emptyMessage}</p>
              </div>
            )}
            {report && !isLoading && !loadError && !isAuthExpired && (
              <>
                {/* 실제 감정 기록의 요일별 평균 강도를 일곱 개 막대로 표시. */}
                <div className="weekly-report-graph-bars">
                  {weeklyGraphItems.map((item) => (
                    <button
                      className={selectedDay === item.day
                        ? 'weekly-report-graph-item is-selected'
                        : 'weekly-report-graph-item'}
                      key={item.day}
                      type="button"
                      onClick={() => setSelectedDay(item.day)}
                      aria-pressed={selectedDay === item.day}
                      aria-label={item.recordCount > 0 && item.value !== null
                        ? `${item.day}요일 감정 강도 평균 ${item.value}점, 기록 ${item.recordCount}건`
                        : item.recordCount > 0
                          ? `${item.day}요일 기록 ${item.recordCount}건, 감정 강도 없음`
                        : `${item.day}요일 감정 기록 없음`}
                    >
                      <span className="weekly-report-graph-value">
                        {item.value ?? '-'}
                      </span>
                      <div className="weekly-report-graph-track" aria-hidden="true">
                        <span style={{ height: `${(item.value ?? 0) * 10}%` }} />
                      </div>
                      <strong>{item.day}</strong>
                    </button>
                  ))}
                </div>

                {/* 사용자가 선택한 요일의 기록 건수와 평균 강도 및 대표 감정 표시. */}
                {selectedGraphItem && (
                  <dl className="weekly-report-graph-details" aria-live="polite">
                    <div>
                      <dt>선택 요일</dt>
                      <dd>{selectedGraphItem.day}요일</dd>
                    </div>
                    <div>
                      <dt>기록 건수</dt>
                      <dd>{selectedGraphItem.recordCount}건</dd>
                    </div>
                    <div>
                      <dt>평균 강도</dt>
                      <dd>{selectedGraphItem.value === null
                        ? '기록 없음'
                        : `${selectedGraphItem.value}/10`}</dd>
                    </div>
                    <div>
                      <dt>대표 감정</dt>
                      <dd>{selectedGraphItem.recordCount > 0
                        ? selectedGraphItem.representativeEmotion
                        : '기록 없음'}</dd>
                    </div>
                  </dl>
                )}

                <p className="weekly-report-graph-note" role="status">
                  감정 기록 {report.recordCount}건의 요일별 평균 강도입니다.
                </p>
              </>
            )}
          </section>

          <button className="weekly-report-graph-back" type="button" onClick={onBack}>
            주간 리포트로 돌아가기
          </button>
        </section>
      </div>
    </main>
  )
}

export default WeeklyReportGraph
