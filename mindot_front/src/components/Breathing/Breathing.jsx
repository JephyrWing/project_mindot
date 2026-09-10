import Navbar from '../Navbar/Navbar.jsx'
import './Breathing.css'

// 3분 호흡 기능을 단계적으로 확장하기 위한 전용 화면 기본 구조 정의.
function Breathing({
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
    <div className="breathing-page">
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

      <main className="breathing-main">
        {/* 마음 돌봄 추천 화면으로 돌아가는 이전 화면 이동 버튼 배치. */}
        <button className="breathing-back-button" type="button" onClick={onBack}>
          <span aria-hidden="true">←</span>
          뒤로 돌아가기
        </button>

        {/* 3분 호흡 전용 화면의 제목과 간단한 설명 배치. */}
        <section className="breathing-content" aria-labelledby="breathing-title">
          <h1 id="breathing-title">3분 호흡</h1>
          <p>3분 동안 천천히 호흡하며 마음을 가라앉히는 공간입니다.</p>
        </section>
      </main>
    </div>
  )
}

export default Breathing
