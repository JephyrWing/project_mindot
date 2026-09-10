import Navbar from '../Navbar/Navbar.jsx'
import './Meditation.css'

// 짧은 명상 기능을 단계적으로 확장하기 위한 전용 화면 기본 구조 정의.
function Meditation({
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
  // 공통 네비게이션과 제목 및 안내 문구만 담은 일단계 화면 반환.
  return (
    <div className="meditation-page">
      {/* 다른 서비스 화면과 동일한 공통 네비게이션 배치. */}
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

      <main className="meditation-main">
        {/* 마음 돌봄 추천 화면으로 돌아가는 이전 화면 이동 버튼 배치. */}
        <button className="meditation-back-button" type="button" onClick={onBack}>
          <span aria-hidden="true">←</span>
          뒤로 돌아가기
        </button>

        {/* 짧은 명상 전용 화면의 제목과 간단한 설명 배치. */}
        <section className="meditation-content" aria-labelledby="meditation-title">
          <h1 id="meditation-title">짧은 명상</h1>
          <p>잠시 호흡에 집중하며 지금의 마음을 살펴보는 공간입니다.</p>
        </section>
      </main>
    </div>
  )
}

export default Meditation
