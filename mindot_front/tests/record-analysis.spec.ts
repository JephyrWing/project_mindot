import { expect, test } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  readJsonBody,
  useAuthenticatedSession,
} from './support'

const quickRecord = completeRecord({
  completionStatus: 'QUICK',
  analysisStatus: 'FAILED',
  automaticThought: null,
  primaryEmotionCode: null,
  primaryIntensity: null,
})

const partialRecord = completeRecord({ completionStatus: 'PARTIAL' })

test.describe('FE-AUTO-009: 감정 분석 편집', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('경계: 미래 발생 시각은 저장하지 않고 기존 상세를 유지한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: completeRecord() }
      }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '수정하기', exact: true }).click()
    const occurredAt = page.getByLabel('날짜와 시간')
    await occurredAt.fill('2999-01-01T12:00')
    await page.getByRole('button', { name: '수정 내용 저장하기' }).click()
    await expect(page.getByText('감정 기록을 수정했습니다.')).toHaveCount(0)
    await expect(page.getByRole('time')).toContainText('2026년 9월 15일')
  })

  test('성공: 과거 발생 시각을 저장하고 화면 시간을 갱신한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: completeRecord() }
      if (url.pathname === '/api/records/1' && request.method() === 'PATCH') {
        return { body: { ...completeRecord(), occurredAt: readJsonBody(request).occurredAt } }
      }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '수정하기', exact: true }).click()
    const occurredAt = page.getByLabel('날짜와 시간')
    await occurredAt.fill('2020-01-01T12:00')
    await page.getByRole('button', { name: '수정 내용 저장하기' }).click()
    await expect(page.getByRole('status')).toContainText('감정 기록을 수정했습니다.')
    await expect(page.getByRole('time')).toContainText('2020년 1월 1일')
  })

  test('성공: 실패한 AI 분석을 다시 요청해 편집 가능한 제안을 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: quickRecord }
      if (url.pathname === '/api/records/1/reanalyze') return { body: partialRecord }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '다시 분석하기' }).click()
    await expect(page.getByText('AI 재분석을 완료했습니다. 제안된 내용을 확인해 주세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
  })

  test('오류: AI 재분석 서버 장애를 별도 안내한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: quickRecord }
      if (url.pathname === '/api/records/1/reanalyze') return { status: 503, body: {} }
    })
    await page.goto('/records/1')
    await page.getByRole('button', { name: '다시 분석하기' }).click()
    await expect(page.getByRole('alert')).toContainText('AI 분석 서버가 일시적으로 응답하지 않습니다')
  })

  test('경계: 범위를 벗어난 대표 감정 강도는 확정하지 않는다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: partialRecord }
      if (url.pathname === '/api/records/1/confirm') return { body: completeRecord() }
    })
    await page.goto('/records/1')
    const primaryIntensity = page.getByLabel('대표 감정 강도')
    await primaryIntensity.fill('11')
    await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
    await expect(page.getByText('사용자 확정값')).toHaveCount(0)
  })

  test('성공: 유효한 분석값을 확정해 COMPLETE 결과를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: partialRecord }
      if (url.pathname === '/api/records/1/confirm') return { body: completeRecord() }
    })
    await page.goto('/records/1')
    const primaryIntensity = page.getByLabel('대표 감정 강도')
    await primaryIntensity.fill('8')
    await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
    await expect(page.getByText('수정한 분석 결과를 최종 확정했습니다.')).toBeVisible()
    await expect(page.getByText('사용자 확정값')).toBeVisible()
  })

  test('성공: 누락 정보 보완 질문을 조회하고 해당 답변 입력칸으로 이동한다', async ({ page }) => {
    let questionCalls = 0
    const recordWithMissingInformation = completeRecord({
      completionStatus: 'PARTIAL',
      situationText: null,
      primaryIntensity: null,
    })

    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: recordWithMissingInformation }
      }
      if (url.pathname === '/api/records/1/questions/missing' && request.method() === 'GET') {
        questionCalls += 1
        return {
          body: {
            emotionRecordId: 1,
            completionStatus: 'PARTIAL',
            questions: [
              {
                fieldName: 'situationText',
                question: '어떤 상황에서 이런 감정을 느꼈나요?',
                required: false,
              },
              {
                fieldName: 'primaryIntensity',
                question: '그 감정의 강도는 0부터 10 중 어느 정도였나요?',
                required: false,
              },
            ],
          },
        }
      }
    })

    await page.goto('/records/1')

    await expect(page.getByRole('heading', { name: '조금 더 알려 주세요' })).toBeVisible()
    const situationQuestion = page.getByRole('button', { name: /어떤 상황에서 이런 감정을 느꼈나요/ })
    await expect(situationQuestion).toContainText('선택 답변')
    await situationQuestion.click()
    await expect(page.locator('[name="situationText"]')).toBeFocused()
    expect(questionCalls).toBeGreaterThanOrEqual(1)
  })

  test('성공: AI 제안을 거절하고 원문만 간편 기록으로 유지한다', async ({ page }) => {
    let rejectCalls = 0
    let confirmCalls = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') {
        return { body: partialRecord }
      }
      if (url.pathname === '/api/records/1/reject' && request.method() === 'POST') {
        rejectCalls += 1
        return {
          body: completeRecord({
            completionStatus: 'QUICK',
            analysisStatus: 'REJECTED',
            situationText: null,
            automaticThought: null,
            primaryEmotionCode: null,
            primaryIntensity: null,
            secondaryEmotions: [],
            contextCategory: null,
            relatedPersonType: null,
            details: {},
          }),
        }
      }
      if (url.pathname === '/api/records/1/confirm') {
        confirmCalls += 1
      }
    })

    await page.goto('/records/1')
    await page.getByRole('button', { name: 'AI 제안 거절하기' }).click()

    await expect(page.getByText('AI 제안을 거절했습니다. 작성한 원문은 그대로 저장됩니다.')).toBeVisible()
    await expect(page.getByText('제안 거절됨')).toBeVisible()
    await expect(page.getByRole('button', { name: '다시 분석하기' })).toBeVisible()
    await expect(page.getByText(partialRecord.rawText)).toBeVisible()
    expect(rejectCalls).toBe(1)
    expect(confirmCalls).toBe(0)
  })

  test('오류: AI 제안 거절 실패를 안내하고 제안 편집 화면을 유지한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: partialRecord }
      if (url.pathname === '/api/records/1/reject') return { status: 503, body: {} }
    })

    await page.goto('/records/1')
    await page.getByRole('button', { name: 'AI 제안 거절하기' }).click()

    await expect(page.getByRole('alert')).toContainText('AI 제안을 거절하지 못했습니다')
    await expect(page.getByRole('button', { name: 'AI 제안 거절하기' })).toBeVisible()
    await expect(page.getByRole('button', { name: '수정한 결과 확정하기' })).toBeVisible()
  })
})


test('확정한 기록의 비어 있는 항목은 대시로 표시한다', async ({ page }) => {
  await useAuthenticatedSession(page)
  await mockApi(page, (_request, url) => url.pathname === '/api/records/1' ? {
    body: completeRecord({ situationText: '   ', automaticThought: null,
      primaryEmotionCode: null, primaryIntensity: null, contextCategory: '',
      relatedPersonType: null, secondaryEmotions: [],
      details: { interpretation: '', bodyReaction: null, behavior: '  ' } }),
  } : undefined)
  await page.goto('/records/1')
  for (const label of ['상황', '자동으로 떠오른 생각', '감정', '함께 느낀 감정', '관련된 사람', '신체 반응', '행동']) {
    const row = page.locator('.emotion-detail-list > div').filter({ has: page.locator('dt').filter({ hasText: new RegExp(`^${label}$`) }) })
    await expect(row.locator('dd')).toHaveText('-')
  }
  await expect(page.locator('.emotion-detail-tags strong')).toHaveText('-')
  await expect(page.locator('.emotion-detail-tags span').nth(0)).toHaveText('-')
  await expect(page.locator('.emotion-detail-tags span').nth(1)).toHaveText('-')
  await expect(page.getByText(/분석 전/)).toHaveCount(0)
})

test('직접 입력한 짜증은 입력 중, 확정 후와 재조회 후 모두 그대로 표시한다', async ({ page }) => {
  await useAuthenticatedSession(page)
  let record = completeRecord({ completionStatus: 'PARTIAL', primaryEmotionCode: null, primaryIntensity: 0 })
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/records/1' && request.method() === 'GET') return { body: record }
    if (url.pathname === '/api/records/1/confirm') {
      const changes = readJsonBody(request)
      expect(changes.primaryEmotionCode).toBe('짜증')
      record = { ...record, ...changes, completionStatus: 'COMPLETE' }
      return { body: record }
    }
  })
  await page.goto('/records/1')
  await page.locator('select[name="primaryEmotionCode"]').selectOption('__CUSTOM_EMOTION__')
  await page.getByRole('textbox', { name: '직접 입력 감정 이름' }).fill('  짜증  ')
  await expect(page.locator('.emotion-detail-tags strong')).toHaveText('짜증')
  await page.getByRole('button', { name: '수정한 결과 확정하기' }).click()
  await expect(page.getByText('사용자 확정값')).toBeVisible()
  await expect(page.locator('.emotion-detail-tags strong')).toHaveText('짜증')
  await expect(page.locator('.emotion-detail-tags')).toContainText('강도 0/10')
  await page.reload()
  await expect(page.locator('.emotion-detail-tags strong')).toHaveText('짜증')
})
