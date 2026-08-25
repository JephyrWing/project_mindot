// MINDOT 오프라인 안내 자산을 구분하기 위한 캐시 이름 설정.
const cachePrefix = 'mindot-pwa-'
const offlineCacheName = `${cachePrefix}offline-v1`

// 네트워크 연결 없이 안내 화면을 표시하는 데 필요한 최소 자산 설정.
const offlineAssets = [
  '/offline.html',
  '/manifest.webmanifest',
  '/icons/mindot-icon-192.png',
  '/icons/mindot-icon-512.png',
  '/icons/mindot-apple-touch-icon.png',
]

// 서비스 워커 설치 시 최소 오프라인 안내 자산 저장과 즉시 활성화 처리.
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(offlineCacheName)
      .then((cache) => cache.addAll(offlineAssets))
      .then(() => self.skipWaiting()),
  )
})

// 새 서비스 워커 활성화 시 이전 버전 캐시 정리와 현재 화면 관리 처리.
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => Promise.all(
      cacheNames
        .filter((cacheName) => (
          cacheName.startsWith(cachePrefix)
          && cacheName !== offlineCacheName
        ))
        .map((cacheName) => caches.delete(cacheName)),
    )).then(() => self.clients.claim()),
  )
})

// 문서 이동 요청의 네트워크 실패 시 오프라인 안내 화면 반환 처리.
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET' || event.request.mode !== 'navigate') return

  event.respondWith(
    fetch(event.request).catch(() => caches.match('/offline.html')),
  )
})
