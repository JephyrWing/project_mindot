import { expect, test } from '@playwright/test'
import { mockApi, useAuthenticatedSession } from './support'

const majorRoutes = [
  '/',
  '/records',
  '/records/new',
  '/reports/weekly',
  '/reports/monthly',
  '/daily-care',
  '/settings',
  '/centers',
]

test.describe('FE-AUTO-030: 접근성·반응형', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page)
  })

  for (const width of [320, 360, 768, 1440]) {
    test(`경계: 주요 화면이 ${width}px에서 가로로 넘치지 않는다`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      for (const route of majorRoutes) {
        await page.goto(route)
        await page.locator('main').first().waitFor()
        const hasHorizontalOverflow = await page.evaluate(() => (
          document.documentElement.scrollWidth > window.innerWidth
        ))
        expect(hasHorizontalOverflow, `${route} ${width}px viewport overflow`).toBe(false)
      }
    })
  }

  test('성공: 키보드로 메뉴를 열고 닫으면 초점이 복원된다', async ({ page }) => {
    await page.goto('/')
    const menuButton = page.getByRole('button', { name: '메뉴 열기' })
    await menuButton.focus()
    await page.keyboard.press('Enter')
    await expect(menuButton).toHaveAttribute('aria-expanded', 'true')
    await page.keyboard.press('Escape')
    await expect(menuButton).toHaveAttribute('aria-expanded', 'false')
    await expect(menuButton).toBeFocused()
  })

  test('성공: 주요 입력과 비동기 상태에 접근 가능한 이름과 키보드 순서가 있다', async ({ page }) => {
    await page.goto('/records/new')
    await expect(page.getByLabel('지금의 감정')).toBeVisible()
    await expect(page.locator('[aria-live="polite"]').first()).toBeAttached()
    await page.getByLabel('지금의 감정').fill('키보드로 입력한 감정')
    await page.getByLabel('지금의 감정').press('Tab')
    await expect(page.getByRole('button', { name: '음성 녹음' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(page.getByLabel('지금의 감정')).toBeFocused()
  })

  test('오류: 비동기 실패 안내를 접근 가능한 alert로 전달하고 입력 초점을 유지한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/quick'
        ? { status: 500, body: { message: '저장 실패' } }
        : undefined
    ))
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('접근성 오류 안내 확인')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toContainText('서버 오류로 저장하지 못했습니다')
    await input.focus()
    await expect(input).toBeFocused()
    await expect(input).toHaveValue('접근성 오류 안내 확인')
  })
})
