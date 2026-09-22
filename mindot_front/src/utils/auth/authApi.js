import httpClient from '../api/httpClient.js'
import {
  clearAuthSession,
  setAccessToken,
  setUserRole,
} from './tokenStorage.js'

// 프론트에서 허용할 소셜 로그인 제공자 경로 목록 설정.
const supportedSocialProviders = new Set(['kakao', 'google'])

// 로그인 응답의 Access Token은 메모리에, 회원 권한은 현재 브라우저 세션에 저장.
const saveAuthentication = (data) => {
  if (typeof data?.accessToken !== 'string' || !data.accessToken) {
    clearAuthSession()
    throw new Error('Access Token이 없는 인증 응답입니다.')
  }

  setAccessToken(data.accessToken)
  setUserRole(data.userRole)
}

// 동적 API 경로에 사용할 소셜 로그인 제공자 값 검증.
const validateSocialProvider = (provider) => {
  if (!supportedSocialProviders.has(provider)) {
    throw new Error('지원하지 않는 소셜 로그인 제공자입니다.')
  }
}

export const createAuthApi = (client) => {
  // React StrictMode에서도 앱 시작 재발급 요청을 한 번만 보내기 위한 공유 Promise.
  let restorePromise = null

  return {
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

      saveAuthentication(data)
      return data
    },

    // 새로고침으로 비워진 메모리 토큰을 HttpOnly Refresh Token 쿠키로 복구.
    restoreAuthentication: async () => {
      if (!restorePromise) {
        restorePromise = client
          .post('/api/auth/refresh', null, { skipAuth: true })
          .then(({ data }) => {
            saveAuthentication(data)
            return data
          })
          .finally(() => {
            restorePromise = null
          })
      }

      return restorePromise
    },

    // 백엔드에서 CSRF 방지 state 쿠키와 소셜 제공자 인가 URL을 발급받는 처리.
    getSocialAuthorizationUrl: async (provider) => {
      validateSocialProvider(provider)

      const { data } = await client.get(
        `/api/auth/oauth/${provider}/authorize`,
        { skipAuth: true },
      )

      return data
    },

    // 소셜 제공자 콜백의 인가 코드와 state를 백엔드로 전달하는 처리.
    completeSocialLogin: async (provider, callbackData) => {
      validateSocialProvider(provider)

      const { data } = await client.post(
        `/api/auth/oauth/${provider}`,
        callbackData,
        { skipAuth: true },
      )

      if (!data.signupRequired) saveAuthentication(data)
      return data
    },

    // 신규 소셜 회원의 필수 동의를 제출하고 발급된 로그인 정보를 저장하는 처리.
    completeSocialSignup: async (signupTicket, agreements) => {
      const { data } = await client.post(
        '/api/auth/oauth/signup',
        { signupTicket, ...agreements },
        { skipAuth: true },
      )

      saveAuthentication(data)
      return data
    },

    logout: async () => {
      try {
        await client.post('/api/auth/logout', null, { skipAuth: true })
      } finally {
        clearAuthSession()
      }
    },
  }
}

export const {
  signup,
  login,
  logout,
  restoreAuthentication,
  getSocialAuthorizationUrl,
  completeSocialLogin,
  completeSocialSignup,
} = createAuthApi(httpClient)
