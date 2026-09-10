import { useEffect, useState } from 'react'
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
  // 백엔드에서 조회하거나 생성한 선택 주의 리포트 상태 관리.
  const [report, setReport] = useState(null)
  // 선택 주의 리포트 API 호출 진행 여부 상태 관리.
  const [isLoading, setIsLoading] = useState(true)
  // 선택 주의 리포트 API 호출 실패 안내 상태 관리.
  const [loadError, setLoadError] = useState('')
  // 선택한 주의 월요일부터 일요일까지 표시할 기간 계산.
  const selectedWeek = getWeekRange(weekOffset)

  // 선택 주 변경 시 저장된 리포트 조회와 미생성 리포트 생성 요청 처리.
  useEffect(() => {
    let isActive = true

    const loadWeeklyReport = async () => {
      setIsLoading(true)
      setLoadError('')
      setReport(null)

      try {
        const savedReport = await getWeeklyReport(selectedWeek.weekStart)

        if (isActive) setReport(savedReport)
      } catch (getError) {
        if (!isActive) return

        if (getError.response?.status !== 404) {
          setLoadError('주간 리포트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
          setIsLoading(false)
          return
        }

        try {
          const generatedReport = await generateWeeklyReport(selectedWeek.weekStart)

          if (isActive) setReport(generatedReport)
        } catch (generateError) {
          if (!isActive) return

          setLoadError(
            generateError.response?.status === 409
              ? '선택한 주에 감정 기록이 없어 리포트를 만들 수 없습니다.'
              : '주간 리포트를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
          )
        }
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadWeeklyReport()

    return () => {
      isActive = false
    }
  }, [selectedWeek.weekStart])

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
              onClick={() => setWeekOffset((currentOffset) => currentOffset - 1)}
            >
              ← 이전 주
            </button>
            <button
              type="button"
              onClick={() => setWeekOffset((currentOffset) => Math.min(currentOffset + 1, 0))}
              disabled={weekOffset === 0}
            >
              다음 주 →
            </button>
          </div>

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
            {loadError && (
              <p className="weekly-report-graph-note" role="alert">
                {loadError}
              </p>
            )}
            {report && !isLoading && !loadError && (
              <p className="weekly-report-graph-note" role="status">
                감정 기록 {report.recordCount}건의 리포트 데이터를 불러왔습니다.
              </p>
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
