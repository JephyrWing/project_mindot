import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Navbar from '../Navbar/Navbar.jsx'
import './WeeklyReportGraph.css'

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

          {/* 이후 실제 주간 리포트 그래프를 추가할 기본 영역 배치. */}
          <section className="weekly-report-graph-placeholder" aria-label="주간 감정 그래프 영역">
            <strong>주간 감정 변화</strong>
            <p>그래프가 이곳에 표시됩니다.</p>
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
