/** R63 多方会话类型定义（v1.5：conversation/participant/message 三级模型） */
import type { PageQuery } from '@/common/web/types/api'

/** 会话类型：看房预约群聊 / 租约群聊（R68 审核通过自动建群）/ 居民-小区管理员直通 /
 *  工单临时群聊（R74 管理端受理后同事务建群，终审关闭时回收为只读） */
export type ConversationType = 'VIEWING_GROUP' | 'LEASE_GROUP' | 'DIRECT' | 'WORK_ORDER_GROUP'

/** 会话类型中文标签 */
export const conversationTypeLabels: Record<ConversationType, string> = {
  VIEWING_GROUP: '看房群聊',
  LEASE_GROUP: '租约群聊',
  DIRECT: '直通',
  WORK_ORDER_GROUP: '工单群聊'
}

/** 会话状态（R68/V20）：ACTIVE-进行中 / CLOSED-已关闭（停收停发，历史只读留档） */
export type ConversationStatus = 'ACTIVE' | 'CLOSED'

/** 会话状态中文标签 */
export const conversationStatusLabels: Record<ConversationStatus, string> = {
  ACTIVE: '进行中',
  CLOSED: '已关闭'
}

/** 参与者角色（决定气泡对齐与标识） */
export type ConversationRole = 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'

/** 参与者角色中文标签（群成员展示用） */
export const conversationRoleLabels: Record<ConversationRole, string> = {
  RESIDENT: '居民',
  STAFF: '服务人员',
  ADMIN: '小区管理员',
  SUPER_ADMIN: '超级管理员'
}

/** 会话参与者（后端 ConversationParticipantVO；仅会话详情返回，列表不含） */
export interface IConversationParticipant {
  /** 居民 = resident.id；服务人员 / 管理员 / 超管 = sys_user.id（多态引用口径） */
  userId: number
  name: string
  role: ConversationRole
  /**
   * 参与者身份空间（DEF-075：与 userId **成对使用**，两空间主键可撞号——
   * 居民 1 与 系统用户 1 是两个人）。缺省按 role 推断。
   */
  ownerType?: 'USER' | 'RESIDENT'
}

/** 会话消息（后端 ConversationMessageVO；WS /topic/conversation/{id} 同构推送） */
export interface IConversationMessage {
  image?: import('@/common/conversation/types/chat').ChatImage | null
  id: number
  conversationId: number
  senderId: number
  senderName: string
  senderRole: ConversationRole | 'SYSTEM'
  messageType?: 'TEXT' | 'IMAGE' | 'LEASE_AGREEMENT'
  agreementId?: number | null
  content: string
  createdAt: string
}

/** 会话（列表元素：类型/标题/关联对象/最后消息摘要/未读数） */
export interface IConversation {
  id: number
  type: ConversationType
  /** 会话状态（V20 起后端返回；缺省按进行中处理，兼容存量数据） */
  status?: ConversationStatus
  /** 会话关闭时间（仅 CLOSED 有值） */
  closedAt?: string | null
  title: string
  communityId: number | null
  /** 关联业务对象（VIEWING_GROUP=看房预约 id；LEASE_GROUP=租约 id；WORK_ORDER_GROUP=工单 id；DIRECT=null） */
  relatedId: number | null
  lastMessage: string | null
  lastMessageAt: string | null
  unreadCount: number
  createdAt: string
  /** 参与者数量（仅会话详情返回；列表不含） */
  participantCount?: number
  /** 参与者列表（仅会话详情返回；R74 工单临时群聊面板据此展示群成员） */
  participants?: IConversationParticipant[]
}

/** 创建/获取直通会话请求（居民端：与本小区管理员；DEF-076 管理端可代居民发起） */
export interface IDirectConversationDTO {
  communityId: number
  /**
   * 管理端/超管**代居民发起**时必填（缺省 → 400「请指定直通会话的居民」）；
   * 居民端仅可为本人发起（传他人 → 403），故居民侧不传。
   */
  residentId?: number
}

/** 发送消息请求 */
export interface IConversationMessageDTO {
  content: string
}

/**
 * 会话内发起看房预约请求（R69/V20：POST /conversations/{id}/viewing-appointments）。
 * 字段与房源页预约完全同构，但**不带 leaseId 或未登录访问者身份字段**——所属租约由会话 relatedId 推导，
 * 联系人身份由当前居民 JWT 确定，前端自行填写会造成越权关联。
 */
export interface ConversationViewingCreateDTO {
  housingId: number
  appointmentDate: string
  startTime: string
  endTime: string
  /** 可选联系信息；为空时后端取居民档案手机号 */
  contactPhone?: string
  remark?: string
}

/** 会话列表查询参数 */
export interface IConversationQuery extends PageQuery {
  type?: ConversationType
}
