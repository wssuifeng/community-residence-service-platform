import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
let useNotificationStore: typeof import('@/common/messaging/store/notification')['useNotificationStore']
const api = vi.hoisted(() => ({ pull: vi.fn(), unread: vi.fn(), connect: vi.fn() }))
vi.mock('@/common/messaging/api/notification', () => ({ pullNotifications: api.pull, listUnreadNotifications: api.unread }))
vi.mock('@/common/messaging/utils/websocket', () => ({ connectWebSocket: api.connect, disconnectWebSocket: vi.fn() }))
vi.mock('@/common/web/utils/dataFreshness', () => ({ invalidateData: vi.fn() }))
const item = (id: number, isRead = false) => ({ id, seq: id, title: `通知${id}`, content: '内容', isRead, sourceType: 'SYSTEM' as const, sourceId: null, createdAt: '2026-10-16T12:00:00' })
beforeEach(async () => { vi.resetModules(); useNotificationStore = (await import('@/common/messaging/store/notification')).useNotificationStore; setActivePinia(createPinia()); vi.resetAllMocks(); api.pull.mockResolvedValue({ notifications: [], latestSeq: 0 }); api.unread.mockResolvedValue({ count: 0, notifications: [] }) })
describe('铃铛完整未读快照', () => {
 it('7条未读全部可见，排除已读；不限于5条或20条', async () => {
  const store = useNotificationStore()
  api.pull.mockResolvedValue({ notifications: [item(99, true)], latestSeq: 99 })
  api.unread.mockResolvedValue({ count: 27, notifications: Array.from({ length: 27 }, (_, i) => item(i+1)) })
  await store.refreshBaseline()
  expect(store.unreadCount).toBe(27)
  expect(store.unread).toHaveLength(27)
  expect(store.unread.map((n) => n.id)).not.toContain(99)
  store.markLocalRead(27)
  expect(store.unread).toHaveLength(26)
  expect(store.unreadCount).toBe(26)
 })
 it('另一页面已读后的服务端快照会移除旧消息，不只是改角标', async () => {
  const store = useNotificationStore(); api.unread.mockResolvedValueOnce({ count: 1, notifications: [item(1)] }).mockResolvedValueOnce({ count: 0, notifications: [] })
  await store.refreshBaseline(); await store.refreshBaseline(); expect(store.unread).toEqual([])
 })
 it('退出后迟到的旧账号请求不能污染新会话', async () => {
  let resolve!: (value: import('@/common/messaging/api/notification').UnreadNotifications) => void
  api.unread.mockReturnValue(new Promise(r => { resolve = r }))
  const store = useNotificationStore(); const pending = store.refreshBaseline(); store.cleanup()
  resolve({ count: 1, notifications: [item(1)] }); await pending
  expect(store.unreadCount).toBe(0); expect(store.notifications).toHaveLength(0)
 })
 it('读取期间标记已读，迟到快照不能重新出现；刷新失败不清空既有未读', async () => {
  const store = useNotificationStore(); api.unread.mockResolvedValue({ count: 1, notifications: [item(1)] }); await store.refreshBaseline()
  let resolve!: (value: import('@/common/messaging/api/notification').UnreadNotifications) => void
  api.unread.mockReturnValue(new Promise(r => { resolve = r })); const pending = store.refreshBaseline()
  store.markLocalRead(1); resolve({ count: 1, notifications: [item(1)] }); await pending; expect(store.unreadCount).toBe(0)
  api.unread.mockResolvedValue({ count: 1, notifications: [item(2)] }); await store.refreshBaseline()
  api.unread.mockRejectedValue(new Error('offline')); await store.refreshBaseline(); expect(store.unreadCount).toBe(1)
 })
 it('WS布尔已读不当未读；重复推送不重复计数，退出后的旧推送忽略', async () => {
  const store = useNotificationStore(); store.init(); await store.refreshBaseline()
  const push = api.connect.mock.calls[0][0]
  push(item(1,true)); push(item(2,false)); push(item(2,false)); expect(store.unreadCount).toBe(1)
  store.cleanup(); push(item(3,false)); expect(store.unreadCount).toBe(0)
 })
})
