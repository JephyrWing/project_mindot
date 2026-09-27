import { expect, test, type Page } from '@playwright/test'
import {
  mockApi,
  openReflection,
  proposal,
  useAuthenticatedSession,
  useGuestSession,
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
    const status = page.getByRole('status').filter({ hasText: '성찰을 완전히 중단했습니다' })
    await expect(status.getByText('성찰을 완전히 중단했습니다', { exact: true })).toBeVisible()
    await expect(status).toContainText('문답은 보존되지만 이 성찰을 이어갈 수 없습니다.')
  })

  test('경계: 진행 중 목록은 OPEN 전용 페이지 API를 사용하고 선택한 세션을 재개한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/open/paged' && request.method() === 'GET') {
        expect(url.searchParams.get('page')).toBe('0')
        expect(url.searchParams.get('size')).toBe('3')
        return {
          body: {
            content: [openReflection({ rawText: '재개 가능한 OPEN 세션' })],
            totalElements: 1, totalPages: 1,
          },
        }
      }
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        return { body: openReflection() }
      }
    })

    await page.goto('/records')
    await expect(page.getByText('재개 가능한 OPEN 세션')).toBeVisible()
    await expect(page.getByRole('region', { name: '진행 중인 CBT 성찰' }).locator('.open-reflections-list button')).toHaveCount(1)
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
    {
      id: 52, name: 'COMPLETED', title: '성찰 결과가 저장되었습니다',
      description: '기록한 생각의 변화와 선택한 패턴을 나중에 다시 확인할 수 있습니다.',
    },
    {
      id: 53, name: 'CANCELLED', title: '성찰을 완전히 중단했습니다',
      description: '문답은 보존되지만 이 성찰을 이어갈 수 없습니다.',
    },
    {
      id: 54, name: 'SAFETY_STOPPED', title: '안전을 위해 성찰을 중단했습니다',
      description: '필요하다면 주변의 도움이나 전문 기관의 지원을 받아 주세요.',
    },
  ]) {
    test(`성공: ${terminalState.name} 종료 상태에 맞는 안내를 표시한다`, async ({ page }) => {
      await prepareSessions(page)
      await page.goto(`/cbt/sessions/${terminalState.id}`)
      const status = page.getByRole('status').filter({ hasText: terminalState.title })
      await expect(status.getByText(terminalState.title, { exact: true })).toBeVisible()
      await expect(status).toContainText(terminalState.description)
      await expect(page.getByRole('button', { name: '기록 목록으로' })).toBeVisible()
    })
  }
})

test.describe('FE-AUTO-015: 진행 중·완료 CBT 목록', () => {
  for (const kind of [
    { api: '/api/reflections/open/paged', url: '/reflections/open', title: '진행 중인 CBT 성찰', all: '진행 중 CBT 성찰 전체 보기', nav: '진행 중 CBT 성찰 페이지', completed: false },
    { api: '/api/reflections/completed', url: '/reflections', title: '완료한 CBT 성찰', all: '완료한 CBT 성찰 목록 보기', nav: '완료한 CBT 성찰 페이지', completed: true },
  ]) {
    test(`${kind.title}: 요약 3건·전체 10건 페이지 이동과 선택한 상세를 연결한다`, async ({ page }) => {
      await useAuthenticatedSession(page)
      const sessions = Array.from({ length: 11 }, (_, index) => ({
        ...openReflection({ sessionId: 100 + index, rawText: `목록 검증 기록 ${index + 1}` }),
        status: kind.completed ? 'COMPLETED' : 'OPEN',
        alternativeThoughtText: `목록 검증 결과 ${index + 1}`,
        completedAt: '2026-09-20T03:00:00Z',
      }))
      const queries: string[] = []
      await mockApi(page, (request, url) => {
        if (url.pathname === kind.api) {
          const number = Number(url.searchParams.get('page'))
          const size = Number(url.searchParams.get('size'))
          queries.push(`${number}:${size}`)
          return { body: { content: sessions.slice(number * size, (number + 1) * size), totalElements: 11, totalPages: Math.ceil(11 / size) } }
        }
        if (url.pathname === '/api/reflections/110') return { body: openReflection({ sessionId: 110, status: kind.completed ? 'COMPLETED' : 'OPEN', confirmedResult: kind.completed ? proposal : null }) }
        if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: openReflection({ sessionId: 110 }) }
      })
      await page.goto('/records')
      let region = page.getByRole('region', { name: kind.title, exact: true })
      await expect(region.getByText('11개', { exact: true })).toBeVisible()
      await expect(region.locator(kind.completed ? '.completed-reflections-preview button' : '.open-reflections-list button')).toHaveCount(3)
      await region.getByRole('button', { name: kind.all }).click()
      await expect(page).toHaveURL(kind.url)
      region = page.getByRole('region', { name: kind.title, exact: true })
      await expect(region.locator('.open-reflections-list button')).toHaveCount(10)
      const nav = page.getByRole('navigation', { name: kind.nav })
      await expect(nav.getByRole('button', { name: '이전', exact: true })).toBeDisabled()
      await nav.getByRole('button', { name: '다음', exact: true }).click()
      await expect(region.locator('.open-reflections-list button')).toHaveCount(1)
      await expect(nav).toContainText('2 / 2')
      await expect(nav.getByRole('button', { name: '다음', exact: true })).toBeDisabled()
      await nav.getByRole('button', { name: '이전', exact: true }).click()
      await expect(region.locator('.open-reflections-list button')).toHaveCount(10)
      await nav.getByRole('button', { name: '다음', exact: true }).click()
      await region.locator('.open-reflections-list button').click()
      if (kind.completed) {
        await expect(page).toHaveURL('/reflections/110')
        await expect(page.getByRole('heading', { name: '완료된 CBT 결과' })).toBeVisible()
        await page.getByRole('button', { name: '주간 리포트로 돌아가기' }).click()
        await expect(page).toHaveURL('/reflections')
      } else {
        await page.getByRole('button', { name: 'CBT 성찰 이어하기' }).click()
        await expect(page).toHaveURL('/cbt/sessions/110')
      }
      expect(queries).toEqual(expect.arrayContaining(['0:3', '0:10', '1:10']))
    })

    test(`${kind.title}: 조회 실패 재시도와 빈 상태를 표시한다`, async ({ page }) => {
      await useAuthenticatedSession(page)
      let fail = true
      await mockApi(page, (_request, url) => url.pathname === kind.api
        ? fail ? { status: 500, body: { message: '목록 검증 조회 실패' } } : { body: { content: [], totalElements: 0, totalPages: 0 } }
        : undefined)
      await page.goto(kind.url)
      await expect(page.getByRole('alert')).toContainText('목록 검증 조회 실패')
      fail = false
      await page.getByRole('button', { name: '다시 불러오기' }).click()
      await expect(page.getByText(kind.completed ? '아직 완료한 CBT 성찰이 없습니다.' : '현재 진행 중인 CBT 성찰이 없습니다.')).toBeVisible()
      await expect(page.getByRole('navigation', { name: kind.nav })).toHaveCount(0)
    })

    test(`${kind.title}: 비로그인 직접 접근을 차단한다`, async ({ page }) => {
      await useGuestSession(page)
      await mockApi(page)
      await page.goto(kind.url)
      await expect(page).toHaveURL('/')
      await expect(page.getByRole('dialog', { name: '로그인이 필요한 서비스입니다' })).toBeVisible()
    })
  }
})
