import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { INotification, IPulledNotification } from '@/types/modules/notification'
import { pullNotifications, listUnreadNotifications } from '@/api/notification'
import { connectWebSocket, disconnectWebSocket } from '@/utils/websocket'

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
  const unreadCount = ref(0)
  const lastSeq = ref(0)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  const latest = computed(() => notifications.value.slice(0, 20))

  function handlePush(raw: unknown): void {
    const payload = raw as WsNotificationPayload
    if (notifications.value.some((n) => n.id === payload.id || n.seq === payload.seq)) return
    const item: IPulledNotification = { ...payload, isRead: payload.isRead === 1 }
    notifications.value.unshift(item)
    if (!item.isRead) unreadCount.value += 1
    lastSeq.value = Math.max(lastSeq.value, Number(payload.seq))
  }

  /** 拉取增量与服务端未读数（登录初始化与轮询共用；未读数以服务端口径为准） */
  async function refreshBaseline(): Promise<void> {
    try {
      const result = await pullNotifications(lastSeq.value)
      for (const item of result.notifications) {
        if (!notifications.value.some((n) => n.id === item.id)) {
          notifications.value.unshift(item)
        }
      }
      lastSeq.value = Math.max(lastSeq.value, result.latestSeq)
      const unread = await listUnreadNotifications()
      unreadCount.value = unread.count
    } catch {
      /* 静默，轮询兜底 */
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
    connectWebSocket(handlePush)
    startPolling()
  }

  /** 登出清理（user store logout 调用） */
  function cleanup(): void {
    disconnectWebSocket()
    stopPolling()
    notifications.value = []
    unreadCount.value = 0
    lastSeq.value = 0
  }

  function markLocalRead(id: number): void {
    const item = notifications.value.find((n) => n.id === id)
    if (item && !item.isRead) {
      item.isRead = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
  }

  function markAllLocalRead(): void {
    notifications.value.forEach((n) => (n.isRead = true))
    unreadCount.value = 0
  }

  return {
    notifications,
    latest,
    unreadCount,
    lastSeq,
    init,
    cleanup,
    refreshBaseline,
    markLocalRead,
    markAllLocalRead
  }
})
