// 서버에서 생성한 마음 돌봄 추천 조회와 추천 피드백 저장 API
import httpClient from '../api/httpClient.js'

export const createDailyCareApi = (client) => ({
  getRecommendation: async () => (await client.get('/api/daily-care/recommendation')).data,
  saveRecommendationFeedback: async (recommendationId, feedback) => (
    await client.post(`/api/daily-care/recommendations/${recommendationId}/feedback`, { feedback })
  ).data,
})

export const { getRecommendation, saveRecommendationFeedback } = createDailyCareApi(httpClient)
