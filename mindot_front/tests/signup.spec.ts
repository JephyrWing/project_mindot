import { expect, test, type Page } from '@playwright/test'
import { mockApi, useGuestSession } from './support'

async function fillValidSignup(page: Page, includeConsents = true) {
  await page.getByLabel('이메일').fill('tester@example.com')
  await page.getByLabel('비밀번호', { exact: true }).fill('Mindot!123')
  await page.getByLabel('비밀번호 확인').fill('Mindot!123')
  await page.getByRole('button', { name: '일치 여부 확인' }).click()
  await page.getByLabel('닉네임').fill('테스터')
  if (includeConsents) {
    await page.getByLabel('이용약관 동의').check()
    await page.getByLabel('개인정보 처리 동의').check()
    await page.getByLabel('AI 분석 동의').check()
  }
}

test.describe('FE-AUTO-003: 회원가입', () => {
  test.beforeEach(async ({ page }) => {
    await useGuestSession(page)
  })

  test('경계: 잘못된 이메일 형식은 가입을 차단한다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/signup')
    await page.getByLabel('이메일').fill('잘못된이메일')
    await page.getByLabel('이메일').blur()
    await expect(page.getByText('올바른 이메일 형식으로 입력해 주세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '가입하기' })).toBeDisabled()
  })

  test('경계: 필수 동의가 없으면 가입 버튼을 활성화하지 않는다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/signup')
    await fillValidSignup(page, false)
    await expect(page.getByRole('button', { name: '가입하기' })).toBeDisabled()
  })

  test('오류: 중복 이메일을 별도 메시지로 안내한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/signup'
        ? { status: 409, body: { message: 'duplicate' } }
        : undefined
    ))
    await page.goto('/signup')
    await fillValidSignup(page)
    await page.getByRole('button', { name: '가입하기' }).click()
    await expect(page.getByRole('alert')).toHaveText('이미 사용 중인 이메일입니다.')
  })

  test('오류: 네트워크 연결 실패를 안내하고 입력을 유지한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/signup' ? 'abort' : undefined
    ))
    await page.goto('/signup')
    await fillValidSignup(page)
    await page.getByRole('button', { name: '가입하기' }).click()
    await expect(page.getByRole('alert')).toHaveText('서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
    await expect(page.getByLabel('이메일')).toHaveValue('tester@example.com')
  })

  test('성공: 정상 가입 후 로그인 화면으로 이동한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/signup'
        ? { status: 201, body: { userId: 1 } }
        : undefined
    ))
    await page.goto('/signup')
    await fillValidSignup(page)
    await page.getByRole('button', { name: '가입하기' }).click()
    await expect(page).toHaveURL('/login')
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
  })
})
