import { useEffect } from 'react'
import './LoginRequiredModal.css'

// 로그인이 필요한 기능을 비로그인 사용자가 선택했을 때 안내하는 모달 정의.
function LoginRequiredModal({ onClose, onLogin }) {
  // Escape 키 선택 시 안내 모달을 닫기 위한 키보드 이벤트 연결과 정리.
  useEffect(() => {
    const handleEscape = (event) => {
      if (event.key === 'Escape') onClose()
    }

    window.addEventListener('keydown', handleEscape)

    return () => window.removeEventListener('keydown', handleEscape)
  }, [onClose])

  // 로그인 필요 안내와 로그인 화면 이동 버튼을 포함한 모달 반환.
  return (
    <div className="login-required-backdrop" role="presentation">
      <section
        className="login-required-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="login-required-title"
        aria-describedby="login-required-description"
      >
        <h2 id="login-required-title">로그인이 필요한 서비스입니다</h2>
        <p id="login-required-description">
          해당 기능을 이용하시려면 먼저 로그인해 주세요.
        </p>

        {/* 로그인 화면 이동과 현재 화면 유지 선택 버튼 배치. */}
        <div className="login-required-actions">
          <button type="button" onClick={onClose}>
            다음에 하기
          </button>
          <button
            className="login-required-login-button"
            type="button"
            onClick={onLogin}
            autoFocus
          >
            로그인하기
          </button>
        </div>
      </section>
    </div>
  )
}

export default LoginRequiredModal
