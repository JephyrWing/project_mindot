// 반복 패턴 알림 설정 API
import httpClient from '../api/httpClient.js'

// 테스트용 HTTP 클라이언트를 주입할 수 있는 알림 설정 API 생성
export const createNotificationsApi = (client) => ({
  // 로그인 사용자의 반복 패턴 알림 설정 조회
  getNotificationPreferences: async () => {
    const { data } = await client.get('/api/notifications/preferences')

    return data
  },

  // 반복 패턴 알림 수신 여부와 희망 시각 변경
  updateNotificationPreferences: async ({
    patternAlertEnabled,
    preferredTime,
  }) => {
    const { data } = await client.put('/api/notifications/preferences', {
      patternAlertEnabled,
      preferredTime,
    })

    return data
  },

  // 로그인 사용자의 반복 패턴 알림을 최신순으로 조회
  getNotifications: async ({ page = 0, size = 10 } = {}) => {
    const { data } = await client.get('/api/notifications', {
      params: { page, size },
    })

    return data
  },

  // 로그인 사용자의 읽지 않은 반복 패턴 알림 개수 조회
  getUnreadNotificationCount: async () => {
    const { data } = await client.get('/api/notifications/unread-count')

    return data
  },

  // 선택한 반복 패턴 알림 읽음 처리
  markNotificationRead: async (notificationId) => {
    const { data } = await client.patch(
      `/api/notifications/${notificationId}/read`,
    )

    return data
  },

  // 선택한 반복 패턴 알림 삭제
  deleteNotification: async (notificationId) => {
    await client.delete(`/api/notifications/${notificationId}`)
  },
})

// 공통 인증과 토큰 재발급 처리가 적용된 알림 설정 API 제공
export const {
  getNotificationPreferences,
  updateNotificationPreferences,
  getNotifications,
  getUnreadNotificationCount,
  markNotificationRead,
  deleteNotification,
} = createNotificationsApi(httpClient)
