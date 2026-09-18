import { expect, test } from '@playwright/test'
import { completeRecord, mockApi, readJsonBody, useAuthenticatedSession } from './support'

const pattern = { similarCaseCount: 4, patternSummary: '과거 비슷한 상황에서 상대의 평가를 걱정하는 흐름을 확인했어요.',
  repeatedDistortionCodes: ['MIND_READING'], helpfulAlternativeThought: null,
  recommendation: '이번에도 비슷한 흐름인지 살펴볼까요?' }
test.beforeEach(async ({ page }) => useAuthenticatedSession(page))

test('CBT 완료 기록을 다시 열면 패턴 요청과 알림을 모두 생략한다', async ({ page }) => {
  let calls = 0
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/1') return { body: completeRecord({ cbtStarted: true, cbtCompleted: true }) }
    if (url.pathname.endsWith('/pattern-explanation')) { calls++; return { body: pattern } }
  })
  await page.goto('/records/1')
  await expect(page.getByText('팀 발표 직전', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '반복 패턴 알림' })).toHaveCount(0)
  expect(calls).toBe(0)
})

test('CBT가 진행 중이면 기존처럼 결과가 있는 패턴 알림을 표시한다', async ({ page }) => {
  let calls = 0
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/1') return { body: completeRecord({ cbtStarted: true, cbtCompleted: false }) }
    if (url.pathname.endsWith('/pattern-explanation')) { calls++; return { body: pattern } }
  })
  await page.goto('/records/1')
  await expect(page.getByText(pattern.patternSummary)).toBeVisible()
  expect(calls).toBe(1)
})

test('CBT 완료 후 상세로 돌아와도 이전의 늦은 패턴 응답을 표시하지 않는다', async ({ page }) => {
  let completed = false
  let calls = 0
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  await mockApi(page, async (_request, url) => {
    if (url.pathname === '/api/records/1') return { body: completeRecord({ cbtStarted: true, cbtCompleted: completed }) }
    if (url.pathname.endsWith('/pattern-explanation')) {
      calls++
      await pending
      return { body: pattern }
    }
  })
  await page.goto('/records/1')
  await expect.poll(() => calls).toBe(1)
  await page.getByRole('button', { name: 'CBT 검사 하기' }).click()
  await expect(page.getByRole('heading', { name: 'CBT 성찰', exact: true })).toBeVisible()
  // Simulate the persisted completed session before returning to the same record.
  completed = true
  await page.goBack()
  await expect(page.getByText('팀 발표 직전', { exact: true })).toBeVisible()
  const response = page.waitForResponse('**/pattern-explanation')
  release()
  await response
  await expect(page.getByRole('heading', { name: '반복 패턴 알림' })).toHaveCount(0)
  expect(calls).toBe(1)
})

test('확정 직후 한 번 자동 요청하며 결과를 기다리지 않고 저장을 완료한다', async ({ page }) => {
  let calls = 0
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  await mockApi(page, async (request, url) => {
    if (url.pathname === '/api/records/1') return { body: completeRecord({ completionStatus: 'PARTIAL' }) }
    if (url.pathname.endsWith('/confirm')) return { body: completeRecord() }
    if (url.pathname.endsWith('/pattern-explanation')) {
      calls++
      await pending
      return { body: pattern }
    }
  })
  await page.goto('/records/1')
  await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
  expect(calls).toBe(0)
  await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
  await expect(page.getByText('수정한 분석 결과를 최종 확정했습니다.')).toBeVisible()
  await expect.poll(() => calls).toBe(1)
  await expect(page.getByRole('heading', { name: '반복 패턴 알림' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'CBT 검사 하기' })).toBeEnabled()
  release()
  await expect(page.getByText(pattern.patternSummary)).toBeVisible()
  await expect(page.getByRole('button', { name: '패턴 설명 요청' })).toHaveCount(0)
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await expect(page.getByText(pattern.patternSummary)).toHaveCount(0)
  await page.getByRole('button', { name: '수정 취소' }).click()
  await expect(page.getByText(pattern.patternSummary)).toBeVisible()
  expect(calls).toBe(1)
  await page.locator('.emotion-detail-pattern-notice').scrollIntoViewIfNeeded()
  await page.screenshot({ path: test.info().outputPath('automatic-pattern-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.locator('.emotion-detail-pattern-notice').scrollIntoViewIfNeeded()
  await page.screenshot({ path: test.info().outputPath('automatic-pattern-mobile.png') })
})

test('안전 안내가 필요한 기록에서는 자동 패턴 요청을 시작하지 않는다', async ({ page }) => {
  let calls = 0
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/1') return { body: completeRecord({
      safetyNotice: { safetyEventId: 123, actionCode: 'SHOW_CRISIS_NOTICE', riskLevel: 'CRISIS' },
    }) }
    if (url.pathname.includes('/notice-shown')) return { status: 204 }
    if (url.pathname.endsWith('/pattern-explanation')) { calls++; return { body: pattern } }
  })
  await page.goto('/records/1')
  await expect(page.getByRole('alertdialog')).toBeVisible()
  await page.getByRole('button', { name: '안전 안내 확인' }).click()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '반복 패턴 알림' })).toHaveCount(0)
  expect(calls).toBe(0)
})

for (const failure of [401, 403, 409, 502, 'abort', 'empty', 'blank'] as const) {
  test(`패턴 요청 ${failure}이면 알림·오류 없이 기록 상세를 유지한다`, async ({ page }) => {
    let calls = 0
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records/1') return { body: completeRecord() }
      if (url.pathname.endsWith('/pattern-explanation')) {
        calls++
        if (failure === 'abort') return 'abort'
        if (failure === 'empty') return { status: 204 }
        if (failure === 'blank') return { body: { ...pattern, patternSummary: ' ' } }
        return { status: failure, body: {} }
      }
    })
    const ended = failure === 'abort'
      ? page.waitForEvent('requestfailed', (request) => request.url().includes('/pattern-explanation'))
      : page.waitForResponse('**/pattern-explanation')
    await page.goto('/records/1')
    await ended
    await expect(page.getByRole('button', { name: '수정하기', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '반복 패턴 알림' })).toHaveCount(0)
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(calls).toBe(1)
  })
}

test('수정 전 늦은 응답을 버리고 수정한 기록의 결과만 표시한다', async ({ page }) => {
  let current = completeRecord()
  let calls = 0
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  await mockApi(page, async (request, url) => {
    if (url.pathname === '/api/records/1') {
      if (request.method() === 'PATCH') {
        const { analysis, ...fields } = readJsonBody(request)
        current = { ...current, ...fields, ...analysis }
      }
      return { body: current }
    }
    if (url.pathname.endsWith('/pattern-explanation')) {
      calls++
      if (calls === 1) {
        await pending
        return { body: { ...pattern, patternSummary: '이전 결과' } }
      }
      return { body: { ...pattern, patternSummary: '수정 후 결과' } }
    }
  })
  await page.goto('/records/1')
  await expect.poll(() => calls).toBe(1)
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await page.getByRole('textbox', { name: '기록 원문', exact: true }).fill('새 원문')
  await page.getByRole('button', { name: '수정 내용 저장하기' }).click()
  await expect(page.getByText('수정 후 결과')).toBeVisible()
  release()
  await expect(page.getByText('이전 결과')).toHaveCount(0)
  expect(calls).toBe(2)
})
