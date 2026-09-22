import { expect, test } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  openReflection,
  useAuthenticatedSession,
} from './support'

test.describe('FE-AUTO-011: CBT 시작', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('경계: 감정 기록이 없으면 성찰 시작을 차단한다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/cbt')
    await expect(page.getByText('감정 기록을 선택한 뒤 시작해 주세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '성찰 시작하기' })).toBeDisabled()
  })

  test('경계: 자동적 생각이 없는 PARTIAL 기록은 생각 입력을 요구한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return {
          body: completeRecord({
            completionStatus: 'PARTIAL',
            automaticThought: '',
          }),
        }
      }
    })
    await page.goto('/cbt?emotionRecordId=1')
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await expect(page.getByLabel('그때 처음 떠오른 생각')).toBeVisible()
    await expect(page.getByRole('button', { name: '저장하고 시작하기' })).toBeDisabled()
  })

  test('성공: 생각을 확정한 뒤 CBT 세션을 열어 질문을 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: completeRecord({ completionStatus: 'PARTIAL', automaticThought: '' }) }
      }
      if (url.pathname === '/api/records/1/confirm') {
        return { body: completeRecord({ completionStatus: 'COMPLETE', automaticThought: '발표에서 실수하면 모두가 실망할 거야.' }) }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return { body: openReflection() }
    })
    await page.goto('/cbt?emotionRecordId=1')
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await page.getByLabel('그때 처음 떠오른 생각').fill('발표에서 실수하면 모두가 실망할 거야.')
    await page.getByRole('button', { name: '저장하고 시작하기' }).dblclick()
    await expect(page).toHaveURL('/cbt/sessions/51')
    await expect(page.getByText('그 생각을 뒷받침하는 근거가 있나요?')).toBeVisible()
  })

  test('오류: 세션 시작 응답 유실 후 저장된 생각과 재시도 동작을 유지한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: completeRecord({ completionStatus: 'PARTIAL', automaticThought: '' }) }
      }
      if (url.pathname === '/api/records/1/confirm') {
        return { body: completeRecord({ completionStatus: 'COMPLETE', automaticThought: '저장된 자동적 생각' }) }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') return 'abort'
    })
    await page.goto('/cbt?emotionRecordId=1')
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await page.getByLabel('그때 처음 떠오른 생각').fill('저장된 자동적 생각')
    await page.getByRole('button', { name: '저장하고 시작하기' }).click()
    await expect(page.getByRole('alert')).toContainText('작성한 내용을 유지하고 다시 확인해 주세요')
    await expect(page.getByText('생각은 저장됐습니다. 성찰 시작을 다시 시도할 수 있어요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '성찰 시작하기' })).toBeVisible()
  })

  test('성공: 세션 시작 응답 유실 후 같은 저장된 생각으로 다시 시작한다', async ({ page }) => {
    let openAttempts = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: completeRecord({ completionStatus: 'PARTIAL', automaticThought: '' }) }
      }
      if (url.pathname === '/api/records/1/confirm') {
        return { body: completeRecord({ automaticThought: '재시도할 저장된 생각' }) }
      }
      if (url.pathname === '/api/reflections/open' && request.method() === 'POST') {
        openAttempts += 1
        return openAttempts === 1 ? 'abort' : { body: openReflection() }
      }
    })
    await page.goto('/cbt?emotionRecordId=1')
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await page.getByLabel('그때 처음 떠오른 생각').fill('재시도할 저장된 생각')
    await page.getByRole('button', { name: '저장하고 시작하기' }).click()
    await expect(page.getByText('생각은 저장됐습니다. 성찰 시작을 다시 시도할 수 있어요.')).toBeVisible()
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await expect(page).toHaveURL('/cbt/sessions/51')
    await expect(page.getByText('그 생각을 뒷받침하는 근거가 있나요?')).toBeVisible()
  })

  test('오류: 다른 생각으로 이미 확정된 충돌은 덮어쓰지 않고 상세 확인을 안내한다', async ({ page }) => {
    let detailCalls = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        detailCalls += 1
        return detailCalls <= 2
          ? { body: completeRecord({ completionStatus: 'PARTIAL', automaticThought: '' }) }
          : { body: completeRecord({ automaticThought: '서버에 먼저 저장된 다른 생각' }) }
      }
      if (url.pathname === '/api/records/1/confirm') {
        return { status: 409, body: { message: '이미 확정된 기록입니다.' } }
      }
    })
    await page.goto('/cbt?emotionRecordId=1')
    await page.getByRole('button', { name: '성찰 시작하기' }).click()
    await page.getByLabel('그때 처음 떠오른 생각').fill('덮어쓰면 안 되는 새 생각')
    await page.getByRole('button', { name: '저장하고 시작하기' }).click()
    await expect(page.getByRole('alert')).toContainText('기록이 다른 생각으로 확정되어 있습니다')
    await expect(page.getByLabel('그때 처음 떠오른 생각')).toHaveValue('덮어쓰면 안 되는 새 생각')
  })
})
