import { expect, test } from '@playwright/test'
import { mockApi, useAuthenticatedSession, useGuestSession } from './support'

const insightResponse = (groupBy: string) => {
  if (groupBy === 'time') {
    return {
      groupBy,
      totalSampleCount: 3,
      groups: [
        { groupCode: 'MORNING', sampleCount: 2, emotionCounts: { ANXIETY: 2 } },
        { groupCode: 'EVENING', sampleCount: 1, emotionCounts: { JOY: 1 } },
      ],
    }
  }
  if (groupBy === 'situation') {
    return {
      groupBy,
      totalSampleCount: 3,
      groups: [
        { groupCode: 'WORK', sampleCount: 2, emotionCounts: { ANXIETY: 1, JOY: 1 } },
        { groupCode: 'PERFORMANCE', sampleCount: 1, emotionCounts: { ANXIETY: 1 } },
      ],
    }
  }

  return {
    groupBy,
    totalSampleCount: 3,
    groups: [
      { groupCode: 'COLLEAGUE', sampleCount: 2, emotionCounts: { ANXIETY: 2 } },
      { groupCode: 'UNSPECIFIED', sampleCount: 1, emotionCounts: { JOY: 1 } },
    ],
  }
}

test.describe('FE-AUTO-030: 감정 인사이트', () => {
  test('성공: 전용 화면에서 시간대·상황·관계별 분포와 표본 수를 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    const requestedGroupBys = new Set<string>()
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/insights/emotions' && request.method() === 'GET') {
        const groupBy = url.searchParams.get('groupBy') ?? ''
        requestedGroupBys.add(groupBy)
        return { body: insightResponse(groupBy) }
      }
    })

    await page.goto('/insights/emotions')

    await expect(page).toHaveURL('/insights/emotions')
    await expect(page.getByRole('heading', { name: '감정 인사이트' })).toBeVisible()
    await expect(page.getByText('총 3개의 확정 기록')).toBeVisible()

    const timeSection = page.getByRole('region', { name: '시간대별 감정 분포' })
    await expect(timeSection.getByRole('heading', { name: '아침' })).toBeVisible()
    await expect(timeSection.getByText('표본 2개')).toBeVisible()
    await expect(timeSection.getByRole('img', { name: '아침: 불안 2개' })).toBeVisible()

    const situationSection = page.getByRole('region', { name: '상황별 감정 분포' })
    await expect(situationSection.getByRole('heading', { name: '업무' })).toBeVisible()
    await expect(situationSection.getByRole('img', { name: /업무:.*불안 1개.*기쁨 1개|업무:.*기쁨 1개.*불안 1개/ })).toBeVisible()

    const relationshipSection = page.getByRole('region', { name: '관계별 감정 분포' })
    await expect(relationshipSection.getByRole('heading', { name: '직장 동료' })).toBeVisible()
    await expect(relationshipSection.getByRole('heading', { name: '관계 정보 없음' })).toBeVisible()
    expect([...requestedGroupBys].sort()).toEqual(['relationship', 'situation', 'time'])
  })

  test('성공: 사이드바에서 감정 인사이트 전용 화면으로 이동한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/insights/emotions' && request.method() === 'GET') {
        return { body: insightResponse(url.searchParams.get('groupBy') ?? '') }
      }
    })

    await page.goto('/')
    await page.getByRole('button', { name: '메뉴 열기' }).click()
    await page.getByRole('link', { name: '감정 인사이트' }).click()
    await expect(page).toHaveURL('/insights/emotions')
    await expect(page.getByRole('heading', { name: '감정 인사이트' })).toBeVisible()
  })

  test('빈 상태: 확정 기록이 없으면 세 분포마다 안내를 표시한다', async ({ page }) => {
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/insights/emotions' && request.method() === 'GET') {
        return {
          body: {
            groupBy: url.searchParams.get('groupBy'),
            totalSampleCount: 0,
            groups: [],
          },
        }
      }
    })

    await page.goto('/insights/emotions')
    await expect(page.getByText('총 0개의 확정 기록')).toBeVisible()
    await expect(page.getByText('표시할 확정 기록이 없습니다.')).toHaveCount(3)
  })

  test('오류: 인사이트 조회 실패 후 다시 시도할 수 있다', async ({ page }) => {
    await useAuthenticatedSession(page)
    let shouldFail = true
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/insights/emotions' && request.method() === 'GET') {
        if (shouldFail) return { status: 500, body: {} }
        return { body: insightResponse(url.searchParams.get('groupBy') ?? '') }
      }
    })

    await page.goto('/insights/emotions')
    await expect(page.getByRole('alert')).toContainText('감정 인사이트를 불러오지 못했습니다.')
    shouldFail = false
    await page.getByRole('button', { name: '다시 시도' }).click()
    await expect(page.getByText('총 3개의 확정 기록')).toBeVisible()
  })

  test('반응형: 휴대전화 폭에서도 세 분포가 화면 밖으로 넘치지 않는다', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/insights/emotions' && request.method() === 'GET') {
        return { body: insightResponse(url.searchParams.get('groupBy') ?? '') }
      }
    })

    await page.goto('/insights/emotions')
    await expect(page.getByRole('region', { name: '관계별 감정 분포' })).toBeVisible()
    const viewportWidths = await page.evaluate(() => ({
      content: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }))
    expect(viewportWidths.content).toBeLessThanOrEqual(viewportWidths.viewport)
  })

  test('권한: 비로그인 사용자의 전용 화면 직접 접근을 차단한다', async ({ page }) => {
    await useGuestSession(page)
    await mockApi(page)
    await page.goto('/insights/emotions')
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('dialog', { name: '로그인이 필요한 서비스입니다' })).toBeVisible()
  })
})
