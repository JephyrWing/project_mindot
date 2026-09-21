import { isWeekStart } from '../reports/weeklyReportData.js'

// URL에서 사용하는 숫자 식별자를 유효한 양의 정수로 변환.
const parseIdentifier = (value) => {
  if (!/^\d+$/.test(value ?? '')) return null

  const identifier = Number(value)
  return Number.isSafeInteger(identifier) && identifier > 0
    ? identifier
    : null
}

// 마지막 슬래시 유무와 관계없이 같은 화면으로 인식하기 위한 경로 정규화.
const normalizePathname = (pathname) => {
  if (!pathname || pathname === '/') return '/'

  return pathname.replace(/\/+$/, '') || '/'
}

// 현재 브라우저 주소를 애플리케이션 화면과 상세 식별자로 변환.
export const readAppRoute = () => {
  const pathname = normalizePathname(window.location.pathname)
  const searchParams = new URLSearchParams(window.location.search)
  const weekStart = isWeekStart(searchParams.get('weekStart')) ? searchParams.get('weekStart') : null
  const returnWeek = isWeekStart(searchParams.get('returnWeek')) ? searchParams.get('returnWeek') : null
  const emotionRecordMatch = pathname.match(/^\/records\/(\d+)$/)
  const cbtSessionMatch = pathname.match(/^\/cbt\/sessions\/(\d+)$/)
  const completedReflectionMatch = pathname.match(/^\/reflections\/(\d+)$/)
  const oauthCallbackMatch = pathname.match(/^\/oauth\/(kakao|google)\/callback$/)

  if (pathname === '/login') return { page: 'login' }
  if (pathname === '/signup') return { page: 'signup' }
  if (pathname === '/settings') return { page: 'settings' }
  if (pathname === '/about') return { page: 'about' }
  if (pathname === '/terms') return { page: 'terms' }
  if (pathname === '/privacy') return { page: 'privacy' }
  if (pathname === '/about/research') return { page: 'research' }
  if (oauthCallbackMatch) {
    return {
      page: 'oauth-callback',
      provider: oauthCallbackMatch[1],
    }
  }
  if (pathname === '/records/new') return { page: 'emotion-record' }
  if (pathname === '/records') return { page: 'emotion-history' }
  if (emotionRecordMatch) {
    return {
      page: 'emotion-record-detail',
      emotionRecordId: parseIdentifier(emotionRecordMatch[1]),
      returnWeek,
    }
  }
  if (cbtSessionMatch) {
    return {
      page: 'cbt',
      reflectionSessionId: parseIdentifier(cbtSessionMatch[1]),
    }
  }
  if (pathname === '/cbt') {
    return {
      page: 'cbt',
      emotionRecordId: parseIdentifier(searchParams.get('emotionRecordId')),
    }
  }
  if (pathname === '/reports/weekly') return { page: 'weekly-report', weekStart }
  if (pathname === '/reports/monthly') return { page: 'monthly-report' }
  if (pathname === '/insights/emotions') return { page: 'emotion-insights' }
  if (completedReflectionMatch) {
    return {
      page: 'completed-reflection',
      reflectionSessionId: parseIdentifier(completedReflectionMatch[1]),
      returnWeek,
    }
  }
  if (pathname === '/centers') return { page: 'center' }
  if (pathname === '/daily-care/breathing') return { page: 'breathing' }
  if (pathname === '/daily-care/meditation') return { page: 'meditation' }
  if (pathname === '/daily-care') return { page: 'daily-care' }
  if (pathname === '/admin') return { page: 'admin' }

  return { page: 'main' }
}

// 화면 이름과 상세 식별자를 브라우저에 표시할 URL 경로로 변환.
export const createAppPath = (page, parameters = {}) => {
  const emotionRecordId = parseIdentifier(String(parameters.emotionRecordId ?? ''))
  const reflectionSessionId = parseIdentifier(String(parameters.reflectionSessionId ?? ''))
  const returnQuery = isWeekStart(parameters.returnWeek) ? `?returnWeek=${parameters.returnWeek}` : ''

  if (page === 'login') return '/login'
  if (page === 'signup') return '/signup'
  if (page === 'settings') return '/settings'
  if (page === 'about') return '/about'
  if (page === 'terms') return '/terms'
  if (page === 'privacy') return '/privacy'
  if (page === 'research') return '/about/research'
  if (
    page === 'oauth-callback'
    && ['kakao', 'google'].includes(parameters.provider)
  ) {
    return `/oauth/${parameters.provider}/callback`
  }
  if (page === 'emotion-record') return '/records/new'
  if (page === 'emotion-history') return '/records'
  if (page === 'emotion-record-detail' && emotionRecordId) {
    return `/records/${emotionRecordId}${returnQuery}`
  }
  if (page === 'cbt' && reflectionSessionId) {
    return `/cbt/sessions/${reflectionSessionId}`
  }
  if (page === 'cbt' && emotionRecordId) {
    return `/cbt?emotionRecordId=${emotionRecordId}`
  }
  if (page === 'cbt') return '/cbt'
  if (page === 'weekly-report') return `/reports/weekly${isWeekStart(parameters.weekStart) ? `?weekStart=${parameters.weekStart}` : ''}`
  if (page === 'monthly-report') return '/reports/monthly'
  if (page === 'emotion-insights') return '/insights/emotions'
  if (page === 'completed-reflection' && reflectionSessionId) {
    return `/reflections/${reflectionSessionId}${returnQuery}`
  }
  if (page === 'center') return '/centers'
  if (page === 'breathing') return '/daily-care/breathing'
  if (page === 'meditation') return '/daily-care/meditation'
  if (page === 'daily-care') return '/daily-care'
  if (page === 'admin') return '/admin'

  return '/'
}
