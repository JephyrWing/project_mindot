import { useEffect, useState } from 'react'
import './PwaInstallPrompt.css'

// PWA 설치 가능 여부와 설치 방법을 모든 화면에서 안내하는 컴포넌트 정의.
function PwaInstallPrompt() {
  // 브라우저가 전달한 설치 이벤트의 나중 실행을 위한 상태 관리.
  const [installPrompt, setInstallPrompt] = useState(null)
  // 현재 PWA 설치 완료 여부 상태 관리.
  const [isInstalled, setIsInstalled] = useState(() =>
    window.matchMedia('(display-mode: standalone)').matches
    || window.navigator.standalone === true,
  )
  // iPhone과 iPad의 수동 설치 안내 제공 여부 상태 관리.
  const [isIos] = useState(() => {
    const userAgent = window.navigator.userAgent.toLowerCase()
    const isAppleMobile = /iphone|ipad|ipod/.test(userAgent)
    const isTouchMac = window.navigator.platform === 'MacIntel'
      && window.navigator.maxTouchPoints > 1

    return isAppleMobile || isTouchMac
  })
  // 현재 탭에서 사용자가 설치 안내를 닫았는지 여부 상태 관리.
  const [isDismissed, setIsDismissed] = useState(
    () => window.sessionStorage.getItem('mindot-pwa-install-dismissed') === 'true',
  )
  // 오프라인 중 설치 안내 노출을 방지하기 위한 네트워크 상태 관리.
  const [isOnline, setIsOnline] = useState(() => window.navigator.onLine)
  // 설치 진행과 완료 결과를 화면에 표시하기 위한 상태 관리.
  const [installStatus, setInstallStatus] = useState('idle')

  // 브라우저의 설치 가능·완료·표시 방식·네트워크 이벤트 연결과 정리.
  useEffect(() => {
    const displayModeQuery = window.matchMedia('(display-mode: standalone)')

    const handleInstallAvailable = (event) => {
      event.preventDefault()
      setInstallPrompt(event)
      setInstallStatus('available')
    }
    const handleInstalled = () => {
      setInstallPrompt(null)
      setIsInstalled(true)
      setInstallStatus('installed')
      window.sessionStorage.removeItem('mindot-pwa-install-dismissed')
    }
    const handleDisplayModeChange = (event) => {
      if (event.matches) setIsInstalled(true)
    }
    const handleOnline = () => setIsOnline(true)
    const handleOffline = () => setIsOnline(false)

    window.addEventListener('beforeinstallprompt', handleInstallAvailable)
    window.addEventListener('appinstalled', handleInstalled)
    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
    displayModeQuery.addEventListener('change', handleDisplayModeChange)

    return () => {
      window.removeEventListener('beforeinstallprompt', handleInstallAvailable)
      window.removeEventListener('appinstalled', handleInstalled)
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
      displayModeQuery.removeEventListener('change', handleDisplayModeChange)
    }
  }, [])

  // 현재 탭에서 설치 안내를 다시 표시하지 않기 위한 닫기 처리.
  const handleDismiss = () => {
    window.sessionStorage.setItem('mindot-pwa-install-dismissed', 'true')
    setIsDismissed(true)
  }

  // 저장한 브라우저 설치 이벤트를 사용자 선택 시 한 번만 실행하는 처리.
  const handleInstall = async () => {
    if (!installPrompt || installStatus === 'installing') return

    setInstallStatus('installing')

    try {
      await installPrompt.prompt()
      const { outcome } = await installPrompt.userChoice

      setInstallPrompt(null)

      if (outcome === 'accepted') {
        setIsInstalled(true)
        setInstallStatus('installed')
        window.sessionStorage.removeItem('mindot-pwa-install-dismissed')
      } else {
        handleDismiss()
      }
    } catch {
      setInstallStatus('available')
    }
  }

  // 개발 서버·오프라인·사용자 닫기 상태에서는 설치 안내를 숨기는 처리.
  if (!import.meta.env.PROD || !isOnline || isDismissed) return null

  const isInstallAvailable = Boolean(installPrompt) && !isInstalled
  const showIosGuide = isIos && !isInstalled
  const showInstalledMessage = installStatus === 'installed'

  // 설치할 수 없거나 이미 설치된 일반 실행 상태에서는 안내를 숨기는 처리.
  if (!isInstallAvailable && !showIosGuide && !showInstalledMessage) return null

  // 설치 방식과 진행 상태에 맞춘 안내 문구 구성.
  let title = 'MINDOT 앱 설치'
  let description = '홈 화면에서 더 빠르고 편리하게 MINDOT을 시작할 수 있습니다.'

  if (showIosGuide) {
    title = 'MINDOT 홈 화면 추가'
    description = "Safari의 공유 버튼에서 '홈 화면에 추가'를 선택해 주세요."
  }

  if (showInstalledMessage) {
    title = 'MINDOT 설치 완료'
    description = '이제 홈 화면에서 MINDOT을 바로 시작할 수 있습니다.'
  }

  // 설치 버튼과 브라우저별 안내를 포함한 하단 알림 반환.
  return (
    <aside
      className={`pwa-install-prompt${showInstalledMessage ? ' is-installed' : ''}`}
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      <div className="pwa-install-prompt__content">
        <strong>{title}</strong>
        <span>{description}</span>
      </div>

      <div className="pwa-install-prompt__actions">
        {isInstallAvailable && (
          <button
            className="pwa-install-prompt__install-button"
            type="button"
            onClick={handleInstall}
            disabled={installStatus === 'installing'}
          >
            {installStatus === 'installing' ? '설치 중' : '설치하기'}
          </button>
        )}
        <button
          className="pwa-install-prompt__dismiss-button"
          type="button"
          onClick={handleDismiss}
        >
          {showInstalledMessage || showIosGuide ? '확인' : '나중에'}
        </button>
      </div>
    </aside>
  )
}

export default PwaInstallPrompt
