// 지역과 기관 유형을 사용해 백엔드의 실제 기관 검색 API를 호출.
import httpClient from '../api/httpClient.js'

// 테스트용 HTTP 클라이언트를 주입할 수 있는 기관 검색 API 함수를 생성.
export const createCentersApi = (client) => ({
  // 선택한 지역·기관 유형과 페이지 조건으로 기관 목록을 조회.
  searchCenters: async (params) => {
    const { data } = await client.get('/api/centers', { params })

    return data
  },
})

// 공통 인증 및 토큰 재발급 처리가 적용된 기관 검색 함수를 제공.
export const {
  searchCenters,
} = createCentersApi(httpClient)