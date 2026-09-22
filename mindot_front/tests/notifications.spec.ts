import { expect, test, type Page } from '@playwright/test'
import { mockApi, useAuthenticatedSession } from './support'

const notifications = [
  {
    notificationId: 1,
    title: '월요일 아침 불안 패턴',
    message: '최근 비슷한 감정이 반복되었습니다.',
    recommendedAction: '3분 호흡을 해 보세요.',
    createdAt: '2026-09-18T00:00:00Z',
    read: false,
  },
  {
    notificationId: 2,
    title: '알림 삭제 실패 확인',
    message: '실패 시 이 항목은 유지되어야 합니다.',
    createdAt: '2026-09-18T00:01:00Z',
    read: true,
  },
]

async function openNotifications(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: /알림 열기/ }).click()
}

test.describe('FE-AUTO-021: 알림', () => {
  test.beforeEach(async ({ page }) => {
    await useAuthenticatedSession(page)
  })

  test('성공: 가상 시간의 주기 갱신 후 읽지 않은 알림 배지를 반영한다', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-09-18T09:00:00+09:00') })
    let unreadCalls = 0
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/notifications/unread-count') {
        unreadCalls += 1
        return { body: { unreadCount: unreadCalls <= 2 ? 1 : 2 } }
      }
      if (url.pathname === '/api/notifications') return { body: { content: notifications, totalElements: 2 } }
    })

    await page.goto('/')
    await expect(page.getByRole('button', { name: /읽지 않은 알림 1개/ })).toBeVisible()
    await page.clock.fastForward('01:00')
    await expect(page.getByRole('button', { name: /읽지 않은 알림 2개/ })).toBeVisible()
  })

  test('성공: 알림을 읽으면 화면의 미읽음 배지가 감소한다', async ({ page }) => {
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/notifications/unread-count') return { body: { unreadCount: 1 } }
      if (url.pathname === '/api/notifications') return { body: { content: notifications, totalElements: 2 } }
      if (url.pathname === '/api/notifications/1/read') return { body: { ...notifications[0], read: true } }
    })

    await openNotifications(page)
    await page.locator('.notification-list__content').filter({ hasText: '월요일 아침 불안 패턴' }).click()
    await expect(page.locator('.notification-badge')).not.toBeVisible()
    await expect(page.getByRole('button', { name: '알림 열기', exact: true })).toBeVisible()
  })

  test('경계: 삭제 확인을 취소하면 알림을 그대로 유지한다', async ({ page }) => {
    await mockApi(page, (_request, url) => {
      if (url.pathname === '/api/notifications/unread-count') return { body: { unreadCount: 1 } }
      if (url.pathname === '/api/notifications') return { body: { content: notifications, totalElements: 2 } }
    })

    await openNotifications(page)
    page.once('dialog', (dialog) => dialog.dismiss())
    await page.getByRole('button', { name: '월요일 아침 불안 패턴 알림 삭제' }).click()
    await expect(page.getByText('월요일 아침 불안 패턴')).toBeVisible()
  })

  test('성공: 삭제를 확인하면 대상 알림이 목록에서 사라진다', async ({ page }) => {
    let deleted = false
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/notifications/unread-count') return { body: { unreadCount: 1 } }
      if (url.pathname === '/api/notifications' && request.method() === 'GET') {
        return { body: { content: deleted ? notifications.slice(1) : notifications, totalElements: deleted ? 1 : 2 } }
      }
      if (url.pathname === '/api/notifications/1' && request.method() === 'DELETE') {
        deleted = true
        return { status: 204, body: null }
      }
    })

    await openNotifications(page)
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '월요일 아침 불안 패턴 알림 삭제' }).click()
    await expect(page.getByText('월요일 아침 불안 패턴')).not.toBeVisible()
  })

  test('오류: 삭제 실패를 안내하고 대상 알림을 목록에 유지한다', async ({ page }) => {
    let deleteAttempts = 0
    await mockApi(page, (request, url) => {
      if (url.pathname === '/api/notifications/unread-count') return { body: { unreadCount: 1 } }
      if (url.pathname === '/api/notifications') return { body: { content: notifications, totalElements: 2 } }
      if (url.pathname === '/api/notifications/2' && request.method() === 'DELETE') {
        deleteAttempts += 1
        return deleteAttempts === 1
          ? { status: 500, body: { message: '알림 삭제 실패' } }
          : { status: 204, body: null }
      }
    })

    await openNotifications(page)
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '알림 삭제 실패 확인 알림 삭제' }).click()
    await expect(page.getByRole('alert')).toHaveText('알림 삭제 실패')
    await expect(page.getByText('알림 삭제 실패 확인')).toBeVisible()
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '알림 삭제 실패 확인 알림 삭제' }).click()
    await expect(page.getByText('알림 삭제 실패 확인')).not.toBeVisible()
  })
})
