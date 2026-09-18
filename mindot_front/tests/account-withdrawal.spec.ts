import { expect, test, type Page } from '@playwright/test'
import { mockApi, useAuthenticatedSession } from './support'

test.describe('FE-AUTO-024: 회원 탈퇴', () => {
  const prepareSettings = async (page: Page, withdrawalResult: 'error' | 'success') => {
    await useAuthenticatedSession(page)

    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/users/me' && request.method() === 'GET') {
        return { body: { email: 'tester@example.com', displayName: '테스터' } }
      }
      if (url.pathname === '/api/users/me' && request.method() === 'DELETE') {
        return withdrawalResult === 'error'
          ? { status: 500, body: { message: '탈퇴 처리 오류' } }
          : { status: 204, body: null }
      }
      if (url.pathname === '/api/notifications/preferences') {
        return { body: { patternAlertEnabled: false, preferredTime: '09:00:00' } }
      }
      if (url.pathname === '/api/consents') {
        return { body: [{ consentType: 'AI_ANALYSIS', granted: true, changeable: true }] }
      }
    })
    await page.goto('/settings')
  }

  test('경계: 최종 확인을 취소하면 설정 화면과 계정이 유지된다', async ({ page }) => {
    await prepareSettings(page, 'success')
    await page.getByRole('button', { name: '탈퇴하기' }).click()
    await page.getByRole('button', { name: '취소' }).click()
    await expect(page).toHaveURL('/settings')
    await expect(page.getByRole('button', { name: '탈퇴하기' })).toBeVisible()
  })

  test('오류: 탈퇴 API 실패를 안내하고 설정 화면을 유지한다', async ({ page }) => {
    await prepareSettings(page, 'error')
    await page.getByRole('button', { name: '탈퇴하기' }).click()
    await page.getByRole('button', { name: '회원 탈퇴' }).click()
    await expect(page.getByRole('alert')).toHaveText('탈퇴 처리 오류')
    await expect(page).toHaveURL('/settings')
  })

  test('성공: 탈퇴 후 메인으로 이동하고 비로그인 메뉴가 표시된다', async ({ page }) => {
    await prepareSettings(page, 'success')
    await page.getByRole('button', { name: '탈퇴하기' }).click()
    await page.getByRole('button', { name: '회원 탈퇴' }).click()
    await expect(page).toHaveURL('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()
  })
})
