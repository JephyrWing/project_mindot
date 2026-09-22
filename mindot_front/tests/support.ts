import type { Page, Request } from '@playwright/test'

export type ApiMockResult = {
  status?: number
  body?: unknown
  contentType?: string
} | 'abort'

type ApiResolver = (
  request: Request,
  url: URL,
) => ApiMockResult | undefined | Promise<ApiMockResult | undefined>

// E2E 인증 상태는 브라우저 저장소의 토큰 대신 Refresh Token 쿠키 흐름을 모사.
const authenticatedPages = new WeakMap<Page, string>()

const emptyPage = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
}

export async function useGuestSession(page: Page) {
  authenticatedPages.delete(page)
  await page.addInitScript(() => {
    window.localStorage.setItem('mindot.appIntroShown', 'true')
    window.sessionStorage.clear()
  })
}

export async function useAuthenticatedSession(
  page: Page,
  role = 'ROLE_USER',
) {
  authenticatedPages.set(page, role)
  await page.addInitScript(() => {
    window.localStorage.setItem('mindot.appIntroShown', 'true')
    window.sessionStorage.clear()
  })
}

export async function mockApi(page: Page, resolver?: ApiResolver) {
  let bootstrapRole = authenticatedPages.get(page) ?? null

  // 실제 회전된 Refresh Token 쿠키처럼 새 문서를 불러올 때마다 복구를 허용.
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) {
      bootstrapRole = authenticatedPages.get(page) ?? null
    }
  })

  await page.route('http://localhost:8080/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    let overridden: ApiMockResult | undefined

    // 앱 시작 시에는 HttpOnly 쿠키를 받은 서버처럼 메모리용 토큰을 한 번 발급.
    if (
      bootstrapRole
      && url.pathname === '/api/auth/refresh'
      && request.method() === 'POST'
    ) {
      overridden = {
        body: {
          accessToken: 'playwright-access-token',
          userRole: bootstrapRole,
        },
      }
      bootstrapRole = null
    } else {
      overridden = await resolver?.(request, url)
    }

    if (overridden === 'abort') {
      await route.abort('failed')
      return
    }

    let response = overridden

    if (!response) {
      if (
        url.pathname === '/api/auth/refresh'
        && request.method() === 'POST'
      ) {
        response = { status: 401, body: {} }
      } else if (url.pathname === '/api/users/me' && request.method() === 'GET') {
        response = { body: { timezone: 'Asia/Seoul' } }
      } else if (url.pathname === '/api/notifications/unread-count') {
        response = { body: { unreadCount: 0 } }
      } else if (
        url.pathname === '/api/reflections/open/paged'
        && request.method() === 'GET'
      ) {
        response = { body: { ...emptyPage, size: Number(url.searchParams.get('size') ?? 5) } }
      } else if (
        url.pathname === '/api/reflections/open'
        && request.method() === 'GET'
      ) {
        response = { body: [] }
      } else if (
        url.pathname === '/api/records'
        && request.method() === 'GET'
      ) {
        response = { body: { ...emptyPage, size: 3 } }
      } else if (
        /^\/api\/records\/\d+\/questions\/missing$/.test(url.pathname)
        && request.method() === 'GET'
      ) {
        response = {
          body: {
            emotionRecordId: Number(url.pathname.split('/')[3]),
            completionStatus: 'PARTIAL',
            questions: [],
          },
        }
      } else if (url.pathname === '/api/patterns/recent') {
        response = { body: [] }
      } else if (url.pathname === '/api/daily-care/recommendation' && request.method() === 'GET') {
        response = { body: dailyCareRecommendation() }
      } else if (url.pathname === '/api/consents/history') {
        response = { body: { ...emptyPage, size: 5 } }
      } else if (url.pathname === '/api/admin/users') {
        response = { body: emptyPage }
      } else {
        response = {
          status: 404,
          body: { message: `No Playwright mock for ${request.method()} ${url.pathname}` },
        }
      }
    }

    const contentType = response.contentType ?? 'application/json'
    const body = contentType === 'application/json'
      ? JSON.stringify(response.body ?? {})
      : String(response.body ?? '')

    await route.fulfill({
      status: response.status ?? 200,
      contentType,
      body,
    })
  })
}

export function readJsonBody(request: Request) {
  try {
    return request.postDataJSON()
  } catch {
    return null
  }
}

export const dailyCareRecommendation = (overrides: Record<string, unknown> = {}) => ({
  recommendationId: 71,
  title: '3분 호흡으로 긴장을 천천히 낮춰 보세요.',
  description: '가장 최근에 기록한 감정을 기준으로 안내합니다.',
  activity: 'BREATHING',
  source: 'latest-record',
  emotionRecordId: 1,
  reflectionSessionId: null,
  createdAt: '2026-09-22T00:00:00Z',
  feedback: null,
  ...overrides,
})

export const completeRecord = (overrides: Record<string, unknown> = {}) => ({
  emotionRecordId: 1,
  recordId: 1,
  rawText: '발표를 앞두고 많이 긴장했다.',
  occurredAt: '2026-09-15T01:30:00Z',
  timeBucket: 'MORNING',
  weekdayType: 'WEEKDAY',
  analysisStatus: 'COMPLETED',
  completionStatus: 'COMPLETE',
  situationText: '팀 발표 직전',
  automaticThought: '실수하면 모두가 나를 이상하게 볼 거야.',
  primaryEmotionCode: 'ANXIETY',
  primaryIntensity: 7,
  secondaryEmotions: [{ code: 'FEAR', intensity: 5 }],
  contextCategory: 'PERFORMANCE',
  relatedPersonType: 'COLLEAGUE',
  details: {
    interpretation: '평가받는 상황으로 느꼈다.',
    bodyReaction: '심장이 빨리 뛰었다.',
    behavior: '발표를 피하고 싶었다.',
  },
  ...overrides,
})

export const openReflection = (overrides: Record<string, unknown> = {}) => ({
  sessionId: 51,
  emotionRecordId: 1,
  revision: 1,
  status: 'OPEN',
  phase: 'QUESTIONING',
  createdAt: '2026-09-15T02:00:00Z',
  record: {
    automaticThought: '실수하면 모두가 나를 이상하게 볼 거야.',
  },
  messages: [
    { messageNumber: 1, role: 'AI', content: '그 생각을 뒷받침하는 근거가 있나요?' },
  ],
  job: { status: 'COMPLETED', retryable: false },
  currentProposal: null,
  confirmedResult: null,
  ...overrides,
})

export const proposal = {
  proposalId: 900,
  assessmentType: 'DETECTED',
  beforeText: '실수하면 모두가 나를 이상하게 볼 거야.',
  afterText: '실수해도 준비한 내용을 차분히 이어갈 수 있어.',
  comparisonExplanation: '한 번의 실수를 전체 평가로 확대하지 않도록 바꾸었습니다.',
  evidenceForText: '이전에 발표 중 말을 멈춘 적이 있다.',
  evidenceAgainstText: '그때도 동료들이 기다려 주었다.',
  suggestions: [
    {
      code: 'CATASTROPHIZING_FORTUNE_TELLING',
      explanation: '앞으로 생길 일을 가장 나쁘게 예상했습니다.',
    },
  ],
}

export const weeklyReport = (overrides: Record<string, unknown> = {}) => ({
  recordCount: 2,
  dominantEmotionCode: 'ANXIETY',
  averageIntensity: 6.5,
  completedCbtCount: 1,
  averageHelpfulnessScore: 4,
  emotionCounts: { ANXIETY: 2 },
  weekdayCounts: { MONDAY: 1, TUESDAY: 1 },
  timeBucketCounts: { MORNING: 2 },
  distortionChangeCounts: {
    CONFIRMED_INSIGHT: { CATASTROPHIZING_FORTUNE_TELLING: 1 },
  },
  repeatedPatterns: [
    {
      emotionCode: 'ANXIETY',
      weekday: 'MONDAY',
      timeBucket: 'MORNING',
      patternLevel: 'REPEATED',
      occurrenceCount: 3,
    },
  ],
  emotionRecordEvidences: [
    {
      emotionRecordId: 2,
      occurredAt: '2026-09-14T01:30:00Z',
      primaryEmotionCode: 'ANXIETY',
      primaryIntensity: 6,
      situationText: '회의 전',
    },
    {
      emotionRecordId: 1,
      occurredAt: '2026-09-15T01:30:00Z',
      primaryEmotionCode: 'ANXIETY',
      primaryIntensity: 7,
      situationText: '팀 발표 직전',
    },
  ],
  completedCbtEvidences: [
    {
      sessionId: 51,
      completedAt: '2026-09-15T03:00:00Z',
      automaticThought: '실수하면 모두가 나를 이상하게 볼 거야.',
    },
  ],
  sourceSnapshotAt: '2026-09-16T00:00:00Z',
  ...overrides,
})

export const monthlyComposition = (month: string, entries: { day: number; emotion: string | null; context?: string | null }[] = [
  { day: 1, emotion: 'ANXIETY', context: 'PERFORMANCE' }, { day: 1, emotion: 'ANXIETY', context: 'PERFORMANCE' },
  { day: 2, emotion: 'CALM', context: 'DAILY_LIFE' },
]) => {
  const [year, m] = month.split('-').map(Number)
  const length = new Date(Date.UTC(year, m, 0)).getUTCDate()
  const date = (day: number) => `${month}-${String(day).padStart(2, '0')}`
  const counts = (rows: typeof entries) => [...new Set(rows.map(r => r.emotion))].map(emotion => ({ emotion, count: rows.filter(r => r.emotion === emotion).length }))
  const group = (value: string | null, rows: typeof entries) => ({ value, recordCount: rows.length, emotions: counts(rows) })
  const split = Math.floor(length / 2)
  return {
    version: 2, timezone: 'Asia/Seoul', recordCount: entries.length, emotions: counts(entries),
    days: Array.from({ length }, (_, i) => group(date(i+1), entries.filter(r => r.day === i+1))),
    contexts: [...new Set(entries.map(r => r.context ?? null))].map(value => group(value, entries.filter(r => (r.context ?? null) === value))),
    halves: [[1, split], [split+1, length]].map(([start,end]) => ({ periodStart: date(start), periodEnd: date(end), ...group(null, entries.filter(r => r.day >= start && r.day <= end)) })),
  }
}

export const monthlyReport = (month: string, overrides: Record<string, unknown> = {}) => ({
  emotionComposition: monthlyComposition(month),
  periodStart: `${month}-01`,
  periodEnd: monthlyComposition(month).days.at(-1)!.value,
  recordCount: 3,
  dominantEmotionCode: 'ANXIETY',
  averageIntensity: 5.5,
  completedCbtCount: 1,
  averageHelpfulnessScore: 4,
  summaryText: 'ANXIETY 감정과 PERFORMANCE 상황이 자주 기록되었습니다.',
  firstHalfAverageIntensity: 7,
  secondHalfAverageIntensity: 4,
  intensityTrend: 'DECREASED',
  emotionCounts: { ANXIETY: 2, CALM: 1 },
  contextCategoryCounts: { PERFORMANCE: 2, DAILY_LIFE: 1 },
  dailyTrends: [
    {
      date: `${month}-01`,
      recordCount: 2,
      averageIntensity: 7,
      dominantEmotionCode: 'ANXIETY',
    },
    {
      date: `${month}-02`,
      recordCount: 1,
      averageIntensity: 4,
      dominantEmotionCode: 'CALM',
    },
  ],
  sourceSnapshotAt: '2026-09-16T00:00:00Z',
  ...overrides,
})
