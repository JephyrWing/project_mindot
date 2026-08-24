import httpClient from '../api/httpClient.js'

// 감정 기록 API 호출 함수를 생성하는 팩토리 정의.
export const createRecordsApi = (client) => ({
  // 로그인 사용자의 간편 감정 원문을 백엔드에 저장하는 처리.
  createQuickRecord: async (record) => {
    const { data } = await client.post('/api/records/quick', record)

    return data
  },
})

// 공통 HTTP 클라이언트를 사용하는 감정 기록 API 함수 제공.
export const { createQuickRecord } = createRecordsApi(httpClient)
