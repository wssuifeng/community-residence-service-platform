import type { PageQuery } from '@/types/api'

/**
 * 公告状态（接口设计.md 9.5.1 / Flyway V1 notice 表：
 * DRAFT-草稿, PUBLISHED-已发布, WITHDRAWN-已撤回；
 * EXPIRED 为定时任务到期判定标注，见 9.5.1.3 删除规则）
 */
export type NoticeStatus = 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN' | 'EXPIRED'

/** 公告状态中文标签（供 StatusTag 使用） */
export const noticeStatusLabels: Record<NoticeStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  WITHDRAWN: '已撤回',
  EXPIRED: '已过期'
}

/**
 * 公告类型（接口设计.md 9.5.1.1，@ValidStatus(enumClass=NoticeType.class)，
 * 文档仅示例 ANNOUNCEMENT，后端枚举其余取值落地时在此扩展）
 */
export type NoticeType = 'ANNOUNCEMENT'

/** 公告类型中文标签 */
export const noticeTypeLabels: Record<NoticeType, string> = {
  ANNOUNCEMENT: '公告'
}

/** 公告优先级（Priority 枚举，Flyway V1 priority 列注释：LOW/NORMAL/HIGH/URGENT） */
export type NoticePriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

/** 公告优先级中文标签 */
export const noticePriorityLabels: Record<NoticePriority, string> = {
  LOW: '低',
  NORMAL: '普通',
  HIGH: '高',
  URGENT: '紧急'
}

/**
 * 公告目标受众（接口设计.md 9.5.1.1，@ValidStatus(enumClass=TargetAudience.class)，
 * 文档仅示例 ALL，后端枚举其余取值落地时在此扩展）
 */
export type TargetAudience = 'ALL'

/** 公告目标受众中文标签 */
export const targetAudienceLabels: Record<TargetAudience, string> = {
  ALL: '全部居民'
}

/** 公告（接口设计.md 9.5.1.1 响应；
 * 后端 NoticeVO 当前实际返回 id/communityId/communityName/title/content/status/
 * publishTime/endTime/viewCount/publisherId/publisherName/createdAt，
 * type/priority/expireTime/targetAudience 均已不返回（漂移字段，读取需空值防御）；
 * notice 表已有 is_pinned 列但 VO 暂未暴露，置顶判定同时兼容 pinned 布尔字段） */
export interface INotice {
  id: number
  /** SUPER_ADMIN 全系统广播时为 null */
  communityId: number | null
  communityName: string | null
  title: string
  content: string
  /** 漂移字段：后端 NoticeVO 不再返回 */
  type?: NoticeType
  /** 漂移字段：后端 NoticeVO 不再返回 */
  priority?: NoticePriority
  status: NoticeStatus
  publishTime: string | null
  /** 漂移字段：后端实际返回 endTime，读取侧优先用 endTime */
  expireTime?: string | null
  /** 后端实际返回字段：截止/失效时间 */
  endTime?: string | null
  /** 置顶标记（notice 表 is_pinned 列，VO 暴露前恒缺省） */
  pinned?: boolean
  /** 置顶标记的备选命名（防御 VO 字段名落地差异） */
  isPinned?: boolean
  /** 漂移字段：后端 NoticeVO 不再返回 */
  targetAudience?: TargetAudience
  viewCount: number
  publisherId: number
  publisherName: string
  createdAt: string
}

/** 创建/更新公告请求（接口设计.md 9.5.1.1 / 9.5.1.2） */
export interface INoticeSaveRequest {
  /** SUPER_ADMIN 广播时传 null，ADMIN 必须指定（限绑定社区） */
  communityId: number | null
  title: string
  content: string
  type: NoticeType
  priority: NoticePriority
  /** ISO 8601 格式 */
  publishTime: string
  /** 缺省为 publishTime + 30 天 */
  expireTime?: string
  targetAudience: TargetAudience
}

/** 公告列表查询参数（接口设计.md 9.5.1.5） */
export interface INoticeQuery extends PageQuery {
  communityId?: number
  type?: NoticeType
  priority?: NoticePriority
  /** 匹配标题/内容 */
  keyword?: string
}

/** 公告查看记录（接口设计.md 9.5.1.9） */
export interface INoticeViewer {
  residentId: number
  residentName: string
  viewedAt: string
}
