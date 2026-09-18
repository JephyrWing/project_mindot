import { expect, test } from '@playwright/test'
import {
  completeRecord,
  mockApi,
  useAuthenticatedSession,
} from './support'

test.describe('FE-AUTO-008: 감정 기록 조회', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('성공: 검색·기간·감정·상황·정렬 조건에 맞는 결과를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (!['/api/records', '/api/records/semantic-search'].includes(url.pathname)) return undefined
      const matchesSelection = url.pathname === '/api/records/semantic-search'
        && url.searchParams.get('period') === 'WEEK'
        && url.searchParams.get('emotionCode') === 'ANXIETY'
        && url.searchParams.get('contextCategory') === 'PERFORMANCE'
        && Boolean(url.searchParams.get('query'))
      return {
        body: {
          content: matchesSelection
            ? [completeRecord({ rawText: '조건에 맞는 의미 검색 결과' })]
            : [completeRecord({ rawText: '기본 기록' })],
          page: 0,
          size: 3,
          totalElements: 1,
          totalPages: 1,
        },
      }
    })

    await page.goto('/records')
    await page.getByRole('button', { name: '이번 주' }).click()
    await page.locator('#emotion-history-emotion').selectOption('ANXIETY')
    await page.locator('#emotion-history-context').selectOption('PERFORMANCE')
    await page.locator('#emotion-history-sort').selectOption('intensity-high')
    await page.getByRole('button', { name: '의미 검색' }).click()
    await page.getByLabel('기록 검색').fill('발표 전에 걱정했던 기록')
    await page.getByRole('button', { name: '검색', exact: true }).click()
    await expect(page.getByText('조건에 맞는 의미 검색 결과')).toBeVisible()
  })

  test('성공: 목록 페이지를 이동하고 다시 이전 페이지로 돌아온다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname !== '/api/records' || request.method() !== 'GET') return undefined
      const currentPage = Number(url.searchParams.get('page') ?? 0)
      return {
        body: {
          content: [completeRecord({
            emotionRecordId: currentPage + 1,
            rawText: currentPage ? '두 번째 페이지 기록' : '첫 번째 페이지 기록',
          })],
          page: currentPage,
          size: 3,
          totalElements: 4,
          totalPages: 2,
        },
      }
    })
    await page.goto('/records')
    await expect(page.getByText('첫 번째 페이지 기록')).toBeVisible()
    await page.getByRole('button', { name: '다음' }).click()
    await expect(page.getByText('두 번째 페이지 기록')).toBeVisible()
    await page.getByRole('button', { name: '이전' }).click()
    await expect(page.getByText('첫 번째 페이지 기록')).toBeVisible()
  })

  test('성공: 상세 화면을 열고 목록으로 복귀한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/records' && request.method() === 'GET') {
        return { body: { content: [completeRecord()], page: 0, size: 3, totalElements: 1, totalPages: 1 } }
      }
      if (url.pathname === '/api/records/1') return { body: completeRecord() }
    })
    await page.goto('/records')
    await page.getByRole('button', { name: '불안 감정 기록 상세 보기' }).click()
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
    await page.getByRole('button', { name: '목록으로' }).click()
    await expect(page).toHaveURL('/records')
  })

  test('경계: 조건에 맞는 기록이 없으면 빈 상태를 안내한다', async ({ page }) => {
    await mockApi(page)
    await page.goto('/records')
    await expect(page.getByText('아직 작성한 감정 기록이 없습니다.')).toBeVisible()
  })

  test('오류: 목록 조회 실패 후 다시 불러오면 데이터를 표시한다', async ({ page }) => {
    let listCalls = 0
    await mockApi(page, (request, url) => {
      if (url.pathname !== '/api/records' || request.method() !== 'GET') return undefined
      listCalls += 1
      if (listCalls <= 2) return { status: 500, body: { message: '목록 조회 실패' } }
      return { body: { content: [completeRecord()], page: 0, size: 3, totalElements: 1, totalPages: 1 } }
    })
    await page.goto('/records')
    await expect(page.getByRole('alert')).toContainText('목록 조회 실패')
    await page.getByRole('button', { name: '다시 불러오기' }).click()
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
  })

  test('오류: 상세 404 후 다시 불러오면 상세 데이터를 복구한다', async ({ page }) => {
    let detailCalls = 0
    await mockApi(page, (_request, url) => {
      if (url.pathname !== '/api/records/1') return undefined
      detailCalls += 1
      if (detailCalls <= 2) return { status: 404, body: {} }
      return { body: completeRecord() }
    })
    await page.goto('/records/1')
    await expect(page.getByRole('alert')).toContainText('선택한 감정 기록을 찾을 수 없습니다.')
    await page.getByRole('button', { name: '다시 불러오기' }).click()
    await expect(page.getByText('발표를 앞두고 많이 긴장했다.')).toBeVisible()
  })
})
