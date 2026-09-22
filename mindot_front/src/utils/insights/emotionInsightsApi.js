import httpClient from '../api/httpClient.js'

// 감정 인사이트 API 호출 함수를 생성하는 팩토리 정의.
export const createEmotionInsightsApi = (client) => ({
  // 로그인 사용자의 확정 기록을 선택한 기준별 감정 분포로 조회하는 처리.
  getEmotionInsights: async (groupBy) => {
    const { data } = await client.get('/api/insights/emotions', {
      params: { groupBy },
    })

    return data
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 감정 인사이트 API 함수 제공.
export const { getEmotionInsights } = createEmotionInsightsApi(httpClient)
