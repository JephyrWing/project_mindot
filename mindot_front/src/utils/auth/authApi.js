import httpClient from '../api/httpClient.js'
import { clearAccessToken, setAccessToken } from './tokenStorage.js'

export const createAuthApi = (client) => ({
  // 입력받은 회원 정보를 백엔드 회원가입 API로 전달하는 처리.
  signup: async (account) => {
    const { data } = await client.post(
      '/api/auth/signup',
      account,
      { skipAuth: true },
    )

    return data
  },

  login: async (credentials) => {
    const { data } = await client.post(
      '/api/auth/login',
      credentials,
      { skipAuth: true },
    )

    setAccessToken(data.accessToken)
    return data
  },

  logout: async () => {
    try {
      await client.post('/api/auth/logout', null, { skipAuth: true })
    } finally {
      clearAccessToken()
    }
  },
})

export const { signup, login, logout } = createAuthApi(httpClient)
