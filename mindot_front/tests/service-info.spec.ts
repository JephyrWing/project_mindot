import { expect, test } from '@playwright/test'
import { mockApi, useGuestSession } from './support'

const publicPages = [
  { path: '/about', heading: 'MINDOT 소개' },
  { path: '/terms', heading: '이용약관' },
  { path: '/privacy', heading: '개인정보 처리방침' },
  { path: '/about/research', heading: '연구 근거·AI 해석 한계' },
]

test.describe('FE-AUTO-031: 서비스·정책 페이지', () => {
  test.beforeEach(async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)
  })

  for (const infoPage of publicPages) {
    test(`성공: 비로그인 사용자가 ${infoPage.heading} 페이지에 직접 접근한다`, async ({ page }) => {
      await page.goto(infoPage.path)
      await expect(page).toHaveURL(infoPage.path)
      await expect(page.getByRole('heading', { level: 1, name: infoPage.heading })).toBeVisible()
      await expect(page.getByRole('navigation', { name: '서비스·정책 안내' })).toBeVisible()
    })
  }

  test('성공: 개인정보 보유 기간과 원본 음성 파기 및 탈퇴 삭제를 안내한다', async ({ page }) => {
    await page.goto('/privacy')
    await expect(page.getByRole('cell', { name: '회원 탈퇴 시까지', exact: true }).first()).toBeVisible()
    await expect(page.getByRole('cell', { name: '변환 처리 후 원본 음성은 저장하지 않음' })).toBeVisible()
    await expect(page.getByText(/계정과 사용자 소유 기록을 삭제하고 로그인 세션을 폐기합니다/)).toBeVisible()
  })

  test('성공: 연구 근거와 AI 한계를 함께 표시하고 공식 참고 자료를 연결한다', async ({ page }) => {
    await page.goto('/about/research')
    await expect(page.getByText(/MINDOT 자체의 임상적 효과나 진단 정확성을 입증하는 것은 아닙니다/)).toBeVisible()
    await expect(page.getByRole('link', { name: /미국 국립정신건강연구소/ })).toHaveAttribute('href', /nimh\.nih\.gov/)
    await expect(page.getByRole('link', { name: /세계보건기구\(WHO\).*Ethics and governance/ })).toHaveAttribute('href', /who\.int/)
  })

  test('성공: 문서 탭을 선택하면 새로고침 없이 주소와 내용을 바꾼다', async ({ page }) => {
    await page.goto('/about')
    await page.getByRole('navigation', { name: '서비스·정책 안내' })
      .getByRole('link', { name: '개인정보 처리방침' })
      .click()
    await expect(page).toHaveURL('/privacy')
    await expect(page.getByRole('heading', { level: 1, name: '개인정보 처리방침' })).toBeVisible()
  })

  test('성공: 회원가입 화면에서 약관과 개인정보 처리방침을 별도 창 링크로 제공한다', async ({ page }) => {
    await page.goto('/signup')
    await expect(page.getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms')
    await expect(page.getByRole('link', { name: '개인정보 처리방침' })).toHaveAttribute('href', '/privacy')
    await expect(page.getByRole('link', { name: '이용약관' })).toHaveAttribute('target', '_blank')
  })
})
