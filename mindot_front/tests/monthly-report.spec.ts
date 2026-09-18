import { expect, test } from '@playwright/test'
import { mockApi, monthlyReport, useAuthenticatedSession } from './support'

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
    await expect(page.getByText('후반 강도 낮아짐')).toBeVisible()
    await expect(page.getByRole('heading', { name: '날짜별 감정 강도' })).toBeVisible()
    await expect(page.getByText('불안 감정과 발표·시험 상황이 자주 기록되었습니다.')).toBeVisible()
  })

  test('성공: 월 후반 강도가 높아진 변화 상태를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return {
          body: monthlyReport(url.searchParams.get('month') ?? '2026-09', {
            firstHalfAverageIntensity: 3,
            secondHalfAverageIntensity: 7,
            intensityTrend: 'INCREASED',
          }),
        }
      }
    })
    await page.goto('/reports/monthly')
    await expect(page.getByText('후반 강도 높아짐')).toBeVisible()
    await expect(page.getByText('월 초반보다 후반에 기록한 감정의 평균 강도가 높아졌습니다.')).toBeVisible()
  })

  test('경계: 초반과 후반 강도가 비슷한 상태를 구분해 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return { body: monthlyReport(url.searchParams.get('month') ?? '2026-09', { intensityTrend: 'STABLE' }) }
      }
    })
    await page.goto('/reports/monthly')
    await expect(page.getByText('비슷한 흐름')).toBeVisible()
  })

  test('경계: 비교 자료가 부족한 변화 상태를 별도로 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/monthly' && request.method() === 'GET') {
        return {
          body: monthlyReport(url.searchParams.get('month') ?? '2026-09', {
            firstHalfAverageIntensity: null,
            secondHalfAverageIntensity: null,
            intensityTrend: 'INSUFFICIENT_DATA',
          }),
        }
      }
    })
    await page.goto('/reports/monthly')
    await expect(page.getByText('비교 자료 부족')).toBeVisible()
    await expect(page.getByText('월 초반과 후반을 비교하려면 감정 강도 기록이 더 필요합니다.')).toBeVisible()
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
