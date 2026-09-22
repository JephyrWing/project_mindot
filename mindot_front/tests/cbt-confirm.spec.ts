import { expect, test, type Page } from '@playwright/test'
import {
  mockApi,
  openReflection,
  proposal,
  readJsonBody,
  useAuthenticatedSession,
} from './support'

const reviewView = openReflection({
  revision: 5,
  phase: 'PROPOSAL_REVIEW',
  currentProposal: proposal,
  messages: [{ messageNumber: 1, role: 'AI', content: '제안을 확인해 주세요.' }],
})

async function prepareReview(
  page: Page,
  confirmResult: 'success' | 'conflict' = 'success',
  reviewStatus: 'CONFIRMED' | 'REJECTED' = 'REJECTED',
) {
  await useAuthenticatedSession(page)
  await mockApi(page, (request, url) => {
    if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: reviewView }
    if (url.pathname === '/api/reflections/open') return { body: reviewView }
    if (url.pathname === '/api/reflections/51/confirm') {
      if (confirmResult === 'conflict') {
        return { status: 409, body: { message: '현재 상태에서는 확정할 수 없습니다.' } }
      }
      return {
        body: openReflection({
          revision: 6,
          status: 'COMPLETED',
          phase: 'COMPLETED',
          currentProposal: null,
          confirmedResult: {
            ...proposal,
            reviews: [{ code: 'CATASTROPHIZING_FORTUNE_TELLING', reviewStatus }],
          },
        }),
      }
    }
    if (url.pathname === '/api/reflections/51/retry-embedding') {
      return { status: 204, body: null }
    }
  })
  await page.goto('/cbt/sessions/51')
}

async function fillValidReview(page: Page) {
  await page.getByLabel('이 설명이 내 생각과 맞나요?').selectOption('REJECTED')
  const scoreInputs = page.locator('.cbt-confirm-scores input')
  await scoreInputs.nth(0).fill('80')
  await scoreInputs.nth(1).fill('40')
  await scoreInputs.nth(2).fill('3')
  await scoreInputs.nth(3).fill('4')
}

test.describe('FE-AUTO-014: CBT 결과 확정', () => {
  test('경계: 유형 검토가 없으면 결과를 확정하지 않는다', async ({ page }) => {
    await prepareReview(page)
    const scoreInputs = page.locator('.cbt-confirm-scores input')
    await scoreInputs.nth(0).fill('80')
    await scoreInputs.nth(1).fill('40')
    await scoreInputs.nth(2).fill('3')
    await scoreInputs.nth(3).fill('4')
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()
    await expect(page.getByRole('heading', { name: '확인한 성찰 결과' })).toHaveCount(0)
    await expect(page.getByLabel('이 설명이 내 생각과 맞나요?')).toBeVisible()
  })

  test('경계: 점수 범위를 벗어나면 결과를 확정하지 않는다', async ({ page }) => {
    await prepareReview(page)
    await page.getByLabel('이 설명이 내 생각과 맞나요?').selectOption('REJECTED')
    const scoreInputs = page.locator('.cbt-confirm-scores input')
    await scoreInputs.nth(0).fill('101')
    await scoreInputs.nth(1).fill('40')
    await scoreInputs.nth(2).fill('3')
    await scoreInputs.nth(3).fill('4')
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()
    await expect(page.getByRole('heading', { name: '확인한 성찰 결과' })).toHaveCount(0)
    await expect(scoreInputs.nth(0)).toHaveValue('101')
  })

  test('오류: 확정 충돌을 안내하고 검토 화면을 유지한다', async ({ page }) => {
    await prepareReview(page, 'conflict')
    await fillValidReview(page)
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()
    await expect(page.getByRole('alert')).toContainText('현재 상태에서는 확정할 수 없습니다.')
    await expect(page.getByLabel('이 설명이 내 생각과 맞나요?')).toBeVisible()
  })

  test('성공: 유효한 검토와 점수를 저장해 완료 결과를 유지한다', async ({ page }) => {
    await prepareReview(page)
    await fillValidReview(page)
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()
    await expect(page.getByText('성찰 결과가 저장됐습니다.')).toBeVisible()
    await expect(page.getByRole('heading', { name: '확인한 성찰 결과' })).toBeVisible()
    await expect(page.getByText('동의하지 않은 제안')).toBeVisible()
  })

  test('성공: 수락한 유형은 사용자가 확인한 패턴으로 완료 결과에 표시한다', async ({ page }) => {
    await prepareReview(page, 'success', 'CONFIRMED')
    await page.getByLabel('이 설명이 내 생각과 맞나요?').selectOption('CONFIRMED')
    const scoreInputs = page.locator('.cbt-confirm-scores input')
    await scoreInputs.nth(0).fill('80')
    await scoreInputs.nth(1).fill('40')
    await scoreInputs.nth(2).fill('3')
    await scoreInputs.nth(3).fill('4')
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()
    await expect(page.getByText('내가 확인한 패턴')).toBeVisible()
  })

  test('성공: BEFORE·AFTER 라벨을 추가·제거하고 제거·지속·신규 변화를 저장한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    let submittedBody: Record<string, unknown> | null = null

    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: reviewView }
      if (url.pathname === '/api/reflections/open') return { body: reviewView }
      if (url.pathname === '/api/reflections/51/confirm') {
        submittedBody = readJsonBody(request) as Record<string, unknown>
        return {
          body: openReflection({
            revision: 6,
            status: 'COMPLETED',
            phase: 'COMPLETED',
            currentProposal: null,
            confirmedResult: {
              ...proposal,
              reviews: submittedBody.reviews,
              beforeDistortions: submittedBody.beforeDistortions,
              afterDistortions: submittedBody.afterDistortions,
            },
          }),
        }
      }
    })

    await page.goto('/cbt/sessions/51')
    await page.getByLabel('이 설명이 내 생각과 맞나요?').selectOption('CONFIRMED')

    await page.getByLabel('BEFORE 라벨 선택').selectOption('PERSONALIZATION')
    await page.getByRole('button', { name: 'BEFORE 라벨 추가' }).click()
    await page.getByRole('button', { name: 'BEFORE 파국화·미래예측 라벨 제거' }).click()
    await page.getByLabel('BEFORE 라벨 선택').selectOption('CATASTROPHIZING_FORTUNE_TELLING')
    await page.getByRole('button', { name: 'BEFORE 라벨 추가' }).click()

    await page.getByLabel('AFTER 라벨 선택').selectOption('CATASTROPHIZING_FORTUNE_TELLING')
    await page.getByRole('button', { name: 'AFTER 라벨 추가' }).click()
    await page.getByLabel('AFTER 라벨 선택').selectOption('MIND_READING')
    await page.getByRole('button', { name: 'AFTER 라벨 추가' }).click()

    const scoreInputs = page.locator('.cbt-confirm-scores input')
    await scoreInputs.nth(0).fill('80')
    await scoreInputs.nth(1).fill('40')
    await scoreInputs.nth(2).fill('3')
    await scoreInputs.nth(3).fill('4')
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()

    expect(submittedBody).toMatchObject({
      beforeDistortions: [
        { code: 'PERSONALIZATION', reviewStatus: 'CONFIRMED' },
        { code: 'CATASTROPHIZING_FORTUNE_TELLING', reviewStatus: 'CONFIRMED' },
      ],
      afterDistortions: [
        { code: 'CATASTROPHIZING_FORTUNE_TELLING', reviewStatus: 'CONFIRMED' },
        { code: 'MIND_READING', reviewStatus: 'CONFIRMED' },
      ],
    })

    const changes = page.getByLabel('인지왜곡 라벨 변화')
    await expect(changes.locator('.is-removed')).toContainText('개인화')
    await expect(changes.locator('.is-persisted')).toContainText('파국화·미래예측')
    await expect(changes.locator('.is-new')).toContainText('독심술')
  })

  test('경계: 최종 제안의 뜻을 정정하면 확정하지 않고 대화를 이어간다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const correctedView = openReflection({
      revision: 6,
      phase: 'QUESTIONING',
      currentProposal: null,
      messages: [
        ...reviewView.messages,
        { messageNumber: 2, role: 'USER', content: '제 뜻은 실수해도 다시 이어갈 수 있다는 의미예요.' },
        { messageNumber: 3, role: 'AI', content: '정정한 의미를 반영해 다시 살펴볼게요.' },
      ],
    })
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reflections/51' && request.method() === 'GET') return { body: reviewView }
      if (url.pathname === '/api/reflections/open') return { body: reviewView }
      if (url.pathname === '/api/reflections/51/turn') return { body: correctedView }
    })
    await page.goto('/cbt/sessions/51')
    await page.getByLabel('제안의 뜻 정정 또는 설명 요청').fill('제 뜻은 실수해도 다시 이어갈 수 있다는 의미예요.')
    await page.getByRole('button', { name: '보내기' }).click()
    await expect(page.getByText('정정한 의미를 반영해 다시 살펴볼게요.')).toBeVisible()
    await expect(page.getByRole('heading', { name: '확인한 성찰 결과' })).toHaveCount(0)
  })

  test('성공: 확정 완료 후 검색 연결 재시도를 화면에서 완료한다', async ({ page }) => {
    await prepareReview(page)
    await fillValidReview(page)
    await page.getByRole('button', { name: '이 생각과 유형 검토를 확인하고 저장' }).click()
    await page.getByRole('button', { name: '검색 연결 다시 시도' }).click()
    await expect(page.getByRole('status')).toHaveText('완료 결과의 검색 연결을 다시 준비했습니다.')
    await expect(page.getByRole('heading', { name: '확인한 성찰 결과' })).toBeVisible()
  })

  test('반응형: 휴대전화에서도 BEFORE·AFTER 편집 영역이 화면 밖으로 넘치지 않는다', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await prepareReview(page)

    await expect(page.getByLabel('BEFORE/AFTER 인지왜곡 비교')).toBeVisible()
    const viewportWidths = await page.evaluate(() => ({
      content: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }))
    expect(viewportWidths.content).toBeLessThanOrEqual(viewportWidths.viewport)
  })
})
