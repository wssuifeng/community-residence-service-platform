import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { ConversationInboxItem, ConversationSource, InboxType } from '@/types/modules/conversationInbox'
export function listConversationInbox(query: { page: number; size: number; type?: InboxType; keyword?: string; unreadOnly?: boolean }) {
 return http.get<PageResult<ConversationInboxItem>>('/conversation-inbox', query)
}
export interface InboxMessageWindow<T> { records: T[]; startAtUnread: boolean; firstUnreadMessageId: number | null; hasOlder: boolean; hasNewer: boolean }
export function listInboxWindow<T>(source: ConversationSource, id: number, beforeId?: number, afterId?: number) {
 return http.get<InboxMessageWindow<T>>(`/conversation-inbox/${source}/${id}/messages`, { beforeId, afterId, size: 40 })
}
export async function listInboxMessages<T>(source: ConversationSource, id: number, beforeId?: number, afterId?: number) {
 return (await listInboxWindow<T>(source, id, beforeId, afterId)).records
}
export function markFeedbackRead(id: number, lastMessageId: number) { return http.post<void>(`/conversation-inbox/FEEDBACK/${id}/read`, { lastMessageId }) }
