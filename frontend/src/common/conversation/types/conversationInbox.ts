import type { ConversationType } from '@/common/conversation/types/conversation'
export type ConversationSource = 'CONVERSATION' | 'FEEDBACK'
export type InboxType = ConversationType | 'FEEDBACK'
export interface ConversationInboxItem {
 source: ConversationSource; id: number; type: InboxType; title: string; status: string
 communityId: number; communityName: string | null; relatedId: number | null; houseCode: string | null
 residentLabel: string | null; lastMessage: string | null; activityAt: string; unreadCount: number
}
export const inboxKey = (item: Pick<ConversationInboxItem, 'source' | 'id'>) => `${item.source}:${item.id}`
export const inboxTypeLabels: Record<InboxType, string> = { LEASE_GROUP: '租约', VIEWING_GROUP: '看房', WORK_ORDER_GROUP: '工单', FEEDBACK: '反馈', DIRECT: '直通' }
