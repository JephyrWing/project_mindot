import { emotionLabel } from '../../utils/records/emotions.js'
import { distortionLabels } from '../CBT/distortionLabels.js'
import { useEffect, useRef, useState } from 'react'
import { getMyProfile } from '../../utils/users/usersApi.js'
import { addDays, currentWeekStart, dateInZone, weeklyRecords } from '../../utils/reports/weeklyReportData.js'
import { reportEmotionLabel } from '../../utils/records/emotionColors.js'
import WeeklyEmotionCharts from './WeeklyEmotionCharts.jsx'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
  exportWeeklyReportPdf,
  generateWeeklyReport,
  getWeeklyReport,
} from '../../utils/reports/reportsApi.js'
import './WeeklyReport.css'

const pdfMaximumDayCount = 31

const getInclusiveDayCount = (startDate, endDate) => (
  Math.floor(
    (Date.parse(`${endDate}T00:00:00Z`) - Date.parse(`${startDate}T00:00:00Z`))
    / 86_400_000,
  ) + 1
)

// ISO 시각을 주간 리포트 근거 목록에 표시할 한국어 날짜 형식으로 변환.
const formatEvidenceDate = (occurredAt, timezone) => {
  if (!occurredAt) return '기록 시각 없음'

  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: timezone,
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(occurredAt))
}

// 주간 리포트 조회 및 생성 API 오류를 사용자 안내 문구로 변환.
const getReportErrorMessage = (error) => {
  if (!error.response) {
    return error.isAxiosError ? '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.' : error.message
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

// PDF 내보내기 API 오류를 날짜 및 인증 상태에 맞는 안내 문구로 변환.
const getPdfExportErrorMessage = (error) => {
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (error.response.status === 400) {
    return '선택한 날짜와 PDF 포함 범위를 확인해 주세요.'
  }
  if (error.response.status === 401) {
    return '로그인 정보가 만료되었습니다. 다시 로그인해 주세요.'
  }
  if (error.response.status === 404) {
    return 'PDF를 생성할 사용자 정보를 찾을 수 없습니다.'
  }
  if ([409, 422].includes(error.response.status)) {
    return '선택한 기간에 PDF로 내보낼 감정 기록 또는 완료한 CBT 성찰이 없습니다.'
  }

  return 'PDF 파일을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

// 공통 네비게이션과 실제 주간 리포트 API 결과를 제공하는 화면 컴포넌트 정의.
function WeeklyReport({
  weekStart,
  onWeekChange,
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onRecordDetail,
  onCompletedReflection,
  onCenter,
  onDailyCare,
  onMonthlyReport,
  onBack,
  onHome,
}) {
  // 현재 주를 기준으로 사용자가 이동한 주간 위치 상태 관리.
  const [defaultWeek, setDefaultWeek] = useState(() => currentWeekStart())
  const [timezone, setTimezone] = useState('Asia/Seoul')
  const forceRefresh = useRef(false)
  const [chartRecords, setChartRecords] = useState([])
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
  // PDF 내보내기 날짜 선택 방식 상태 관리.
  const [pdfSelectionMode, setPdfSelectionMode] = useState('range')
  // 기간 선택 방식의 PDF 시작일 상태 관리.
  const [pdfStartDate, setPdfStartDate] = useState(
    () => currentWeekStart(),
  )
  // 기간 선택 방식의 PDF 종료일 상태 관리.
  const [pdfEndDate, setPdfEndDate] = useState(
    () => dateInZone(new Date(), 'Asia/Seoul'),
  )
  // 여러 날짜 직접 선택 방식의 현재 날짜 입력값 상태 관리.
  const [pdfDateInput, setPdfDateInput] = useState('')
  // 여러 날짜 직접 선택 방식에서 추가한 날짜 목록 상태 관리.
  const [pdfSelectedDates, setPdfSelectedDates] = useState([])
  // PDF에 포함할 감정 기록과 CBT 결과 범위 상태 관리.
  const [pdfContentType, setPdfContentType] = useState('BOTH')
  // 완료 CBT의 전체 질문과 답변 포함 여부 상태 관리.
  const [includeFullCbtConversation, setIncludeFullCbtConversation] = useState(false)
  // PDF 내보내기 완료 안내 문구 상태 관리.
  const [exportMessage, setExportMessage] = useState('')
  // 선택한 주의 완료 CBT 전용 목록 화면 표시 상태 관리.
  const [showCompletedCbtList, setShowCompletedCbtList] = useState(false)
  const selectedStart = weekStart ?? defaultWeek
  const selectedWeek = { weekStart: selectedStart, weekEnd: addDays(selectedStart, 6),
    label: `${selectedStart} ~ ${addDays(selectedStart, 6)}` }
  const todayDate = dateInZone(new Date(), timezone)

  // URL의 선택 주가 바뀌거나 상세 화면에서 돌아오면 PDF 기본 날짜도 같은 주로 맞춘다.
  useEffect(() => {
    setPdfSelectionMode('range')
    setPdfStartDate(selectedStart)
    setPdfEndDate(addDays(selectedStart, 6) > todayDate ? todayDate : addDays(selectedStart, 6))
    setPdfSelectedDates([])
    setExportError('')
    setExportMessage('')
  }, [selectedStart, todayDate])

  // Load profile timezone and a single, internally consistent report snapshot.
  useEffect(() => {
    let active = true
    const force = forceRefresh.current
    forceRefresh.current = false
    const load = async () => {
      setIsLoading(true)
      setLoadError('')
      setEmptyMessage('')
      setReport(null)
      setChartRecords([])
      try {
        const profile = await getMyProfile()
        const zone = profile.timezone
        if (!zone) throw new Error('사용자 시간대를 확인할 수 없습니다. 다시 불러와 주세요.')
        const start = weekStart ?? currentWeekStart(zone)
        if (!active) return
        setTimezone(zone)
        setDefaultWeek(currentWeekStart(zone))
        let saved
        if (!force) {
          try {
            saved = await getWeeklyReport(start)
          } catch (error) {
            if (error.response?.status !== 404) throw error
          }
          if (saved) {
            try { weeklyRecords(saved, start, zone) } catch { saved = null }
          }
        }
        if (!active) return
        if (!saved) saved = await generateWeeklyReport(start)
        const records = weeklyRecords(saved, start, zone)
        if (active) { setReport(saved); setChartRecords(records) }
      } catch (error) {
        if (!active) return
        if (error.response?.status === 409) setEmptyMessage('선택한 주에 감정 기록과 완료된 CBT 성찰이 없습니다.')
        else setLoadError(getReportErrorMessage(error))
      } finally {
        if (active) { setIsLoading(false); setIsRefreshing(false) }
      }
    }
    load()
    return () => { active = false }
  }, [reloadCount, weekStart])

  const handleWeekMove = (days) => {
    setShowCompletedCbtList(false)
    const nextWeekStart = addDays(selectedStart, days * 7)

    onWeekChange(nextWeekStart)
  }
  const handleReportRefresh = () => {
    if (isRefreshing || isLoading) return
    forceRefresh.current = true
    setIsRefreshing(true)
    setReloadCount((count) => count + 1)
  }

  // 여러 날짜 직접 선택 방식에서 중복을 제외한 날짜 추가 처리.
  const handlePdfDateAdd = () => {
    if (!pdfDateInput) {
      setExportError('추가할 날짜를 먼저 선택해 주세요.')
      setExportMessage('')
      return
    }

    if (pdfDateInput > todayDate) {
      setExportError('미래 날짜는 PDF에 포함할 수 없습니다.')
      setExportMessage('')
      return
    }

    if (pdfSelectedDates.includes(pdfDateInput)) {
      setExportError('이미 추가한 날짜입니다.')
      setExportMessage('')
      return
    }


    if (pdfSelectedDates.length >= pdfMaximumDayCount) {
      setExportError('PDF에 직접 선택할 수 있는 날짜는 최대 31개입니다.')
      setExportMessage('')
      return
    }

    setPdfSelectedDates((currentDates) => (
      [...currentDates, pdfDateInput].sort()
    ))
    setPdfDateInput('')
    setExportError('')
    setExportMessage('')
  }

  // 여러 날짜 직접 선택 방식에서 선택한 날짜 한 건 제거 처리.
  const handlePdfDateRemove = (dateToRemove) => {
    setPdfSelectedDates((currentDates) => (
      currentDates.filter((selectedDate) => selectedDate !== dateToRemove)
    ))
    setExportError('')
    setExportMessage('')
  }

  // 사용자가 지정한 날짜와 포함 범위를 사용한 상담용 PDF 내려받기 처리.
  const handlePdfExport = async () => {
    if (isExporting) return

    if (pdfSelectionMode === 'range'
      && (!pdfStartDate || !pdfEndDate)) {
      setExportError('시작일과 종료일을 모두 선택해 주세요.')
      setExportMessage('')
      return
    }

    if (pdfSelectionMode === 'range' && pdfEndDate < pdfStartDate) {
      setExportError('종료일은 시작일보다 빠를 수 없습니다.')
      setExportMessage('')
      return
    }


    if (pdfSelectionMode === 'range'
      && (pdfStartDate > todayDate || pdfEndDate > todayDate)) {
      setExportError('미래 날짜는 PDF에 포함할 수 없습니다.')
      setExportMessage('')
      return
    }

    if (pdfSelectionMode === 'range'
      && getInclusiveDayCount(pdfStartDate, pdfEndDate) > pdfMaximumDayCount) {
      setExportError('PDF는 최대 31일까지 내보낼 수 있습니다.')
      setExportMessage('')
      return
    }

    if (pdfSelectionMode === 'dates' && pdfSelectedDates.length === 0) {
      setExportError('PDF에 포함할 날짜를 하나 이상 추가해 주세요.')
      setExportMessage('')
      return
    }


    if (pdfSelectionMode === 'dates'
      && pdfSelectedDates.some((selectedDate) => selectedDate > todayDate)) {
      setExportError('미래 날짜는 PDF에 포함할 수 없습니다.')
      setExportMessage('')
      return
    }

    setIsExporting(true)
    setExportError('')
    setExportMessage('')

    try {
      const pdfBlob = await exportWeeklyReportPdf({
        startDate: pdfSelectionMode === 'range' ? pdfStartDate : null,
        endDate: pdfSelectionMode === 'range' ? pdfEndDate : null,
        selectedDates: pdfSelectionMode === 'dates' ? pdfSelectedDates : null,
        contentType: pdfContentType,
        includeFullCbtConversation: pdfContentType !== 'EMOTION_RECORDS'
          && includeFullCbtConversation,
      })
      const downloadUrl = window.URL.createObjectURL(pdfBlob)
      const downloadLink = document.createElement('a')
      const fileDateLabel = pdfSelectionMode === 'range'
        ? `${pdfStartDate}-${pdfEndDate}`
        : pdfSelectedDates.length === 1
          ? pdfSelectedDates[0]
          : `${pdfSelectedDates[0]}-외-${pdfSelectedDates.length - 1}일`

      downloadLink.href = downloadUrl
      downloadLink.download = `mindot-report-${fileDateLabel}.pdf`
      document.body.appendChild(downloadLink)
      downloadLink.click()
      downloadLink.remove()
      window.URL.revokeObjectURL(downloadUrl)
      setExportMessage('선택한 조건의 PDF 파일 다운로드를 시작했습니다.')
    } catch (error) {
      setExportError(getPdfExportErrorMessage(error))
    } finally {
      setIsExporting(false)
    }
  }

  // 주간 통계 분포와 주요 요약값을 API 결과에서 화면 표시 형식으로 변환.
  const summaryItems = report ? [
    { label: '기록 횟수', value: `${report.recordCount}회` },
    {
      label: '주요 감정',
      value: emotionLabel(report.dominantEmotionCode, '기록 없음'),
    },
    {
      label: '평균 강도',
      value: Number.isFinite(report.averageIntensity)
        ? `${report.averageIntensity.toFixed(1)}/10`
        : '기록 없음',
    },
    { label: '완료 CBT', value: `${report.completedCbtCount}회` },
  ] : []
  // 주간 리포트에서 분리한 완료 CBT 전용 목록 화면 반환.
  if (showCompletedCbtList && report) {
    const completedCbtEvidences = report.completedCbtEvidences ?? []

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
          <section className="weekly-report-card weekly-report-cbt-list-screen" aria-labelledby="weekly-report-cbt-list-title">
            <BrandLogo className="weekly-report-logo" onClick={onHome} />
            <h1 id="weekly-report-cbt-list-title">완료한 CBT 성찰</h1>
            <p className="weekly-report-description">{selectedWeek.label}에 완료한 성찰을 모아 확인합니다.</p>
            <div className="weekly-report-cbt-list-count">총 {completedCbtEvidences.length}건</div>
            <div className="weekly-report-cbt-list">
              {completedCbtEvidences.map((evidence) => (
                <article key={evidence.sessionId}>
                  <header>
                    <strong>{evidence.confirmedResult ? '성찰 후 정리한 생각' : '대안적 사고 (기존 결과)'}</strong>
                    <span>
                      {Number.isFinite(evidence.helpfulnessScore)
                        ? `도움 정도 ${evidence.helpfulnessScore}/5`
                        : '도움 정도 미입력'}
                    </span>
                  </header>
                  <p>{evidence.confirmedResult?.afterText ?? evidence.alternativeThoughtText ?? '저장된 생각이 없습니다.'}</p>
                  {evidence.confirmedResult?.beforeText && <p className="weekly-report-cbt-before">처음 생각 · {evidence.confirmedResult.beforeText}</p>}
                  {onCompletedReflection && <button type="button" onClick={() => onCompletedReflection(evidence.sessionId, selectedStart)}>
                    성찰 결과 자세히 보기
                  </button>}
                </article>
              ))}
            </div>
            <button className="weekly-report-back-button" type="button" onClick={() => setShowCompletedCbtList(false)}>
              주간 리포트로 돌아가기
            </button>
          </section>
        </div>
      </main>
    )
  }

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
        <section
          className="weekly-report-card"
          aria-labelledby="weekly-report-title"
          aria-busy={isLoading || isRefreshing}
        >
          <BrandLogo className="weekly-report-logo" onClick={onHome} />

          <h1 id="weekly-report-title">주간 리포트</h1>
          <p className="weekly-report-description">
            선택한 주의 감정 기록과 CBT 성찰 흐름을 확인하는 공간입니다.
            <span>
              감정 통계는 감정 발생일, CBT 통계는 성찰 완료일 기준이며 PDF에는 선택한 감정 기록과 연결된 CBT가 포함됩니다.
            </span>
          </p>

          <div className="weekly-report-period">
            <span>리포트 기간</span>
            <strong>{selectedWeek.label}</strong>
          </div>

          <div className="weekly-report-navigation" aria-label="주간 리포트 기간 선택">
            <button
              type="button"
              onClick={() => handleWeekMove(-1)}
            >
              ← 이전 주
            </button>
            <button
              type="button"
              onClick={() => handleWeekMove(1)}
              disabled={selectedStart >= currentWeekStart(timezone)}
            >
              다음 주 →
            </button>
          </div>

          {/* 주간·월간 리포트 사이의 동일 위치 이동 메뉴 배치. */}
          <nav className="weekly-report-view-switch" aria-label="리포트 종류 선택">
            <button type="button" aria-current="page" disabled>
              주간 리포트
            </button>
            <button type="button" onClick={onMonthlyReport}>
              월간 리포트
            </button>
          </nav>

          <h2 className="weekly-report-selected-period">
            {selectedWeek.label} 요약
          </h2>

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
              {/* 월간 리포트와 동일한 구분선형 통계 요약 배치. */}
              <dl className="weekly-report-summary">
                {summaryItems.map((summaryItem) => (
                  <div key={summaryItem.label}>
                    <dt>{summaryItem.label}</dt>
                    <dd>{summaryItem.value}</dd>
                  </div>
                ))}
              </dl>

              <WeeklyEmotionCharts key={selectedStart} records={chartRecords} timezone={timezone}
                onRecordDetail={(id) => onRecordDetail?.(id, selectedStart)} />

              <section className="weekly-report-patterns">
                <h2>성찰로 알아차린 생각 패턴</h2>
                <p>완료한 CBT 성찰에서 스스로 확인한 생각의 경향을 모아 보여드립니다.</p>
                {Object.entries(report.distortionChangeCounts?.CONFIRMED_INSIGHT ?? {}).length ? <ul>
                  {Object.entries(report.distortionChangeCounts.CONFIRMED_INSIGHT).map(([code, count]) => <li key={code}>{distortionLabels[code] ?? code}: {count}회</li>)}
                </ul> : <p>이번 주 성찰에서 확인한 생각 패턴이 없습니다.</p>}
              </section>

              <section
                className="weekly-report-evidence"
                aria-labelledby="weekly-report-emotion-evidence-title"
              >
                <h2 id="weekly-report-emotion-evidence-title">근거 감정 기록</h2>
                {chartRecords.length > 0 ? (
                  <div>
                    {chartRecords.map((evidence) => (
                      <article key={evidence.emotionRecordId}>
                        <header>
                          <strong>
                            {reportEmotionLabel(evidence.primaryEmotionCode)}
                            {Number.isFinite(evidence.primaryIntensity)
                              ? ` · 강도 ${evidence.primaryIntensity}/10`
                              : ''}
                          </strong>
                          <time dateTime={evidence.occurredAt}>
                            {formatEvidenceDate(evidence.occurredAt, timezone)}
                          </time>
                        </header>
                        <p>{evidence.situationText || '상황 정보가 없습니다.'}</p>
                        {onRecordDetail && (
                          <button
                            type="button"
                            onClick={() => onRecordDetail(evidence.emotionRecordId, selectedStart)}
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
                className="weekly-report-cbt-summary"
                aria-labelledby="weekly-report-cbt-evidence-title"
              >
                <h2 id="weekly-report-cbt-evidence-title">완료한 CBT 성찰</h2>
                <p>{(report.completedCbtEvidences ?? []).length > 0
                  ? `선택한 주에 완료한 CBT 성찰이 ${(report.completedCbtEvidences ?? []).length}건 있습니다.`
                  : '선택한 주에 완료한 CBT 성찰이 없습니다.'}</p>
                {(report.completedCbtEvidences ?? []).length > 0 && <button type="button" onClick={() => setShowCompletedCbtList(true)}>
                  완료한 CBT 성찰 보기
                </button>}
              </section>

            </>
          ) : (
            <div className="weekly-report-state" role="status">
              <strong>아직 표시할 리포트가 없습니다.</strong>
              <p>{emptyMessage || '감정 기록을 남기면 이곳에서 한 주의 흐름을 확인할 수 있습니다.'}</p>
            </div>
          )}

          {/* 최신 데이터 갱신 동작의 공통 위치 배치. */}
          {report && (
            <div className="weekly-report-data-actions">
              <button
                type="button"
                onClick={handleReportRefresh}
                disabled={isRefreshing}
              >
                {isRefreshing ? '최신화 중…' : '최신 기록으로 다시 만들기'}
              </button>
            </div>
          )}

          {/* 날짜와 포함 내용을 직접 정하는 상담용 PDF 내보내기 설정 영역 배치. */}
          <section
            className="weekly-report-export"
            aria-labelledby="weekly-report-export-title"
          >
            <div className="weekly-report-export-heading">
              <h2 id="weekly-report-export-title">PDF 내보내기</h2>
              <p>상담 시 확인할 날짜와 포함할 기록을 선택해 주세요.</p>
            </div>

            <fieldset className="weekly-report-export-mode">
              <legend>날짜 선택 방법</legend>
              <div>
                <label>
                  <input
                    type="radio"
                    name="pdf-selection-mode"
                    value="range"
                    checked={pdfSelectionMode === 'range'}
                    disabled={isExporting}
                    onChange={(event) => {
                      setPdfSelectionMode(event.target.value)
                      setExportError('')
                      setExportMessage('')
                    }}
                  />
                  <span>기간으로 선택</span>
                </label>
                <label>
                  <input
                    type="radio"
                    name="pdf-selection-mode"
                    value="dates"
                    checked={pdfSelectionMode === 'dates'}
                    disabled={isExporting}
                    onChange={(event) => {
                      setPdfSelectionMode(event.target.value)
                      setExportError('')
                      setExportMessage('')
                    }}
                  />
                  <span>날짜 직접 선택</span>
                </label>
              </div>
            </fieldset>

            {pdfSelectionMode === 'range' ? (
              /* 시작일부터 종료일까지 연속된 날짜 범위 입력 영역 표시. */
              <div className="weekly-report-export-range">
                <label>
                  <span>시작일</span>
                  <input
                    type="date"
                    value={pdfStartDate}
                    max={todayDate}
                    disabled={isExporting}
                    onChange={(event) => {
                      setPdfStartDate(event.target.value)
                      setExportError('')
                      setExportMessage('')
                    }}
                  />
                </label>
                <label>
                  <span>종료일</span>
                  <input
                    type="date"
                    value={pdfEndDate}
                    min={pdfStartDate || undefined}
                    max={todayDate}
                    disabled={isExporting}
                    onChange={(event) => {
                      setPdfEndDate(event.target.value)
                      setExportError('')
                      setExportMessage('')
                    }}
                  />
                </label>
              </div>
            ) : (
              /* 서로 떨어진 여러 날짜를 하나씩 추가하는 직접 선택 영역 표시. */
              <div className="weekly-report-export-dates">
                <label htmlFor="weekly-report-pdf-date">
                  <span>추가할 날짜</span>
                  <div>
                    <input
                      id="weekly-report-pdf-date"
                      type="date"
                      value={pdfDateInput}
                      max={todayDate}
                      disabled={isExporting}
                      onChange={(event) => {
                        setPdfDateInput(event.target.value)
                        setExportError('')
                        setExportMessage('')
                      }}
                    />
                    <button
                      type="button"
                      onClick={handlePdfDateAdd}
                      disabled={isExporting}
                    >
                      날짜 추가
                    </button>
                  </div>
                </label>

                {pdfSelectedDates.length > 0 ? (
                  <ul aria-label="PDF에 포함할 선택 날짜">
                    {pdfSelectedDates.map((selectedDate) => (
                      <li key={selectedDate}>
                        <time dateTime={selectedDate}>{selectedDate}</time>
                        <button
                          type="button"
                          aria-label={`${selectedDate} 삭제`}
                          disabled={isExporting}
                          onClick={() => handlePdfDateRemove(selectedDate)}
                        >
                          삭제
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="weekly-report-export-empty-date">
                    아직 추가한 날짜가 없습니다.
                  </p>
                )}
              </div>
            )}

            <div className="weekly-report-export-options">
              <label>
                <span>PDF 포함 내용</span>
                <select
                  value={pdfContentType}
                  disabled={isExporting}
                  onChange={(event) => {
                    const selectedContentType = event.target.value

                    setPdfContentType(selectedContentType)
                    if (selectedContentType === 'EMOTION_RECORDS') {
                      setIncludeFullCbtConversation(false)
                    }
                    setExportError('')
                    setExportMessage('')
                  }}
                >
                  <option value="EMOTION_RECORDS">감정 기록만</option>
                  <option value="CBT_RESULTS">완료 CBT만</option>
                  <option value="BOTH">감정 기록과 완료 CBT 모두</option>
                </select>
              </label>

              <label className="weekly-report-export-checkbox">
                <input
                  type="checkbox"
                  checked={includeFullCbtConversation}
                  disabled={isExporting || pdfContentType === 'EMOTION_RECORDS'}
                  onChange={(event) => {
                    setIncludeFullCbtConversation(event.target.checked)
                    setExportError('')
                    setExportMessage('')
                  }}
                />
                <span>
                  <strong>CBT 전체 대화 포함</strong>
                  <small>체크하면 완료한 CBT의 질문과 답변 전체를 포함합니다.</small>
                </span>
              </label>
            </div>

            <button
              className="weekly-report-export-button"
              type="button"
              onClick={handlePdfExport}
              disabled={isExporting}
            >
              {isExporting ? 'PDF 준비 중…' : '선택한 내용 PDF로 저장'}
            </button>

            {exportError && (
              <p className="weekly-report-error" role="alert">{exportError}</p>
            )}
            {exportMessage && (
              <p className="weekly-report-export-success" role="status">
                {exportMessage}
              </p>
            )}
          </section>

          {report && (
            <p className="weekly-report-snapshot">
              최근 집계 시각 · {formatEvidenceDate(report.sourceSnapshotAt, timezone)}
            </p>
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
