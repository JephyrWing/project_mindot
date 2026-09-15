import { useEffect, useMemo, useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import {
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

  return error.response.data?.message
    ?? '월간 리포트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
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

  // 선택 월 변경 시 저장된 리포트 조회 후 미생성 상태에서는 자동 생성 요청 처리.
  useEffect(() => {
    let isActive = true

    const loadMonthlyReport = async () => {
      setIsLoading(true)
      setReport(null)
      setLoadError('')
      setEmptyMessage('')
      setRefreshMessage('')

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
            <div className="monthly-report-state" role="status">
              월간 리포트를 불러오고 있습니다.
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
              </dl>

              <section className="monthly-report-summary-text" aria-labelledby="monthly-summary-title">
                <h2 id="monthly-summary-title">이번 달 마음 흐름</h2>
                <p>{report.summaryText || '표시할 월간 요약이 없습니다.'}</p>
              </section>

              <button
                className="monthly-report-refresh"
                type="button"
                onClick={handleRefresh}
                disabled={isRefreshing}
              >
                {isRefreshing ? '갱신 중…' : '최신 기록으로 갱신'}
              </button>
              {refreshMessage && (
                <p className="monthly-report-refresh-message" role="status">
                  {refreshMessage}
                </p>
              )}
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
