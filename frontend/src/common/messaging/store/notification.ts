import { invalidateNotification } from '@/common/web/utils/dataFreshness'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { INotification, IPulledNotification } from '@/common/messaging/types/notification'
import { pullNotifications, listUnreadNotifications } from '@/common/messaging/api/notification'
import { connectWebSocket, disconnectWebSocket } from '@/common/messaging/utils/websocket'

/**
 * 通知中心状态（C11）：WS 实时推送 + 30s 轮询兜底双保障；
 * seq 全局递增，去重与补拉都以 seq 为游标。
 * WS 载荷 isRead 为 0/1 数字（后端 NotificationVO），入口统一转布尔
 */

const POLL_INTERVAL = 30000

/** 列表项：通知页全量（含 recipientId）或 WS/补拉载荷 */
type NotificationListItem = INotification | IPulledNotification

/** WS 推送载荷（后端 NotificationVO：isRead 为 0/1 数字，入口统一转布尔） */
type WsNotificationPayload = Omit<IPulledNotification, 'isRead'> & { isRead: number }

export const useNotificationStore = defineStore('notification', () => {
  const notifications = ref<NotificationListItem[]>([])
  const lastSeq = ref(0)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  const unread = computed(() => notifications.value.filter(n => !n.isRead).sort((a, b) => Number(b.seq) - Number(a.seq)))
  const unreadCount = computed(() => unread.value.length)
  const latest = computed(() => notifications.value.slice(0, 20))
  let generation = 0
  let requestVersion = 0
  let pushVersion = 0
  const pushedAt = new Map<number, number>()
  const locallyRead = new Set<number>()

  function handlePush(raw: unknown): void {
    const payload = raw as WsNotificationPayload
    if (locallyRead.has(payload.id) || notifications.value.some((n) => n.id === payload.id || n.seq === payload.seq)) return
    const item: IPulledNotification = { ...payload, isRead: payload.isRead === 1 || (payload.isRead as unknown) === true }
    invalidateNotification(payload)
    notifications.value.unshift(item)
    pushedAt.set(item.id, ++pushVersion)
    lastSeq.value = Math.max(lastSeq.value, Number(payload.seq))
  }

  /** 拉取增量游标与完整未读快照；铃铛列表和数量从同一快照派生 */
  async function refreshBaseline(): Promise<void> {
    const session = generation
    const request = ++requestVersion
    const startingPush = pushVersion
    try {
      const [result, snapshot] = await Promise.all([pullNotifications(lastSeq.value), listUnreadNotifications()])
      if (session !== generation || request !== requestVersion) return
      if (result.latestSeq > lastSeq.value) result.notifications.filter(item => item.seq > lastSeq.value).forEach(invalidateNotification)
      lastSeq.value = Math.max(lastSeq.value, result.latestSeq)
      /* 未读列表是完整权威快照，不从最新20条推算；保留请求期间的新推送，排除本地已读。 */
      const items = new Map<number, NotificationListItem>()
      for (const item of snapshot.notifications) {
        if (!locallyRead.has(item.id)) items.set(item.id, { ...item, isRead: false })
      }
      for (const item of notifications.value) {
        if ((pushedAt.get(item.id) ?? 0) > startingPush && !item.isRead && !locallyRead.has(item.id)) items.set(item.id, item)
      }
      notifications.value = [...items.values()].sort((a, b) => Number(b.seq) - Number(a.seq))
    } catch {
      /* 保留上次未读结果，不把加载失败渲染成零未读；下次打开或轮询重试。 */
    }
  }

  function startPolling(): void {
    stopPolling()
    pollTimer = setInterval(refreshBaseline, POLL_INTERVAL)
  }

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  /** 登录后初始化（user store setSession 调用）：先对齐基线再连 WS */
  function init(): void {
    void refreshBaseline()
    const session = generation
    connectWebSocket((raw) => { if (session === generation) handlePush(raw) })
    startPolling()
  }

  /** 登出清理（user store logout 调用） */
  function cleanup(): void {
    disconnectWebSocket()
    stopPolling()
    generation += 1
    requestVersion += 1
    pushedAt.clear()
    locallyRead.clear()
    notifications.value = []
    lastSeq.value = 0
  }

  function markLocalRead(id: number): void {
    locallyRead.add(id)
    const item = notifications.value.find((n) => n.id === id)
    if (item && !item.isRead) {
      item.isRead = true
    }
  }

  function markAllLocalRead(): void {
    requestVersion += 1
    notifications.value.forEach((n) => { locallyRead.add(n.id); n.isRead = true })
  }

  return {
    notifications,
    latest,
    unread,
    unreadCount,
    lastSeq,
    init,
    cleanup,
    refreshBaseline,
    markLocalRead,
    markAllLocalRead
  }
})
