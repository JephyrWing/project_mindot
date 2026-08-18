import { useEffect, useState } from 'react'
import './Sidebar.css'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'

// 열림 상태와 주요 화면 이동을 담당하는 모바일 사이드바 컴포넌트 정의.
function Sidebar({
  isAuthenticated,
  isLoggingOut,
  onLogin,
  onLogout,
  onSignUp,
  onCenter,
  onHome,
}) {
  // 사이드바 열림 여부 상태 관리.
  const [isOpen, setIsOpen] = useState(false)

  // 사이드바 열림 중 배경 스크롤 차단과 Escape 키 닫기 처리.
  useEffect(() => {
    if (!isOpen) return undefined

    const previousOverflow = document.body.style.overflow
    const handleEscape = (event) => {
      if (event.key === 'Escape') setIsOpen(false)
    }

    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', handleEscape)

    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', handleEscape)
    }
  }, [isOpen])

  // 사이드바 닫기 후 선택한 화면으로 이동 처리.
  const moveToPage = (movePage) => {
    setIsOpen(false)
    movePage()
  }

  // 햄버거 버튼과 슬라이드 메뉴로 구성한 사이드바 반환.
  return (
    <>
      {/* SVG 없이 세 개의 선으로 구성한 햄버거 버튼 배치. */}
      <button
        className="main-menu-button"
        type="button"
        onClick={() => setIsOpen(true)}
        aria-label="메뉴 열기"
        aria-controls="main-sidebar"
        aria-expanded={isOpen}
      >
        <span />
        <span />
        <span />
      </button>

      {/* 사이드바 바깥 영역 선택 시 메뉴 닫기 처리. */}
      <button
        className={`main-sidebar-overlay${isOpen ? ' is-open' : ''}`}
        type="button"
        onClick={() => setIsOpen(false)}
        aria-label="메뉴 닫기"
        tabIndex={isOpen ? 0 : -1}
      />

      {/* 화면 왼쪽에서 열리고 닫히는 모바일 사이드바 배치. */}
      <aside
        className={`main-sidebar${isOpen ? ' is-open' : ''}`}
        id="main-sidebar"
        aria-hidden={!isOpen}
        inert={!isOpen}
      >
        <div className="main-sidebar__header">
          <BrandLogo className="main-sidebar__brand" onClick={onHome} />
          <button
            className="main-sidebar__close"
            type="button"
            onClick={() => setIsOpen(false)}
            aria-label="메뉴 닫기"
          >
            ×
          </button>
        </div>

        {/* 인증 상태에 따른 로그인·회원가입 또는 로그아웃 버튼 배치. */}
        <nav className="main-sidebar__navigation" aria-label="주요 메뉴">
          {isAuthenticated ? (
            <button
              className="main-sidebar__logout"
              type="button"
              onClick={() => moveToPage(onLogout)}
              disabled={isLoggingOut}
            >
              {isLoggingOut ? '로그아웃 중…' : '로그아웃'}
            </button>
          ) : (
            <>
              <button type="button" onClick={() => moveToPage(onLogin)}>
                로그인
              </button>
              <button type="button" onClick={() => moveToPage(onSignUp)}>
                회원가입
              </button>
            </>
          )}
          <button type="button" onClick={() => moveToPage(onCenter)}>
            관련 기관 찾기
          </button>
        </nav>
      </aside>
    </>
  )
}

export default Sidebar
