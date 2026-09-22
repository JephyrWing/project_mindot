// 오늘 기준 최근 8주의 반복 감정 패턴 API
import httpClient from '../api/httpClient.js'

// 테스트용 HTTP 클라이언트를 주입할 수 있는 반복 패턴 API 생성
export const createPatternsApi = (client) => ({
  // 로그인 사용자의 최근 8주 반복 감정 패턴 조회
  getRecentEmotionPatterns: async () => {
    const { data } = await client.get('/api/patterns/recent')

    return data
  },
  // 로그인 사용자의 확인된 최근 8주 반복 패턴 목록 조회.
  getEmotionPatterns: async () => {
    const { data } = await client.get('/api/patterns')

    return data
  },
  // 선택한 반복 패턴의 집계와 근거 감정 기록 상세 조회.
  getEmotionPatternDetail: async (patternId) => {
    const { data } = await client.get(`/api/patterns/${patternId}`)

    return data
  },
  // 선택한 반복 패턴과 관점의 도움 여부를 서버에 저장.
  submitEmotionPatternFeedback: async (patternId, feedback) => {
    const { data } = await client.post(
      `/api/patterns/${patternId}/feedback`,
      { feedback },
    )

    return data
  },
})

// 공통 인증과 토큰 재발급 처리가 적용된 반복 패턴 API 제공
export const {
  getRecentEmotionPatterns,
  getEmotionPatterns,
  getEmotionPatternDetail,
  submitEmotionPatternFeedback,
} = createPatternsApi(httpClient)
