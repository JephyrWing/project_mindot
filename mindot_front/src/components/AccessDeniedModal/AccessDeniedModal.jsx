import { useEffect } from 'react'
import './AccessDeniedModal.css'

// 로그인은 되어 있지만 선택한 기능을 사용할 권한이 없을 때 표시하는 모달 정의.
function AccessDeniedModal({ onClose }) {
  // Escape 키 선택 시 권한 안내 모달을 닫기 위한 키보드 이벤트 연결과 정리.
  useEffect(() => {
    const handleEscape = (event) => {
      if (event.key === 'Escape') onClose()
    }

    window.addEventListener('keydown', handleEscape)
    return () => window.removeEventListener('keydown', handleEscape)
  }, [onClose])

  // 접근 권한 안내와 확인 버튼을 포함한 모달 반환.
  return (
    <div className="access-denied-backdrop" role="presentation">
      <section
        className="access-denied-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="access-denied-title"
        aria-describedby="access-denied-description"
      >
        <h2 id="access-denied-title">접근 권한이 없습니다</h2>
        <p id="access-denied-description">
          현재 계정으로는 해당 기능을 이용할 수 없습니다.
        </p>
        <button type="button" onClick={onClose} autoFocus>
          확인
        </button>
      </section>
    </div>
  )
}

export default AccessDeniedModal
