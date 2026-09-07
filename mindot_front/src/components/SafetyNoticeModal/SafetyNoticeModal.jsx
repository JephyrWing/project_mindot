import { useEffect, useRef, useState } from 'react'
import { markSafetyNoticeShown } from '../../utils/safety/safetyApi.js'
import './SafetyNoticeModal.css'

// 백엔드 안전 사유 코드를 사용자에게 이해하기 쉬운 문구로 변환하기 위한 목록 설정.
const safetyReasonLabels = {
  SELF_HARM_EXPLICIT: '자신을 해칠 수 있다는 표현이 확인되었습니다.',
  SUICIDE_EXPLICIT: '삶을 끝내고 싶다는 직접적인 표현이 확인되었습니다.',
  HARM_TO_OTHERS_EXPLICIT: '다른 사람을 해칠 수 있다는 표현이 확인되었습니다.',
  IMMEDIATE_DANGER: '즉각적인 위험 가능성이 확인되었습니다.',
  AMBIGUOUS_SAFETY_SIGNAL: '안전을 위해 추가 확인이 필요한 표현이 확인되었습니다.',
}

// 감지된 안전 수준에 따라 즉시 행동 안내를 제공하는 공통 모달 정의.
function SafetyNoticeModal({ notice, onClose }) {
  // 안내 확인 버튼으로 초점을 이동하기 위한 요소 참조 관리.
  const closeButtonRef = useRef(null)
  // 같은 모달 생명주기에서 표시 확인 API의 중복 호출을 막기 위한 식별자 관리.
  const reportedSafetyEventIdRef = useRef(null)
  // 안전 안내 표시 이력 전송 실패 여부 상태 관리.
  const [hasTrackingError, setHasTrackingError] = useState(false)

  const isCrisis = notice.actionCode === 'SHOW_CRISIS_NOTICE'
    || notice.riskLevel === 'CRISIS'
  const reasonText = safetyReasonLabels[notice.reasonCode]
    ?? '현재 표현에서 안전 확인이 필요한 신호가 감지되었습니다.'

  // 모달 표시 후 배경 스크롤 차단과 안전 안내 표시 이력 API 호출 처리.
  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    const safetyEventId = notice.safetyEventId
    const handleEscape = (event) => {
      if (event.key === 'Escape') onClose()
    }

    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', handleEscape)
    closeButtonRef.current?.focus()

    if (safetyEventId && reportedSafetyEventIdRef.current !== safetyEventId) {
      reportedSafetyEventIdRef.current = safetyEventId
      markSafetyNoticeShown(safetyEventId).catch(() => {
        setHasTrackingError(true)
      })
    }

    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', handleEscape)
    }
  }, [notice.safetyEventId, onClose])

  // 위험 수준에 맞는 안내와 공식 긴급 연락 수단을 포함한 모달 반환.
  return (
    <div className="safety-notice-backdrop" role="presentation">
      <section
        className={`safety-notice-modal ${isCrisis ? 'is-crisis' : 'is-review'}`}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="safety-notice-title"
        aria-describedby="safety-notice-description"
      >
        <p className="safety-notice-level">
          {isCrisis ? '긴급 안전 안내' : '마음 안전 확인'}
        </p>
        <h2 id="safety-notice-title">
          {isCrisis
            ? '지금은 안전을 가장 먼저 확인해 주세요'
            : '혼자 견디지 않아도 괜찮습니다'}
        </h2>
        <div id="safety-notice-description">
          <p>{reasonText}</p>
          <p>
            {isCrisis
              ? '지금 자신이나 다른 사람을 해칠 가능성이 있거나 즉각적인 위험이 있다면 혼자 있지 말고 바로 도움을 요청해 주세요.'
              : '힘든 마음이 이어진다면 신뢰할 수 있는 사람이나 전문 상담기관에 현재 상태를 알려 주세요.'}
          </p>
        </div>

        {/* 대한민국 공식 긴급 신고 및 자살예방 상담 연락 수단 배치. */}
        <div className="safety-notice-contacts" aria-label="도움을 요청할 연락처">
          <a href="tel:112">
            <strong>112</strong>
            <span>경찰 긴급신고</span>
          </a>
          <a href="tel:119">
            <strong>119</strong>
            <span>응급 구조 요청</span>
          </a>
          <a href="tel:109">
            <strong>109</strong>
            <span>24시간 자살예방상담</span>
          </a>
        </div>

        <p className="safety-notice-disclaimer">
          MINDOT은 긴급 구조나 의료적 진단 및 치료를 대신하지 않습니다.
        </p>

        {hasTrackingError && (
          <p className="safety-notice-tracking" role="status">
            안내 표시 이력을 서버에 기록하지 못했지만 안전 안내는 계속 확인할 수 있습니다.
          </p>
        )}

        <button
          className="safety-notice-close"
          ref={closeButtonRef}
          type="button"
          onClick={onClose}
        >
          안전 안내 확인
        </button>
      </section>
    </div>
  )
}

export default SafetyNoticeModal
