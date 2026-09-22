import { expect, test } from '@playwright/test'
import { mockApi, useAuthenticatedSession } from './support'

test.describe('FE-AUTO-026: 관리자', () => {
  test('오류: 일반 사용자는 관리자 데이터에 접근할 수 없다', async ({ page }) => {
    await useAuthenticatedSession(page, 'ROLE_USER')
    await mockApi(page, (_request, url) => {
      if (url.pathname.startsWith('/api/admin/')) return { status: 403, body: {} }
    })

    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: '관리자 화면에 접근할 수 없습니다' })).toBeVisible()
    await expect(page.getByText('member@example.com')).toHaveCount(0)
  })

  test('성공: 관리자는 회원 목록과 안전 이벤트 상세를 조회한다', async ({ page }) => {
    await useAuthenticatedSession(page, 'ROLE_ADMIN')
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/admin/users') {
        return {
          body: {
            content: [{
              userId: 1,
              displayName: '관리 대상 회원',
              email: 'member@example.com',
              status: 'ACTIVE',
              userRole: 'ROLE_USER',
              safetyEventCount: 1,
              createdAt: '2026-09-01T00:00:00Z',
            }],
            totalElements: 1,
            totalPages: 1,
            page: 0,
          },
        }
      }
      if (url.pathname === '/api/admin/safety-events') {
        return {
          body: {
            content: [{
              safetyEventId: 10,
              displayName: '관리 대상 회원',
              email: 'member@example.com',
              riskLevel: 'CRISIS',
              createdAt: '2026-09-18T00:00:00Z',
              noticeShownAt: '2026-09-18T00:01:00Z',
            }],
            totalElements: 1,
            totalPages: 1,
            page: 0,
          },
        }
      }
      if (url.pathname === '/api/admin/safety-events/10') {
        return {
          body: {
            safetyEventId: 10,
            displayName: '관리 대상 회원',
            email: 'member@example.com',
            riskLevel: 'CRISIS',
            reasonCode: 'IMMEDIATE_DANGER',
            actionCode: 'SHOW_CRISIS_NOTICE',
            occurredAt: '2026-09-18T00:00:00Z',
            rawText: '안전 확인이 필요한 기록 원문',
            emotionRecordId: 99,
          },
        }
      }
    })

    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: '관리자', exact: true })).toBeVisible()
    await expect(page.getByText('member@example.com').first()).toBeVisible()
    await expect(page.getByText('안전 확인이 필요한 기록 원문')).toBeVisible()
  })

  test('경계: 관리자 데이터가 비어 있으면 회원과 안전 신호 빈 상태를 구분한다', async ({ page }) => {
    await useAuthenticatedSession(page, 'ROLE_ADMIN')
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/admin/users' || url.pathname === '/api/admin/safety-events') {
        return {
          body: {
            content: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
          },
        }
      }
    })

    await page.goto('/admin')
    await expect(page.getByText('가입한 회원이 없습니다.')).toBeVisible()
    await expect(page.getByText('발생한 안전 신호가 없습니다.')).toBeVisible()
  })
})
