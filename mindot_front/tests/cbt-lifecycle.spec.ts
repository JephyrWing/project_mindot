import { expect, test, type Page } from '@playwright/test'
import {
  mockApi,
  openReflection,
  useAuthenticatedSession,
} from './support'

test.describe('FE-AUTO-015: CBT 상태', () => {
  const byId = (id: number) => {
    if (id === 52) return openReflection({ sessionId: id, status: 'COMPLETED' })
    if (id === 53) return openReflection({ sessionId: id, status: 'CANCELLED' })
    if (id === 54) return openReflection({ sessionId: id, status: 'SAFETY_STOPPED' })
    return openReflection({ sessionId: id })
  }

  const prepareSessions = async (page: Page) => {
    await useAuthenticatedSession(page)
    let loadedSessionId = 51
    await mockApi(page, (request, url) => {
      const match = url.pathname.match(/^\/api\/reflections\/(\d+)$/)
      if (match && request.method() === 'GET') {
        loadedSessionId = Number(match[1])
        return { body: byId(loadedSessionId) }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
        return { body: byId(loadedSessionId) }
      }
      if (url.pathname === '/api/reflections/51/cancel') {
        return { body: byId(53) }
      }
    })
  }

  test('성공: OPEN 세션을 나중에 이어하기로 목록에 보존한다', async ({ page }) => {
    await prepareSessions(page)
    await page.goto('/cbt/sessions/51')
    await page.getByRole('button', { name: '나중에 이어하기' }).click()
    await expect(page).toHaveURL('/records')
  })

  test('성공: 완전 중단 확인 후 CANCELLED 안내를 표시한다', async ({ page }) => {
    await prepareSessions(page)
    await page.goto('/cbt/sessions/51')
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '성찰 완전히 중단' }).click()
    await expect(page.getByText('성찰을 완전히 중단했습니다. 문답은 보존됩니다.')).toBeVisible()
  })

  test('경계: 진행 중 목록에는 OPEN 세션만 남고 종료 세션은 재개할 수 없다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/open' && request.method() === 'GET') {
        return {
          body: [
            openReflection({ rawText: '재개 가능한 OPEN 세션' }),
            openReflection({ sessionId: 52, status: 'COMPLETED', rawText: '완료된 세션' }),
            openReflection({ sessionId: 53, status: 'CANCELLED', rawText: '취소된 세션' }),
          ],
        }
      }
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        return { body: openReflection() }
      }
    })

    await page.goto('/records')
    await expect(page.getByText('재개 가능한 OPEN 세션')).toBeVisible()
    await expect(page.getByText('완료된 세션')).toHaveCount(0)
    await expect(page.getByText('취소된 세션')).toHaveCount(0)
    await page.getByText('재개 가능한 OPEN 세션').click()
    await expect(page.getByRole('button', { name: 'CBT 성찰 이어하기' })).toBeVisible()
  })

  test('오류: 완전 중단 실패 시 OPEN 상태와 재개 동작을 유지한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        return { body: openReflection() }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
        return { body: openReflection() }
      }
      if (url.pathname === '/api/reflections/51/cancel') {
        return { status: 500, body: { message: '중단 요청을 처리하지 못했습니다.' } }
      }
    })

    await page.goto('/cbt/sessions/51')
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '성찰 완전히 중단' }).click()
    await expect(page.getByRole('alert')).toContainText('중단 요청을 처리하지 못했습니다.')
    await expect(page.getByRole('button', { name: '나중에 이어하기' })).toBeVisible()
    await expect(page.getByLabel('답변')).toBeEnabled()
  })

  for (const terminalState of [
    { id: 52, name: 'COMPLETED', message: '성찰 결과가 저장됐습니다.' },
    { id: 53, name: 'CANCELLED', message: '성찰을 완전히 중단했습니다. 문답은 보존됩니다.' },
    { id: 54, name: 'SAFETY_STOPPED', message: '안전을 위해 성찰을 중단했습니다.' },
  ]) {
    test(`성공: ${terminalState.name} 종료 상태에 맞는 안내를 표시한다`, async ({ page }) => {
      await prepareSessions(page)
      await page.goto(`/cbt/sessions/${terminalState.id}`)
      await expect(page.getByText(terminalState.message)).toBeVisible()
      await expect(page.getByRole('button', { name: '기록 목록으로' })).toBeVisible()
    })
  }
})
