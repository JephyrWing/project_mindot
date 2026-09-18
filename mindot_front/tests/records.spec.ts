import { expect, test } from '@playwright/test'
import {
  mockApi,
  useAuthenticatedSession,
} from './support'

const savedRecord = {
  recordId: 7,
  rawText: '오늘은 조금 불안했다.',
  occurredAt: '2026-09-18T01:00:00Z',
  timeBucket: 'MORNING',
  weekdayType: 'WEEKDAY',
  analysisStatus: 'PENDING',
}

test.describe('FE-AUTO-007: 감정 기록', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('경계: 빈 입력은 저장하지 않고 화면에서 안내한다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toHaveText('지금의 감정을 입력해 주세요.')
  })

  test('경계: 1000자는 저장하고 1001번째 문자는 입력되지 않는다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/quick'
        ? { status: 201, body: savedRecord }
        : undefined
    ))
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await expect(input).toHaveAttribute('maxlength', '1000')
    await input.fill('가'.repeat(1001))
    await expect(input).toHaveValue('가'.repeat(1000))
    await expect(page.getByText('1000/1000')).toBeVisible()
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('heading', { name: '기록 완료' })).toBeVisible()
  })

  test('오류: 네트워크 실패 시 작성 중 입력을 보존한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/quick' ? 'abort' : undefined
    ))
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('오늘은 조금 불안했다.')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없습니다')
    await expect(input).toHaveValue('오늘은 조금 불안했다.')
  })

  test('성공: 응답 유실 후 재시도하면 기록 완료 화면으로 복구한다', async ({ page }) => {
    let firstAttempt = true
    await mockApi(page, (_request, url) => {
      if (url.pathname !== '/api/records/quick') return undefined
      if (firstAttempt) {
        firstAttempt = false
        return 'abort'
      }
      return { status: 201, body: savedRecord }
    })
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('오늘은 조금 불안했다.')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없습니다')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('heading', { name: '기록 완료' })).toBeVisible()
  })

  test('성공: 연속 제출은 하나의 완료 화면만 만들고 새 기록은 입력을 초기화한다', async ({ page }) => {
    let alreadySaved = false
    await mockApi(page, async (_request, url) => {
      if (url.pathname !== '/api/records/quick') return undefined
      if (alreadySaved) return { status: 409, body: { message: '이미 저장된 기록입니다.' } }
      alreadySaved = true
      await new Promise((resolve) => setTimeout(resolve, 80))
      return { status: 201, body: savedRecord }
    })
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('오늘은 조금 불안했다.')
    await page.getByRole('button', { name: '기록하기' }).dblclick()
    await expect(page.getByRole('heading', { name: '기록 완료' })).toBeVisible()
    await page.getByRole('button', { name: '새 기록 작성' }).click()
    await expect(input).toHaveValue('')
    await expect(page.getByRole('status').filter({ hasText: '기록 상태' })).toContainText('작성 전')
  })
})
