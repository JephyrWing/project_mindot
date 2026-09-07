import httpClient from '../api/httpClient.js'

// 안전 안내 표시 이력 API 호출 함수를 생성하는 팩토리 정의.
export const createSafetyApi = (client) => ({
  // 안전 안내 모달이 실제 표시된 이벤트의 최초 표시 시각 기록 처리.
  markSafetyNoticeShown: async (safetyEventId) => {
    await client.post(`/api/safety-events/${safetyEventId}/notice-shown`)
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 안전 안내 API 함수 제공.
export const { markSafetyNoticeShown } = createSafetyApi(httpClient)
