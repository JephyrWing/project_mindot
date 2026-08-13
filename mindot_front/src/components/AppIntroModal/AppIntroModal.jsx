import { useEffect, useRef } from 'react'
import BrandLogo from '../BrandLogo/BrandLogo.jsx'
import './AppIntroModal.css'

// 앱 시작 시 Mindot의 목적과 주요 기능을 안내하는 모달 컴포넌트 정의.
function AppIntroModal({ onClose, onHome }) {
  // 모달 표시 직후 시작 버튼으로 초점을 이동하기 위한 참조 관리.
  const startButtonRef = useRef(null)

  // 모달 표시 중 배경 스크롤 차단과 Escape 키 닫기 기능 설정.
  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    const handleEscape = (event) => {
      if (event.key === 'Escape') onClose()
    }

    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', handleEscape)
    startButtonRef.current?.focus()

    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', handleEscape)
    }
  }, [onClose])

  // 모달 바깥 배경 선택 시 안내창을 닫기 위한 처리.
  const handleBackdropClick = (event) => {
    if (event.target === event.currentTarget) onClose()
  }

  // 로고 선택 시 안내창을 닫고 메인페이지로 이동하는 처리.
  const handleHomeClick = () => {
    onHome()
    onClose()
  }

  // 서비스 소개와 주요 기능 및 이용 범위를 포함한 안내창 반환.
  return (
    <div className="app-intro-backdrop" onMouseDown={handleBackdropClick}>
      <section
        className="app-intro-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="app-intro-title"
        aria-describedby="app-intro-description app-intro-notice"
      >
        <BrandLogo className="app-intro-logo" onClick={handleHomeClick} />

        <h1 id="app-intro-title">마음을 이해하는 작은 시작</h1>
        <p className="app-intro-description" id="app-intro-description">
          Mindot은 오늘의 감정과 생각을 기록하고, AI와의 대화를 통해
          마음의 흐름을 돌아보도록 돕는 자기이해 보조 앱입니다.
        </p>

        {/* 사용자가 앱에서 이용할 수 있는 주요 기능의 간단한 목록 표시. */}
        <ul className="app-intro-features" aria-label="Mindot 주요 기능">
          <li>감정 기록</li>
          <li>AI CBT 대화</li>
          <li>주간 리포트</li>
        </ul>

        {/* 서비스의 의료적 이용 범위를 명확하게 알리는 안내 문구 배치. */}
        <p className="app-intro-notice" id="app-intro-notice">
          Mindot은 의료적 진단이나 치료를 대신하지 않습니다.
        </p>

        <button ref={startButtonRef} type="button" onClick={onClose}>
          MINDOT 시작하기
        </button>
      </section>
    </div>
  )
}

export default AppIntroModal
