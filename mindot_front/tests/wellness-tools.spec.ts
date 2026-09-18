import { expect, test, type Page } from '@playwright/test'
import { mockApi, useAuthenticatedSession } from './support'

async function prepareWellnessTools(page: Page) {
  await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
  await useAuthenticatedSession(page)
  await mockApi(page)
}

async function enableVoiceGuidance(page: Page) {
  await page.addInitScript(() => {
    class MockUtterance {
      text: string
      lang = ''
      rate = 1
      constructor(text: string) { this.text = text }
    }
    Object.defineProperty(window, 'SpeechSynthesisUtterance', {
      configurable: true,
      value: MockUtterance,
    })
    Object.defineProperty(window, 'speechSynthesis', {
      configurable: true,
      value: { speak: () => {}, cancel: () => {} },
    })
  })
}

async function disableVoiceGuidance(page: Page) {
  await page.addInitScript(() => {
    Reflect.deleteProperty(window, 'speechSynthesis')
    Reflect.deleteProperty(window, 'SpeechSynthesisUtterance')
  })
}

test.describe('FE-AUTO-028: 마음 돌봄 도구', () => {
  test.beforeEach(async ({ page }) => {
    await prepareWellnessTools(page)
  })

  test('성공: 호흡 타이머를 시작·정지하고 처음 시간으로 초기화한다', async ({ page }) => {
    await page.goto('/daily-care/breathing')
    await expect(page.getByRole('timer')).toHaveText('3:00')
    await page.getByRole('button', { name: '호흡 시작하기' }).click()
    await page.clock.fastForward(5_000)
    await expect(page.getByRole('timer')).toHaveText('2:55')
    await page.getByRole('button', { name: '잠시 멈추기' }).click()
    await page.clock.fastForward(3_000)
    await expect(page.getByRole('timer')).toHaveText('2:55')
    await page.getByRole('button', { name: '처음부터' }).click()
    await expect(page.getByRole('timer')).toHaveText('3:00')
  })

  test('경계: 진행 중인 호흡을 수동으로 마치면 완료 화면을 표시한다', async ({ page }) => {
    await page.goto('/daily-care/breathing')
    await page.getByRole('button', { name: '호흡 시작하기' }).click()
    await page.clock.fastForward(1_000)
    await page.getByRole('button', { name: '호흡 마치기' }).click()
    await expect(page.getByRole('heading', { name: '호흡을 마쳤어요' })).toBeVisible()
  })

  test('성공: 일시정지한 호흡을 남은 시간부터 재개한다', async ({ page }) => {
    await page.goto('/daily-care/breathing')
    await page.getByRole('button', { name: '호흡 시작하기' }).click()
    await page.clock.fastForward(5_000)
    await page.getByRole('button', { name: '잠시 멈추기' }).click()
    await expect(page.getByRole('timer')).toHaveText('2:55')
    await page.getByRole('button', { name: '이어서 시작하기' }).click()
    await page.clock.fastForward(5_000)
    await expect(page.getByRole('timer')).toHaveText('2:50')
  })

  test('성공: 호흡 시간이 끝나면 자동으로 완료 화면을 표시한다', async ({ page }) => {
    await page.goto('/daily-care/breathing')
    await page.getByRole('button', { name: '호흡 시작하기' }).click()
    await page.clock.fastForward(180_500)
    await expect(page.getByRole('heading', { name: '호흡을 마쳤어요' })).toBeVisible()
    await expect(page.getByRole('timer')).toHaveText('0:00')
  })

  test('성공: 명상 단계를 진행·정지하고 수동 완료한다', async ({ page }) => {
    await page.goto('/daily-care/meditation')
    await page.getByRole('button', { name: '명상 시작하기' }).click()
    await page.clock.fastForward(16_000)
    await expect(page.getByText('2/4 단계')).toBeVisible()
    await page.getByRole('button', { name: '잠시 멈추기' }).click()
    await expect(page.getByRole('status')).toHaveText('일시정지')
    await page.getByRole('button', { name: '명상 마치기' }).click()
    await expect(page.getByRole('status')).toHaveText('명상 완료')
  })

  test('성공: 일시정지한 명상을 재개하고 시간이 끝나면 자동 완료한다', async ({ page }) => {
    await page.goto('/daily-care/meditation')
    await page.getByRole('button', { name: '명상 시작하기' }).click()
    await page.clock.fastForward(16_000)
    await page.getByRole('button', { name: '잠시 멈추기' }).click()
    await expect(page.getByRole('timer')).toHaveText('0:44')
    await page.getByRole('button', { name: '이어서 시작하기' }).click()
    await page.clock.fastForward(44_500)
    await expect(page.getByRole('status')).toHaveText('명상 완료')
    await expect(page.getByRole('heading', { name: '명상을 마쳤어요' })).toBeVisible()
  })

  test('성공: 지원 브라우저에서 음성 안내 선택 상태와 화면 안내를 함께 표시한다', async ({ page }) => {
    await enableVoiceGuidance(page)
    await page.goto('/daily-care/meditation')
    await expect(page.getByLabel('음성 안내 사용')).toBeChecked()
    await page.getByRole('button', { name: '명상 시작하기' }).click()
    await expect(page.getByRole('status')).toHaveText('명상 진행 중')
    await expect(page.getByRole('heading', { name: '자세 가다듬기' })).toBeVisible()
    await expect(page.getByText('등과 어깨의 힘을 풀고 편안한 자세를 만들어 보세요.')).toBeVisible()
  })

  test('오류: 음성 합성 미지원 환경에서도 화면 안내로 명상을 진행한다', async ({ page }) => {
    await disableVoiceGuidance(page)
    await page.goto('/daily-care/meditation')
    await expect(page.getByText('음성 안내를 지원하지 않는 브라우저에서는 화면 안내만 제공됩니다.')).toBeVisible()
    await expect(page.getByLabel('음성 안내 사용')).toBeDisabled()
    await expect(page.getByLabel('음성 안내 사용')).not.toBeChecked()
    await page.getByRole('button', { name: '명상 시작하기' }).click()
    await expect(page.getByRole('status')).toHaveText('명상 진행 중')
    await expect(page.getByRole('heading', { name: '자세 가다듬기' })).toBeVisible()
  })
})
