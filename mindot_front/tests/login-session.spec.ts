import { expect, test } from '@playwright/test'
import {
  mockApi,
  openReflection,
  useAuthenticatedSession,
  useGuestSession,
} from './support'

test.describe('FE-AUTO-004: 로그인', () => {
  test.beforeEach(async ({ page }) => {
    await useGuestSession(page)
  })

  test('오류: 잘못된 계정 정보를 안내하고 로그인 화면을 유지한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/login' ? { status: 401, body: {} } : undefined
    ))
    await page.goto('/login')
    await page.getByLabel('이메일', { exact: true }).fill('tester@example.com')
    await page.getByLabel('비밀번호', { exact: true }).fill('Mindot!123')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('로그인에 실패했습니다')
    await expect(page).toHaveURL('/login')
  })

  test('경계: 연속 클릭 중에는 한 번의 성공 화면만 표시한다', async ({ page }) => {
    let accepted = false
    await mockApi(page, async (_request, url) => {
      if (url.pathname !== '/api/auth/login') return undefined
      if (accepted) return { status: 429, body: { message: '중복 로그인 요청' } }
      accepted = true
      await new Promise((resolve) => setTimeout(resolve, 100))
      return { body: { accessToken: 'signed-in-token', userRole: 'ROLE_USER' } }
    })
    await page.goto('/login')
    await page.getByLabel('이메일', { exact: true }).fill('tester@example.com')
    await page.getByLabel('비밀번호', { exact: true }).fill('Mindot!123')
    await page.getByRole('button', { name: '로그인', exact: true }).dblclick()
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('heading', { name: '오늘의 마음은 어떤가요?' })).toBeVisible()
  })

  test('성공: 로그인 후 메인으로 이동하고 기억한 이메일만 다시 표시한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/login'
        ? { body: { accessToken: 'signed-in-token', userRole: 'ROLE_USER' } }
        : undefined
    ))
    await page.goto('/login')
    await page.getByLabel('이메일', { exact: true }).fill('tester@example.com')
    await page.getByLabel('비밀번호', { exact: true }).fill('Mindot!123')
    await page.getByLabel('이메일 기억하기').check()
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page).toHaveURL('/')
    await page.goto('/login')
    await expect(page.getByLabel('이메일', { exact: true })).toHaveValue('tester@example.com')
    await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('')
  })
})

test.describe('FE-AUTO-005: 인증 세션', () => {
  test('성공: 동시에 발생한 401을 한 번의 재발급으로 복구해 두 화면 데이터를 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const firstUnauthorized = new Set(['/api/records', '/api/reflections/open'])
    let refreshCalls = 0
    let releaseRefresh!: () => void
    const refreshPending = new Promise<void>((resolve) => { releaseRefresh = resolve })
    // 두 401 응답이 도착하기 전에 재발급이 끝나면 동시 요청 검증이 아니게 된다.
    const unauthorizedResponses = Promise.all([...firstUnauthorized].map((path) => (
      page.waitForResponse((response) => new URL(response.url()).pathname === path && response.status() === 401)
        .then((response) => response.finished())
    )))
    await mockApi(page, async (request, url) => {
      if (url.pathname === '/api/auth/refresh') {
        refreshCalls += 1
        await refreshPending
        return refreshCalls === 1
          ? { body: { accessToken: 'shared-refreshed-token' } }
          : { status: 500, body: { message: '중복 재발급 요청' } }
      }
      if (firstUnauthorized.has(url.pathname) && request.method() === 'GET') {
        firstUnauthorized.delete(url.pathname)
        return { status: 401, body: {} }
      }
      if (url.pathname === '/api/records' && request.method() === 'GET') {
        return {
          body: { content: [], page: 0, size: 3, totalElements: 0, totalPages: 0 },
        }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'GET') {
        return { body: [openReflection({ rawText: '재발급 후 복원한 CBT' })] }
      }
    })

    await page.goto('/records')
    try {
      await unauthorizedResponses
      await expect.poll(() => refreshCalls).toBe(1)
    } finally {
      releaseRefresh()
    }
    await expect(page.getByText('아직 작성한 감정 기록이 없습니다.')).toBeVisible()
    await expect(page.getByText('재발급 후 복원한 CBT')).toBeVisible()
    expect(refreshCalls).toBe(1)
  })

  test('성공: 401 후 토큰을 재발급해 보호 화면을 복구한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    let recordCalls = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/auth/refresh') {
        return { body: { accessToken: 'refreshed-token' } }
      }
      if (url.pathname === '/api/records' && request.method() === 'GET') {
        recordCalls += 1
        if (recordCalls === 1) return { status: 401, body: {} }
        return {
          body: { content: [], page: 0, size: 3, totalElements: 0, totalPages: 0 },
        }
      }
    })

    await page.goto('/records')
    await expect(page.getByRole('heading', { name: '감정 기록 목록' })).toBeVisible()
    await expect(page.getByText('아직 작성한 감정 기록이 없습니다.')).toBeVisible()
  })

  test('오류: 토큰 재발급 실패 시 로그인 필요 상태로 전환한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records') return { status: 401, body: {} }
      if (url.pathname === '/api/auth/refresh') return { status: 401, body: {} }
    })
    await page.goto('/records')
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('dialog', { name: '로그인이 필요한 서비스입니다' })).toBeVisible()
  })

  test('오류: 권한이 없는 상세 화면은 접근 제한을 안내한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/1'
        ? { status: 403, body: { message: 'forbidden' } }
        : undefined
    ))
    await page.goto('/records/1')
    await expect(page.getByRole('dialog', { name: '접근 권한이 없습니다' })).toBeVisible()
  })

  test('경계: USER와 ADMIN 역할에 맞는 메뉴만 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page, 'ROLE_USER')
    await mockApi(page)
    await page.goto('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await expect(page.getByRole('link', { name: '관리자 페이지' })).toHaveCount(0)
  })

  test('경계: ADMIN 역할에는 관리자 메뉴를 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page, 'ROLE_ADMIN')
    await mockApi(page)
    await page.goto('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await expect(page.getByRole('link', { name: '관리자 페이지' })).toBeVisible()
  })

  test('성공: 로그아웃 후 비로그인 메인 화면으로 전환한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/logout' ? { status: 204, body: null } : undefined
    ))
    await page.goto('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page.getByRole('heading', { name: '오늘의 마음은 어떤가요?' })).toBeVisible()
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()
  })

  test('오류: 로그아웃 API 실패에도 로컬 로그인 상태를 정리한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/auth/logout'
        ? { status: 500, body: { message: 'logout failed' } }
        : undefined
    ))
    await page.goto('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await page.getByRole('button', { name: '로그아웃' }).click()
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()
  })
})
