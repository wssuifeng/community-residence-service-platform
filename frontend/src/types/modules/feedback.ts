import type { PageQuery } from '@/types/api'

/** 反馈状态（接口设计.md 9.6.1，状态机：待受理→会话中→已办结） */
export type FeedbackStatus = 'OPEN' | 'IN_PROGRESS' | 'CLOSED'

/** 反馈状态中文标签 */
export const feedbackStatusLabels: Record<FeedbackStatus, string> = {
  OPEN: '待受理',
  IN_PROGRESS: '会话中',
  CLOSED: '已办结'
}

/** 反馈类别（接口设计.md 9.6.1.1，FeedbackCategory：建议/投诉/咨询） */
export type FeedbackCategory = 'SUGGESTION' | 'COMPLAINT' | 'INQUIRY'

/** 反馈类别中文标签 */
export const feedbackCategoryLabels: Record<FeedbackCategory, string> = {
  SUGGESTION: '建议',
  COMPLAINT: '投诉',
  INQUIRY: '咨询'
}

/** 会话发送人类型（接口设计.md 9.6.3.1） */
export type FeedbackSenderType = 'RESIDENT' | 'ADMIN'

/** 会话发送人类型中文标签 */
export const feedbackSenderTypeLabels: Record<FeedbackSenderType, string> = {
  RESIDENT: '居民',
  ADMIN: '管理员'
}

/** 反馈附件文件类型（接口设计.md 9.6.2.1：图片/文档） */
export type FeedbackFileType = 'IMAGE' | 'DOCUMENT'

/** 反馈附件文件类型中文标签 */
export const feedbackFileTypeLabels: Record<FeedbackFileType, string> = {
  IMAGE: '图片',
  DOCUMENT: '文档'
}

/** 反馈单（接口设计.md 9.6.1.1 / 9.6.1.2 响应 data） */
export interface IFeedback {
  id: number
  feedbackNumber: string
  residentId: number
  residentName: string
  communityId: number
  communityName: string
  category: FeedbackCategory
  title: string
  content: string
  contactPhone: string | null
  isAnonymous: boolean
  status: FeedbackStatus
  handlerId: number | null
  handlerName: string | null
  assignedAt: string | null
  closedAt: string | null
  closeReason: string | null
  createdAt: string
  updatedAt: string
}

/** 提交反馈请求（接口设计.md 9.6.1.1 请求体） */
export interface FeedbackCreateDTO {
  communityId: number
  category: FeedbackCategory
  title: string
  content: string
  contactPhone?: string
  isAnonymous: boolean
  attachmentIds?: number[]
}

/** 关闭反馈请求（接口设计.md 9.6.1.4 请求体） */
export interface FeedbackCloseDTO {
  /** 后端 CloseFeedbackDTO 字段为 remark（接口文档 reason 为漂移命名） */
  remark: string
}

/** 反馈列表查询参数（接口设计.md 9.6.1.3） */
export interface FeedbackListQuery extends PageQuery {
  status?: FeedbackStatus
  category?: FeedbackCategory
  startTime?: string
  endTime?: string
  keyword?: string
}

/** 反馈附件（接口设计.md 9.6.2.1 / 9.6.2.3 响应 data） */
export interface IFeedbackAttachment {
  id: number
  feedbackId: number
  fileName: string
  fileUrl: string
  fileSize: number
  fileType: FeedbackFileType
  createdAt: string
}

/** 反馈会话消息（接口设计.md 9.6.3.1 / 9.6.3.2 响应 data） */
export interface IFeedbackMessage {
  id: number
  feedbackId: number
  senderId: number
  senderType: FeedbackSenderType
  senderName: string
  content: string
  createdAt: string
}

/** 发送会话消息请求（接口设计.md 9.6.3.1 请求体） */
export interface FeedbackMessageCreateDTO {
  content: string
}

/** 会话消息列表查询参数（接口设计.md 9.6.3.2，默认时间升序） */
export interface FeedbackMessageQuery extends PageQuery {
  sortOrder?: 'ASC' | 'DESC'
}

/** WebSocket 会话消息推送载荷（接口设计.md 9.6.3.3 消息格式） */
export interface FeedbackWsMessage {
  type: 'FEEDBACK_MESSAGE'
  data: IFeedbackMessage
}
