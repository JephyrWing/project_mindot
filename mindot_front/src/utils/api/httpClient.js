import axios from 'axios'
import {
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from '../auth/tokenStorage.js'

const apiBaseUrl =
  import.meta.env?.VITE_API_BASE_URL ?? "http://localhost:8080";
const authPaths = [
  '/api/auth/login',
  '/api/auth/refresh',
  '/api/auth/logout',
]

const isAuthRequest = (url = '') => {
  const pathname = new URL(url, 'http://localhost:8080').pathname
  return authPaths.includes(pathname)
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
            clearAccessToken()
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
