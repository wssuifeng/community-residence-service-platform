import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IConversation,
  IConversationMessage,
  IConversationMessageDTO,
  IConversationQuery,
  IDirectConversationDTO
} from '@/types/modules/conversation'

/**
 * R63 多方会话接口（v1.5）：看房预约群聊 + 居民-社区管理员直通。
 * 实时性复用 R30/R59 模式：WS /topic/conversation/{id} 推送（参与者鉴权）
 * + HTTP 轮询兜底（组件层）；发送走 HTTP。
 */

/** 我的会话列表（参与者视角，含未读数与最后消息摘要） */
export function listConversations(params?: IConversationQuery) {
  return http.get<PageResult<IConversation>>('/conversations', params)
}

/**
 * 会话详情（V20 新增端点 R68）：返回含 status/closedAt 的会话本身，
 * 供会话详情页判定「已关闭」只读态与租约群聊业务入口（relatedId=租约 ID）。
 */
export function getConversation(id: number) {
  return http.get<IConversation>(`/conversations/${id}`)
}

/** 会话消息列表（参与者可读，时间正序非分页） */
export function listConversationMessages(conversationId: number) {
  return http.get<IConversationMessage[]>(`/conversations/${conversationId}/messages`)
}

/** 仅确认当前会话实际展示的最后一条消息；后端游标原子前移。 */
export function markConversationRead(conversationId: number, lastMessageId: number) {
  return http.post<void>(`/conversations/${conversationId}/read`, { lastMessageId })
}

/** 发送会话消息（参与者可发；WS 广播给全部在线参与者） */
export function sendConversationMessage(conversationId: number, data: IConversationMessageDTO) {
  return http.post<IConversationMessage>(`/conversations/${conversationId}/messages`, data)
}

/**
 * 创建/获取居民与所在社区管理员的直通会话（幂等：同（DIRECT，社区，居民）已有则返回同一条）。
 * 居民端为本人；**管理端/超管传 `residentId` 代居民发起**（DEF-076，缺省 → 400、
 * 未绑定该社区 → 403）。
 */
export function openDirectConversation(data: IDirectConversationDTO) {
  return http.post<IConversation>('/conversations/direct', data)
}

/**
 * 建/取**租约沟通会话**（DEF-076 管理端补建入口，接口设计 §9.14 扩展）：
 * `POST /conversations/lease-groups`，ADMIN/SUPER_ADMIN；幂等返回既有或新建的
 * `LEASE_GROUP`，并由后端回写 `lease_record.conversation_id`。
 * 用于存量/旁路数据（审批内核未建群）下管理端自救建群。
 */
export function ensureLeaseGroupConversation(leaseId: number) {
  return http.post<IConversation>('/conversations/lease-groups', { leaseId })
}

export function getConversationViewingContext(id:number) {
  return http.get<import('@/types/modules/housing').IHousing>(`/conversations/${id}/viewing-context`)
}
export function listConversationViewingSlots(id:number,query:import('@/types/modules/housing').AvailableViewingTimeslotQuery) {
  return http.get<import('@/types/modules/housing').IAvailableViewingTimeslot[]>(`/conversations/${id}/viewing-slots`,query)
}
export function createConversationViewing(id:number,data:import('@/types/modules/conversation').ConversationViewingCreateDTO) {
  return http.post<import('@/types/modules/housing').IViewingAppointment>(`/conversations/${id}/viewing-appointments`,data)
}
