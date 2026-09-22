import { expect, test, type Page } from '@playwright/test'
import { mockApi, openReflection, useAuthenticatedSession } from './support'

async function mockSafetyRecord(page: Page, riskLevel: 'REVIEW' | 'CRISIS') {
  await mockApi(page, (_request, url) => {
    if (url.pathname === '/api/records/quick') {
      const crisis = riskLevel === 'CRISIS'
      return {
        body: {
          recordId: crisis ? 2 : 1,
          occurredAt: '2026-09-18T01:00:00Z',
          timeBucket: 'MORNING',
          weekdayType: 'WEEKDAY',
          analysisStatus: 'PENDING',
          safetyNotice: {
            safetyEventId: crisis ? 202 : 101,
            riskLevel,
            actionCode: crisis ? 'SHOW_CRISIS_NOTICE' : 'SHOW_REVIEW_NOTICE',
            reasonCode: crisis ? 'IMMEDIATE_DANGER' : 'AMBIGUOUS_SAFETY_SIGNAL',
          },
        },
      }
    }
    if (/^\/api\/safety-events\/\d+\/notice-shown$/.test(url.pathname)) {
      return { status: 204, body: null }
    }
  })
}

test.describe('FE-AUTO-025: 안전 안내', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('성공: REVIEW 기록에 공식 연락 수단과 비진단 고지를 표시한다', async ({ page }) => {
    await mockSafetyRecord(page, 'REVIEW')
    await page.goto('/records/new')
    await page.getByLabel('지금의 감정').fill('마음이 너무 힘들다.')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alertdialog', { name: '혼자 견디지 않아도 괜찮습니다' })).toBeVisible()
    for (const number of ['112', '119', '109']) {
      await expect(page.getByRole('link', { name: new RegExp(number) })).toHaveAttribute('href', `tel:${number}`)
    }
    await expect(page.getByText(/의료적 진단 및 치료를 대신하지 않습니다/)).toBeVisible()
  })

  test('성공: CRISIS 기록에 즉시 안전 확인 안내를 표시한다', async ({ page }) => {
    await mockSafetyRecord(page, 'CRISIS')
    await page.goto('/records/new')
    await page.getByLabel('지금의 감정').fill('즉각적인 위험이 느껴진다.')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alertdialog', { name: '지금은 안전을 가장 먼저 확인해 주세요' })).toBeVisible()
    await expect(page.getByRole('link', { name: /112/ })).toHaveAttribute('href', 'tel:112')
  })

  test('오류: 안전 중단된 CBT 세션은 대화를 막고 중단 상태를 표시한다', async ({ page }) => {
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/reflections/55') {
        return { body: openReflection({ sessionId: 55, status: 'SAFETY_STOPPED' }) }
      }
      if (url.pathname === '/api/reflections/open') {
        return { body: openReflection({ sessionId: 55, status: 'SAFETY_STOPPED' }) }
      }
    })
    await page.goto('/cbt/sessions/55')
    await expect(page.getByText('안전을 위해 성찰을 중단했습니다.')).toBeVisible()
  })

  test('경계: StrictMode에서도 같은 안전 이벤트 표시 이력을 중복 전송하지 않는다', async ({ page }) => {
    let noticeAttempts = 0
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/records/quick') {
        return {
          body: {
            recordId: 1,
            occurredAt: '2026-09-18T01:00:00Z',
            timeBucket: 'MORNING',
            weekdayType: 'WEEKDAY',
            analysisStatus: 'PENDING',
            safetyNotice: {
              safetyEventId: 303,
              riskLevel: 'REVIEW',
              actionCode: 'SHOW_REVIEW_NOTICE',
              reasonCode: 'AMBIGUOUS_SAFETY_SIGNAL',
            },
          },
        }
      }
      if (url.pathname === '/api/safety-events/303/notice-shown') {
        noticeAttempts += 1
        return noticeAttempts === 1
          ? { status: 204, body: null }
          : { status: 500, body: { message: '중복 표시 이력' } }
      }
    })

    await page.goto('/records/new')
    await page.getByLabel('지금의 감정').fill('안전 안내 중복 전송 확인')
    await page.getByRole('button', { name: '기록하기' }).click()
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await page.waitForTimeout(100)
    await expect(page.getByText('안내 표시 이력을 서버에 기록하지 못했지만')).toHaveCount(0)
  })
})
