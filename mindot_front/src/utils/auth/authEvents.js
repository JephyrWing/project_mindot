// Access Token 재발급 실패를 애플리케이션에 알리는 전역 이벤트 이름 설정.
export const authExpiredEventName = 'mindot:auth-expired'
// 로그인 계정에 요청한 기능의 접근 권한이 없음을 알리는 전역 이벤트 이름 설정.
export const accessDeniedEventName = 'mindot:access-denied'

// 브라우저 화면의 인증 상태 동기화를 요청하는 인증 만료 이벤트 전달.
export const notifyAuthExpired = () => {
  if (typeof window === 'undefined') return

  window.dispatchEvent(new Event(authExpiredEventName))
}

// 현재 화면에 접근 권한 안내 표시를 요청하는 권한 없음 이벤트 전달.
export const notifyAccessDenied = () => {
  if (typeof window === 'undefined') return

  window.dispatchEvent(new Event(accessDeniedEventName))
}
