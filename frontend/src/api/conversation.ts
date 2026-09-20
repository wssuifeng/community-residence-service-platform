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

/** 会话消息列表（参与者可读，时间正序非分页） */
export function listConversationMessages(conversationId: number) {
  return http.get<IConversationMessage[]>(`/conversations/${conversationId}/messages`)
}

/** 发送会话消息（参与者可发；WS 广播给全部在线参与者） */
export function sendConversationMessage(conversationId: number, data: IConversationMessageDTO) {
  return http.post<IConversationMessage>(`/conversations/${conversationId}/messages`, data)
}

/** 创建/获取居民与所在社区管理员的直通会话（幂等：已有会话直接返回） */
export function openDirectConversation(data: IDirectConversationDTO) {
  return http.post<IConversation>('/conversations/direct', data)
}
