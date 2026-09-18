import { expect, test, type Page } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  openReflection,
  useAuthenticatedSession,
} from './support'

type DailyCareOptions = {
  recentRecordsFail?: boolean
  records?: Record<string, unknown>[]
  patterns?: Record<string, unknown>[]
  reflections?: Record<string, unknown>[]
}

async function mockDailyCare(page: Page, {
  recentRecordsFail = false,
  records = [completeRecord()],
  patterns = [{
    emotionCode: 'ANXIETY',
    weekday: 'MONDAY',
    timeBucket: 'MORNING',
    occurrenceCount: 4,
  }],
  reflections = [],
}: DailyCareOptions = {}) {
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/records') {
      if (recentRecordsFail && url.searchParams.get('period') === 'RECENT_7_DAYS') return 'abort'
      return {
        body: {
          content: records,
          totalElements: records.length,
          totalPages: records.length ? 1 : 0,
          page: 0,
          size: Math.max(1, records.length),
        },
      }
    }
    if (url.pathname === '/api/patterns/recent') {
      return { body: patterns }
    }
    if (url.pathname === '/api/reflections/open') return { body: reflections }
  })
}

test.describe('FE-AUTO-020: 마음 돌봄 추천', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('성공: 반복 패턴을 우선 반영한 추천을 표시한다', async ({ page }) => {
    await mockDailyCare(page)
    await page.goto('/daily-care')
    await expect(page.getByText('최근 8주 반복 패턴 기반')).toBeVisible()
    await expect(page.getByText('3분 호흡으로 반복되는 긴장을 천천히 낮춰 보세요.')).toBeVisible()
  })

  test('오류: 일부 API 실패를 안내하면서 가능한 추천은 유지한다', async ({ page }) => {
    await mockDailyCare(page, { recentRecordsFail: true })
    await page.goto('/daily-care')
    await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없습니다')
    await expect(page.getByText('3분 호흡으로 반복되는 긴장을 천천히 낮춰 보세요.')).toBeVisible()
  })

  test('성공: 반복 패턴이 없으면 진행 중 CBT를 우선 추천한다', async ({ page }) => {
    await mockDailyCare(page, {
      patterns: [],
      reflections: [openReflection({ rawText: '이어갈 CBT 기록' })],
    })
    await page.goto('/daily-care')
    await expect(page.getByText('진행 중 CBT 기반')).toBeVisible()
    await expect(page.getByText('멈춰 둔 CBT 성찰을 이어가 보세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '이어서 하기' })).toBeVisible()
  })

  test('성공: 패턴과 진행 중 CBT가 없으면 최근 감정에 맞춰 추천한다', async ({ page }) => {
    await mockDailyCare(page, { patterns: [], reflections: [] })
    await page.goto('/daily-care')
    await expect(page.getByText('최근 감정 기록 기반')).toBeVisible()
    await expect(page.getByText('3분 호흡으로 긴장을 천천히 낮춰 보세요.')).toBeVisible()
  })

  test('경계: 사용할 기록이 없으면 첫 기록을 위한 기본 추천을 표시한다', async ({ page }) => {
    await mockDailyCare(page, { records: [], patterns: [], reflections: [] })
    await page.goto('/daily-care')
    await expect(page.getByText('기본 추천')).toBeVisible()
    await expect(page.getByText('오늘의 마음을 짧게 기록해 보세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '첫 감정 기록하기' })).toBeVisible()
  })

  test('성공: 추천 피드백을 저장하고 새로고침 후에도 선택을 유지한다', async ({ page }) => {
    await mockDailyCare(page)
    await page.goto('/daily-care')
    const helpfulFeedback = page.getByRole('button', { name: '도움됨' })
    await expect(helpfulFeedback).toBeVisible()
    await helpfulFeedback.click()
    await page.reload()
    await expect(page.getByRole('button', { name: '도움됨', pressed: true })).toBeVisible()
  })

  test('성공: 나중에 피드백도 새로고침 후 같은 브라우저에서 유지한다', async ({ page }) => {
    await mockDailyCare(page)
    await page.goto('/daily-care')
    await page.getByRole('button', { name: '나중에' }).click()
    await expect(page.getByRole('status')).toHaveText('나중에 다시 볼 추천으로 기억했습니다.')
    await page.reload()
    await expect(page.getByRole('button', { name: '나중에', pressed: true })).toBeVisible()
  })
})
