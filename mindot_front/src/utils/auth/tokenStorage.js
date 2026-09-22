const accessTokenKey = 'mindot.accessToken'
const userRoleKey = 'mindot.userRole'

// Access Token은 브라우저 저장소에 남기지 않고 현재 JavaScript 실행 메모리에만 보관.
let accessTokenInMemory = null

// 프론트에서 메뉴 노출에 사용하는 서버 회원 권한 코드 목록 설정.
const supportedUserRoles = new Set(['ROLE_USER', 'ROLE_ADMIN'])

// 이전 버전이 sessionStorage에 남긴 토큰도 인증에 사용하지 않고 즉시 제거.
const clearLegacyStoredAccessToken = () => {
  try {
    sessionStorage.removeItem(accessTokenKey)
  } catch {
    // 저장소 접근이 차단된 환경에서도 메모리 기반 인증은 계속 사용.
  }
}

export const getAccessToken = () => {
  clearLegacyStoredAccessToken()
  return accessTokenInMemory
}

export const setAccessToken = (accessToken) => {
  clearLegacyStoredAccessToken()
  accessTokenInMemory = typeof accessToken === 'string' && accessToken
    ? accessToken
    : null
}

export const clearAccessToken = () => {
  accessTokenInMemory = null
  clearLegacyStoredAccessToken()
}

// 로그인 응답으로 받은 회원 권한을 현재 브라우저 세션에만 저장.
export const setUserRole = (userRole) => {
  if (supportedUserRoles.has(userRole)) {
    sessionStorage.setItem(userRoleKey, userRole)
    return
  }

  sessionStorage.removeItem(userRoleKey)
}

// 사이드바 메뉴 구성에 사용할 현재 회원 권한 조회.
export const getUserRole = () => sessionStorage.getItem(userRoleKey)

// 로그아웃과 인증 만료 시 저장된 회원 권한 제거.
export const clearUserRole = () => {
  sessionStorage.removeItem(userRoleKey)
}

// 인증 종료 시 메모리의 Access Token과 회원 권한을 함께 제거.
export const clearAuthSession = () => {
  clearAccessToken()
  clearUserRole()
}
