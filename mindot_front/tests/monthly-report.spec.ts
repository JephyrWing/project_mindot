import { expect, test } from '@playwright/test'
import { mockApi, monthlyComposition, monthlyReport, useAuthenticatedSession } from './support'

test.describe('FE-AUTO-019: 월간 리포트', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('성공: 월간 통계·변화·일별 분포를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return { body: monthlyReport(url.searchParams.get('month') ?? '2026-09') }
      }
    })
    await page.goto('/reports/monthly')
    await expect(page.getByText('기록 횟수').locator('..')).toContainText('3회')
    await expect(page.getByText('평균 도움', { exact: true })).toHaveCount(0)
    await expect(page.getByText('평균 강도', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '날짜별 감정 기록 수' })).toBeVisible()
    await expect(page.getByText('불안 감정과 발표·시험 상황이 자주 기록되었습니다.')).toBeVisible()
  })

  test('초반 2건과 후반 10건의 불안 비율은 모두 50%이고 건수는 다르다', async ({ page }) => {
    const entries = Array.from({ length: 12 }, (_, i) => ({ day: i < 2 ? 1 : 20, emotion: i % 2 ? 'JOY' : 'ANXIETY', context: i < 2 ? 'WORK' : 'DAILY_LIFE' }))
    await mockApi(page, (_request, url) => url.pathname === '/api/reports/monthly'
      ? { body: monthlyReport('2026-09', { recordCount: 12, emotionComposition: monthlyComposition('2026-09', entries) }) } : undefined)
    await page.goto('/reports/monthly')
    const half = page.locator('section[aria-labelledby="monthly-half-trend-title"]')
    await expect(half).toContainText('월 초반 2026-09-01 ~ 2026-09-15 · 2건')
    await expect(half).toContainText('월 후반 2026-09-16 ~ 2026-09-30 · 10건')
    await expect(half).toContainText('불안 · 1건 · 50.0%')
    await expect(half).toContainText('불안 · 5건 · 50.0%')
    const widths = await half.locator('.monthly-composition-bar').evaluateAll(nodes => nodes.map(n => (n as HTMLElement).style.width))
    expect(widths).toEqual(['100%', '100%'])
    const contexts = page.locator('section[aria-labelledby="monthly-distributions-title"] .monthly-composition-bar')
    expect(await contexts.evaluateAll(nodes => nodes.map(n => (n as HTMLElement).style.width))).toEqual(['100%', '20%', '100%'])
    await expect(page.getByText(/후반 강도|강도가 높아졌|강도가 낮아졌/)).toHaveCount(0)
  })

  test('기록 없는 후반은 빈 막대이며 날짜 선택은 키보드로 감정별 건수와 비율을 표시한다', async ({ page }) => {
    await mockApi(page, (_request, url) => url.pathname === '/api/reports/monthly' ? { body: monthlyReport('2026-09') } : undefined)
    await page.goto('/reports/monthly')
    const half = page.locator('section[aria-labelledby="monthly-half-trend-title"]')
    await expect(half).toContainText('월 후반 2026-09-16 ~ 2026-09-30 · 0건')
    await expect(half).toContainText('기록 없음')
    await expect(half.locator('.monthly-composition-bar').nth(1)).toHaveAttribute('style', /width: 0%/)
    const day = page.getByRole('button', { name: '2026-09-02, 기록 1건', exact: true })
    await day.focus()
    await expect(page.locator('.monthly-count-detail')).toContainText('평온 · 1건 · 100.0%')
    await page.keyboard.press('Enter')
    await expect(day).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.monthly-count-day')).toHaveCount(30)
  })

  test('CBT만 있는 달은 감정 기록 없음과 완료 CBT를 구분한다', async ({ page }) => {
    await mockApi(page, (_request, url) => url.pathname === '/api/reports/monthly'
      ? { body: monthlyReport('2026-09', { recordCount: 0, dominantEmotionCode: null, averageIntensity: null, emotionComposition: monthlyComposition('2026-09', []) }) } : undefined)
    await page.goto('/reports/monthly')
    await expect(page.getByText('완료 CBT', { exact: true }).locator('..')).toContainText('1회')
    await expect(page.getByText('기록 횟수', { exact: true }).locator('..')).toContainText('0회')
    await expect(page.locator('.monthly-composition-bar').first()).toHaveAttribute('style', /width: 0%/)
  })

  test('31일·긴 직접 입력·미입력도 모바일에서 잘리지 않고 감정 그룹 순서를 유지한다', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    const long = '직접 입력한 감정의 이름이 길어도 원래 이름을 그대로 표시해야 합니다'
    const entries = [null, long, 'CALM', 'ANGER', 'JOY', 'ANXIETY', 'OTHER', '짜증'].map(emotion => ({ day: 1, emotion, context: null }))
    await mockApi(page, (_request, url) => url.pathname === '/api/reports/monthly'
      ? { body: monthlyReport('2026-08', { recordCount: entries.length, emotionComposition: monthlyComposition('2026-08', entries) }) } : undefined)
    await page.goto('/reports/monthly')
    await expect(page.locator('.monthly-count-day')).toHaveCount(31)
    const legend = page.locator('section[aria-labelledby="monthly-distributions-title"] .monthly-composition-legend').first()
    const labels = await legend.locator('li span').allTextContents()
    expect(labels.map(s => s.split(' · ')[0])).toEqual(['불안', '분노', '짜증', '기쁨', '평온', '기타', long, '감정 미입력'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.getByLabel('리포트 기간', { exact: true }).fill('2026-08')
    await expect(page.getByRole('heading', { name: '2026년 8월 요약' })).toBeVisible()
    await expect(page.locator('.monthly-count-day')).toHaveCount(31)
    await page.screenshot({ path: '../artifacts/monthly-report/monthly-mobile.png', fullPage: true })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.screenshot({ path: '../artifacts/monthly-report/monthly-desktop.png', fullPage: true })
  })

  test('이전 월의 늦은 새로고침 응답이 현재 월을 덮어쓰지 않는다', async ({ page }) => {
    let release: () => void = () => {}
    const pending = new Promise<void>(resolve => { release = resolve })
    let started: () => void = () => {}
    const requested = new Promise<void>(resolve => { started = resolve })
    await mockApi(page, async (request, url) => {
      if (url.pathname !== '/api/reports/monthly') return
      const month = url.searchParams.get('month')!
      if (request.method() === 'POST') { started(); await pending; return { body: monthlyReport(month, { recordCount: 99 }) } }
      return { body: monthlyReport(month) }
    })
    await page.goto('/reports/monthly')
    await page.getByRole('button', { name: '최신 기록으로 다시 만들기' }).click()
    await requested
    await page.getByLabel('리포트 기간', { exact: true }).fill('2026-08')
    await expect(page.getByRole('heading', { name: '2026년 8월 요약' })).toBeVisible()
    release()
    await expect(page.getByText('기록 횟수', { exact: true }).locator('..')).toContainText('3회')
    await expect(page.locator('.monthly-count-day')).toHaveCount(31)
  })

  test('성공: 선택한 달의 PDF를 다운로드한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return { body: monthlyReport(url.searchParams.get('month') ?? '2026-09') }
      }
      if (url.pathname === '/api/reports/monthly/pdf') {
        return { body: '%PDF-1.4 monthly', contentType: 'application/pdf' }
      }
    })
    await page.goto('/reports/monthly')
    const selectedMonth = await page.getByLabel('리포트 기간', { exact: true }).inputValue()
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '월간 리포트 PDF 저장' }).click()
    expect((await downloadPromise).suggestedFilename()).toBe(`mindot-monthly-report-${selectedMonth}.pdf`)
  })

  test('오류: 월간 PDF가 없으면 생성 안내를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return { body: monthlyReport(url.searchParams.get('month') ?? '2026-09') }
      }
      if (url.pathname === '/api/reports/monthly/pdf') return { status: 404, body: {} }
    })
    await page.goto('/reports/monthly')
    await page.getByRole('button', { name: '월간 리포트 PDF 저장' }).click()
    await expect(page.getByRole('alert')).toContainText('먼저 선택한 달의 월간 리포트를 생성해 주세요.')
  })

  test('오류: 월간 PDF 연결 실패를 화면에 안내한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return { body: monthlyReport(url.searchParams.get('month') ?? '2026-09') }
      }
      if (url.pathname === '/api/reports/monthly/pdf') return 'abort'
    })
    await page.goto('/reports/monthly')
    await page.getByRole('button', { name: '월간 리포트 PDF 저장' }).click()
    await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없습니다.')
  })
})
