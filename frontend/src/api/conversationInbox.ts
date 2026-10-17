import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { ConversationInboxItem, ConversationSource, InboxType } from '@/types/modules/conversationInbox'
export function listConversationInbox(query: { page: number; size: number; type?: InboxType; keyword?: string; unreadOnly?: boolean }) {
 return http.get<PageResult<ConversationInboxItem>>('/conversation-inbox', query)
}
export function listInboxMessages<T>(source: ConversationSource, id: number, beforeId?: number, afterId?: number) {
 return http.get<T[]>(`/conversation-inbox/${source}/${id}/messages`, { beforeId, afterId, size: 40 })
}
export function markFeedbackRead(id: number, lastMessageId: number) { return http.post<void>(`/conversation-inbox/FEEDBACK/${id}/read`, { lastMessageId }) }
