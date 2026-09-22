import { expect, test } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  useAuthenticatedSession,
  useGuestSession,
} from './support'

test.describe('FE-AUTO-001: 라우팅·권한', () => {
  test('성공: 비로그인 사용자도 공개 화면에 직접 접근한다', async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)
    await page.goto('/centers')
    await expect(page.getByRole('heading', { name: '관련 기관 찾기' })).toBeVisible()
  })

  test('성공: 비로그인 사용자도 서비스·정책 페이지에 직접 접근한다', async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)
    await page.goto('/about/research')
    await expect(page).toHaveURL('/about/research')
    await expect(page.getByRole('heading', { name: '연구 근거·AI 해석 한계' })).toBeVisible()
  })

  test('오류: 비로그인 사용자의 보호 화면 접근을 차단한다', async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)
    await page.goto('/records')
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('dialog', { name: '로그인이 필요한 서비스입니다' })).toBeVisible()
  })

  test('경계: 존재하지 않는 URL은 메인 화면으로 복귀한다', async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)
    await page.goto('/not-a-real-route')
    await expect(page.getByRole('heading', { name: '오늘의 마음은 어떤가요?' })).toBeVisible()
  })

  test('성공: 로그인 사용자의 보호 URL 직접 접근과 새로고침을 복원한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page)
    await page.goto('/records')
    await expect(page).toHaveURL('/records')
    await expect(page.getByRole('heading', { name: '감정 기록 목록' })).toBeVisible()
    await page.reload()
    await expect(page).toHaveURL('/records')
    await expect(page.getByRole('heading', { name: '감정 기록 목록' })).toBeVisible()
  })
})

test.describe('FE-AUTO-002: 라우팅', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records') {
        return {
          body: {
            content: [completeRecord()],
            page: 0,
            size: 3,
            totalElements: 1,
            totalPages: 1,
          },
        }
      }
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: completeRecord() }
      }
    })
  })

  test('성공: 뒤로 이동하면 이전 목록 화면과 데이터가 복원된다', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '감정 기록 목록' }).click()
    await expect(page).toHaveURL('/records')
    await page.getByRole('button', { name: '불안 감정 기록 상세 보기' }).click()
    await expect(page).toHaveURL('/records/1')
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()

    await page.goBack()
    await expect(page).toHaveURL('/records')
    await expect(page.getByRole('button', { name: '불안 감정 기록 상세 보기' })).toBeVisible()
  })

  test('성공: 앞으로 이동하면 상세 URL과 데이터가 복원된다', async ({ page }) => {
    await page.goto('/records')
    await page.getByRole('button', { name: '불안 감정 기록 상세 보기' }).click()
    await page.goBack()
    await page.goForward()
    await expect(page).toHaveURL('/records/1')
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
  })

  test('경계: 뒤로·앞으로 이동을 반복해도 같은 상세 식별자를 유지한다', async ({ page }) => {
    await page.goto('/records')
    await page.getByRole('button', { name: '불안 감정 기록 상세 보기' }).click()

    for (let index = 0; index < 2; index += 1) {
      await page.goBack()
      await expect(page).toHaveURL('/records')
      await page.goForward()
      await expect(page).toHaveURL('/records/1')
      await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
    }
  })

  test('오류: 앞으로 이동한 상세가 사라지면 오류 화면에서 목록으로 복귀한다', async ({ page }) => {
    let detailCalls = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records' && request.method() === 'GET') {
        return {
          body: {
            content: [completeRecord()],
            page: 0,
            size: 3,
            totalElements: 1,
            totalPages: 1,
          },
        }
      }
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        detailCalls += 1
        return detailCalls <= 2
          ? { body: completeRecord() }
          : { status: 404, body: {} }
      }
    })

    await page.goto('/records')
    await page.getByRole('button', { name: '불안 감정 기록 상세 보기' }).click()
    await page.goBack()
    await page.goForward()
    await expect(page.getByRole('alert')).toContainText('선택한 감정 기록을 찾을 수 없습니다.')
    await page.goBack()
    await expect(page.getByRole('heading', { name: '감정 기록 목록' })).toBeVisible()
  })
})
