import { expect, test, type Page } from '@playwright/test'
import { mockApi, useGuestSession } from './support'

test.describe('FE-AUTO-006: 소셜 로그인', () => {
  test.beforeEach(async ({ page }) => {
    await useGuestSession(page)
  })

  const mockSignupRequiredFlow = async (page: Page) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/auth/oauth/google/authorize') {
        return {
          body: {
            authorizationUrl: '/oauth/google/callback?code=code-1&state=state-1',
          },
        }
      }
      if (url.pathname === '/api/auth/oauth/google') {
        return {
          body: {
            signupRequired: true,
            signupTicket: 'ticket-1',
            email: 'social@example.com',
            displayName: '소셜회원',
          },
        }
      }
      if (url.pathname === '/api/auth/oauth/signup') {
        return {
          body: { accessToken: 'social-token', userRole: 'ROLE_USER' },
        }
      }
    })
  }

  test('성공: Google 인가와 콜백 후 신규 회원 동의를 완료한다', async ({ page }) => {
    await mockSignupRequiredFlow(page)
    await page.goto('/login')
    await page.getByRole('button', { name: 'Google로 계속하기' }).click()
    await expect(page.getByRole('heading', { name: '소셜 회원가입' })).toBeVisible()
    await expect(page.getByText('social@example.com')).toBeVisible()
    await page.getByLabel('이용약관 동의').check()
    await page.getByLabel('개인정보 처리 동의').check()
    await page.getByLabel('AI 분석 동의').check()
    await page.getByRole('button', { name: '동의하고 시작하기' }).click()
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('heading', { name: '오늘의 마음은 어떤가요?' })).toBeVisible()
  })

  test('경계: 신규 소셜 회원의 필수 동의가 없으면 시작할 수 없다', async ({ page }) => {
    await mockSignupRequiredFlow(page)
    await page.goto('/login')
    await page.getByRole('button', { name: 'Google로 계속하기' }).click()
    await expect(page.getByRole('button', { name: '동의하고 시작하기' })).toBeDisabled()
  })

  test('오류: provider 취소를 안내하고 로그인 화면으로 복귀한다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/oauth/google/callback?error=access_denied')
    await expect(page.getByRole('heading', { name: '소셜 로그인을 완료하지 못했습니다' })).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('소셜 로그인이 취소되었습니다')
    await page.getByRole('button', { name: '로그인 화면으로 돌아가기' }).click()
    await expect(page).toHaveURL('/login')
  })

  test('오류: 콜백 API 실패 시 토큰 없이 로그인 복귀 동작을 제공한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/oauth/google'
        ? { status: 500, body: { message: '소셜 로그인 처리 오류' } }
        : undefined
    ))
    await page.goto('/oauth/google/callback?code=bad-code&state=bad-state')
    await expect(page.getByRole('heading', { name: '소셜 로그인을 완료하지 못했습니다' })).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('소셜 로그인 처리 오류')
    await page.getByRole('button', { name: '로그인 화면으로 돌아가기' }).click()
    await expect(page).toHaveURL('/login')
  })
})
