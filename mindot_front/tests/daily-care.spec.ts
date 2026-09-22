import { expect, test, type Page } from '@playwright/test'
import {
  completeRecord, dailyCareRecommendation, mockApi, openReflection,
  readJsonBody, useAuthenticatedSession,
} from './support'

// 추천 선정은 서버의 책임이다. 화면은 응답과 저장된 피드백을 그대로 반영한다.
function recommendationServer(overrides: Record<string, unknown> = {}) {
  return {
    recommendation: dailyCareRecommendation(overrides),
    recommendationFails: false,
    feedbackFails: false,
    feedbackGate: undefined as Promise<void> | undefined,
    feedbackBodies: [] as unknown[],
    reads: 0,
    legacyPatternReads: 0,
  }
}

async function mockDailyCare(page: Page, {
  server = recommendationServer(),
  recentRecordsFail = false,
  records = [completeRecord()],
  recentCount = records.length,
  reflections = [] as Record<string, unknown>[],
} = {}) {
  await mockApi(page, async (request, url) => {
    if (url.pathname === '/api/records') {
      const recent = url.searchParams.get('period') === 'RECENT_7_DAYS'
      if (recent && recentRecordsFail) return 'abort'
      return { body: {
        content: records, totalElements: recent ? recentCount : records.length,
        totalPages: records.length ? 1 : 0, page: 0, size: 1,
      } }
    }
    if (url.pathname === '/api/reflections/open') return { body: reflections }
    if (url.pathname === '/api/patterns/recent') {
      server.legacyPatternReads++
      return { body: [] }
    }
    if (url.pathname === '/api/daily-care/recommendation' && request.method() === 'GET') {
      server.reads++
      return server.recommendationFails
        ? { status: 503, body: { message: '오늘의 추천을 불러오지 못했습니다.' } }
        : { body: server.recommendation }
    }
    if (url.pathname === `/api/daily-care/recommendations/${server.recommendation.recommendationId}/feedback`
      && request.method() === 'POST') {
      const body = readJsonBody(request)
      server.feedbackBodies.push(body)
      await server.feedbackGate
      if (server.feedbackFails) return { status: 503, body: { message: '추천 의견을 저장하지 못했습니다.' } }
      server.recommendation = { ...server.recommendation, feedback: body.feedback }
      return { body: server.recommendation }
    }
  })
  return server
}

test.describe('FE-AUTO-020: 마음 돌봄 추천', () => {
  test.beforeEach(async ({ page }) => { await useAuthenticatedSession(page) })

  for (const scenario of [
    { source: 'recent-trend', label: '최근 7일 감정 흐름 기반', activity: 'RECORD', heading: '마음 기록',
      title: '최근의 좋은 마음을 짧게 기록해 두세요.' },
    { source: 'pattern', label: '최근 8주 반복 패턴 기반', activity: 'BREATHING', heading: '3분 호흡',
      title: '3분 호흡으로 긴장을 천천히 낮춰 보세요.' },
    { source: 'open-reflection', label: '진행 중 CBT 기반', activity: 'CBT', heading: 'CBT 성찰 이어하기',
      title: '멈춰 둔 CBT 성찰을 이어가 보세요.' },
    { source: 'latest-record', label: '최근 감정 기록 기반', activity: 'MEDITATION', heading: '짧은 명상',
      title: '짧은 명상으로 지금의 마음을 살펴보세요.' },
    { source: 'empty', label: '기본 추천', activity: 'RECORD', heading: '마음 기록',
      title: '오늘의 마음을 짧게 기록해 보세요.' },
  ]) {
    test(`성공: 서버의 ${scenario.source} 추천 근거·본문·활동을 표시한다`, async ({ page }) => {
      const server = recommendationServer({
        source: scenario.source, activity: scenario.activity, title: scenario.title,
        description: '서버가 제공한 오늘의 추천 근거입니다.',
        emotionRecordId: scenario.source === 'empty' ? null : 1,
        reflectionSessionId: scenario.source === 'open-reflection' ? 51 : null,
      })
      await mockDailyCare(page, {
        server,
        records: scenario.source === 'empty' ? [] : [completeRecord()],
        reflections: scenario.source === 'open-reflection' ? [openReflection()] : [],
        recentCount: scenario.source === 'empty' ? 0 : 12,
      })
      await page.goto('/daily-care')
      const suggestion = page.getByRole('region', { name: '오늘의 제안' })
      await expect(suggestion).toContainText(scenario.label)
      await expect(suggestion).toContainText(scenario.title)
      await expect(suggestion).toContainText(server.recommendation.description)
      await expect(page.locator('.daily-care-action.is-recommended')).toHaveCount(1)
      await expect(page.locator('.daily-care-action.is-recommended')).toContainText(scenario.heading)
      await expect(page.getByText('최근 7일 기록', { exact: true }).locator('..'))
        .toContainText(scenario.source === 'empty' ? '0개' : '12개')
      expect(server.reads).toBeGreaterThan(0)
      expect(server.legacyPatternReads).toBe(0)
      if (scenario.source === 'empty') {
        await page.getByRole('button', { name: '첫 기록하기', exact: true }).click()
        await expect(page).toHaveURL('/records/new')
      }
    })
  }

  test('오류: 기록 수 조회 실패에도 서버 추천을 유지하고 미확인 건수는 대시로 표시한다', async ({ page }) => {
    await mockDailyCare(page, { recentRecordsFail: true })
    await page.goto('/daily-care')
    await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없습니다')
    await expect(page.getByRole('region', { name: '오늘의 제안' })).toContainText(dailyCareRecommendation().title)
    await expect(page.getByText('최근 7일 기록', { exact: true }).locator('..')).toContainText('-')
  })

  test('오류: 추천 조회 실패를 안내하고 다시 불러오면 서버 추천을 복구한다', async ({ page }) => {
    const server = recommendationServer()
    server.recommendationFails = true
    await mockDailyCare(page, { server })
    await page.goto('/daily-care')
    await expect(page.getByRole('alert')).toContainText('오늘의 추천을 불러오지 못했습니다.')
    await expect(page.getByRole('region', { name: '오늘의 제안' })).toHaveCount(0)
    await expect(page.getByRole('region', { name: '마음 돌봄 활동' })).toBeVisible()
    server.recommendationFails = false
    await page.getByRole('button', { name: '다시 불러오기' }).click()
    await expect(page.getByRole('region', { name: '오늘의 제안' })).toContainText(server.recommendation.title)
    await expect(page.getByRole('alert')).toHaveCount(0)
  })

  for (const { feedback, label, message } of [
    { feedback: 'HELPFUL', label: '도움됨', message: '도움이 된 추천으로 기억했습니다.' },
    { feedback: 'LATER', label: '나중에', message: '나중에 다시 볼 추천으로 기억했습니다.' },
  ]) {
    test(`성공: ${feedback}를 추천 ID로 저장하고 새 브라우저에서도 서버 값으로 복원한다`, async ({ page, browser, baseURL }) => {
      const server = await mockDailyCare(page)
      await page.goto('/daily-care')
      await page.getByRole('button', { name: label, exact: true }).click()
      await expect(page.getByRole('status')).toHaveText(message)
      expect(server.feedbackBodies).toEqual([{ feedback }])
      await page.reload()
      await expect(page.getByRole('button', { name: label, pressed: true })).toBeVisible()
      const freshContext = await browser.newContext({ baseURL })
      try {
        const freshPage = await freshContext.newPage()
        await useAuthenticatedSession(freshPage)
        await mockDailyCare(freshPage, { server })
        await freshPage.goto('/daily-care')
        await expect(freshPage.getByRole('button', { name: label, pressed: true })).toBeVisible()
        await expect(freshPage.getByRole('status')).toHaveText(message)
        expect(server.feedbackBodies).toHaveLength(1)
      } finally { await freshContext.close() }
    })
  }

  test('오류: 피드백 저장 실패는 기존 선택을 유지하고 재시도 성공 후에만 변경한다', async ({ page }) => {
    const server = recommendationServer({ feedback: 'HELPFUL' })
    server.feedbackFails = true
    await mockDailyCare(page, { server })
    await page.goto('/daily-care')
    await page.getByRole('button', { name: '나중에' }).click()
    await expect(page.getByRole('alert')).toHaveText('추천 의견을 저장하지 못했습니다.')
    await expect(page.getByRole('button', { name: '도움됨', pressed: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '나중에', pressed: false })).toBeEnabled()
    server.feedbackFails = false
    await page.getByRole('button', { name: '나중에' }).click()
    await expect(page.getByRole('button', { name: '나중에', pressed: true })).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(server.feedbackBodies).toEqual([{ feedback: 'LATER' }, { feedback: 'LATER' }])
  })

  test('경계: 피드백 저장 중에는 두 버튼을 잠그고 서버 응답 후 선택을 반영한다', async ({ page }) => {
    const server = recommendationServer()
    let finishFeedback!: () => void
    server.feedbackGate = new Promise<void>((resolve) => { finishFeedback = resolve })
    await mockDailyCare(page, { server })
    await page.goto('/daily-care')
    await page.getByRole('button', { name: '도움됨' }).click()
    try {
      await expect(page.getByRole('button', { name: '도움됨', pressed: false })).toBeDisabled()
      await expect(page.getByRole('button', { name: '나중에', pressed: false })).toBeDisabled()
      await expect(page.getByRole('status')).toHaveCount(0)
    } finally { finishFeedback() }
    await expect(page.getByRole('button', { name: '도움됨', pressed: true })).toBeEnabled()
    expect(server.feedbackBodies).toEqual([{ feedback: 'HELPFUL' }])
  })
})
