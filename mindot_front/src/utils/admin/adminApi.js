import httpClient from '../api/httpClient.js'

// 관리자 전용 API 호출 함수를 생성하는 팩토리 정의.
export const createAdminApi = (client) => ({
  // 관리자 회원 목록 API의 권한 검사를 이용한 현재 계정 관리자 여부 확인 처리.
  checkAdminAccess: async () => {
    const { data } = await client.get('/api/admin/users')

    return data
  },

  // 가입일 최신순으로 정렬된 관리자용 회원 기본 정보 목록 조회 처리.
  getAdminUsers: async () => {
    const { data } = await client.get('/api/admin/users')

    return data
  },

  // 관리자에게 허용된 안전 신호 이벤트 목록 조회 처리.
  getAdminSafetyEvents: async () => {
    const { data } = await client.get('/api/admin/safety-events')

    return data
  },

  // 선택한 안전 신호 식별자로 연결된 감정 기록 원문 상세 조회 처리.
  getAdminSafetyEventDetail: async (safetyEventId) => {
    const { data } = await client.get(
      `/api/admin/safety-events/${safetyEventId}`,
    )

    return data
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 관리자 전용 API 함수 제공.
export const {
  checkAdminAccess,
  getAdminUsers,
  getAdminSafetyEvents,
  getAdminSafetyEventDetail,
} = createAdminApi(httpClient)
