import { expect, test, type Page } from '@playwright/test'
import { mockApi, useGuestSession } from './support'

async function mockCenters(page: Page, failFirstSearch = false) {
  let searchAttempts = 0
  await mockApi(page, (_request, url) => {
    if (url.pathname !== '/api/centers') return undefined
    searchAttempts += 1
    if (failFirstSearch && searchAttempts === 1) {
      return { status: 500, body: { message: '기관 검색 실패' } }
    }
    const currentPage = Number(url.searchParams.get('page') ?? 0)
    return {
      body: {
        content: [{
          centerId: currentPage + 1,
          name: currentPage ? '두 번째 페이지 마음센터' : '강남 마음건강센터',
          categoryName: '정신건강복지센터',
          roadAddress: '서울특별시 강남구 테스트로 1',
          address: '서울특별시 강남구 테스트동 1',
          phone: '02-1234-5678',
          placeUrl: 'https://place.map.kakao.com/1',
        }],
        totalElements: 11,
        totalPages: 2,
        page: currentPage,
      },
    }
  })
}

async function selectCenterConditions(page: Page) {
  await page.locator('#center-region').selectOption('서울특별시')
  await page.locator('#center-district').selectOption('강남구')
  await page.locator('#center-town').selectOption('역삼1동')
  await page.locator('#center-type').selectOption('MENTAL_HEALTH_CENTER')
}

test.describe('FE-AUTO-027: 기관 검색', () => {
  test.beforeEach(async ({ page }) => {
    await useGuestSession(page)
    await mockCenters(page)
    await page.goto('/centers')
  })

  test('경계: 검색 조건이 완전하지 않으면 검색 버튼을 비활성화한다', async ({ page }) => {
    await expect(page.getByRole('button', { name: '기관 검색하기' })).toBeDisabled()
  })

  test('성공: 전국 17개 시·도와 대구의 하위 지역을 선택할 수 있다', async ({ page }) => {
    const regionOptions = await page
      .locator('#center-region option:not([disabled])')
      .allTextContents()

    expect(regionOptions).toHaveLength(17)
    expect(regionOptions).toEqual(expect.arrayContaining([
      '서울특별시',
      '부산광역시',
      '대구광역시',
      '인천광역시',
      '광주광역시',
      '대전광역시',
      '울산광역시',
      '세종특별자치시',
      '경기도',
      '강원특별자치도',
      '충청북도',
      '충청남도',
      '전북특별자치도',
      '전라남도',
      '경상북도',
      '경상남도',
      '제주특별자치도',
    ]))

    await page.locator('#center-region').selectOption('대구광역시')
    await expect(page.locator('#center-district')).toContainText('중구')
    await page.locator('#center-district').selectOption('중구')
    await expect(page.locator('#center-town')).toContainText('동인동')
  })

  test('경계: 상위 지역을 바꾸면 하위 지역 선택을 초기화한다', async ({ page }) => {
    await selectCenterConditions(page)
    await page.locator('#center-region').selectOption('경기도')
    await expect(page.locator('#center-district')).toHaveValue('')
    await expect(page.locator('#center-town')).toBeDisabled()
  })

  test('성공: 완전한 조건으로 기관 연락처와 지도 링크를 표시한다', async ({ page }) => {
    await selectCenterConditions(page)
    await page.getByRole('button', { name: '기관 검색하기' }).click()
    await expect(page.getByText('강남 마음건강센터')).toBeVisible()
    await expect(page.getByRole('link', { name: '02-1234-5678' })).toHaveAttribute('href', 'tel:02-1234-5678')
    await expect(page.getByRole('link', { name: '카카오맵에서 보기' })).toHaveAttribute('href', 'https://place.map.kakao.com/1')
  })

  test('성공: 다음 페이지를 선택하면 두 번째 결과 화면을 표시한다', async ({ page }) => {
    await selectCenterConditions(page)
    await page.getByRole('button', { name: '기관 검색하기' }).click()
    await page.getByRole('button', { name: '다음' }).click()
    await expect(page.getByText('두 번째 페이지 마음센터')).toBeVisible()
  })

  test('오류: 기관 검색 실패 후 같은 조건으로 다시 검색하면 결과를 복구한다', async ({ page }) => {
    await mockCenters(page, true)
    await selectCenterConditions(page)
    await page.getByRole('button', { name: '기관 검색하기' }).click()
    await expect(page.getByRole('alert')).toHaveText('기관 검색 실패')
    await page.getByRole('button', { name: '기관 검색하기' }).click()
    await expect(page.getByText('강남 마음건강센터')).toBeVisible()
  })
})
