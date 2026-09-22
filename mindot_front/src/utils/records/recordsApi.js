import httpClient from '../api/httpClient.js'

// 감정 기록 API 호출 함수를 생성하는 팩토리 정의.
export const createRecordsApi = (client) => ({
  // 로그인 사용자의 간편 감정 원문을 백엔드에 저장하는 처리.
  createQuickRecord: async (record, key) => {
    const { data } = await client.post('/api/records/quick', record, { headers: { 'Idempotency-Key': key } })

    return data
  },
  // 로그인 사용자의 감정 기록 목록을 최신순으로 조회하는 처리.
  getEmotionRecords: async (params = {}) => {
    const { data } = await client.get('/api/records', { params })

    return data
  },
  // 입력한 문장과 의미가 비슷한 로그인 사용자의 감정 기록 조회 처리.
  searchEmotionRecordsSemantically: async (params = {}) => {
    const { data } = await client.get('/api/records/semantic-search', { params })

    return data
  },
  // 선택한 감정 기록 식별자로 로그인 사용자의 상세 정보를 조회하는 처리.
  getEmotionRecordDetail: async (emotionRecordId) => {
    const { data } = await client.get(`/api/records/${emotionRecordId}`)

    return data
  },
  // AI 분석 결과에서 비어 있는 항목을 보완 질문으로 조회하는 처리.
  getMissingInformationQuestions: async (emotionRecordId) => {
    const { data } = await client.get(
      `/api/records/${emotionRecordId}/questions/missing`,
    )

    return data
  },
  // AI 분석 결과에 사용자가 보완한 감정 기록 내용을 최종 반영하는 처리.
  confirmEmotionRecord: async (emotionRecordId, record) => {
    const { data } = await client.post(
      `/api/records/${emotionRecordId}/confirm`,
      record,
    )

    return data
  },
  // AI가 제안한 구조화 결과를 거절하고 사용자의 원문만 유지하는 처리.
  rejectEmotionRecordAnalysis: async (emotionRecordId) => {
    const { data } = await client.post(
      `/api/records/${emotionRecordId}/reject`,
    )

    return data
  },
  // AI 분석에 실패하여 간편 기록 상태로 남은 감정 기록의 재분석을 요청하는 처리.
  reanalyzeEmotionRecord: async (emotionRecordId) => {
    const { data } = await client.post(
      `/api/records/${emotionRecordId}/reanalyze`,
    )

    return data
  },
  // 확정된 감정 기록과 유사한 과거 CBT 사례를 기반으로 패턴 설명을 요청하는 처리.
  getEmotionRecordPatternExplanation: async (emotionRecordId, { silentFailure = false } = {}) => {
    const { data } = await client.post(
      `/api/records/${emotionRecordId}/pattern-explanation`,
      undefined,
      { silentFailure, timeout: 60_000 },
    )

    return data
  },
  // 전달한 필드만 수정하고 나머지 기록 값은 유지.
  updateEmotionRecord: async (emotionRecordId, changes) => {
    const { data } = await client.patch(`/api/records/${emotionRecordId}`, changes)
    return data
  },
  // 선택한 감정 기록과 연결된 CBT 성찰 데이터를 함께 삭제하는 처리.
  deleteEmotionRecord: async (emotionRecordId) => {
    await client.delete(`/api/records/${emotionRecordId}`)
  },
})

// 공통 HTTP 클라이언트를 사용하는 감정 기록 API 함수 제공.
export const {
  createQuickRecord,
  getEmotionRecords,
  searchEmotionRecordsSemantically,
  getEmotionRecordDetail,
  getMissingInformationQuestions,
  confirmEmotionRecord,
  rejectEmotionRecordAnalysis,
  reanalyzeEmotionRecord,
  getEmotionRecordPatternExplanation,
  updateEmotionRecord,
  deleteEmotionRecord,
} = createRecordsApi(httpClient)
