// Access Token 재발급 실패를 애플리케이션에 알리는 전역 이벤트 이름 설정.
export const authExpiredEventName = 'mindot:auth-expired'

// 브라우저 화면의 인증 상태 동기화를 요청하는 인증 만료 이벤트 전달.
export const notifyAuthExpired = () => {
  if (typeof window === 'undefined') return

  window.dispatchEvent(new Event(authExpiredEventName))
}
