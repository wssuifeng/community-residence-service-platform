/** R63 多方会话类型定义（v1.5：conversation/participant/message 三级模型） */
import type { PageQuery } from '@/types/api'

/** 会话类型：看房预约群聊 / 居民-社区管理员直通 */
export type ConversationType = 'VIEWING_GROUP' | 'DIRECT'

/** 参与者角色（决定气泡对齐与标识） */
export type ConversationRole = 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'

/** 会话消息（后端 ConversationMessageVO；WS /topic/conversation/{id} 同构推送） */
export interface IConversationMessage {
  id: number
  conversationId: number
  senderId: number
  senderName: string
  senderRole: ConversationRole
  content: string
  createdAt: string
}

/** 会话（列表元素：类型/标题/关联对象/最后消息摘要/未读数） */
export interface IConversation {
  id: number
  type: ConversationType
  title: string
  communityId: number | null
  /** 关联业务对象（VIEWING_GROUP=看房预约 id；DIRECT=null） */
  relatedId: number | null
  lastMessage: string | null
  lastMessageAt: string | null
  unreadCount: number
  createdAt: string
}

/** 创建/获取直通会话请求（居民端：与本社区管理员） */
export interface IDirectConversationDTO {
  communityId: number
}

/** 发送消息请求 */
export interface IConversationMessageDTO {
  content: string
}

/** 会话列表查询参数 */
export interface IConversationQuery extends PageQuery {
  type?: ConversationType
}
