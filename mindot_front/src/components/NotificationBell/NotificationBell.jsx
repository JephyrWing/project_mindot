import { useEffect, useRef, useState } from 'react'
import {
  getNotifications,
  getUnreadNotificationCount,
  markNotificationRead,
  deleteNotification,
} from '../../utils/notifications/notificationsApi.js'
import './NotificationBell.css'

// 알림 생성 시각을 사용자가 읽기 쉬운 한국어 형식으로 변환.
const formatNotificationDate = (createdAt) => {
  const createdDate = new Date(createdAt)

  if (Number.isNaN(createdDate.getTime())) return ''

  return new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(createdDate)
}

// 반복 패턴 알림 개수와 최근 알림 목록을 제공하는 상단 알림 컴포넌트.
function NotificationBell() {
  const containerRef = useRef(null)
  const [isOpen, setIsOpen] = useState(false)
  const [notifications, setNotifications] = useState([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [isLoading, setIsLoading] = useState(false)
  const [deletingId, setDeletingId] = useState(null)
  const [loadError, setLoadError] = useState('')

  // 화면 진입 후 1분 간격으로 읽지 않은 알림 개수 갱신.
  useEffect(() => {
    let isActive = true

    const loadUnreadCount = async () => {
      try {
        const response = await getUnreadNotificationCount()

        if (isActive) setUnreadCount(response.unreadCount ?? 0)
      } catch {
        // 공통 인증 처리 이후에도 실패한 개수 조회는 다음 주기에 다시 시도.
      }
    }

    loadUnreadCount()
    const intervalId = window.setInterval(loadUnreadCount, 60_000)

    return () => {
      isActive = false
      window.clearInterval(intervalId)
    }
  }, [])

  // 알림 목록을 열 때 최신 알림 10건 조회.
  useEffect(() => {
    if (!isOpen) return undefined

    let isActive = true

    const loadNotifications = async () => {
      setIsLoading(true)
      setLoadError('')

      try {
        const response = await getNotifications({ page: 0, size: 10 })

        if (!isActive) return

        setNotifications(
          Array.isArray(response.content) ? response.content : [],
        )
      } catch (error) {
        if (!isActive) return

        setLoadError(
          error.response?.data?.message
          ?? '알림을 불러오지 못했습니다.',
        )
      } finally {
        if (isActive) setIsLoading(false)
      }
    }

    loadNotifications()

    return () => {
      isActive = false
    }
  }, [isOpen])

  // 알림 영역 바깥 선택 또는 Escape 입력 시 목록 닫기.
  useEffect(() => {
    if (!isOpen) return undefined

    const handlePointerDown = (event) => {
      if (!containerRef.current?.contains(event.target)) setIsOpen(false)
    }
    const handleEscape = (event) => {
      if (event.key === 'Escape') setIsOpen(false)
    }

    document.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleEscape)

    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleEscape)
    }
  }, [isOpen])

  // 읽지 않은 알림 선택 시 서버와 화면 상태를 함께 읽음 처리.
  const handleNotificationRead = async (notification) => {
    if (notification.read) return

    try {
      const updatedNotification = await markNotificationRead(
        notification.notificationId,
      )

      setNotifications((currentNotifications) => (
        currentNotifications.map((currentNotification) => (
          currentNotification.notificationId === notification.notificationId
            ? updatedNotification
            : currentNotification
        ))
      ))
      setUnreadCount((currentCount) => Math.max(0, currentCount - 1))
    } catch (error) {
      setLoadError(
        error.response?.data?.message
        ?? '알림을 읽음 처리하지 못했습니다.',
      )
    }
  }

  // 선택한 알림을 서버와 현재 목록에서 함께 삭제.
  const handleNotificationDelete = async (notification) => {
    if (!window.confirm('이 알림을 삭제할까요?')) return

    setDeletingId(notification.notificationId)
    setLoadError('')

    try {
      await deleteNotification(notification.notificationId)
      setNotifications((currentNotifications) => (
        currentNotifications.filter((currentNotification) => (
          currentNotification.notificationId !== notification.notificationId
        ))
      ))
      if (!notification.read) {
        setUnreadCount((currentCount) => Math.max(0, currentCount - 1))
      }
    } catch (error) {
      setLoadError(
        error.response?.data?.message
        ?? '알림을 삭제하지 못했습니다.',
      )
    } finally {
      setDeletingId(null)
    }
  }

  return (
    <div className="notification-center" ref={containerRef}>
      <button
        className="notification-bell"
        type="button"
        aria-label={`알림 열기${unreadCount ? `, 읽지 않은 알림 ${unreadCount}개` : ''}`}
        aria-expanded={isOpen}
        onClick={() => setIsOpen((currentValue) => !currentValue)}
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {unreadCount > 0 && (
          <span className="notification-badge">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {isOpen && (
        <section className="notification-panel" aria-label="반복 패턴 알림">
          <header className="notification-panel__header">
            <div>
              <strong>알림</strong>
              <span>최근 반복 감정 패턴</span>
            </div>
            <button
              type="button"
              aria-label="알림 닫기"
              onClick={() => setIsOpen(false)}
            >
              ×
            </button>
          </header>

          {isLoading ? (
            <p className="notification-panel__state" role="status">
              알림을 불러오고 있습니다.
            </p>
          ) : loadError ? (
            <p className="notification-panel__state is-error" role="alert">
              {loadError}
            </p>
          ) : notifications.length === 0 ? (
            <p className="notification-panel__state">
              아직 도착한 알림이 없습니다.
            </p>
          ) : (
            <ul className="notification-list">
              {notifications.map((notification) => (
                <li key={notification.notificationId}>
                  <button
                    className={`notification-list__content ${notification.read ? 'is-read' : 'is-unread'}`}
                    type="button"
                    onClick={() => handleNotificationRead(notification)}
                  >
                    <span className="notification-list__title">
                      {!notification.read && <i aria-hidden="true" />}
                      {notification.title}
                    </span>
                    <span className="notification-list__message">
                      {notification.message}
                    </span>
                    {notification.recommendedAction && (
                      <span className="notification-list__action">
                        {notification.recommendedAction}
                      </span>
                    )}
                    <time dateTime={notification.createdAt}>
                      {formatNotificationDate(notification.createdAt)}
                    </time>
                  </button>
                  <button
                    className="notification-list__delete"
                    type="button"
                    aria-label={`${notification.title} 알림 삭제`}
                    disabled={deletingId === notification.notificationId}
                    onClick={() => handleNotificationDelete(notification)}
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                      <path d="M3 6h18" />
                      <path d="M8 6V4h8v2" />
                      <path d="M19 6l-1 14H6L5 6" />
                      <path d="M10 11v5M14 11v5" />
                    </svg>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}

export default NotificationBell
