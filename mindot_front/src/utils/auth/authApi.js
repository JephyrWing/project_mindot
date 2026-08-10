import httpClient from '../api/httpClient.js'
import { clearAccessToken, setAccessToken } from './tokenStorage.js'

export const createAuthApi = (client) => ({
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

export const { login, logout } = createAuthApi(httpClient)
