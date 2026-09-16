import axios from 'axios'
import {
  clearAuthSession,
  getAccessToken,
  setAccessToken,
} from '../auth/tokenStorage.js'
import {
  notifyAccessDenied,
  notifyAuthExpired,
} from '../auth/authEvents.js'

const apiBaseUrl =
  import.meta.env?.VITE_API_BASE_URL ?? "http://localhost:8080";
const authPaths = [
  '/api/auth/signup',
  '/api/auth/login',
  '/api/auth/refresh',
  '/api/auth/logout',
]

const isAuthRequest = (url = '') => {
  const pathname = new URL(url, 'http://localhost:8080').pathname
  return authPaths.includes(pathname)
    || pathname.startsWith('/api/auth/oauth/')
}

// 관리자 화면에서 자체 안내할 관리자 API 요청 여부 확인.
const isAdminRequest = (url = '') => {
  const pathname = new URL(url, 'http://localhost:8080').pathname
  return pathname.startsWith('/api/admin')
}

export const createHttpClient = ({
  apiClient = axios.create({
    baseURL: apiBaseUrl,
    withCredentials: true,
  }),
  refreshClient = axios.create({
    baseURL: apiBaseUrl,
    withCredentials: true,
  }),
} = {}) => {
  let refreshPromise = null

  apiClient.interceptors.request.use((config) => {
    if (!config.skipAuth && !isAuthRequest(config.url)) {
      const accessToken = getAccessToken()

      if (accessToken) {
        config.headers.Authorization = `Bearer ${accessToken}`
      }
    }

    return config
  })

  apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
      const originalRequest = error.config

      // 일반 서비스 API의 권한 없음 응답을 전역 안내 모달로 전달.
      if (
        error.response?.status === 403
        && !isAdminRequest(originalRequest?.url)
      ) {
        notifyAccessDenied()
      }

      if (
        error.response?.status !== 401
        || !originalRequest
        || originalRequest._retry
        || isAuthRequest(originalRequest.url)
      ) {
        return Promise.reject(error)
      }

      originalRequest._retry = true

      if (!refreshPromise) {
        refreshPromise = refreshClient
          .post('/api/auth/refresh')
          .then(({ data }) => {
            setAccessToken(data.accessToken)
            return data.accessToken
          })
          .catch((refreshError) => {
            clearAuthSession()
            notifyAuthExpired()
            throw refreshError
          })
          .finally(() => {
            refreshPromise = null
          })
      }

      try {
        const accessToken = await refreshPromise
        originalRequest.headers.Authorization = `Bearer ${accessToken}`
        return apiClient(originalRequest)
      } catch (refreshError) {
        return Promise.reject(refreshError)
      }
    },
  )

  return apiClient
}

const httpClient = createHttpClient()

export default httpClient
