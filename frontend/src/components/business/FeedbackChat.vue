<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ChatMessageList from './ChatMessageList.vue'
import ChatComposer from './ChatComposer.vue'
import { useChatChannel } from '@/composables/useChatChannel'
import { listFeedbackMessages, sendFeedbackMessage } from '@/api/feedback'
import { listInboxMessages, markFeedbackRead } from '@/api/conversationInbox'
import { sendChatImage } from '@/api/chat'
import { useUserStore } from '@/store/user'
import type { IFeedbackMessage } from '@/types/modules/feedback'
const props = defineProps<{ feedbackId: number; closed: boolean; paged?: boolean; initialContent?: string; initialName?: string; initialDate?: string }>()
const emit = defineEmits<{ changed: []; read: [] }>(), user = useUserStore()
const { messages, text, sending, readOnly, sendText, sendImage, loading, error, reload, hasOlder, loadingOlder, loadOlder } = useChatChannel<IFeedbackMessage>({
 id: () => props.feedbackId, closed: () => props.closed, load: listFeedbackMessages,
 window: props.paged ? (id, before, after) => listInboxMessages<IFeedbackMessage>('FEEDBACK', id, before, after) : undefined,
 send: (id, content) => sendFeedbackMessage(id, { content }), image: (id, file) => sendChatImage('feedbacks', id, file),
 topic: id => `/topic/feedback/${id}`, eventType: 'FEEDBACK_MESSAGE', changed: () => emit('changed')
})
const rendered = computed(() => [
 ...(!hasOlder.value && props.initialContent ? [{ id: -props.feedbackId, content: props.initialContent, createdAt: props.initialDate ?? '', senderLabel: props.initialName ?? '居民', mine: user.role === 'RESIDENT' }] : []),
 ...messages.value.map(message => ({ ...message, senderLabel: `${message.senderName}（${message.senderType === 'ADMIN' ? '管理员' : '居民'}）`, mine: message.senderId != null ? message.senderId === user.user?.id && (message.senderType === 'RESIDENT') === (user.role === 'RESIDENT') : message.senderType === 'RESIDENT' && user.role === 'RESIDENT' }))
])
const visibleCursor = ref(-1)
let acknowledged = -1, pending = false, disposed = false
async function read(cursor: number) {
 if (!props.paged) return
 visibleCursor.value = cursor
 if (disposed || pending || cursor <= acknowledged) return
 const id = props.feedbackId; pending = true
 try { await markFeedbackRead(id, cursor); if (!disposed && id === props.feedbackId) { acknowledged = cursor; emit('read') } }
 catch { /* 失败不能冒充已读。 */ }
 finally { pending = false; if (!disposed && id === props.feedbackId && visibleCursor.value > cursor) void read(visibleCursor.value) }
}
watch(() => props.feedbackId, () => { acknowledged = -1; visibleCursor.value = -1 })
onBeforeUnmount(() => { disposed = true })
</script>
<template>
 <section class="feedback-chat">
  <p v-if="loading" role="status">正在加载消息…</p>
  <p v-if="error" role="alert">{{ error }} <button type="button" @click="reload">重试</button></p>
  <button v-if="hasOlder" type="button" class="history-button" :disabled="loadingOlder" @click="loadOlder">{{ loadingOlder ? '加载中…' : '加载更早消息' }}</button>
  <ChatMessageList :messages="rendered" :position-key="`${user.user?.role}:${user.user?.id}:FEEDBACK:${feedbackId}`" @visible="read" />
  <ChatComposer v-model="text" :busy="sending" :closed="closed" :read-only="readOnly || loading || (!!error && !messages.length)" closed-hint="反馈已办结，历史消息只读留档" @send="sendText" @image="sendImage" />
 </section>
</template>
<style scoped>
.feedback-chat{display:flex;flex-direction:column;min-height:0;overflow:hidden}.history-button{border:0;background:var(--color-bg);padding:10px;color:var(--color-primary);cursor:pointer}
</style>
