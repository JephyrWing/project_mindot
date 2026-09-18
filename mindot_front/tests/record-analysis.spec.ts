import { expect, test } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  readJsonBody,
  useAuthenticatedSession,
} from './support'

const quickRecord = completeRecord({
  completionStatus: 'QUICK',
  analysisStatus: 'FAILED',
  automaticThought: null,
  primaryEmotionCode: null,
  primaryIntensity: null,
})

const partialRecord = completeRecord({ completionStatus: 'PARTIAL' })

test.describe('FE-AUTO-009: 감정 분석 편집', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('경계: 미래 발생 시각은 저장하지 않고 기존 상세를 유지한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: quickRecord }
      }
    })
    await page.goto('/records/1')
    const occurredAt = page.getByLabel('날짜와 시간')
    await occurredAt.fill('2999-01-01T12:00')
    await page.getByRole('button', { name: '시각 수정하기' }).click()
    await expect(page.getByText('감정 발생 시각을 수정했습니다.')).toHaveCount(0)
    await expect(page.getByRole('time')).toContainText('2026년 9월 15일')
  })

  test('성공: 과거 발생 시각을 저장하고 화면 시간을 갱신한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: quickRecord }
      if (url.pathname === '/api/records/1' && request.method() === 'PATCH') {
        return { body: { ...quickRecord, occurredAt: readJsonBody(request).occurredAt } }
      }
    })
    await page.goto('/records/1')
    const occurredAt = page.getByLabel('날짜와 시간')
    await occurredAt.fill('2020-01-01T12:00')
    await page.getByRole('button', { name: '시각 수정하기' }).click()
    await expect(page.getByRole('status')).toContainText('감정 발생 시각을 수정했습니다.')
    await expect(page.getByRole('time')).toContainText('2020년 1월 1일')
  })

  test('성공: 실패한 AI 분석을 다시 요청해 편집 가능한 제안을 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: quickRecord }
      if (url.pathname === '/api/records/1/reanalyze') return { body: partialRecord }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '다시 분석하기' }).click()
    await expect(page.getByText('AI 재분석을 완료했습니다. 제안된 내용을 확인해 주세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
  })

  test('오류: AI 재분석 서버 장애를 별도 안내한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: quickRecord }
      if (url.pathname === '/api/records/1/reanalyze') return { status: 503, body: {} }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '다시 분석하기' }).click()
    await expect(page.getByRole('alert')).toContainText('AI 분석 서버가 일시적으로 응답하지 않습니다')
  })

  test('경계: 범위를 벗어난 대표 감정 강도는 확정하지 않는다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: partialRecord }
      if (url.pathname === '/api/records/1/confirm') return { body: completeRecord() }
    })
    await page.goto('/records/1')
    const primaryIntensity = page.getByLabel('대표 감정 강도')
    await primaryIntensity.fill('11')
    await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
    await expect(page.getByText('사용자 확정값')).toHaveCount(0)
  })

  test('성공: 유효한 분석값을 확정해 COMPLETE 결과를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: partialRecord }
      if (url.pathname === '/api/records/1/confirm') return { body: completeRecord() }
    })
    await page.goto('/records/1')
    const primaryIntensity = page.getByLabel('대표 감정 강도')
    await primaryIntensity.fill('8')
    await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
    await expect(page.getByText('수정한 분석 결과를 최종 확정했습니다.')).toBeVisible()
    await expect(page.getByText('사용자 확정값')).toBeVisible()
  })
})
