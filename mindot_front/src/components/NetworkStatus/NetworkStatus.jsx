import { useEffect, useState } from 'react'
import './NetworkStatus.css'

// 브라우저 네트워크 연결 상태를 모든 화면에서 안내하는 컴포넌트 정의.
function NetworkStatus() {
  // 현재 브라우저의 온라인 연결 여부 상태 관리.
  const [isOnline, setIsOnline] = useState(() => navigator.onLine)

  // 네트워크 연결과 해제 이벤트에 따른 상태 갱신 및 이벤트 정리.
  useEffect(() => {
    const handleOnline = () => setIsOnline(true)
    const handleOffline = () => setIsOnline(false)

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [])

  // 온라인 상태에서는 별도 안내를 표시하지 않는 처리.
  if (isOnline) return null

  // 오프라인에서 제한되는 서버 기능을 명확히 알리는 안내 반환.
  return (
    <aside
      className="network-status"
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      <strong>오프라인 상태</strong>
      <span>로그인, 기록 저장과 AI 기능은 연결 후 이용할 수 있습니다.</span>
    </aside>
  )
}

export default NetworkStatus
