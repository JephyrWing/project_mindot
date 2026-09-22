import { expect, test } from '@playwright/test'
import { completeRecord, mockApi, readJsonBody, useAuthenticatedSession } from './support'

test.beforeEach(async ({ page }) => { await useAuthenticatedSession(page) })

test('구조화 확인 단계는 수정 버튼 없이 바로 편집한다', async ({ page }) => {
  await mockApi(page, (_request, url) => url.pathname === '/api/records/1'
    ? { body: completeRecord({ completionStatus: 'PARTIAL', cbtStarted: false }) } : undefined)
  await page.goto('/records/1')
  await expect(page.getByRole('button', { name: '수정하기', exact: true })).toHaveCount(0)
  await expect(page.locator('textarea[name="situationText"]')).toBeEditable()
  await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
})

test('완료 기록의 전체 내용을 수정하고 새로고침해도 유지한다', async ({ page }) => {
  let record = completeRecord({ cbtStarted: false })
  let writes = 0
  await mockApi(page, (request, url) => {
    if (url.pathname !== '/api/records/1') return
    if (request.method() === 'PATCH') {
      writes++
      const { analysis, ...fields } = readJsonBody(request)
      expect(analysis.automaticThought).toBeNull()
      expect(analysis.primaryEmotionCode).toBe('짜증')
      expect(analysis.primaryIntensity).toBe(3)
      expect(analysis.secondaryEmotions).toEqual([{ code: 'JOY', intensity: 2 }])
      expect(analysis.contextCategory).toBe('HEALTH')
      expect(analysis.relatedPersonType).toBe('FRIEND')
      expect(analysis.details.bodyReaction).toBe('손 떨림')
      expect(analysis.details.behavior).toBe('산책')
      record = { ...record, ...fields, ...analysis }
    }
    return { body: record }
  })
  await page.goto('/records/1')
  await expect(page.getByText('해석', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '수정하기', exact: true }).waitFor()
  await page.screenshot({ path: test.info().outputPath('detail-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: test.info().outputPath('detail-mobile.png') })
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await page.getByRole('textbox', { name: '기록 원문', exact: true }).fill('수정한 원문')
  await page.getByLabel('날짜와 시간').fill('2020-01-01T12:00')
  await page.locator('textarea[name="situationText"]').fill('새 상황')
  await page.locator('textarea[name="automaticThought"]').fill('  ')
  await page.locator('select[name="primaryEmotionCode"]').selectOption('짜증')
  await page.getByLabel('대표 감정 강도', { exact: true }).fill('3')
  await page.getByLabel('상황 범주').selectOption('HEALTH')
  await page.getByLabel('관련된 사람').selectOption('FRIEND')
  // Start with an empty secondary list regardless of the default fixture.
  while (await page.locator('.emotion-detail-secondary-emotions button').filter({ hasText: /^삭제$/ }).count()) {
    await page.locator('.emotion-detail-secondary-emotions button').filter({ hasText: /^삭제$/ }).first().click()
  }
  await page.getByRole('button', { name: '보조 감정 추가' }).click()
  await page.getByLabel('보조 감정 1', { exact: true }).selectOption('JOY')
  await page.getByLabel('보조 감정 1 강도', { exact: true }).fill('2')
  await page.getByRole('textbox', { name: '신체 반응', exact: true }).fill('손 떨림')
  await page.getByRole('textbox', { name: '행동', exact: true }).fill('산책')
  await expect(page.getByText('해석', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'CBT 검사 하기' })).toBeDisabled()
  await page.locator('.emotion-detail-edit-actions').scrollIntoViewIfNeeded()
  await page.screenshot({ path: test.info().outputPath('edit-actions-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.locator('.emotion-detail-edit-actions').scrollIntoViewIfNeeded()
  await page.screenshot({ path: test.info().outputPath('edit-actions-mobile.png') })
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.getByRole('button', { name: '수정 내용 저장하기' }).click()
  await expect(page.getByText('감정 기록을 수정했습니다.', { exact: true })).toBeVisible()
  expect(writes).toBe(1)
  await page.reload()
  await expect(page.getByText('수정한 원문', { exact: true })).toBeVisible()
  await expect(page.locator('.emotion-detail-tags strong')).toHaveText('짜증')
  await expect(page.getByRole('time')).toContainText('2020년 1월 1일')
  await expect(page.locator('.emotion-detail-list')).toContainText('새 상황')
  await expect(page.locator('.emotion-detail-list')).toContainText('기쁨 2/10')
})

test('수정 취소는 원래 값을 복원하고 저장 요청을 보내지 않는다', async ({ page }) => {
  let writes = 0
  await mockApi(page, (request, url) => {
    if (url.pathname !== '/api/records/1') return
    if (request.method() === 'PATCH') writes++
    return { body: completeRecord() }
  })
  await page.goto('/records/1')
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await page.getByLabel('기록 원문').fill('취소할 수정')
  await page.getByRole('button', { name: '수정 취소' }).click()
  expect(writes).toBe(0)
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await expect(page.getByLabel('기록 원문')).toHaveValue(completeRecord().rawText)
})

for (const completionStatus of ['PARTIAL', 'COMPLETE']) {
  test(`${completionStatus}: CBT가 시작되면 원문·분석·시각 수정이 차단된다`, async ({ page }) => {
    await mockApi(page, (_request, url) => url.pathname === '/api/records/1'
      ? { body: completeRecord({ cbtStarted: true, completionStatus }) } : undefined)
    await page.goto('/records/1')
    await expect(page.getByText('CBT가 시작된 기록은 수정할 수 없습니다.', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '수정하기', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toHaveCount(0)
    await expect(page.getByLabel('날짜와 시간')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '시각 수정하기' })).toHaveCount(0)
  })
}

test('수정 중 다른 창에서 CBT가 시작되면 충돌을 안내하고 입력을 보존한다', async ({ page }) => {
  await mockApi(page, (request, url) => url.pathname === '/api/records/1'
    ? request.method() === 'PATCH' ? { status: 409, body: {} } : { body: completeRecord() } : undefined)
  await page.goto('/records/1')
  await page.getByRole('button', { name: '수정하기', exact: true }).click()
  await page.getByLabel('기록 원문').fill('저장 시도한 원문')
  await page.getByRole('button', { name: '수정 내용 저장하기' }).click()
  await expect(page.getByRole('alert')).toContainText('CBT가 시작되었거나')
  await expect(page.getByLabel('기록 원문')).toHaveValue('저장 시도한 원문')
})
