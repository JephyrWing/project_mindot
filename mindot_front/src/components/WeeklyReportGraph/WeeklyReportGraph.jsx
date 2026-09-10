import { useState } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import './WeeklyReportGraph.css'

// 실제 API 연결 전 그래프 화면 구성을 확인하기 위한 요일별 예시 값 설정.
const weeklyGraphItems = [
  { day: '월', value: 4 },
  { day: '화', value: 6 },
  { day: '수', value: 5 },
  { day: '목', value: 7 },
  { day: '금', value: 3 },
  { day: '토', value: 5 },
  { day: '일', value: 8 },
]

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

  return `${dateFormatter.format(weekStart)} ~ ${dateFormatter.format(weekEnd)}`
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
  // 선택한 주의 월요일부터 일요일까지 표시할 기간 계산.
  const selectedWeekRange = getWeekRange(weekOffset)

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
            <strong>{selectedWeekRange}</strong>
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

          {/* 월요일부터 일요일까지 감정 강도를 비교하는 막대그래프 기본 구조 배치. */}
          <section
            className="weekly-report-graph-chart"
            aria-labelledby="weekly-report-graph-chart-title"
          >
            <header className="weekly-report-graph-chart-heading">
              <h2 id="weekly-report-graph-chart-title">요일별 감정 강도</h2>
              <span>0~10점</span>
            </header>

            <div className="weekly-report-graph-bars">
              {weeklyGraphItems.map((item) => (
                <div
                  className="weekly-report-graph-item"
                  key={item.day}
                  aria-label={`${item.day}요일 감정 강도 ${item.value}점`}
                >
                  <span className="weekly-report-graph-value">{item.value}</span>
                  <div className="weekly-report-graph-track" aria-hidden="true">
                    <span style={{ height: `${item.value * 10}%` }} />
                  </div>
                  <strong>{item.day}</strong>
                </div>
              ))}
            </div>

            <p className="weekly-report-graph-note">
              현재 그래프는 화면 구성 확인을 위한 예시 값입니다.
            </p>
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
