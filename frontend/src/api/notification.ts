import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IMarkAllReadResult,
  INotification,
  INotificationQuery,
  IPulledNotification,
  IUnreadNotification
} from '@/types/modules/notification'

/** 通知 WebSocket 端点（经 Vite proxy /ws 转发，接口设计.md 9.11.1.6） */
export const NOTIFICATION_WS_ENDPOINT = '/ws/notifications'

/** 通知 WebSocket 用户专属订阅地址（接口设计.md 9.11.1.6） */
export const NOTIFICATION_WS_SUBSCRIBE_DEST = '/user/queue/notifications'

/** 通知列表分页查询（只看本人通知，接口设计.md 9.11.1.1） */
export function listNotifications(params: INotificationQuery) {
  return http.get<PageResult<INotification>>('/notifications', params)
}

/** 未读通知聚合结果（api 层从后端纯数组适配而来） */
export interface UnreadNotifications {
  count: number
  notifications: IUnreadNotification[]
}

/** 未读通知列表（最多返回最新 50 条，接口设计.md 9.11.1.2；
 *  后端返回纯数组，此处聚合 count 供既有调用方使用） */
export async function listUnreadNotifications(): Promise<UnreadNotifications> {
  const list = await http.get<IUnreadNotification[]>('/notifications/unread')
  return { count: list.length, notifications: list }
}

/** 增量补拉聚合结果（api 层从后端纯数组适配而来） */
export interface PulledNotifications {
  notifications: IPulledNotification[]
  latestSeq: number
}

/** 增量补拉通知（客户端记录 lastSeq，重连后补拉遗漏，接口设计.md 9.11.1.3；
 *  后端返回纯数组（文档示例 {notifications, latestSeq} 为偏差），
 *  latestSeq 由数组最大 seq 推导，空数组沿用入参游标） */
export async function pullNotifications(lastSeq: number): Promise<PulledNotifications> {
  const list = await http.get<IPulledNotification[]>('/notifications/pull', { lastSeq })
  const latestSeq = list.reduce((max, item) => Math.max(max, Number(item.seq)), lastSeq)
  return { notifications: list, latestSeq }
}

/** 标记单条通知已读（幂等操作，接口设计.md 9.11.1.4） */
export function markNotificationRead(id: number) {
  return http.patch<null>(`/notifications/${id}/read`)
}

/** 全部标记已读（接口设计.md 9.11.1.5） */
export function markAllNotificationsRead() {
  return http.patch<IMarkAllReadResult>('/notifications/read-all')
}
