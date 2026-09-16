// 오늘 기준 최근 8주의 반복 감정 패턴 API
import httpClient from '../api/httpClient.js'

// 테스트용 HTTP 클라이언트를 주입할 수 있는 반복 패턴 API 생성
export const createPatternsApi = (client) => ({
  // 로그인 사용자의 최근 8주 반복 감정 패턴 조회
  getRecentEmotionPatterns: async () => {
    const { data } = await client.get('/api/patterns/recent')

    return data
  },
})

// 공통 인증과 토큰 재발급 처리가 적용된 반복 패턴 API 제공
export const {
  getRecentEmotionPatterns,
} = createPatternsApi(httpClient)
