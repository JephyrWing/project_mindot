import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './WeeklyReport.css'

// 사용자의 현재 날짜를 기준으로 월요일부터 일요일까지의 주간 범위 계산.
const getCurrentWeekPeriod = () => {
  // 현재 날짜와 요일을 기준으로 이번 주 월요일까지의 이동 일수 계산.
  const today = new Date()
  const dayOfWeek = today.getDay()
  const mondayOffset = dayOfWeek === 0 ? -6 : 1 - dayOfWeek

  // 계산한 이동 일수를 반영한 이번 주 월요일 날짜 생성.
  const weekStart = new Date(today)
  weekStart.setDate(today.getDate() + mondayOffset)

  // 이번 주 월요일에서 6일 뒤인 일요일 날짜 생성.
  const weekEnd = new Date(weekStart)
  weekEnd.setDate(weekStart.getDate() + 6)

  // 연도와 월이 바뀌는 주에도 기간을 명확하게 확인할 수 있는 날짜 형식 설정.
  const dateFormatter = new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })

  // 화면에 표시할 월요일과 일요일 날짜 범위 반환.
  return `${dateFormatter.format(weekStart)} ~ ${dateFormatter.format(weekEnd)}`
}

// 주간 감정 기록 요약을 표시할 간단한 리포트 화면 컴포넌트 정의.
function WeeklyReport({ onBack, onHome }) {
  // 화면을 표시하는 시점의 이번 주 리포트 기간 생성.
  const currentWeekPeriod = getCurrentWeekPeriod()

  // 실제 리포트 데이터 연결 전 제목과 빈 상태만 포함한 기본 화면 반환.
  return (
    <main className="weekly-report-page">
      <section className="weekly-report-card" aria-labelledby="weekly-report-title">
        <BrandLogo className="weekly-report-logo" onClick={onHome} />

        <h1 id="weekly-report-title">주간 리포트</h1>
        <p className="weekly-report-description">
          이번 주 감정 기록을 한눈에 확인하는 공간입니다.
        </p>

        {/* 사용자가 확인 중인 주간 리포트의 시작일과 종료일 표시. */}
        <div className="weekly-report-period">
          <span>리포트 기간</span>
          <strong>{currentWeekPeriod}</strong>
        </div>

        {/* 실제 주간 리포트 API 연결 전 사용자에게 보여 주는 빈 상태 안내. */}
        <div className="weekly-report-empty">
          <strong>아직 표시할 리포트가 없습니다.</strong>
          <p>감정 기록이 쌓이면 이곳에서 한 주의 흐름을 확인할 수 있습니다.</p>
        </div>

        {/* 이전 메인페이지로 돌아가기 위한 기본 이동 버튼 배치. */}
        <button
          className="weekly-report-back-button"
          type="button"
          onClick={onBack}
        >
          메인으로 돌아가기
        </button>
      </section>
    </main>
  )
}

export default WeeklyReport
