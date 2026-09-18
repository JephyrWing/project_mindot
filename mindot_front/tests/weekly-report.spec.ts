import { expect, test } from '@playwright/test'
import { completeRecord, mockApi, useAuthenticatedSession, weeklyReport } from './support'

test.describe('FE-AUTO-016: 주간 리포트', () => {
  test.beforeEach(async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    await useAuthenticatedSession(page)
  })

  test('성공: 미생성된 현재 주 리포트를 자동 생성해 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { status: 404, body: {} }
      }
      if (url.pathname === '/api/reports/weekly' && request.method() === 'POST') {
        return { body: weeklyReport() }
      }
    })

    await page.goto('/reports/weekly')
    await expect(page.getByText('기록 횟수').locator('..')).toContainText('2회')
    await expect(page.locator('.weekly-report-period strong')).toHaveText('2026년 9월 14일 ~ 2026년 9월 20일')
  })

  test('경계: 현재 주에서는 다음 주 이동을 허용하지 않는다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
    })

    await page.goto('/reports/weekly')
    await expect(page.getByRole('button', { name: '다음 주 →' })).toBeDisabled()
  })

  test('오류: 기록이 없는 이전 주는 빈 상태로 구분한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return url.searchParams.get('weekStart') === '2026-09-14'
          ? { body: weeklyReport() }
          : { status: 404, body: {} }
      }
      if (url.pathname === '/api/reports/weekly' && request.method() === 'POST') {
        return { status: 409, body: {} }
      }
    })

    await page.goto('/reports/weekly')
    await page.getByRole('button', { name: '← 이전 주' }).click()
    await expect(page.getByText('선택한 주에 감정 기록이 없어 아직 리포트를 만들 수 없습니다.')).toBeVisible()
    await expect(page.getByRole('button', { name: '다음 주 →' })).toBeEnabled()
  })
})

test.describe('FE-AUTO-017: 주간 분석', () => {
  test.beforeEach(async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    await useAuthenticatedSession(page)
  })

  test('성공: 통계와 반복 패턴을 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
    })

    await page.goto('/reports/weekly')
    await expect(page.getByText('주요 감정').locator('..')).toContainText('불안')
    await expect(page.getByText('파국화·미래예측: 1회')).toBeVisible()
    await expect(page.getByText('2주 이상 반복')).toBeVisible()
  })

  test('성공: 요일 근거 기록을 열고 주간 리포트로 돌아온다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
      if (url.pathname === '/api/records/1') return { body: completeRecord() }
    })

    await page.goto('/reports/weekly')
    await page.getByRole('button', { name: /화요일 감정 강도 평균 7점/ }).click()
    await expect(page.getByText('선택 요일').locator('..')).toContainText('화요일')
    await page.getByRole('button', { name: '기록 상세 보기' }).click()
    await expect(page).toHaveURL('/records/1')
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
    await page.getByRole('button', { name: '주간 리포트로 돌아가기' }).click()
    await expect(page).toHaveURL('/reports/weekly')
  })

  test('성공: 최신 기록으로 갱신한 분석 결과를 화면에 반영한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
      if (url.pathname === '/api/reports/weekly' && request.method() === 'POST') {
        return { body: weeklyReport({ recordCount: 3, averageIntensity: 5.7 }) }
      }
    })

    await page.goto('/reports/weekly')
    await page.getByRole('button', { name: '최신 기록으로 다시 만들기' }).click()
    await expect(page.getByText('기록 횟수').locator('..')).toContainText('3회')
  })

  test('경계: 반복 패턴과 근거가 없는 분석은 각각 빈 상태로 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return {
          body: weeklyReport({
            repeatedPatterns: [],
            emotionRecordEvidences: [],
            completedCbtEvidences: [],
            distortionChangeCounts: { CONFIRMED_INSIGHT: {} },
          }),
        }
      }
    })

    await page.goto('/reports/weekly')
    await expect(page.getByText('반복 기준을 충족한 감정 패턴이 아직 없습니다.')).toBeVisible()
    await expect(page.getByText('리포트에 연결된 감정 기록이 없습니다.')).toBeVisible()
    await expect(page.getByText('선택한 주에 완료한 CBT 성찰이 없습니다.')).toBeVisible()
  })

  test('오류: 분석 조회 실패 후 다시 불러오면 저장된 분석을 표시한다', async ({ page }) => {
    let reportCalls = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        reportCalls += 1
        return reportCalls <= 2
          ? { status: 500, body: { message: '주간 분석 조회 실패' } }
          : { body: weeklyReport() }
      }
    })

    await page.goto('/reports/weekly')
    await expect(page.getByRole('alert')).toContainText('주간 분석 조회 실패')
    await page.getByRole('button', { name: '다시 불러오기' }).click()
    await expect(page.getByText('주요 감정').locator('..')).toContainText('불안')
  })
})
