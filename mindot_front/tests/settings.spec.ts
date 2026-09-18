import { expect, test, type Page } from '@playwright/test'
import { mockApi, useAuthenticatedSession } from './support'

const profile = {
  email: 'tester@example.com',
  displayName: '기존닉네임',
  timezone: 'Asia/Seoul',
}

const preferences = {
  patternAlertEnabled: false,
  preferredTime: '09:00:00',
  timezone: 'Asia/Seoul',
}

async function mockSettings(
  page: Page,
  consentGranted = true,
  failures: { notification?: boolean, consent?: boolean } = {},
) {
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/users/me' && request.method() === 'GET') return { body: profile }
    if (url.pathname === '/api/users/me' && request.method() === 'PATCH') {
      return { body: { ...profile, displayName: '새닉네임' } }
    }
    if (url.pathname === '/api/notifications/preferences' && request.method() === 'GET') {
      return { body: preferences }
    }
    if (url.pathname === '/api/notifications/preferences' && request.method() === 'PUT') {
      if (failures.notification) {
        return { status: 500, body: { message: '알림 설정 저장 실패' } }
      }
      const body = request.postDataJSON()
      return { body: { ...preferences, ...body } }
    }
    if (url.pathname === '/api/consents' && request.method() === 'GET') {
      return { body: [{ consentType: 'AI_ANALYSIS', granted: consentGranted, changeable: true }] }
    }
    if (url.pathname === '/api/consents/AI_ANALYSIS/revoke') {
      if (failures.consent) return { status: 500, body: { message: '동의 변경 실패' } }
      return { body: { consentType: 'AI_ANALYSIS', granted: false, changeable: true } }
    }
    if (url.pathname === '/api/consents/AI_ANALYSIS/grant') {
      if (failures.consent) return { status: 500, body: { message: '동의 변경 실패' } }
      return { body: { consentType: 'AI_ANALYSIS', granted: true, changeable: true } }
    }
  })
}

test.describe('FE-AUTO-022: 설정', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('경계: 프로필을 표시하고 빈 닉네임 저장을 허용하지 않는다', async ({ page }) => {
    await mockSettings(page)
    await page.goto('/settings')
    await expect(page.getByLabel('이메일')).toHaveValue('tester@example.com')
    const displayName = page.getByLabel('닉네임')
    await expect(displayName).toHaveAttribute('maxlength', '80')
    await displayName.fill('')
    await expect(page.getByRole('button', { name: '닉네임 저장' })).toBeDisabled()
  })

  test('성공: 닉네임 저장 결과를 화면에 반영한다', async ({ page }) => {
    await mockSettings(page)
    await page.goto('/settings')
    await page.getByLabel('닉네임').fill('새닉네임')
    await page.getByRole('button', { name: '닉네임 저장' }).click()
    await expect(page.getByText('닉네임을 변경했습니다.', { exact: true })).toBeVisible()
    await expect(page.getByLabel('닉네임')).toHaveValue('새닉네임')
  })

  test('성공: 반복 패턴 알림과 희망 시각 저장 결과를 표시한다', async ({ page }) => {
    await mockSettings(page)
    await page.goto('/settings')
    await page.getByLabel('반복 패턴 알림 받기').check()
    await page.getByLabel('알림 희망 시각').fill('18:30')
    await page.getByRole('button', { name: '알림 설정 저장' }).click()
    await expect(page.getByText('알림 설정을 저장했습니다.', { exact: true })).toBeVisible()
    await expect(page.getByLabel('반복 패턴 알림 받기')).toBeChecked()
    await expect(page.getByLabel('알림 희망 시각')).toHaveValue('18:30')
  })

  test('성공: 반복 패턴 알림을 끄면 시간 입력을 잠그고 해제 상태를 저장한다', async ({ page }) => {
    await mockSettings(page)
    await page.goto('/settings')
    await expect(page.getByLabel('반복 패턴 알림 받기')).not.toBeChecked()
    await expect(page.getByLabel('알림 희망 시각')).toBeDisabled()
    await page.getByRole('button', { name: '알림 설정 저장' }).click()
    await expect(page.getByText('알림 설정을 저장했습니다.', { exact: true })).toBeVisible()
    await expect(page.getByLabel('반복 패턴 알림 받기')).not.toBeChecked()
  })

  test('경계: 알림 시각의 시작과 마지막 분을 저장해 화면에 반영한다', async ({ page }) => {
    await mockSettings(page)
    await page.goto('/settings')
    await page.getByLabel('반복 패턴 알림 받기').check()
    const preferredTime = page.getByLabel('알림 희망 시각')
    await preferredTime.fill('00:00')
    await page.getByRole('button', { name: '알림 설정 저장' }).click()
    await expect(preferredTime).toHaveValue('00:00')
    await preferredTime.fill('23:59')
    await page.getByRole('button', { name: '알림 설정 저장' }).click()
    await expect(preferredTime).toHaveValue('23:59')
  })

  test('경계: 알림을 켠 상태에서 시각이 비어 있으면 저장을 차단한다', async ({ page }) => {
    await mockSettings(page)
    await page.goto('/settings')
    await page.getByLabel('반복 패턴 알림 받기').check()
    await page.getByLabel('알림 희망 시각').fill('')
    await expect(page.getByRole('button', { name: '알림 설정 저장' })).toBeDisabled()
  })

  test('오류: 알림 설정 저장 실패를 안내하고 입력값을 유지한다', async ({ page }) => {
    await mockSettings(page, true, { notification: true })
    await page.goto('/settings')
    await page.getByLabel('반복 패턴 알림 받기').check()
    await page.getByLabel('알림 희망 시각').fill('18:30')
    await page.getByRole('button', { name: '알림 설정 저장' }).click()
    await expect(page.getByRole('alert')).toHaveText('알림 설정 저장 실패')
    await expect(page.getByLabel('알림 희망 시각')).toHaveValue('18:30')
  })
})

test.describe('FE-AUTO-023: AI 동의', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('성공: 서버의 활성 동의 상태를 표시한다', async ({ page }) => {
    await mockSettings(page, true)
    await page.goto('/settings')
    await expect(page.getByLabel('AI 분석 사용')).toBeChecked()
    await expect(page.getByText('감정 분석과 CBT 등 AI 기능을 사용할 수 있습니다.')).toBeVisible()
  })

  test('경계: 동의 철회를 취소하면 활성 상태를 유지한다', async ({ page }) => {
    await mockSettings(page, true)
    await page.goto('/settings')
    const consentToggle = page.getByLabel('AI 분석 사용')
    await consentToggle.click()
    await expect(page.getByRole('dialog', { name: 'AI 분석 동의를 철회할까요?' })).toBeVisible()
    await page.getByRole('button', { name: '취소' }).click()
    await expect(consentToggle).toBeChecked()
  })

  test('성공: 동의를 철회하면 제한 상태와 완료 문구를 표시한다', async ({ page }) => {
    await mockSettings(page, true)
    await page.goto('/settings')
    const consentToggle = page.getByLabel('AI 분석 사용')
    await consentToggle.click()
    await page.getByRole('button', { name: '동의 철회' }).click()
    await expect(page.getByText('AI 분석 동의를 철회했습니다.', { exact: true })).toBeVisible()
    await expect(consentToggle).not.toBeChecked()
    await expect(page.getByText('새로운 감정 AI 분석과 CBT 기능이 제한됩니다.')).toBeVisible()
  })

  test('성공: 비활성 동의에 다시 동의하면 활성 상태로 바뀐다', async ({ page }) => {
    await mockSettings(page, false)
    await page.goto('/settings')
    const consentToggle = page.getByLabel('AI 분석 사용')
    await expect(consentToggle).not.toBeChecked()
    await consentToggle.click()
    await expect(page.getByText('AI 분석에 다시 동의했습니다.', { exact: true })).toBeVisible()
    await expect(consentToggle).toBeChecked()
  })

  test('오류: 동의 변경 실패를 안내하고 기존 활성 상태를 유지한다', async ({ page }) => {
    await mockSettings(page, true, { consent: true })
    await page.goto('/settings')
    const consentToggle = page.getByLabel('AI 분석 사용')
    await consentToggle.click()
    await page.getByRole('button', { name: '동의 철회' }).click()
    await expect(page.getByRole('alert')).toHaveText('동의 변경 실패')
    await expect(consentToggle).toBeChecked()
    await expect(page.getByText('감정 분석과 CBT 등 AI 기능을 사용할 수 있습니다.')).toBeVisible()
  })
})
