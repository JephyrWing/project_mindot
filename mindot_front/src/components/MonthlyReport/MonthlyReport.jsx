import { useEffect, useMemo, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  exportMonthlyReportPdf,
  generateMonthlyReport,
  getMonthlyReport,
} from '../../utils/reports/reportsApi.js'
import './MonthlyReport.css'

// 백엔드 감정 코드를 월간 리포트에 표시할 한국어 이름으로 변환하는 목록 설정.
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

// 백엔드 상황 코드를 월간 리포트에 표시할 한국어 이름으로 변환하는 목록 설정.
const contextCategoryLabels = {
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
}

// 백엔드 월간 강도 변화 코드를 사용자 안내 문구로 변환하는 목록 설정.
const intensityTrendContent = {
  INCREASED: {
    label: '후반 강도 높아짐',
    description: '월 초반보다 후반에 기록한 감정의 평균 강도가 높아졌습니다.',
  },
  DECREASED: {
    label: '후반 강도 낮아짐',
    description: '월 초반보다 후반에 기록한 감정의 평균 강도가 낮아졌습니다.',
  },
  STABLE: {
    label: '비슷한 흐름',
    description: '월 초반과 후반에 기록한 감정의 평균 강도가 비슷합니다.',
  },
  INSUFFICIENT_DATA: {
    label: '비교 자료 부족',
    description: '월 초반과 후반을 비교하려면 감정 강도 기록이 더 필요합니다.',
  },
}

// 감정 강도 숫자를 소수점 한 자리의 사용자 표시값으로 변환.
const formatIntensity = (value) => (
  Number.isFinite(value) ? `${value.toFixed(1)}/10` : '기록 없음'
)

// 감정 강도 숫자를 비교 막대에 사용할 0~100 범위의 비율로 변환.
const getIntensityPercent = (value) => (
  Number.isFinite(value)
    ? Math.min(100, Math.max(0, value * 10))
    : 0
)

// 코드별 기록 수 객체를 많은 순서의 화면 표시 배열로 변환.
const createCountItems = (counts, labels) => Object.entries(counts ?? {})
  .map(([code, count]) => ({
    code,
    label: labels[code] ?? code,
    count: Number(count),
  }))
  .filter((item) => Number.isFinite(item.count) && item.count > 0)
  .sort((firstItem, secondItem) => secondItem.count - firstItem.count)

// 날짜 문자열을 월간 리포트의 간단한 한국어 표시 형식으로 변환.
const formatReportDate = (dateValue) => {
  if (!dateValue) return '날짜 정보 없음'

  const date = new Date(`${dateValue}T00:00:00`)

  if (Number.isNaN(date.getTime())) return '날짜 정보 없음'

  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  }).format(date)
}

// ISO 시각을 최근 집계 시각의 한국어 날짜·시간 형식으로 변환.
const formatSnapshotAt = (dateTimeValue) => {
  if (!dateTimeValue) return '집계 시각 없음'

  const date = new Date(dateTimeValue)

  if (Number.isNaN(date.getTime())) return '집계 시각 없음'

  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

// 백엔드 요약 문장에 포함된 감정·상황 코드를 한국어 이름으로 변환.
const localizeSummaryText = (summaryText) => {
  if (!summaryText) return '표시할 월간 요약이 없습니다.'

  return Object.entries({
    ...emotionCodeLabels,
    ...contextCategoryLabels,
  }).reduce(
    (localizedText, [code, label]) => localizedText.replaceAll(code, label),
    summaryText,
  )
}

// 브라우저 지역 시각의 연월을 백엔드 요청 형식으로 변환.
const toMonthValue = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')

  return `${year}-${month}`
}

// 연월 문자열을 사용자가 읽기 쉬운 한국어 월 표시로 변환.
const formatMonthLabel = (monthValue) => {
  const [year, month] = monthValue.split('-').map(Number)

  return `${year}년 ${month}월`
}

// 선택 월을 지정한 개월 수만큼 이전 또는 다음 월로 이동.
const moveMonthValue = (monthValue, amount) => {
  const [year, month] = monthValue.split('-').map(Number)
  const nextMonth = new Date(year, month - 1 + amount, 1, 12)

  return toMonthValue(nextMonth)
}

// 월간 리포트 API 오류를 화면 안내 문구로 변환.
const getMonthlyReportErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }

  if (error.response.status === 400) {
    return '조회할 연월을 다시 확인해 주세요.'
  }

  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  return error.response.data?.message
    ?? error.response.data?.detail
    ?? '월간 리포트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 월간 PDF 다운로드 오류를 인증·리포트 상태에 맞는 문구로 변환.
const getMonthlyPdfErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }

  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  if (error.response.status === 404) {
    return '먼저 선택한 달의 월간 리포트를 생성해 주세요.'
  }

  return error.response.data?.message
    ?? error.response.data?.detail
    ?? '월간 리포트 PDF를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 월간 감정 기록의 핵심 통계와 월 이동 기능을 제공하는 화면 정의.
function MonthlyReport({
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
  // 현재 월 이후의 리포트 선택 방지를 위한 기준 연월 설정.
  const currentMonth = useMemo(() => toMonthValue(new Date()), [])
  // 조회할 월간 리포트의 선택 연월 상태 관리.
  const [selectedMonth, setSelectedMonth] = useState(currentMonth)
  // 선택 월의 월간 리포트 응답 상태 관리.
  const [report, setReport] = useState(null)
  // 조회·갱신 요청 상태와 사용자 안내 문구 관리.
  const [isLoading, setIsLoading] = useState(true)
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [emptyMessage, setEmptyMessage] = useState('')
  const [refreshMessage, setRefreshMessage] = useState('')
  const [reloadCount, setReloadCount] = useState(0)
  // 월간 PDF 생성 요청과 결과 안내 상태 관리.
  const [isExporting, setIsExporting] = useState(false)
  const [exportError, setExportError] = useState('')
  const [exportMessage, setExportMessage] = useState('')
  // 날짜별 그래프에서 사용자가 선택한 날짜 상태 관리.
  const [selectedTrendDate, setSelectedTrendDate] = useState('')

  // 백엔드 날짜별 집계를 그래프 표시가 안전한 숫자 범위로 정규화.
  const dailyTrends = useMemo(() => (report?.dailyTrends ?? []).map((trend) => ({
    ...trend,
    recordCount: Number.isFinite(trend.recordCount) ? trend.recordCount : 0,
    averageIntensity: Number.isFinite(trend.averageIntensity)
      ? Math.min(10, Math.max(0, trend.averageIntensity))
      : null,
  })), [report])

  // 직접 선택한 날짜 또는 기록이 존재하는 첫 날짜를 상세 표시 대상으로 설정.
  const selectedDailyTrend = useMemo(() => (
    dailyTrends.find((trend) => trend.date === selectedTrendDate)
    ?? dailyTrends.find((trend) => trend.recordCount > 0)
    ?? dailyTrends[0]
    ?? null
  ), [dailyTrends, selectedTrendDate])

  // 현재 응답의 초반·후반 변화 코드를 화면 표시용 내용으로 변환.
  const selectedIntensityTrend = intensityTrendContent[report?.intensityTrend]
    ?? intensityTrendContent.INSUFFICIENT_DATA

  // 감정·상황 기록 수를 많은 순서의 분포 항목으로 변환.
  const emotionCountItems = useMemo(
    () => createCountItems(report?.emotionCounts, emotionCodeLabels),
    [report],
  )
  const contextCountItems = useMemo(
    () => createCountItems(report?.contextCategoryCounts, contextCategoryLabels),
    [report],
  )

  // 선택 월 변경 시 저장된 리포트 조회 후 미생성 상태에서는 자동 생성 요청 처리.
  useEffect(() => {
    let isActive = true

    const loadMonthlyReport = async () => {
      setIsLoading(true)
      setReport(null)
      setLoadError('')
      setEmptyMessage('')
      setRefreshMessage('')
      setSelectedTrendDate('')
      setExportError('')
      setExportMessage('')

      try {
        const savedReport = await getMonthlyReport(selectedMonth)

        if (isActive) setReport(savedReport)
      } catch (getError) {
        if (!isActive) return

        if (getError.response?.status !== 404) {
          setLoadError(getMonthlyReportErrorMessage(getError))
          return
        }

        try {
          const generatedReport = await generateMonthlyReport(selectedMonth)

          if (isActive) setReport(generatedReport)
        } catch (generateError) {
          if (!isActive) return

          if (generateError.response?.status === 409) {
            setEmptyMessage('선택한 달에 감정 기록과 완료한 CBT 성찰이 없습니다.')
          } else {
            setLoadError(getMonthlyReportErrorMessage(generateError))
          }
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadMonthlyReport()

    return () => {
      isActive = false
    }
  }, [reloadCount, selectedMonth])

  // 선택 월의 최신 기록을 기준으로 월간 리포트를 다시 생성하는 처리.
  const handleRefresh = async () => {
    if (isLoading || isRefreshing) return

    setIsRefreshing(true)
    setLoadError('')
    setEmptyMessage('')
    setRefreshMessage('')

    try {
      const refreshedReport = await generateMonthlyReport(selectedMonth)

      setReport(refreshedReport)
      setRefreshMessage('최신 감정 기록으로 월간 리포트를 갱신했습니다.')
    } catch (error) {
      if (error.response?.status === 409) {
        setReport(null)
        setEmptyMessage('선택한 달에 감정 기록과 완료한 CBT 성찰이 없습니다.')
      } else {
        setLoadError(getMonthlyReportErrorMessage(error))
      }
    } finally {
      setIsRefreshing(false)
    }
  }

  // 선택 월의 백엔드 PDF 파일을 브라우저 다운로드로 제공하는 처리.
  const handlePdfExport = async () => {
    if (!report || isExporting || isRefreshing) return

    setIsExporting(true)
    setExportError('')
    setExportMessage('')

    try {
      const pdfBlob = await exportMonthlyReportPdf(selectedMonth)
      const downloadUrl = window.URL.createObjectURL(pdfBlob)
      const downloadLink = document.createElement('a')

      downloadLink.href = downloadUrl
      downloadLink.download = `mindot-monthly-report-${selectedMonth}.pdf`
      document.body.appendChild(downloadLink)
      downloadLink.click()
      downloadLink.remove()
      window.URL.revokeObjectURL(downloadUrl)
      setExportMessage('월간 리포트 PDF 다운로드를 시작했습니다.')
    } catch (error) {
      setExportError(getMonthlyPdfErrorMessage(error))
    } finally {
      setIsExporting(false)
    }
  }

  // 선택 월과 월간 요약을 기존 서비스 화면 디자인으로 구성한 반환.
  return (
    <main className="monthly-report-page">
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

      <div className="monthly-report-content">
        <section className="monthly-report-card" aria-labelledby="monthly-report-title">
          <BrandLogo className="monthly-report-logo" onClick={onHome} />
          <h1 id="monthly-report-title">월간 리포트</h1>
          <p className="monthly-report-description">
            한 달 동안 기록한 감정과 CBT 성찰의 흐름을 확인하는 공간입니다.
            <span>
              감정 통계는 감정 발생일, CBT 통계는 성찰 완료일을 기준으로 집계합니다.
            </span>
          </p>

          {/* 직접 연월 선택과 이전·다음 달 이동 기능 배치. */}
          <div className="monthly-report-period">
            <label htmlFor="monthly-report-month">리포트 기간</label>
            <input
              id="monthly-report-month"
              type="month"
              value={selectedMonth}
              max={currentMonth}
              disabled={isLoading || isRefreshing}
              onChange={(event) => {
                if (event.target.value) setSelectedMonth(event.target.value)
              }}
            />
          </div>
          <div className="monthly-report-navigation" aria-label="월간 리포트 기간 이동">
            <button
              type="button"
              onClick={() => setSelectedMonth(moveMonthValue(selectedMonth, -1))}
              disabled={isLoading || isRefreshing}
            >
              ← 이전 달
            </button>
            <button
              type="button"
              onClick={() => setSelectedMonth(moveMonthValue(selectedMonth, 1))}
              disabled={isLoading || isRefreshing || selectedMonth === currentMonth}
            >
              다음 달 →
            </button>
          </div>

          <h2 className="monthly-report-selected-month">
            {formatMonthLabel(selectedMonth)} 요약
          </h2>

          {isLoading ? (
            <div className="monthly-report-state" role="status" aria-live="polite">
              <strong>월간 리포트를 불러오는 중입니다.</strong>
              <p>선택한 달의 감정 기록을 확인하고 있습니다.</p>
            </div>
          ) : loadError ? (
            <div className="monthly-report-state monthly-report-state--error" role="alert">
              <strong>월간 리포트를 불러오지 못했습니다.</strong>
              <p>{loadError}</p>
              <button type="button" onClick={() => setReloadCount((count) => count + 1)}>
                다시 불러오기
              </button>
            </div>
          ) : report ? (
            <>
              {/* 선택 월의 핵심 통계를 중첩 카드 없이 하나의 요약 행으로 표시. */}
              <dl className="monthly-report-summary">
                <div>
                  <dt>기록 횟수</dt>
                  <dd>{report.recordCount}회</dd>
                </div>
                <div>
                  <dt>주요 감정</dt>
                  <dd>{emotionCodeLabels[report.dominantEmotionCode]
                    ?? report.dominantEmotionCode
                    ?? '기록 없음'}</dd>
                </div>
                <div>
                  <dt>평균 강도</dt>
                  <dd>{Number.isFinite(report.averageIntensity)
                    ? `${report.averageIntensity.toFixed(1)}/10`
                    : '-'}</dd>
                </div>
                <div>
                  <dt>완료 CBT</dt>
                  <dd>{report.completedCbtCount}회</dd>
                </div>
                <div>
                  <dt>평균 도움</dt>
                  <dd>{Number.isFinite(report.averageHelpfulnessScore)
                    ? `${report.averageHelpfulnessScore.toFixed(1)}/5`
                    : '-'}</dd>
                </div>
              </dl>

              {/* 백엔드가 실제 집계한 월간 시작일과 종료일 표시. */}
              <p className="monthly-report-period-caption">
                집계 기간 · {formatReportDate(report.periodStart)} ~ {formatReportDate(report.periodEnd)}
              </p>

              <section className="monthly-report-summary-text" aria-labelledby="monthly-summary-title">
                <h2 id="monthly-summary-title">이번 달 마음 흐름</h2>
                <p>{localizeSummaryText(report.summaryText)}</p>
              </section>

              {/* 월 초반과 후반의 평균 감정 강도 및 변화 방향 비교 표시. */}
              <section
                className="monthly-report-half-trend"
                aria-labelledby="monthly-half-trend-title"
              >
                <header className="monthly-report-half-trend-heading">
                  <h2 id="monthly-half-trend-title">월 초반·후반 비교</h2>
                  <strong>{selectedIntensityTrend.label}</strong>
                </header>

                <div className="monthly-report-half-trend-rows">
                  <div>
                    <span>월 초반</span>
                    <span className="monthly-report-half-trend-track" aria-hidden="true">
                      <span style={{
                        width: `${getIntensityPercent(report.firstHalfAverageIntensity)}%`,
                      }} />
                    </span>
                    <strong>{formatIntensity(report.firstHalfAverageIntensity)}</strong>
                  </div>
                  <div>
                    <span>월 후반</span>
                    <span className="monthly-report-half-trend-track" aria-hidden="true">
                      <span style={{
                        width: `${getIntensityPercent(report.secondHalfAverageIntensity)}%`,
                      }} />
                    </span>
                    <strong>{formatIntensity(report.secondHalfAverageIntensity)}</strong>
                  </div>
                </div>

                <p>{selectedIntensityTrend.description}</p>
              </section>

              {/* 백엔드의 날짜별 평균 감정 강도를 한 달 그래프로 표시. */}
              <section className="monthly-report-chart" aria-labelledby="monthly-chart-title">
                <header className="monthly-report-chart-heading">
                  <h2 id="monthly-chart-title">날짜별 감정 강도</h2>
                  <span>0~10점</span>
                </header>

                <div className="monthly-report-chart-scroll">
                  <div className="monthly-report-chart-bars">
                    {dailyTrends.map((trend) => {
                      const day = Number(trend.date.slice(-2))
                      const isSelected = selectedDailyTrend?.date === trend.date

                      return (
                        <button
                          className={isSelected
                            ? 'monthly-report-chart-item is-selected'
                            : 'monthly-report-chart-item'}
                          key={trend.date}
                          type="button"
                          aria-pressed={isSelected}
                          aria-label={trend.recordCount > 0
                            ? `${day}일, 기록 ${trend.recordCount}건, 평균 강도 ${trend.averageIntensity ?? '미입력'}`
                            : `${day}일, 감정 기록 없음`}
                          onClick={() => setSelectedTrendDate(trend.date)}
                        >
                          <span className="monthly-report-chart-value">
                            {trend.averageIntensity === null
                              ? '-'
                              : trend.averageIntensity.toFixed(1)}
                          </span>
                          <span className="monthly-report-chart-track" aria-hidden="true">
                            <span style={{ height: `${(trend.averageIntensity ?? 0) * 10}%` }} />
                          </span>
                          <strong>{day}</strong>
                        </button>
                      )
                    })}
                  </div>
                </div>

                {/* 선택한 날짜의 기록 수와 평균 강도 및 대표 감정 표시. */}
                {selectedDailyTrend && (
                  <dl className="monthly-report-chart-detail" aria-live="polite">
                    <div>
                      <dt>선택 날짜</dt>
                      <dd>{Number(selectedDailyTrend.date.slice(-2))}일</dd>
                    </div>
                    <div>
                      <dt>기록 건수</dt>
                      <dd>{selectedDailyTrend.recordCount}건</dd>
                    </div>
                    <div>
                      <dt>평균 강도</dt>
                      <dd>{selectedDailyTrend.averageIntensity === null
                        ? '기록 없음'
                        : `${selectedDailyTrend.averageIntensity.toFixed(1)}/10`}</dd>
                    </div>
                    <div>
                      <dt>대표 감정</dt>
                      <dd>{selectedDailyTrend.recordCount > 0
                        ? emotionCodeLabels[selectedDailyTrend.dominantEmotionCode]
                          ?? selectedDailyTrend.dominantEmotionCode
                          ?? '분석 전'
                        : '기록 없음'}</dd>
                    </div>
                  </dl>
                )}
              </section>

              {/* 한 달 동안 기록된 감정과 상황의 횟수 분포 표시. */}
              <section
                className="monthly-report-distributions"
                aria-labelledby="monthly-distributions-title"
              >
                <h2 id="monthly-distributions-title">감정·상황 분포</h2>
                <div className="monthly-report-distribution-columns">
                  <section aria-labelledby="monthly-emotion-count-title">
                    <h3 id="monthly-emotion-count-title">기록한 감정</h3>
                    {emotionCountItems.length > 0 ? (
                      <ul>
                        {emotionCountItems.map((item) => (
                          <li key={item.code}>
                            <div>
                              <span>{item.label}</span>
                              <strong>{item.count}회</strong>
                            </div>
                            <span className="monthly-report-distribution-track" aria-hidden="true">
                              <span style={{
                                width: `${(item.count / emotionCountItems[0].count) * 100}%`,
                              }} />
                            </span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p>분류된 감정 기록이 없습니다.</p>
                    )}
                  </section>

                  <section aria-labelledby="monthly-context-count-title">
                    <h3 id="monthly-context-count-title">기록한 상황</h3>
                    {contextCountItems.length > 0 ? (
                      <ul>
                        {contextCountItems.map((item) => (
                          <li key={item.code}>
                            <div>
                              <span>{item.label}</span>
                              <strong>{item.count}회</strong>
                            </div>
                            <span className="monthly-report-distribution-track" aria-hidden="true">
                              <span style={{
                                width: `${(item.count / contextCountItems[0].count) * 100}%`,
                              }} />
                            </span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p>분류된 상황 기록이 없습니다.</p>
                    )}
                  </section>
                </div>
              </section>

              {/* 주간 리포트와 같은 위치의 최신 데이터 갱신 동작 배치. */}
              <div className="monthly-report-actions">
                <button
                  className="monthly-report-refresh"
                  type="button"
                  onClick={handleRefresh}
                  disabled={isRefreshing || isExporting}
                >
                  {isRefreshing ? '최신화 중…' : '최신 기록으로 다시 만들기'}
                </button>
              </div>

              {refreshMessage && (
                <p className="monthly-report-refresh-message" role="status">
                  {refreshMessage}
                </p>
              )}

              {/* 주간 리포트와 같은 독립 내보내기 영역의 월간 PDF 기능 배치. */}
              <section
                className="monthly-report-pdf-export"
                aria-labelledby="monthly-report-export-title"
              >
                <div className="monthly-report-export-heading">
                  <h2 id="monthly-report-export-title">PDF 내보내기</h2>
                  <p>선택한 달의 감정 기록과 완료한 CBT 요약을 파일로 저장합니다.</p>
                </div>
                <button
                  className="monthly-report-export"
                  type="button"
                  onClick={handlePdfExport}
                  disabled={isExporting || isRefreshing}
                >
                  {isExporting ? 'PDF 준비 중…' : '월간 리포트 PDF 저장'}
                </button>
                {exportMessage && (
                  <p className="monthly-report-export-message" role="status">
                    {exportMessage}
                  </p>
                )}
                {exportError && (
                  <p className="monthly-report-export-error" role="alert">
                    {exportError}
                  </p>
                )}
              </section>

              <p className="monthly-report-snapshot">
                최근 집계 시각 · {formatSnapshotAt(report.sourceSnapshotAt)}
              </p>
            </>
          ) : (
            <div className="monthly-report-state" role="status">
              <strong>기록이 없습니다.</strong>
              <p>{emptyMessage || '선택한 달에 표시할 감정 기록이 없습니다.'}</p>
            </div>
          )}

          <button className="monthly-report-back" type="button" onClick={onBack}>
            메인으로 돌아가기
          </button>
        </section>
      </div>
    </main>
  )
}

export default MonthlyReport
