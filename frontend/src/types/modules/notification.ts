import type { PageQuery } from '@/types/api'

/**
 * 通知来源类型（接口设计.md 9.11.1 / §6.3，文档仅示例 WORK_ORDER，
 * 后端枚举其余取值落地时在此扩展）
 */
export type NotificationSourceType = 'WORK_ORDER'

/** 通知已读状态中文标签（isRead 布尔映射，供 StatusTag 使用） */
export const notificationReadLabels = {
  true: '已读',
  false: '未读'
} as const

/** 通知基础字段（列表/未读/补拉/WS 推送共有的载荷结构） */
export interface INotificationBase {
  id: number
  /** 全局递增序号（Redis INCR 生成），断线重连按此补拉 */
  seq: number
  title: string
  content: string
  sourceType: NotificationSourceType
  sourceId: number | null
  createdAt: string
}

/** 通知记录（接口设计.md 9.11.1.1 列表项，含接收人与已读状态） */
export interface INotification extends INotificationBase {
  recipientId: number
  isRead: boolean
}

/** 未读通知项（接口设计.md 9.11.1.2，未读隐含 isRead=false） */
export type IUnreadNotification = INotificationBase

/** 增量补拉通知项（接口设计.md 9.11.1.3） */
export interface IPulledNotification extends INotificationBase {
  isRead: boolean
}

/** 未读通知响应（接口设计.md 9.11.1.2，最多返回最新 50 条） */
export interface IUnreadNotificationResult {
  count: number
  notifications: IUnreadNotification[]
}

/** 增量补拉响应（接口设计.md 9.11.1.3，按 seq 升序） */
export interface IPullNotificationResult {
  notifications: IPulledNotification[]
  latestSeq: number
}

/** 通知列表查询参数（接口设计.md 9.11.1.1） */
export interface INotificationQuery extends PageQuery {
  isRead?: boolean
  sourceType?: NotificationSourceType
  startTime?: string
  endTime?: string
}

/** 全部标记已读响应（接口设计.md 9.11.1.5） */
export interface IMarkAllReadResult {
  count: number
}

/** 通知 WebSocket 推送消息（接口设计.md 9.11.1.6 / §6.3） */
export interface INotificationWsMessage {
  type: 'NOTIFICATION'
  data: INotificationBase
}
