import { expect, test } from '@playwright/test'
import {
  mockApi,
  readJsonBody,
  useAuthenticatedSession,
  useGuestSession,
} from './support'

const patternSummary = {
  patternId: 31,
  emotionCode: 'ANXIETY',
  weekday: 'MONDAY',
  timeBucket: 'MORNING',
  patternLevel: 'SUSTAINED',
  occurrenceCount: 4,
  distinctDateCount: 3,
  observedWeekCount: 3,
  windowStart: '2026-07-29',
  windowEnd: '2026-09-22',
  feedback: null,
}

const patternDetail = {
  ...patternSummary,
  evidenceRecords: [
    {
      emotionRecordId: 101,
      occurredAt: '2026-09-21T00:30:00Z',
      primaryEmotionCode: 'ANXIETY',
      primaryIntensity: 7,
      situationText: '주간 회의를 준비하던 중',
      rawText: '회의 자료를 다시 보다가 긴장감이 커졌다.',
    },
    {
      emotionRecordId: 92,
      occurredAt: '2026-09-14T00:10:00Z',
      primaryEmotionCode: 'ANXIETY',
      primaryIntensity: 6,
      situationText: '업무를 시작하기 전',
      rawText: '오늘 할 일을 생각하니 마음이 조급했다.',
    },
  ],
}

test.describe('FE-AUTO-031: 반복 패턴 목록·상세·피드백', () => {
  test('성공: 목록에서 집계 근거를 확인하고 상세 화면으로 이동한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/patterns' && request.method() === 'GET') {
        return { body: [patternSummary] }
      }
      if (url.pathname === '/api/patterns/31' && request.method() === 'GET') {
        return { body: patternDetail }
      }
    })

    await page.goto('/insights/patterns')

    await expect(page.getByRole('heading', { name: '반복 패턴' })).toBeVisible()
    await expect(page.getByText('확인된 패턴 1개')).toBeVisible()
    await expect(page.getByRole('heading', { name: '월요일 아침에 불안 감정이 반복됐어요' })).toBeVisible()
    await expect(page.getByText('4회', { exact: true })).toBeVisible()
    await expect(page.getByText('3일', { exact: true })).toBeVisible()
    await expect(page.getByText('3주', { exact: true })).toBeVisible()

    await page.getByRole('button', { name: /월요일 아침에 불안 감정이 반복됐어요 상세 보기/ }).click()

    await expect(page).toHaveURL('/insights/patterns/31')
    await expect(page.getByRole('heading', { name: '근거 기록' })).toBeVisible()
    await expect(page.getByText('기록 #101')).toBeVisible()
    await expect(page.getByText('주간 회의를 준비하던 중')).toBeVisible()
    await expect(page.getByText('회의 자료를 다시 보다가 긴장감이 커졌다.')).toBeVisible()
    await expect(page.getByText(/원인이나 진단을 의미하지 않습니다/)).toBeVisible()
  })

  test('성공: 도움 여부를 서버에 저장하고 다시 선택해 변경한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const submittedFeedback: unknown[] = []
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/patterns/31' && request.method() === 'GET') {
        return { body: patternDetail }
      }
      if (
        url.pathname === '/api/patterns/31/feedback'
        && request.method() === 'POST'
      ) {
        const requestBody = readJsonBody(request)
        submittedFeedback.push(requestBody)
        return {
          body: {
            patternId: 31,
            feedback: (requestBody as { feedback: string }).feedback,
            feedbackAt: '2026-09-22T09:00:00Z',
          },
        }
      }
    })

    await page.goto('/insights/patterns/31')
    const helpfulButton = page.getByRole('button', { name: '도움됐어요' })
    const notHelpfulButton = page.getByRole('button', { name: '도움되지 않았어요' })

    await helpfulButton.click()
    await expect(helpfulButton).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByText('피드백을 저장했습니다.')).toBeVisible()

    await notHelpfulButton.click()
    await expect(notHelpfulButton).toHaveAttribute('aria-pressed', 'true')
    expect(submittedFeedback).toEqual([
      { feedback: 'HELPFUL' },
      { feedback: 'NOT_HELPFUL' },
    ])
  })

  test('빈 상태와 조회 오류에서 안내 및 재시도를 제공한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    let shouldFail = false
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/patterns' && request.method() === 'GET') {
        if (shouldFail) return { status: 500, body: {} }
        return { body: [] }
      }
    })

    await page.goto('/insights/patterns')
    await expect(page.getByText('아직 확인된 반복 패턴이 없습니다.')).toBeVisible()

    shouldFail = true
    await page.reload()
    await expect(page.getByRole('alert')).toContainText('반복 패턴을 불러오지 못했습니다.')

    shouldFail = false
    await page.getByRole('button', { name: '다시 시도' }).click()
    await expect(page.getByText('아직 확인된 반복 패턴이 없습니다.')).toBeVisible()
  })

  test('사이드바 이동과 휴대전화 반응형 화면을 지원한다', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/patterns' && request.method() === 'GET') {
        return { body: [patternSummary] }
      }
    })

    await page.goto('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await page.getByRole('link', { name: '반복 패턴' }).click()
    await expect(page).toHaveURL('/insights/patterns')
    await expect(page.getByRole('heading', { name: '반복 패턴' })).toBeVisible()

    const viewportWidths = await page.evaluate(() => ({
      content: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }))
    expect(viewportWidths.content).toBeLessThanOrEqual(viewportWidths.viewport)
  })

  test('권한: 비로그인 사용자의 목록·상세 직접 접근을 차단한다', async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)

    await page.goto('/insights/patterns/31')

    await expect(page).toHaveURL('/')
    await expect(page.getByRole('dialog', { name: '로그인이 필요한 서비스입니다' })).toBeVisible()
  })
})
