// MINDOT 오프라인 안내 자산을 구분하기 위한 캐시 이름 설정.
const cachePrefix = 'mindot-pwa-'
const offlineCacheName = `${cachePrefix}offline-v1`
const pageCacheName = `${cachePrefix}pages-v1`
const staticCacheName = `${cachePrefix}static-v1`

// 현재 서비스 워커에서 유지해야 하는 캐시 이름 목록 설정.
const currentCacheNames = new Set([
  offlineCacheName,
  pageCacheName,
  staticCacheName,
])

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

// 새 서비스 워커 활성화 시 사용하지 않는 이전 버전 캐시 정리와 현재 화면 관리 처리.
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => Promise.all(
      cacheNames
        .filter((cacheName) => (
          cacheName.startsWith(cachePrefix)
          && !currentCacheNames.has(cacheName)
        ))
        .map((cacheName) => caches.delete(cacheName)),
    )).then(() => self.clients.claim()),
  )
})

// 정상 응답만 지정한 캐시에 복사하여 저장하는 처리.
const saveResponseToCache = async (cacheName, request, response) => {
  if (!response.ok || response.type !== 'basic') return

  const cache = await caches.open(cacheName)
  await cache.put(request, response.clone())
}

// 화면 이동 요청은 최신 응답을 우선하고 실패 시 최근 방문 화면 또는 안내 화면 반환 처리.
const handleNavigationRequest = async (request) => {
  try {
    const networkResponse = await fetch(request)

    await saveResponseToCache(pageCacheName, request, networkResponse)
    return networkResponse
  } catch {
    return await caches.match(request)
      || await caches.match('/')
      || caches.match('/offline.html')
  }
}

// 빌드된 CSS와 JavaScript 및 PNG 아이콘은 저장된 응답을 우선 사용하는 처리.
const handleStaticAssetRequest = async (request) => {
  const cachedResponse = await caches.match(request)

  if (cachedResponse) return cachedResponse

  const networkResponse = await fetch(request)

  await saveResponseToCache(staticCacheName, request, networkResponse)
  return networkResponse
}

// 문서와 앱 셸 자산만 처리하고 API 및 외부 요청을 제외하는 분기 처리.
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return

  const requestUrl = new URL(event.request.url)
  const isSameOrigin = requestUrl.origin === self.location.origin
  const isApiRequest = requestUrl.pathname.startsWith('/api/')

  if (!isSameOrigin || isApiRequest) return

  if (event.request.mode === 'navigate') {
    event.respondWith(handleNavigationRequest(event.request))
    return
  }

  const isAppShellAsset = requestUrl.pathname.startsWith('/assets/')
    || requestUrl.pathname.startsWith('/icons/')

  if (isAppShellAsset) {
    event.respondWith(handleStaticAssetRequest(event.request))
  }
})
