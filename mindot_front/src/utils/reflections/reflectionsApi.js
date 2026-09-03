import httpClient from '../api/httpClient.js'

// CBT 성찰 API 호출 함수를 생성하는 팩토리 정의.
export const createReflectionsApi = (client) => ({
  // 저장된 감정 기록을 기반으로 CBT 성찰 세션을 시작하는 처리.
  startReflection: async (emotionRecordId) => {
    const { data } = await client.post(
      `/api/reflections/start/${emotionRecordId}`,
    )

    return data
  },

  // 현재 CBT 질문에 대한 사용자 답변을 같은 성찰 세션으로 전달하는 처리.
  submitReflectionAnswer: async (sessionId, answer) => {
    const { data } = await client.post(
      `/api/reflections/${sessionId}/turn`,
      { answer },
    )

    return data
  },

  // 진행 중인 CBT 성찰 세션을 취소하여 다시 재개할 수 없게 하는 처리.
  cancelReflection: async (sessionId) => {
    await client.post(`/api/reflections/${sessionId}/cancel`)
  },

  // 로그인 사용자의 진행 중인 OPEN CBT 성찰 세션 목록 조회 처리.
  getOpenReflectionSessions: async () => {
    const { data } = await client.get('/api/reflections/open')

    return data
  },

  // 사용자가 선택한 CBT 성찰 세션의 질문과 답변 이력 상세 조회 처리.
  getReflectionSessionDetail: async (sessionId) => {
    const { data } = await client.get(`/api/reflections/${sessionId}`)

    return data
  },

  // 사용자가 검토한 CBT 성찰 결과를 최종 확정하는 처리.
  confirmReflection: async (sessionId, confirmation) => {
    await client.post(
      `/api/reflections/${sessionId}/confirm`,
      confirmation,
    )
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 CBT 성찰 API 함수 제공.
export const {
  startReflection,
  submitReflectionAnswer,
  cancelReflection,
  getOpenReflectionSessions,
  getReflectionSessionDetail,
  confirmReflection,
} = createReflectionsApi(httpClient)
