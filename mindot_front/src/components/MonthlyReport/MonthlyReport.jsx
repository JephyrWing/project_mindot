import { emotionCodeLabels, emotionLabel } from '../../utils/records/emotions.js'
import { useEffect, useMemo, useRef, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  exportMonthlyReportPdf,
  generateMonthlyReport,
  getMonthlyReport,
} from '../../utils/reports/reportsApi.js'
import './MonthlyReport.css'
import MonthlyEmotionCharts from './MonthlyEmotionCharts.jsx'

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
const localizeSummaryText = (summaryText, emotionCounts) => {
  if (!summaryText) return '표시할 월간 요약이 없습니다.'

  const labels = {
    ...emotionCodeLabels,
    ...contextCategoryLabels,
    // Match full stored names first, so e.g. "JOY 뒤의 허전함" is preserved.
    ...Object.fromEntries(Object.keys(emotionCounts ?? {}).map((code) => [code, emotionLabel(code)])),
  }
  const tokens = Object.keys(labels).sort((a, b) => b.length - a.length)
    .map((code) => code.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return summaryText.replace(new RegExp(tokens.join('|'), 'g'), (code) => labels[code])
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
  onWeeklyReport,
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
  const requestEpoch = useRef({ version: 0 })

  // 선택 월 변경 시 저장된 리포트 조회 후 미생성 상태에서는 자동 생성 요청 처리.
  useEffect(() => {
    let isActive = true
    const tracker = requestEpoch.current
    const epoch = ++tracker.version

    const loadMonthlyReport = async () => {
      setIsLoading(true)
      setReport(null)
      setLoadError('')
      setEmptyMessage('')
      setRefreshMessage('')
      setIsRefreshing(false)
      setIsExporting(false)
      setExportError('')
      setExportMessage('')

      try {
        const savedReport = await getMonthlyReport(selectedMonth)

        if (isActive) setReport(savedReport)
      } catch (getError) {
        if (!isActive) return

        if (getError.response?.status === 409) {
          setEmptyMessage('선택한 달에 감정 기록과 완료한 CBT 성찰이 없습니다.')
          return
        }
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
      if (tracker.version === epoch) tracker.version++
    }
  }, [reloadCount, selectedMonth])

  // 선택 월의 최신 기록을 기준으로 월간 리포트를 다시 생성하는 처리.
  const handleRefresh = async () => {
    if (isLoading || isRefreshing) return

    const epoch = requestEpoch.current.version
    setIsRefreshing(true)
    setLoadError('')
    setEmptyMessage('')
    setRefreshMessage('')

    try {
      const refreshedReport = await generateMonthlyReport(selectedMonth)

      if (epoch !== requestEpoch.current.version) return
      setReport(refreshedReport)
      setRefreshMessage('최신 감정 기록으로 월간 리포트를 갱신했습니다.')
    } catch (error) {
      if (epoch !== requestEpoch.current.version) return
      if (error.response?.status === 409) {
        setReport(null)
        setEmptyMessage('선택한 달에 감정 기록과 완료한 CBT 성찰이 없습니다.')
      } else {
        setLoadError(getMonthlyReportErrorMessage(error))
      }
    } finally {
      if (epoch === requestEpoch.current.version) setIsRefreshing(false)
    }
  }

  // 선택 월의 백엔드 PDF 파일을 브라우저 다운로드로 제공하는 처리.
  const handlePdfExport = async () => {
    if (!report || isExporting || isRefreshing) return

    const epoch = requestEpoch.current.version
    setIsExporting(true)
    setExportError('')
    setExportMessage('')

    try {
      const pdfBlob = await exportMonthlyReportPdf(selectedMonth)
      if (epoch !== requestEpoch.current.version) return
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
      if (epoch === requestEpoch.current.version) setExportError(getMonthlyPdfErrorMessage(error))
    } finally {
      if (epoch === requestEpoch.current.version) setIsExporting(false)
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
        <section
          className="monthly-report-card"
          aria-labelledby="monthly-report-title"
          aria-busy={isLoading || isRefreshing}
        >
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
              onChange={(event) => {
                if (event.target.value) setSelectedMonth(event.target.value)
              }}
            />
          </div>
          <div className="monthly-report-navigation" aria-label="월간 리포트 기간 이동">
            <button
              type="button"
              onClick={() => setSelectedMonth(moveMonthValue(selectedMonth, -1))}
            >
              ← 이전 달
            </button>
            <button
              type="button"
              onClick={() => setSelectedMonth(moveMonthValue(selectedMonth, 1))}
              disabled={selectedMonth === currentMonth}
            >
              다음 달 →
            </button>
          </div>

          {/* 주간·월간 리포트 사이의 동일 위치 이동 메뉴 배치. */}
          <nav className="monthly-report-view-switch" aria-label="리포트 종류 선택">
            <button type="button" onClick={onWeeklyReport}>
              주간 리포트
            </button>
            <button type="button" aria-current="page" disabled>
              월간 리포트
            </button>
          </nav>

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
                  <dd>{emotionLabel(report.dominantEmotionCode, '기록 없음')}</dd>
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

              </dl>

              {/* 백엔드가 실제 집계한 월간 시작일과 종료일 표시. */}
              <p className="monthly-report-period-caption">
                집계 기간 · {formatReportDate(report.periodStart)} ~ {formatReportDate(report.periodEnd)} · {report.emotionComposition?.timezone}
              </p>

              <section className="monthly-report-summary-text" aria-labelledby="monthly-summary-title">
                <h2 id="monthly-summary-title">이번 달 마음 흐름</h2>
                <p>{localizeSummaryText(report.summaryText, report.emotionCounts)}</p>
              </section>

              {report.emotionComposition ? <MonthlyEmotionCharts key={report.periodStart} composition={report.emotionComposition} />
                : <p role="alert">감정 구성 집계를 불러오지 못했습니다. 최신 기록으로 다시 만들어 주세요.</p>}

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
