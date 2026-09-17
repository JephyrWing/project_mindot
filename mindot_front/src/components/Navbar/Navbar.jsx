import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import Sidebar from '../Sidebar/Sidebar.jsx'
import NotificationBell from '../NotificationBell/NotificationBell.jsx'
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
  onDailyCare,
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
        onDailyCare={onDailyCare}
        onHome={onHome}
      />

      {/* 로고 선택 시 메인 페이지로 이동하는 공통 프로젝트 로고 배치. */}
      <BrandLogo className="app-navigation-brand" onClick={onHome} />

      {/* 로그인 사용자에게만 알림과 설정 화면 이동 기능 표시. */}
      {isAuthenticated && (
        <div className="app-navigation-actions">
          <NotificationBell />
          <a
            className="app-navigation-settings"
            href="/settings"
            aria-label="설정 열기"
            title="설정"
          >
            <svg
              className="app-navigation-settings-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M10 2h4l.5 2.2 1.7.7 1.9-1.2 2.8 2.8-1.2 1.9.7 1.7L22 10v4l-2.2.5-.7 1.7 1.2 1.9-2.8 2.8-1.9-1.2-1.7.7L14 22h-4l-.5-2.2-1.7-.7-1.9 1.2-2.8-2.8 1.2-1.9-.7-1.7L2 14v-4l2.2-.5.7-1.7-1.2-1.9 2.8-2.8 1.9 1.2 1.7-.7L10 2Z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          </a>
        </div>
      )}
    </header>
  )
}

export default Navbar
