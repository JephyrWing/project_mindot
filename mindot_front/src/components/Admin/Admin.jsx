import Navbar from '../Navbar/Navbar.jsx'
import './Admin.css'

// 관리자 기능을 단계적으로 추가하기 위한 기본 화면 구조 정의.
function Admin({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onDailyCare,
  onHome,
}) {
  // 회원 관리와 안전 신호 관리 영역으로 구성한 관리자 화면 반환.
  return (
    <div className="admin-page">
      {/* 다른 서비스 화면과 동일한 크기와 기능의 공통 네비게이션 배치. */}
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

      <main className="admin-main">
        {/* 관리자 화면의 목적을 알려 주는 상단 제목 영역. */}
        <header className="admin-heading">
          <h1>관리자</h1>
          <p>회원 정보와 서비스 안전 신호를 확인하는 공간입니다.</p>
        </header>

        {/* 추후 회원 목록 API를 연결할 기본 영역. */}
        <section className="admin-section" aria-labelledby="admin-users-title">
          <div>
            <h2 id="admin-users-title">회원 관리</h2>
            <p>가입한 회원의 기본 정보와 안전 신호 발생 횟수를 확인합니다.</p>
          </div>
          <span>API 연결 전</span>
        </section>

        {/* 추후 안전 신호 목록과 상세 API를 연결할 기본 영역. */}
        <section className="admin-section" aria-labelledby="admin-safety-title">
          <div>
            <h2 id="admin-safety-title">안전 신호 관리</h2>
            <p>감지된 안전 신호 목록과 연결된 감정 기록을 확인합니다.</p>
          </div>
          <span>API 연결 전</span>
        </section>
      </main>
    </div>
  )
}

export default Admin
