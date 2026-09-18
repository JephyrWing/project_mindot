// 로그인 사용자 프로필 API
import httpClient from '../api/httpClient.js'

// 테스트용 HTTP 클라이언트를 주입할 수 있는 프로필 API 생성
export const createUsersApi = (client) => ({
  // 로그인 사용자 프로필 조회
  getMyProfile: async () => {
    const { data } = await client.get('/api/users/me')

    return data
  },

  // 로그인 사용자 닉네임 변경
  updateMyProfile: async (displayName) => {
    const { data } = await client.patch('/api/users/me', {
      displayName,
    })

    return data
  },

  // 로그인 사용자 회원 탈퇴
  withdrawMyAccount: async () => {
    await client.delete('/api/users/me')
  },
})

// 공통 인증과 토큰 재발급 처리가 적용된 프로필 API 제공
export const {
  getMyProfile,
  updateMyProfile,
  withdrawMyAccount,
} = createUsersApi(httpClient)
