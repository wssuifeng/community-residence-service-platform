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

/**
 * 公告目标范围类型（V9 R25 v1.2：COMMUNITY-社区, BUILDING-楼栋；
 * DEF-046 V13：GUEST-游客可见，仅超管可勾选、无目标 ID 纯标记）
 */
export type NoticeTargetType = 'COMMUNITY' | 'BUILDING' | 'GUEST'

/**
 * 公告目标范围项（后端 NoticeVO.TargetItem，V9 起真实返回）：
 * 无 targets 记录 = 全系统广播（仅超管）；
 * GUEST 项为纯标记（DEF-046 V13），targetId/targetName 为 null
 */
export interface INoticeTargetItem {
  targetType: NoticeTargetType
  targetId: number | null
  /** 社区名/楼栋名，可空 */
  targetName: string | null
}

/** 公告目标范围项请求体（后端 TargetItemDTO；GUEST 纯标记不传 targetId） */
export interface INoticeSaveTargetItem {
  targetType: NoticeTargetType
  targetId?: number
}

/**
 * 公告（后端 NoticeVO，V9 后 type/priority/isPinned/targets/expireTime 均真实返回；
 * targetAudience 后端 VO 不返回，读取仍需空值防御）
 */
export interface INotice {
  id: number
  /** 首个社区目标 ID；全系统广播时为 null */
  communityId: number | null
  /** 首个社区目标名称 */
  communityName: string | null
  /** 目标范围列表（R25 v1.2：多社区/楼栋定向；空=全系统广播） */
  targets: INoticeTargetItem[]
  title: string
  content: string
  type: NoticeType
  priority: NoticePriority
  status: NoticeStatus
  publishTime: string | null
  /** 失效时间别名（与 endTime 同值，V12 起真实返回） */
  expireTime: string | null
  /** 失效时间 */
  endTime: string | null
  /** 置顶：0-普通, 1-置顶（R25 v1.2；排序由后端负责，前端如实渲染） */
  isPinned: number
  /** 置顶标记旧布尔命名兜底（历史防御式读取，正常路径不出现） */
  pinned?: boolean
  /** 后端 NoticeVO 不返回，读取需空值防御 */
  targetAudience?: TargetAudience
  viewCount: number
  publisherId: number
  publisherName: string
  createdAt: string
}

/** 创建/更新公告请求（CreateNoticeDTO：targets 优先，communityId 单目标写法向后兼容） */
export interface INoticeSaveRequest {
  /** 单目标旧写法；与 targets 并设时后端以 targets 为准 */
  communityId: number | null
  /** 目标范围列表（空=按 communityId 单目标/全系统广播仅超管） */
  targets?: INoticeSaveTargetItem[]
  /** 置顶：0-普通, 1-置顶 */
  isPinned: number
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

/** 公告列表查询参数（Controller @RequestParam：keyword/priority/isPinned/type 透传） */
export interface INoticeQuery extends PageQuery {
  communityId?: number
  type?: NoticeType
  priority?: NoticePriority
  /** 置顶过滤：0-普通, 1-置顶 */
  isPinned?: number
  /** 匹配标题/内容 */
  keyword?: string
}

/** 公告查看记录（接口设计.md 9.5.1.9） */
export interface INoticeViewer {
  residentId: number
  residentName: string
  viewedAt: string
}
