// 프로덕션 환경과 현재 브라우저의 서비스 워커 지원 여부 확인 후 기본 워커 등록 처리.
export const registerServiceWorker = () => {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return

  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // 서비스 워커 등록 실패가 기존 웹 기능을 중단하지 않도록 오류 종료 처리.
    })
  }, { once: true })
}
