import { expect, test } from '@playwright/test'
import { mockApi, useAuthenticatedSession, weeklyReport } from './support'

test.describe('FE-AUTO-018: 상담 PDF', () => {
  test.beforeEach(async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    await useAuthenticatedSession(page)
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
      if (url.pathname === '/api/reports/export/pdf') {
        return { body: '%PDF-1.4 playwright', contentType: 'application/pdf' }
      }
    })
    await page.goto('/reports/weekly')
  })

  test('경계: 시작일이 비어 있으면 다운로드하지 않고 안내한다', async ({ page }) => {
    await page.getByLabel('시작일').fill('')
    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    await expect(page.getByRole('alert')).toHaveText('시작일과 종료일을 모두 선택해 주세요.')
  })

  test('경계: 종료일이 시작일보다 빠르면 안내한다', async ({ page }) => {
    await page.getByLabel('시작일').fill('2026-09-10')
    await page.getByLabel('종료일').fill('2026-09-09')
    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    await expect(page.getByRole('alert')).toHaveText('종료일은 시작일보다 빠를 수 없습니다.')
  })

  test('경계: 직접 선택 모드에서 날짜가 없으면 안내한다', async ({ page }) => {
    await page.getByLabel('날짜 직접 선택').check()
    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    await expect(page.getByRole('alert')).toHaveText('PDF에 포함할 날짜를 하나 이상 추가해 주세요.')
  })

  test('경계: 미래 날짜는 다운로드하지 않고 안내한다', async ({ page }) => {
    await page.getByLabel('시작일').fill('2026-09-18')
    await page.getByLabel('종료일').fill('2026-09-19')
    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    await expect(page.getByRole('alert')).toHaveText('미래 날짜는 PDF에 포함할 수 없습니다.')
  })

  test('경계: 31일을 넘는 기간은 다운로드하지 않고 안내한다', async ({ page }) => {
    await page.getByLabel('시작일').fill('2026-08-01')
    await page.getByLabel('종료일').fill('2026-09-18')
    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    await expect(page.getByRole('alert')).toHaveText('PDF는 최대 31일까지 내보낼 수 있습니다.')
  })

  test('성공: 선택 기간과 옵션의 PDF를 다운로드하고 완료를 표시한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
      if (url.pathname === '/api/reports/export/pdf') {
        const body = request.postDataJSON()
        const matchesSelection = body.startDate === '2026-09-10'
          && body.endDate === '2026-09-12'
          && body.contentType === 'CBT_RESULTS'
          && body.includeFullCbtConversation === true
        return matchesSelection
          ? { body: '%PDF-1.4 playwright', contentType: 'application/pdf' }
          : { status: 400, body: { message: '선택 조건 불일치' } }
      }
    })
    await page.getByLabel('시작일').fill('2026-09-10')
    await page.getByLabel('종료일').fill('2026-09-12')
    await page.getByLabel('PDF 포함 내용').selectOption('CBT_RESULTS')
    await page.getByLabel('CBT 전체 대화 포함').check()
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('mindot-report-2026-09-10-2026-09-12.pdf')
    await expect(page.getByRole('status')).toContainText('선택한 조건의 PDF 파일 다운로드를 시작했습니다.')
  })

  test('오류: PDF 생성 서버 오류를 화면에 안내한다', async ({ page }) => {
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/reports/weekly' && request.method() === 'GET') {
        return { body: weeklyReport() }
      }
      if (url.pathname === '/api/reports/export/pdf') {
        return { status: 500, body: { message: 'PDF 생성 실패' } }
      }
    })

    await page.getByRole('button', { name: '선택한 내용 PDF로 저장' }).click()
    await expect(page.getByRole('alert')).toContainText('PDF 파일을 만들지 못했습니다')
  })
})
