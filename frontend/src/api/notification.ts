import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IMarkAllReadResult,
  INotification,
  INotificationQuery,
  IPullNotificationResult,
  IUnreadNotificationResult
} from '@/types/modules/notification'

/** 通知 WebSocket 端点（经 Vite proxy /ws 转发，接口设计.md 9.11.1.6） */
export const NOTIFICATION_WS_ENDPOINT = '/ws/notifications'

/** 通知 WebSocket 用户专属订阅地址（接口设计.md 9.11.1.6） */
export const NOTIFICATION_WS_SUBSCRIBE_DEST = '/user/queue/notifications'

/** 通知列表分页查询（只看本人通知，接口设计.md 9.11.1.1） */
export function listNotifications(params: INotificationQuery) {
  return http.get<PageResult<INotification>>('/notifications', params)
}

/** 未读通知列表（最多返回最新 50 条，接口设计.md 9.11.1.2） */
export function listUnreadNotifications() {
  return http.get<IUnreadNotificationResult>('/notifications/unread')
}

/** 增量补拉通知（客户端记录 lastSeq，重连后补拉遗漏，接口设计.md 9.11.1.3） */
export function pullNotifications(lastSeq: number) {
  return http.get<IPullNotificationResult>('/notifications/pull', { lastSeq })
}

/** 标记单条通知已读（幂等操作，接口设计.md 9.11.1.4） */
export function markNotificationRead(id: number) {
  return http.patch<null>(`/notifications/${id}/read`)
}

/** 全部标记已读（接口设计.md 9.11.1.5） */
export function markAllNotificationsRead() {
  return http.patch<IMarkAllReadResult>('/notifications/read-all')
}
