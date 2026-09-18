import { expect, test } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  useAuthenticatedSession,
} from './support'

test.describe('FE-AUTO-010: 기록 후속 기능', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('오류: 패턴 근거가 부족하면 이유를 안내한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: completeRecord() }
      if (url.pathname === '/api/records/1/pattern-explanation') return { status: 409, body: {} }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '패턴 설명 요청' }).click()
    await expect(page.getByRole('alert')).toContainText('완료된 CBT 기록이 아직 충분하지 않습니다')
  })

  test('성공: 유사 사례 기반 패턴 설명을 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: completeRecord() }
      if (url.pathname === '/api/records/1/pattern-explanation') {
        return {
          body: {
            similarCaseCount: 2,
            patternSummary: '평가 상황에서 불안과 미래 예측이 반복됩니다.',
            repeatedDistortionCodes: ['CATASTROPHIZING_FORTUNE_TELLING'],
            helpfulAlternativeThought: '실수해도 다시 이어갈 수 있다.',
            recommendation: '발표 전 짧은 호흡을 해 보세요.',
          },
        }
      }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '패턴 설명 요청' }).click()
    await expect(page.getByText('평가 상황에서 불안과 미래 예측이 반복됩니다.')).toBeVisible()
  })

  test('경계: 삭제 확인을 취소하면 기록 상세를 유지한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: completeRecord() }
      if (url.pathname === '/api/records/1' && request.method() === 'DELETE') return { status: 204, body: null }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '기록 삭제하기' }).click()
    await expect(page.getByText('정말 삭제하시겠습니까?')).toBeVisible()
    await page.getByRole('button', { name: '취소' }).click()
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
    await expect(page).toHaveURL('/records/1')
  })

  test('성공: 삭제를 최종 확인하면 목록으로 이동한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: completeRecord() }
      if (url.pathname === '/api/records/1' && request.method() === 'DELETE') return { status: 204, body: null }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '기록 삭제하기' }).click()
    await page.getByRole('button', { name: '삭제 확인' }).click()
    await expect(page).toHaveURL('/records')
  })
})
