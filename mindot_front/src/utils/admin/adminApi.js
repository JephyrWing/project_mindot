import httpClient from '../api/httpClient.js'

// 관리자 전용 API 호출 함수를 생성하는 팩토리 정의.
export const createAdminApi = (client) => ({
  // 관리자 회원 목록 API의 권한 검사를 이용한 현재 계정 관리자 여부 확인 처리.
  checkAdminAccess: async () => {
    await client.get('/api/admin/users')
  },
})

// 공통 인증 HTTP 클라이언트를 사용하는 관리자 권한 확인 함수 제공.
export const { checkAdminAccess } = createAdminApi(httpClient)
