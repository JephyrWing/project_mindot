import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Sidebar from '../Sidebar/Sidebar.jsx'
import './Navbar.css'

// 모든 서비스 화면에서 동일한 상단 네비게이션을 제공하는 공통 컴포넌트 정의.
function Navbar({
  className = '',
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onEmotionHistory,
  onCenter,
  onHome,
}) {
  // 화면별 추가 클래스와 공통 네비게이션 클래스 결합.
  const navbarClassName = `app-navigation-header${className ? ` ${className}` : ''}`

  // 공통 사이드바 버튼과 메인 이동 로고로 구성한 상단 네비게이션 반환.
  return (
    <header className={navbarClassName}>
      {/* 인증 상태와 주요 화면 이동 기능을 공통 사이드바에 전달하는 연결. */}
      <Sidebar
        isAuthenticated={isAuthenticated}
        isLoggingOut={isLoggingOut}
        onLogin={onLogin}
        onLogout={onLogout}
        onSignUp={onSignUp}
        onEmotionHistory={onEmotionHistory}
        onCenter={onCenter}
        onHome={onHome}
      />

      {/* 로고 선택 시 메인 페이지로 이동하는 공통 프로젝트 로고 배치. */}
      <BrandLogo className="app-navigation-brand" onClick={onHome} />
    </header>
  )
}

export default Navbar
