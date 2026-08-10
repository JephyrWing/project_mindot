const accessTokenKey = 'mindot.accessToken'

export const getAccessToken = () => sessionStorage.getItem(accessTokenKey)

export const setAccessToken = (accessToken) => {
  sessionStorage.setItem(accessTokenKey, accessToken)
}

export const clearAccessToken = () => {
  sessionStorage.removeItem(accessTokenKey)
}
