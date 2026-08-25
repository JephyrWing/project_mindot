// 새 서비스 워커 설치 직후 대기 상태를 건너뛰기 위한 처리.
self.addEventListener('install', () => {
  self.skipWaiting()
})

// 활성화된 서비스 워커가 현재 열린 MINDOT 화면을 바로 관리하기 위한 처리.
self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})
