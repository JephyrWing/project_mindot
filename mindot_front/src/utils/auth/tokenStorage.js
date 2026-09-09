const accessTokenKey = 'mindot.accessToken'
const userRoleKey = 'mindot.userRole'

// 프론트에서 메뉴 노출에 사용하는 서버 회원 권한 코드 목록 설정.
const supportedUserRoles = new Set(['ROLE_USER', 'ROLE_ADMIN'])

export const getAccessToken = () => sessionStorage.getItem(accessTokenKey)

export const setAccessToken = (accessToken) => {
  sessionStorage.setItem(accessTokenKey, accessToken)
}

export const clearAccessToken = () => {
  sessionStorage.removeItem(accessTokenKey)
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

// 인증 종료 시 Access Token과 회원 권한을 함께 제거.
export const clearAuthSession = () => {
  clearAccessToken()
  clearUserRole()
}
