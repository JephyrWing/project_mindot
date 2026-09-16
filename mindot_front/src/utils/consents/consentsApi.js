import httpClient from '../api/httpClient.js'

// 로그인 사용자의 동의 상태 API 생성
export const createConsentsApi = (client) => ({
  // 동의 종류별 현재 상태 조회
  getCurrentConsents: async () => {
    const { data } = await client.get('/api/consents')

    return data
  },

  // 현재 버전의 AI 분석 동의 저장
  grantAiAnalysisConsent: async () => {
    const { data } = await client.post('/api/consents/AI_ANALYSIS/grant')

    return data
  },

  // AI 분석 동의 철회
  revokeAiAnalysisConsent: async () => {
    const { data } = await client.post('/api/consents/AI_ANALYSIS/revoke')

    return data
  },
})

export const {
  getCurrentConsents,
  grantAiAnalysisConsent,
  revokeAiAnalysisConsent,
} = createConsentsApi(httpClient)
