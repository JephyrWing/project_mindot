import { expect, test } from '@playwright/test'
import {
  mockApi,
  openReflection,
  useAuthenticatedSession,
} from './support'

test.describe('FE-AUTO-012: CBT 재개', () => {
  test('성공: 직접 URL에서 저장된 전체 문답을 순서대로 복원하고 답변을 이어간다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const restored = openReflection({
      revision: 4,
      messages: [
        { messageNumber: 1, role: 'AI', content: '무슨 일이 있었나요?' },
        { messageNumber: 2, role: 'USER', content: '발표 중 잠깐 말을 멈췄어요.' },
        { messageNumber: 3, role: 'AI', content: '그 순간 어떤 생각이 들었나요?' },
      ],
    })

    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        return { body: restored }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
        return { body: restored }
      }
    })

    await page.goto('/cbt/sessions/51')
    const messages = page.locator('.cbt-chat-messages .cbt-message p')
    await expect(messages).toHaveText([
      '무슨 일이 있었나요?',
      '발표 중 잠깐 말을 멈췄어요.',
      '그 순간 어떤 생각이 들었나요?',
    ])
    await expect(page.getByLabel('답변')).toBeEnabled()
  })

  test('경계: 저장된 문답이 없어도 진행 중 세션의 답변 입력을 복원한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const emptySession = openReflection({ messages: [] })
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        return { body: emptySession }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
        return { body: emptySession }
      }
    })

    await page.goto('/cbt/sessions/51')
    await expect(page.getByRole('log')).toBeVisible()
    await expect(page.locator('.cbt-chat-messages .cbt-message')).toHaveCount(0)
    await expect(page.getByLabel('답변')).toBeEnabled()
  })

  test('오류: 존재하지 않는 세션 URL은 오류와 재확인 동작을 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/reflections/99'
        ? { status: 404, body: { message: '진행 중인 CBT 세션을 찾을 수 없습니다.' } }
        : undefined
    ))

    await page.goto('/cbt/sessions/99')
    await expect(page.getByRole('alert')).toContainText('진행 중인 CBT 세션을 찾을 수 없습니다.')
    await expect(page.getByRole('button', { name: '현재 결과 확인' })).toBeVisible()
    await expect(page.getByLabel('답변')).toHaveCount(0)
  })
})

test.describe('FE-AUTO-013: CBT 대화', () => {
  test('경계: 빈 답변은 전송할 수 없다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: openReflection() }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: openReflection() }
    })
    await page.goto('/cbt/sessions/51')
    await expect(page.getByRole('button', { name: '보내기' })).toBeDisabled()
  })

  test('성공: 답변 저장·처리 대기 후 최신 AI 응답을 표시한다', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    await useAuthenticatedSession(page)
    const initial = openReflection()
    const processing = openReflection({
      revision: 2,
      job: { status: 'PROCESSING', retryable: false },
      messages: [
        ...initial.messages,
        { messageNumber: 2, role: 'USER', content: '한 번 실수한 경험이 근거예요.' },
      ],
    })
    const completed = openReflection({
      revision: 3,
      messages: [
        ...processing.messages,
        { messageNumber: 3, role: 'AI', content: '반대되는 경험도 떠올려 볼까요?' },
      ],
    })
    let detailCalls = 0

    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        detailCalls += 1
        return { body: detailCalls <= 2 ? initial : completed }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
        return { body: initial }
      }
      if (url.pathname === '/api/reflections/51/turn') {
        return { body: processing }
      }
    })

    await page.goto('/cbt/sessions/51')
    await page.getByLabel('답변').fill('한 번 실수한 경험이 근거예요.')
    await page.getByRole('button', { name: '보내기' }).dblclick()
    await expect(page.getByRole('status')).toContainText('답변은 저장됐습니다')
    await page.clock.fastForward(2_100)
    await expect(page.getByText('반대되는 경험도 떠올려 볼까요?')).toBeVisible()
  })

  test('오류: 답변 저장 실패를 안내하고 입력을 보존한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const initial = openReflection()
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: initial }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: initial }
      if (url.pathname === '/api/reflections/51/turn') {
        return { status: 500, body: { message: 'temporary failure' } }
      }
    })
    await page.goto('/cbt/sessions/51')
    await page.getByLabel('답변').fill('이 입력은 실패해도 남아야 합니다.')
    await page.getByRole('button', { name: '보내기' }).click()
    await expect(page.getByRole('alert')).toContainText('temporary failure')
    await expect(page.getByLabel('답변')).toHaveValue('이 입력은 실패해도 남아야 합니다.')
  })

  test('경계: PENDING 상태에서는 답변 입력을 잠그고 저장 상태를 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const pendingView = openReflection({
      job: { jobId: 10, status: 'PENDING', retryable: false },
    })
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: pendingView }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: pendingView }
    })

    await page.goto('/cbt/sessions/51')
    await expect(page.getByRole('status')).toContainText('답변은 저장됐습니다')
    await expect(page.getByLabel('답변')).toBeDisabled()
  })

  test('성공: 늦게 도착한 이전 처리 응답보다 최신 완료 응답을 유지한다', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    await useAuthenticatedSession(page)
    const initial = openReflection({ job: { jobId: 1, status: 'COMPLETED', retryable: false } })
    const processing = openReflection({
      revision: 2,
      job: { jobId: 2, status: 'PROCESSING', retryable: false },
      messages: [
        ...initial.messages,
        { messageNumber: 2, role: 'USER', content: '응답 순서 확인 답변' },
      ],
    })
    const completed = openReflection({
      revision: 3,
      job: { jobId: 2, status: 'COMPLETED', retryable: false },
      messages: [
        ...processing.messages,
        { messageNumber: 3, role: 'AI', content: '가장 최신 완료 응답입니다.' },
      ],
    })
    let turned = false
    let pollCalls = 0
    await mockApi(page, async (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        if (!turned) return { body: initial }
        pollCalls += 1
        if (pollCalls === 1) {
          await new Promise((resolve) => setTimeout(resolve, 120))
          return { body: processing }
        }
        return { body: completed }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: initial }
      if (url.pathname === '/api/reflections/51/turn') {
        turned = true
        return { body: processing }
      }
    })

    await page.goto('/cbt/sessions/51')
    await page.getByLabel('답변').fill('응답 순서 확인 답변')
    await page.getByRole('button', { name: '보내기' }).click()
    await page.clock.fastForward(4_100)
    await expect(page.getByText('가장 최신 완료 응답입니다.')).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: '응답을 준비하고 있어요' })).toHaveCount(0)
  })

  test('오류: 재시도 가능한 생성 실패는 저장된 입력으로 완료 결과를 복구한다', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    await useAuthenticatedSession(page)
    const failed = openReflection({
      revision: 2,
      job: { jobId: 20, status: 'FAILED', retryable: true },
      messages: [
        { messageNumber: 1, role: 'AI', content: '첫 질문' },
        { messageNumber: 2, role: 'USER', content: '저장된 답변' },
      ],
    })
    const processing = openReflection({
      ...failed,
      revision: 3,
      job: { jobId: 21, status: 'PROCESSING', retryable: false },
    })
    const completed = openReflection({
      ...failed,
      revision: 4,
      job: { jobId: 21, status: 'COMPLETED', retryable: false },
      messages: [
        ...failed.messages,
        { messageNumber: 3, role: 'AI', content: '재시도 후 복구된 답변입니다.' },
      ],
    })
    let retried = false
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') {
        return { body: retried ? completed : failed }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: failed }
      if (url.pathname === '/api/reflections/51/retry') {
        retried = true
        return { body: processing }
      }
    })

    await page.goto('/cbt/sessions/51')
    await expect(page.getByRole('alert')).toContainText('저장된 입력으로 다시 시도할 수 있어요')
    await page.getByRole('button', { name: '생성 다시 시도' }).click()
    await page.clock.fastForward(2_100)
    await expect(page.getByText('재시도 후 복구된 답변입니다.')).toBeVisible()
  })

  test('오류: 409 충돌은 작성한 답변을 보존하고 현재 결과 확인을 제공한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const initial = openReflection()
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: initial }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: initial }
      if (url.pathname === '/api/reflections/51/turn') {
        return { status: 409, body: { message: '다른 화면에서 세션이 먼저 변경되었습니다.' } }
      }
    })

    await page.goto('/cbt/sessions/51')
    await page.getByLabel('답변').fill('충돌해도 보존할 답변')
    await page.getByRole('button', { name: '보내기' }).click()
    await expect(page.getByRole('alert')).toContainText('다른 화면에서 세션이 먼저 변경되었습니다.')
    await expect(page.getByLabel('답변')).toHaveValue('충돌해도 보존할 답변')
    await expect(page.getByRole('button', { name: '현재 결과 확인' })).toBeVisible()
  })
})
