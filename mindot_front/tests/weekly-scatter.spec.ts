import { expect, test } from '@playwright/test'
import { completeRecord, mockApi, openReflection, proposal, useAuthenticatedSession, weeklyReport } from './support'

const start = '2026-09-14'
const sample = (id: number, time: string, emotion: string | null, intensity: number | null) => ({
  emotionRecordId: id, occurredAt: `${start}T${time}:00+09:00`, primaryEmotionCode: emotion,
  primaryIntensity: intensity, situationText: `테스트 기록 ${id}`,
})
const records = [sample(1, '00:00', 'ANXIETY', 0), sample(2, '08:10', 'JOY', 10),
  sample(3, '12:20', 'OTHER', null), sample(4, '18:30', '복잡한 마음', 4),
  sample(5, '21:40', null, 5), sample(6, '22:00', '짜증', 6),
  { ...sample(7, '23:59', '잔잔한 기대', 2), occurredAt: '2026-09-20T23:59:00+09:00' }]
const report = (rows = records) => weeklyReport({ periodStart: start, recordCount: rows.length, emotionRecordEvidences: rows })

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date('2026-09-21T10:00:00+09:00') })
  await useAuthenticatedSession(page)
})

test('전체 67건과 중복 ID: 점·두 분포·요약이 일치하고 밀집 기록도 각각 선택한다', async ({ page }) => {
  const many = [...records, ...Array.from({ length: 60 }, (_, i) => sample(i + 8, '12:20', i % 2 ? '짜증' : 'JOY', i % 11))]
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/reports/weekly') return { body: { ...report(many), emotionRecordEvidences: [...many, many[0]] } }
    if (url.pathname === '/api/records/67') return { body: completeRecord({ emotionRecordId: 67 }) }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(67)
  await expect(page.getByText('기록 횟수').locator('..')).toContainText('67회')
  await expect(page.locator('.weekly-report-summary')).not.toContainText('도움')
  for (const title of ['요일 분포', '시간대 분포']) {
    const distribution = page.getByRole('region', { name: title, exact: true })
    expect(await distribution.locator('li').evaluateAll((items) => items.reduce((n, item) => n + Number(item.getAttribute('data-total')), 0))).toBe(67)
    expect(await distribution.locator('[data-count]').evaluateAll((items) => items.reduce((n, item) => n + Number(item.getAttribute('data-count')), 0))).toBe(67)
  }
  const bars = page.getByRole('region', { name: '요일 분포', exact: true }).locator('.weekly-stacked-bar')
  expect(await bars.nth(0).evaluate((el) => el.getAttribute('style'))).not.toEqual(await bars.nth(6).evaluate((el) => el.getAttribute('style')))
  await page.locator('.weekly-scatter-records summary').click()
  await expect(page.locator('.weekly-scatter-records button')).toHaveCount(67)
  await page.locator('.weekly-scatter-records button').filter({ hasText: '2026-09-14 월요일 12:20' }).last().click()
  await expect(page).toHaveURL(`/records/67?returnWeek=${start}`)
})

test('브라우저가 LA 시간대여도 계정 시간대로 표시하며 강도·색·호버·키보드·선택 주 복원을 유지한다', async ({ browser }) => {
  const context = await browser.newContext({ timezoneId: 'America/Los_Angeles', viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  await useAuthenticatedSession(page)
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/reports/weekly') return { body: report() }
    if (url.pathname === '/api/records/1') return { body: completeRecord() }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  const first = page.locator('.weekly-scatter-hit[data-record-id="1"]')
  const last = page.locator('.weekly-scatter-hit[data-record-id="7"]')
  await expect(first).toHaveAttribute('aria-label', /월요일 00:00/)
  await expect(last).toHaveAttribute('aria-label', /일요일 23:59/)
  expect(await first.evaluate((el: HTMLElement) => el.style.top)).toBe('0%')
  expect(Number.parseFloat(await last.evaluate((el: HTMLElement) => el.style.top))).toBeGreaterThan(99)
  const zero = page.locator('[data-intensity="0"]'), ten = page.locator('[data-intensity="10"]'), missing = page.locator('[data-intensity="missing"]')
  expect((await ten.boundingBox())!.width).toBeGreaterThan((await zero.boundingBox())!.width)
  await expect(missing).toHaveCSS('background-color', 'rgb(255, 255, 255)')
  await expect(zero).toHaveCSS('background-color', 'rgb(255, 122, 144)')
  for (const name of ['기타', '복잡한 마음', '감정 미입력', '잔잔한 기대', '짜증']) await expect(page.getByRole('list', { name: '이번 주 감정 범례' })).toContainText(name)
  await first.hover()
  await expect(page.locator('.weekly-scatter-tooltip')).toContainText('강도 0/10')
  await page.screenshot({ path: test.info().outputPath('weekly-desktop.png'), fullPage: true })
  await page.locator('.weekly-scatter').screenshot({ path: test.info().outputPath('weekly-desktop-chart.png') })
  await page.locator('.weekly-emotion-distributions').screenshot({ path: test.info().outputPath('weekly-desktop-distributions.png') })
  await first.focus()
  await first.press('Enter')
  await expect(page).toHaveURL(`/records/1?returnWeek=${start}`)
  await page.reload()
  await page.getByRole('button', { name: '주간 리포트로 돌아가기' }).click()
  await expect(page).toHaveURL(`/reports/weekly?weekStart=${start}`)
  await first.click()
  await page.goBack()
  await expect(page).toHaveURL(`/reports/weekly?weekStart=${start}`)
  await expect(first).toBeVisible()
  await context.close()
})

test('모바일 터치는 요약만 열고 별도 상세 보기로 이동하며 화면 밖으로 잘리지 않는다', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await context.newPage()
  await useAuthenticatedSession(page)
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/reports/weekly') return { body: report() }
    if (url.pathname === '/api/records/7') return { body: completeRecord({ emotionRecordId: 7 }) }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await page.locator('.weekly-scatter-hit[data-record-id="7"]').tap()
  await expect(page).toHaveURL(`/reports/weekly?weekStart=${start}`)
  const tooltip = page.locator('.weekly-scatter-tooltip')
  await expect(tooltip).toContainText('잔잔한 기대')
  const box = (await tooltip.boundingBox())!
  expect(box.x).toBeGreaterThanOrEqual(0)
  expect(box.x + box.width).toBeLessThanOrEqual(390)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: test.info().outputPath('weekly-mobile.png'), fullPage: true })
  await page.locator('.weekly-scatter').screenshot({ path: test.info().outputPath('weekly-mobile-chart.png') })
  await page.locator('.weekly-emotion-distributions').screenshot({ path: test.info().outputPath('weekly-mobile-distributions.png') })
  await tooltip.getByRole('button', { name: '상세 보기' }).tap()
  await expect(page).toHaveURL(`/records/7?returnWeek=${start}`)
  await context.close()
})

test('누락된 캐시 필드는 전체 리포트를 한 번 재생성하고 실패하면 재시도 경로를 제공한다', async ({ page }) => {
  let posts = 0
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/reports/weekly') {
      if (request.method() === 'GET') return { body: weeklyReport({ emotionRecordEvidences: undefined }) }
      posts++
      return posts === 1 ? { status: 500, body: { message: '리포트 갱신 실패' } } : { body: report() }
    }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await expect(page.getByRole('alert')).toContainText('리포트 갱신 실패')
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(0)
  expect(posts).toBe(1)
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(7)
  expect(posts).toBe(2)
})

test('빠른 주 변경과 이전 주의 늦은 갱신 응답은 현재 주를 덮어쓰지 않는다', async ({ page }) => {
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  let refreshing = false
  await mockApi(page, async (request, url) => {
    if (url.pathname === '/api/reports/weekly') {
      const week = url.searchParams.get('weekStart')!
      if (request.method() === 'POST') { refreshing = true; await pending; return { body: report() } }
      if (week === start) return { body: report() }
      return { body: weeklyReport({ periodStart: week, recordCount: 1,
        emotionRecordEvidences: [{ ...sample(9, '12:00', 'CALM', 3), occurredAt: `${week}T12:00:00+09:00` }] }) }
    }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await page.getByRole('button', { name: '최신 기록으로 다시 만들기' }).click()
  await expect.poll(() => refreshing).toBe(true)
  await page.getByRole('button', { name: '← 이전 주' }).click()
  await page.getByRole('button', { name: '← 이전 주' }).click()
  await expect(page).toHaveURL('/reports/weekly?weekStart=2026-08-31')
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(1)
  const response = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/api/reports/weekly'))
  release()
  await response
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(1)
  await expect(page.locator('.weekly-scatter-hit')).toHaveAttribute('aria-label', /2026-08-31/)
})

test('CBT만 있는 주는 감정 0건을 표시하고 완료 CBT 상세와 선택한 주로 복귀한다', async ({ page }) => {
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/reports/weekly') return { body: report([]) }
    if (url.pathname === '/api/reflections/51') return { body: openReflection({ status: 'COMPLETED', confirmedResult: { ...proposal, reviews: [] } }) }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await expect(page.getByText('이번 주에는 감정 기록이 없습니다.')).toBeVisible()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(0)
  await page.getByRole('button', { name: '완료한 CBT 성찰 보기' }).click()
  await page.getByRole('button', { name: '성찰 결과 자세히 보기' }).click()
  await expect(page).toHaveURL(`/reflections/51?returnWeek=${start}`)
  await expect(page.getByRole('region', { name: '성찰 결과와 생각 패턴' }).getByText(proposal.afterText, { exact: true })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '주간 리포트로 돌아가기' }).click()
  await expect(page).toHaveURL(`/reports/weekly?weekStart=${start}`)
})

test('프로필 시간대 조회 실패도 빈 주로 처리하지 않고 다시 조회한다', async ({ page }) => {
  let fail = true
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/users/me') return fail ? { status: 500, body: { message: '시간대 조회 실패' } } : { body: { timezone: 'Asia/Seoul' } }
    if (url.pathname === '/api/reports/weekly') return { body: report() }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await expect(page.getByRole('alert')).toContainText('시간대 조회 실패')
  fail = false
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(7)
})

test('산점도 가로·세로 75%와 오른쪽 감정 필터: 분류·범례·목록·키보드·점 위치를 유지한다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await mockApi(page, (_request, url) => url.pathname === '/api/reports/weekly' ? { body: report() } : undefined)
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(7)
  const frame = (await page.locator('.weekly-scatter-frame').boundingBox())!
  const layout = (await page.locator('.weekly-scatter-layout').boundingBox())!
  const filter = (await page.getByRole('group', { name: '감정 필터' }).boundingBox())!
  expect(frame.width / layout.width).toBeCloseTo(0.75, 2)
  expect((await page.locator('.weekly-scatter-plot').boundingBox())!.height).toBe(360)
  expect(filter.x).toBeGreaterThanOrEqual(frame.x + frame.width)
  const first = page.locator('.weekly-scatter-hit[data-record-id="1"]')
  const originalPosition = await first.getAttribute('style')
  await first.hover()
  await expect(page.locator('.weekly-scatter-tooltip')).toBeVisible()
  await page.getByRole('radio', { name: '긍정적 감정' }).check()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(1)
  await expect(page.locator('.weekly-scatter-hit')).toHaveAttribute('data-record-id', '2')
  await expect(page.locator('.weekly-scatter-tooltip')).toHaveCount(0)
  await expect(page.getByRole('list', { name: '이번 주 감정 범례' })).toContainText('기쁨')
  await expect(page.getByRole('list', { name: '이번 주 감정 범례' })).not.toContainText('불안')
  await page.getByRole('radio', { name: '전체 표시' }).focus()
  await page.getByRole('radio', { name: '전체 표시' }).press('ArrowDown')
  await expect(page.getByRole('radio', { name: '부정적 감정' })).toBeChecked()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(2)
  await expect(first).toHaveAttribute('style', originalPosition!)
  await page.locator('.weekly-scatter-records summary').click()
  await expect(page.locator('.weekly-scatter-records button')).toHaveCount(2)
  await expect(page.locator('.weekly-scatter-records')).toContainText('짜증')
  await expect(page.getByText('기록 횟수').locator('..')).toContainText('7회')
  expect(await page.getByRole('region', { name: '요일 분포', exact: true }).locator('li').evaluateAll((items) => items.reduce((n, item) => n + Number(item.getAttribute('data-total')), 0))).toBe(7)
  await page.locator('.weekly-scatter-records summary').click()
  await page.locator('.weekly-scatter').screenshot({ path: test.info().outputPath('weekly-filter-desktop.png') })
  await page.getByRole('radio', { name: '전체 표시' }).check()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(7)
  await page.setViewportSize({ width: 390, height: 844 })
  const mobileFilter = (await page.getByRole('group', { name: '감정 필터' }).boundingBox())!
  const mobileFrame = (await page.locator('.weekly-scatter-frame').boundingBox())!
  expect(mobileFilter.y + mobileFilter.height).toBeLessThanOrEqual(mobileFrame.y)
  await page.getByRole('radio', { name: '부정적 감정' }).check()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.locator('.weekly-scatter').screenshot({ path: test.info().outputPath('weekly-filter-mobile.png') })
})

test('선택한 분류에 기록이 없으면 안내하고 전체 표시로 미등록 감정을 복원한다', async ({ page }) => {
  await mockApi(page, (_request, url) => url.pathname === '/api/reports/weekly'
    ? { body: report([sample(1, '09:00', '복잡한 마음', 5)]) } : undefined)
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await page.getByRole('radio', { name: '부정적 감정' }).check()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(0)
  await expect(page.getByText('선택한 감정에 해당하는 기록이 없습니다.')).toBeVisible()
  await page.getByRole('radio', { name: '전체 표시' }).check()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(1)
  await expect(page.getByRole('list', { name: '이번 주 감정 범례' })).toContainText('복잡한 마음')
})

test('상세에서 발생 시각을 다른 주로 수정한 뒤 돌아오면 무효화된 캐시를 재생성한다', async ({ page }) => {
  let changed = false
  let generations = 0
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/reports/weekly') {
      if (request.method() === 'POST') { generations++; return { body: report(records.slice(1)) } }
      return changed ? { status: 404, body: {} } : { body: report() }
    }
    if (url.pathname === '/api/records/1') {
      if (request.method() === 'PATCH') changed = true
      return { body: completeRecord({ cbtStarted: false, occurredAt: changed ? '2026-09-21T00:00:00Z' : records[0].occurredAt }) }
    }
  })
  await page.goto(`/reports/weekly?weekStart=${start}`)
  await page.locator('.weekly-scatter-hit[data-record-id="1"]').click()
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await page.getByLabel('날짜와 시간').fill('2026-09-21T09:00')
  await page.getByRole('button', { name: '수정 내용 저장하기' }).click()
  await expect(page.getByText('감정 기록을 수정했습니다.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '주간 리포트로 돌아가기' }).click()
  await expect(page.locator('.weekly-scatter-hit')).toHaveCount(6)
  await expect(page.getByText('기록 횟수').locator('..')).toContainText('6회')
  expect(generations).toBe(1)
})
