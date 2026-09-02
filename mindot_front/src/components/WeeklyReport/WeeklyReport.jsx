import { useEffect, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  exportWeeklyReportPdf,
  generateWeeklyReport,
  getWeeklyReport,
} from '../../utils/reports/reportsApi.js'
import './WeeklyReport.css'

// 백엔드 감정 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
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

// 백엔드 요일 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const weekdayLabels = {
  MONDAY: '월요일',
  TUESDAY: '화요일',
  WEDNESDAY: '수요일',
  THURSDAY: '목요일',
  FRIDAY: '금요일',
  SATURDAY: '토요일',
  SUNDAY: '일요일',
}

// 백엔드 시간대 코드를 사용자에게 표시할 한국어 이름으로 변환하기 위한 목록 설정.
const timeBucketLabels = {
  DAWN: '새벽',
  MORNING: '아침',
  AFTERNOON: '오후',
  EVENING: '저녁',
  NIGHT: '밤',
}

// 반복 감정 패턴 강도를 사용자에게 안내할 문구로 변환하기 위한 목록 설정.
const patternLevelLabels = {
  RECENT: '최근 반복',
  REPEATED: '2주 이상 반복',
  SUSTAINED: '지속 패턴',
  LONG_TERM: '장기 패턴',
}

// 지역 시각 기준 날짜를 백엔드 LocalDate 요청 형식으로 변환.
const toLocalDateValue = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')

  return `${year}-${month}-${day}`
}

// 사용자가 선택한 주를 기준으로 월요일과 일요일 날짜 범위 계산.
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
    weekEnd: toLocalDateValue(weekEnd),
    label: `${dateFormatter.format(weekStart)} ~ ${dateFormatter.format(weekEnd)}`,
  }
}

// ISO 시각을 주간 리포트 근거 목록에 표시할 한국어 날짜 형식으로 변환.
const formatEvidenceDate = (occurredAt) => {
  if (!occurredAt) return '기록 시각 없음'

  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(occurredAt))
}

// 숫자 통계 객체를 기록 수가 많은 순서의 화면 표시 배열로 변환.
const createCountItems = (counts, labels) => Object.entries(counts ?? {})
  .sort(([, firstCount], [, secondCount]) => secondCount - firstCount)
  .map(([code, count]) => ({
    code,
    label: labels[code] ?? code,
    count,
  }))

// 주간 리포트 조회 및 생성 API 오류를 사용자 안내 문구로 변환.
const getReportErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 400) {
    return '선택한 주간 범위를 확인해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }

  return error.response.data?.message
    || error.response.data?.detail
    || '주간 리포트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 공통 네비게이션과 실제 주간 리포트 API 결과를 제공하는 화면 컴포넌트 정의.
function WeeklyReport({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onRecordDetail,
  onCenter,
  onDailyCare,
  onBack,
  onHome,
}) {
  // 현재 주를 기준으로 사용자가 이동한 주간 위치 상태 관리.
  const [weekOffset, setWeekOffset] = useState(0)
  // 백엔드에서 조회하거나 생성한 선택 주의 리포트 상태 관리.
  const [report, setReport] = useState(null)
  // 주간 리포트 최초 조회 및 자동 생성 진행 상태 관리.
  const [isLoading, setIsLoading] = useState(true)
  // 주간 리포트 조회 및 생성 실패 안내 상태 관리.
  const [loadError, setLoadError] = useState('')
  // 기록이 없는 주의 빈 리포트 안내 상태 관리.
  const [emptyMessage, setEmptyMessage] = useState('')
  // 사용자의 주간 리포트 재조회 요청 횟수 상태 관리.
  const [reloadCount, setReloadCount] = useState(0)
  // 최신 기록 기준 리포트 갱신 요청 진행 상태 관리.
  const [isRefreshing, setIsRefreshing] = useState(false)
  // 상담용 PDF 내보내기 요청 진행 상태 관리.
  const [isExporting, setIsExporting] = useState(false)
  // 상담용 PDF 내보내기 실패 안내 상태 관리.
  const [exportError, setExportError] = useState('')

  // 선택한 주의 월요일 요청값과 화면 표시 기간 생성.
  const selectedWeek = getWeekRange(weekOffset)

  // 선택한 주의 저장 리포트를 조회하고 미생성 상태이면 최신 기록으로 자동 생성 요청.
  useEffect(() => {
    let isActive = true

    const loadWeeklyReport = async () => {
      setIsLoading(true)
      setLoadError('')
      setEmptyMessage('')
      setExportError('')
      setReport(null)

      try {
        const savedReport = await getWeeklyReport(selectedWeek.weekStart)

        if (isActive) setReport(savedReport)
      } catch (getError) {
        if (getError.response?.status !== 404) {
          if (isActive) setLoadError(getReportErrorMessage(getError))
          return
        }

        try {
          const generatedReport = await generateWeeklyReport(selectedWeek.weekStart)

          if (isActive) setReport(generatedReport)
        } catch (generateError) {
          if (!isActive) return

          if (generateError.response?.status === 409) {
            setEmptyMessage('선택한 주에 감정 기록이 없어 아직 리포트를 만들 수 없습니다.')
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

  // 선택한 주의 최신 감정 기록과 CBT 결과를 사용한 리포트 재생성 처리.
  const handleReportRefresh = async () => {
    if (isRefreshing || isLoading) return

    setIsRefreshing(true)
    setLoadError('')
    setEmptyMessage('')
    setExportError('')

    try {
      const refreshedReport = await generateWeeklyReport(selectedWeek.weekStart)

      setReport(refreshedReport)
    } catch (error) {
      setReport(null)

      if (error.response?.status === 409) {
        setEmptyMessage('선택한 주에 감정 기록이 없어 아직 리포트를 만들 수 없습니다.')
      } else {
        setLoadError(getReportErrorMessage(error))
      }
    } finally {
      setIsRefreshing(false)
    }
  }

  // 선택 주의 감정 기록과 완료 CBT 결과를 PDF로 내려받는 처리.
  const handlePdfExport = async () => {
    if (!report || isExporting) return

    setIsExporting(true)
    setExportError('')

    try {
      const pdfBlob = await exportWeeklyReportPdf({
        startDate: report.periodStart,
        endDate: report.periodEnd,
      })
      const downloadUrl = window.URL.createObjectURL(pdfBlob)
      const downloadLink = document.createElement('a')

      downloadLink.href = downloadUrl
      downloadLink.download = `mindot-weekly-report-${report.periodStart}.pdf`
      document.body.appendChild(downloadLink)
      downloadLink.click()
      downloadLink.remove()
      window.URL.revokeObjectURL(downloadUrl)
    } catch (error) {
      setExportError(getReportErrorMessage(error))
    } finally {
      setIsExporting(false)
    }
  }

  // 주간 통계 분포와 주요 요약값을 API 결과에서 화면 표시 형식으로 변환.
  const summaryItems = report ? [
    { label: '기록 횟수', value: `${report.recordCount}회` },
    {
      label: '주요 감정',
      value: emotionCodeLabels[report.dominantEmotionCode] ?? '기록 없음',
    },
    {
      label: '평균 강도',
      value: Number.isFinite(report.averageIntensity)
        ? `${report.averageIntensity.toFixed(1)}/5`
        : '-',
    },
    { label: '완료 CBT', value: `${report.completedCbtCount}회` },
    {
      label: '평균 도움',
      value: Number.isFinite(report.averageHelpfulnessScore)
        ? `${report.averageHelpfulnessScore.toFixed(1)}/5`
        : '-',
    },
  ] : []
  const distributionGroups = report ? [
    {
      title: '감정 분포',
      items: createCountItems(report.emotionCounts, emotionCodeLabels),
    },
    {
      title: '요일 분포',
      items: createCountItems(report.weekdayCounts, weekdayLabels),
    },
    {
      title: '시간대 분포',
      items: createCountItems(report.timeBucketCounts, timeBucketLabels),
    },
  ] : []

  // 실제 API 리포트와 기간 탐색 기능을 포함한 주간 리포트 화면 반환.
  return (
    <main className="weekly-report-page">
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

      <div className="weekly-report-content">
        <section className="weekly-report-card" aria-labelledby="weekly-report-title">
          <BrandLogo className="weekly-report-logo" onClick={onHome} />

          <h1 id="weekly-report-title">주간 리포트</h1>
          <p className="weekly-report-description">
            선택한 주의 감정 기록과 CBT 성찰 흐름을 확인하는 공간입니다.
          </p>

          <div className="weekly-report-period">
            <span>리포트 기간</span>
            <strong>{selectedWeek.label}</strong>
          </div>

          <div className="weekly-report-navigation" aria-label="주간 리포트 기간 선택">
            <button
              type="button"
              onClick={() => setWeekOffset((currentOffset) => currentOffset - 1)}
              disabled={isLoading}
            >
              ← 이전 주
            </button>
            <button
              type="button"
              onClick={() => setWeekOffset((currentOffset) => currentOffset + 1)}
              disabled={weekOffset === 0 || isLoading}
            >
              다음 주 →
            </button>
          </div>

          {isLoading ? (
            <div className="weekly-report-state" role="status" aria-live="polite">
              <strong>주간 리포트를 불러오는 중입니다.</strong>
              <p>선택한 주의 감정 기록을 확인하고 있습니다.</p>
            </div>
          ) : loadError ? (
            <div className="weekly-report-state weekly-report-state--error" role="alert">
              <strong>주간 리포트를 불러오지 못했습니다.</strong>
              <p>{loadError}</p>
              <button
                type="button"
                onClick={() => setReloadCount((currentCount) => currentCount + 1)}
              >
                다시 불러오기
              </button>
            </div>
          ) : report ? (
            <>
              <div className="weekly-report-data-actions">
                <button
                  type="button"
                  onClick={handleReportRefresh}
                  disabled={isRefreshing || isExporting}
                >
                  {isRefreshing ? '최신화 중…' : '최신 기록으로 다시 만들기'}
                </button>
                <button
                  type="button"
                  onClick={handlePdfExport}
                  disabled={isExporting || isRefreshing}
                >
                  {isExporting ? 'PDF 준비 중…' : '상담용 PDF 저장'}
                </button>
              </div>

              {exportError && (
                <p className="weekly-report-error" role="alert">{exportError}</p>
              )}

              <section
                className="weekly-report-summary"
                aria-labelledby="weekly-report-summary-title"
              >
                <h2 id="weekly-report-summary-title">선택한 주 요약</h2>
                <div className="weekly-report-summary-grid">
                  {summaryItems.map((summaryItem) => (
                    <article key={summaryItem.label}>
                      <span>{summaryItem.label}</span>
                      <strong>{summaryItem.value}</strong>
                    </article>
                  ))}
                </div>
              </section>

              <section
                className="weekly-report-distributions"
                aria-labelledby="weekly-report-distributions-title"
              >
                <h2 id="weekly-report-distributions-title">기록 분포</h2>
                <div>
                  {distributionGroups.map((group) => (
                    <section key={group.title}>
                      <h3>{group.title}</h3>
                      {group.items.length > 0 ? (
                        <ul>
                          {group.items.map((item) => (
                            <li key={item.code}>
                              <span>{item.label}</span>
                              <strong>{item.count}회</strong>
                            </li>
                          ))}
                        </ul>
                      ) : (
                        <p>집계할 기록이 없습니다.</p>
                      )}
                    </section>
                  ))}
                </div>
              </section>

              <section
                className="weekly-report-patterns"
                aria-labelledby="weekly-report-patterns-title"
              >
                <h2 id="weekly-report-patterns-title">반복 감정 패턴</h2>
                {(report.repeatedPatterns ?? []).length > 0 ? (
                  <ul>
                    {report.repeatedPatterns.map((pattern, index) => (
                      <li key={`${pattern.emotionCode}-${pattern.weekday}-${pattern.timeBucket}-${index}`}>
                        <strong>
                          {emotionCodeLabels[pattern.emotionCode] ?? pattern.emotionCode}
                          {' · '}
                          {pattern.weekday ? `${weekdayLabels[pattern.weekday] ?? pattern.weekday} · ` : ''}
                          {timeBucketLabels[pattern.timeBucket] ?? pattern.timeBucket}
                        </strong>
                        <span>
                          {patternLevelLabels[pattern.patternLevel] ?? pattern.patternLevel}
                          {' · '}{pattern.occurrenceCount}회 기록
                        </span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p>반복 기준을 충족한 감정 패턴이 아직 없습니다.</p>
                )}
              </section>

              <section
                className="weekly-report-evidence"
                aria-labelledby="weekly-report-emotion-evidence-title"
              >
                <h2 id="weekly-report-emotion-evidence-title">근거 감정 기록</h2>
                {(report.emotionRecordEvidences ?? []).length > 0 ? (
                  <div>
                    {report.emotionRecordEvidences.map((evidence) => (
                      <article key={evidence.emotionRecordId}>
                        <header>
                          <strong>
                            {emotionCodeLabels[evidence.primaryEmotionCode]
                              ?? evidence.primaryEmotionCode
                              ?? '분석 전'}
                            {Number.isFinite(evidence.primaryIntensity)
                              ? ` · 강도 ${evidence.primaryIntensity}/5`
                              : ''}
                          </strong>
                          <time dateTime={evidence.occurredAt}>
                            {formatEvidenceDate(evidence.occurredAt)}
                          </time>
                        </header>
                        <p>{evidence.situationText || '상황 정보가 없습니다.'}</p>
                        {onRecordDetail && (
                          <button
                            type="button"
                            onClick={() => onRecordDetail(evidence.emotionRecordId)}
                          >
                            기록 상세 보기
                          </button>
                        )}
                      </article>
                    ))}
                  </div>
                ) : (
                  <p>리포트에 연결된 감정 기록이 없습니다.</p>
                )}
              </section>

              <section
                className="weekly-report-evidence"
                aria-labelledby="weekly-report-cbt-evidence-title"
              >
                <h2 id="weekly-report-cbt-evidence-title">완료한 CBT 성찰</h2>
                {(report.completedCbtEvidences ?? []).length > 0 ? (
                  <div>
                    {report.completedCbtEvidences.map((evidence) => (
                      <article key={evidence.sessionId}>
                        <header>
                          <strong>대안적 사고</strong>
                          <span>
                            {Number.isFinite(evidence.helpfulnessScore)
                              ? `도움 정도 ${evidence.helpfulnessScore}/5`
                              : '도움 정도 미입력'}
                          </span>
                        </header>
                        <p>{evidence.alternativeThoughtText || '정리된 대안적 사고가 없습니다.'}</p>
                      </article>
                    ))}
                  </div>
                ) : (
                  <p>선택한 주에 완료한 CBT 성찰이 없습니다.</p>
                )}
              </section>

              <p className="weekly-report-snapshot">
                최근 집계 시각 · {formatEvidenceDate(report.sourceSnapshotAt)}
              </p>
            </>
          ) : (
            <div className="weekly-report-state">
              <strong>아직 표시할 리포트가 없습니다.</strong>
              <p>{emptyMessage || '감정 기록을 남기면 이곳에서 한 주의 흐름을 확인할 수 있습니다.'}</p>
            </div>
          )}

          <button
            className="weekly-report-back-button"
            type="button"
            onClick={onBack}
          >
            메인으로 돌아가기
          </button>
        </section>
      </div>
    </main>
  )
}

export default WeeklyReport
