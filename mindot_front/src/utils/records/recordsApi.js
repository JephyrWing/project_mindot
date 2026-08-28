import httpClient from '../api/httpClient.js'

// 감정 기록 API 호출 함수를 생성하는 팩토리 정의.
export const createRecordsApi = (client) => ({
  // 로그인 사용자의 간편 감정 원문을 백엔드에 저장하는 처리.
  createQuickRecord: async (record) => {
    const { data } = await client.post('/api/records/quick', record)

    return data
  },
  // 로그인 사용자의 감정 기록 목록을 최신순으로 조회하는 처리.
  getEmotionRecords: async () => {
    const { data } = await client.get('/api/records')

    return data
  },
  // 선택한 감정 기록 식별자로 로그인 사용자의 상세 정보를 조회하는 처리.
  getEmotionRecordDetail: async (emotionRecordId) => {
    const { data } = await client.get(`/api/records/${emotionRecordId}`)

    return data
  },
  // 선택한 감정 기록의 실제 감정 발생 시각을 수정하는 처리.
  updateEmotionRecordOccurredAt: async (emotionRecordId, occurredAt) => {
    const { data } = await client.patch(`/api/records/${emotionRecordId}`, {
      occurredAt,
    })

    return data
  },
})

// 공통 HTTP 클라이언트를 사용하는 감정 기록 API 함수 제공.
export const {
  createQuickRecord,
  getEmotionRecords,
  getEmotionRecordDetail,
  updateEmotionRecordOccurredAt,
} = createRecordsApi(httpClient)
