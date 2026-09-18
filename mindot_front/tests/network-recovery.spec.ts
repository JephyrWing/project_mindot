import { expect, test } from '@playwright/test'
import { mockApi, openReflection, useAuthenticatedSession } from './support'

test.describe('FE-AUTO-029: 네트워크 복구', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('오류: 오프라인 저장 실패를 안내하고 작성 내용을 보존한다', async ({ context, page }) => {
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records/quick') return 'abort'
    })
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('연결이 끊겨도 이 문장은 남아야 한다.')
    await context.setOffline(true)
    await expect(page.getByRole('status').filter({ hasText: '오프라인 상태' })).toBeVisible()
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toContainText('서버에 연결할 수 없습니다')
    await expect(input).toHaveValue('연결이 끊겨도 이 문장은 남아야 한다.')
  })

  test('성공: 온라인 복귀 후 보존된 내용으로 다시 저장한다', async ({ context, page }) => {
    let connectionAvailable = false
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records/quick') {
        if (!connectionAvailable) return 'abort'
        return {
          body: {
            recordId: 5,
            occurredAt: '2026-09-18T01:00:00Z',
            timeBucket: 'MORNING',
            weekdayType: 'WEEKDAY',
            analysisStatus: 'PENDING',
          },
        }
      }
    })
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('연결이 끊겨도 이 문장은 남아야 한다.')
    await context.setOffline(true)
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(input).toHaveValue('연결이 끊겨도 이 문장은 남아야 한다.')
    await context.setOffline(false)
    connectionAvailable = true
    await expect(page.getByText('오프라인 상태')).not.toBeVisible()
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('heading', { name: '감정 기록 상세' })).toBeVisible()
  })

  test('경계: 오프라인과 온라인 전환 상태를 즉시 갱신한다', async ({ context, page }) => {
    await mockApi(page)
    await page.goto('/records/new')
    await context.setOffline(true)
    await expect(page.getByRole('status').filter({ hasText: '오프라인 상태' })).toBeVisible()
    await context.setOffline(false)
    await expect(page.getByText('오프라인 상태')).not.toBeVisible()
    await expect(page.getByLabel('지금의 감정')).toBeEnabled()
  })

  test('성공: CBT 답변 응답 유실 후 같은 입력으로 재시도해 최신 응답을 표시한다', async ({ page }) => {
    const initial = openReflection()
    const recovered = openReflection({
      revision: 2,
      messages: [
        ...initial.messages,
        { messageNumber: 2, role: 'USER', content: '응답 유실에도 보존할 답변' },
        { messageNumber: 3, role: 'AI', content: '재시도한 답변을 정상적으로 받았습니다.' },
      ],
    })
    let answerAttempts = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: initial }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: initial }
      if (url.pathname === '/api/reflections/51/turn') {
        answerAttempts += 1
        return answerAttempts === 1 ? 'abort' : { body: recovered }
      }
    })

    await page.goto('/cbt/sessions/51')
    const answer = page.getByLabel('답변')
    await answer.fill('응답 유실에도 보존할 답변')
    await page.getByRole('button', { name: '보내기' }).click()
    await expect(page.getByRole('alert')).toContainText('작성한 내용을 유지하고 다시 확인해 주세요')
    await expect(answer).toHaveValue('응답 유실에도 보존할 답변')
    await page.getByRole('button', { name: '보내기' }).click()
    await expect(page.getByText('재시도한 답변을 정상적으로 받았습니다.')).toBeVisible()
    await expect(answer).toHaveValue('')
  })

  test('오류: 401과 재발급 실패는 로그인 필요 상태로 복귀한다', async ({ page }) => {
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records/quick') return { status: 401, body: {} }
      if (url.pathname === '/api/auth/refresh') return { status: 401, body: {} }
    })
    await page.goto('/records/new')
    await page.getByLabel('지금의 감정').fill('로그인 만료 시 보존할 입력')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('dialog', { name: '로그인이 필요한 서비스입니다' })).toBeVisible()
  })

  test('오류: 403은 권한 안내를 표시하고 작성 화면을 유지한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/quick'
        ? { status: 403, body: { message: '기록 권한 없음' } }
        : undefined
    ))
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('권한 오류에도 보존할 입력')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('dialog', { name: '접근 권한이 없습니다' })).toBeVisible()
    await expect(input).toHaveValue('권한 오류에도 보존할 입력')
  })

  test('오류: 404는 저장 대상을 찾을 수 없음을 안내하고 입력을 보존한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/quick'
        ? { status: 404, body: {} }
        : undefined
    ))
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('대상 없음에도 보존할 입력')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toContainText('감정 기록을 저장할 대상을 찾을 수 없습니다')
    await expect(input).toHaveValue('대상 없음에도 보존할 입력')
  })

  test('오류: 409는 중복 처리 원인을 안내하고 입력을 보존한다', async ({ page }) => {
    await mockApi(page, (_request, url) => (
      url.pathname === '/api/records/quick'
        ? { status: 409, body: { message: '이미 저장된 감정 기록입니다.' } }
        : undefined
    ))
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('충돌에도 보존할 입력')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toHaveText('이미 저장된 감정 기록입니다.')
    await expect(input).toHaveValue('충돌에도 보존할 입력')
  })

  test('오류: 5xx는 입력을 유지하고 같은 화면에서 재시도해 복구한다', async ({ page }) => {
    let saveAttempts = 0
    await mockApi(page, (_request, url) => {
      if (url.pathname !== '/api/records/quick') return undefined
      saveAttempts += 1
      return saveAttempts === 1
        ? { status: 500, body: { message: 'server error' } }
        : {
          status: 201,
          body: {
            recordId: 8,
            occurredAt: '2026-09-18T01:00:00Z',
            timeBucket: 'MORNING',
            weekdayType: 'WEEKDAY',
            analysisStatus: 'PENDING',
          },
        }
    })
    await page.goto('/records/new')
    const input = page.getByLabel('지금의 감정')
    await input.fill('서버 오류 후 재시도할 입력')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alert')).toContainText('작성한 내용을 유지하고 다시 시도해 주세요')
    await expect(input).toHaveValue('서버 오류 후 재시도할 입력')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('heading', { name: '감정 기록 상세' })).toBeVisible()
  })
})
