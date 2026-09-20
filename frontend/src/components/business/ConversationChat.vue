<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listConversationMessages, sendConversationMessage } from '@/api/conversation'
import type { IConversationMessage } from '@/types/modules/conversation'
import { subscribe, isWsConnected } from '@/utils/websocket'
import { formatDateTime } from '@/utils/date'
import { useUserStore } from '@/store/user'

/**
 * R63 多方会话窗口（群聊/直通通用）：参与者在同一会话内实时沟通。
 * 与反馈会话/看房会话同构：发送走 HTTP、实时靠 WS 推送（按 id 去重）+
 * HTTP 轮询兜底（WS 在线降频）；非参与者 403 降级只读并停止轮询。
 */

const props = defineProps<{ conversationId: number }>()

const POLL_RELAXED_INTERVAL = 15000
const POLL_FALLBACK_INTERVAL = 5000

const userStore = useUserStore()

const messages = ref<IConversationMessage[]>([])
const messageInput = ref('')
const sending = ref(false)
const readOnly = ref(false)
const messageListRef = ref<HTMLElement | null>(null)
let unsubscribeWs: (() => void) | null = null
let pollTimer: ReturnType<typeof setTimeout> | null = null

function isMine(message: IConversationMessage): boolean {
  return message.senderId === userStore.user?.id
}

function scrollToBottom(): void {
  const container = messageListRef.value
  if (container) container.scrollTop = container.scrollHeight
}

async function loadMessages(): Promise<void> {
  try {
    messages.value = await listConversationMessages(props.conversationId)
    void nextTick(scrollToBottom)
  } catch {
    /* 非参与者/会话不存在：降级只读，停止轮询 */
    readOnly.value = true
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }
}

async function handleSend(): Promise<void> {
  const content = messageInput.value.trim()
  if (content === '' || sending.value) return
  sending.value = true
  try {
    const message = await sendConversationMessage(props.conversationId, { content })
    messageInput.value = ''
    /* 本端消息也会经 WS 广播回来，按 id 去重 */
    if (!messages.value.some((item) => item.id === message.id)) {
      messages.value.push(message)
    }
    void nextTick(scrollToBottom)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败，请稍后重试')
  } finally {
    sending.value = false
  }
}

/** WS 载荷归一：{type, data} 包裹为主，兼容裸 VO；形态异常丢弃走轮询兜底 */
function extractWsMessage(raw: unknown): IConversationMessage | null {
  const event = raw as { type?: string; data?: IConversationMessage }
  const candidate =
    event?.type === 'CONVERSATION_MESSAGE' && event.data
      ? event.data
      : event?.type === undefined && typeof (raw as IConversationMessage)?.content === 'string'
        ? (raw as IConversationMessage)
        : null
  if (candidate && typeof candidate.createdAt === 'string' && typeof candidate.id === 'number') {
    return candidate
  }
  return null
}

function handleWsPush(raw: unknown): void {
  const message = extractWsMessage(raw)
  if (message && !messages.value.some((item) => item.id === message.id)) {
    messages.value.push(message)
    void nextTick(scrollToBottom)
  }
}

/* 轮询兜底：setTimeout 链按 WS 连接状态自适应选间隔 */
function schedulePoll(): void {
  pollTimer = setTimeout(() => {
    void loadMessages().finally(() => {
      if (!readOnly.value) schedulePoll()
    })
  }, isWsConnected() ? POLL_RELAXED_INTERVAL : POLL_FALLBACK_INTERVAL)
}

onMounted(() => {
  void loadMessages()
  unsubscribeWs = subscribe(`/topic/conversation/${props.conversationId}`, handleWsPush)
  if (!readOnly.value) schedulePoll()
})

onUnmounted(() => {
  unsubscribeWs?.()
  unsubscribeWs = null
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <section class="conversation-chat">
    <div ref="messageListRef" class="message-list">
      <div v-if="messages.length === 0" class="chat-empty">暂无消息，说点什么开始沟通</div>
      <div
        v-for="message in messages"
        :key="message.id"
        class="message-row"
        :class="{ mine: isMine(message) }"
      >
        <div class="bubble">
          <p class="bubble-meta">{{ message.senderName }} · {{ formatDateTime(message.createdAt) }}</p>
          <p class="bubble-content">{{ message.content }}</p>
        </div>
      </div>
    </div>

    <footer v-if="!readOnly" class="chat-input">
      <input
        v-model="messageInput"
        type="text"
        class="chat-text"
        placeholder="输入消息…"
        maxlength="500"
        @keyup.enter="handleSend"
      />
      <button type="button" class="chat-send" :disabled="sending || messageInput.trim() === ''" @click="handleSend">
        发送
      </button>
    </footer>
    <footer v-else class="chat-readonly">仅会话参与者可参与沟通</footer>
  </section>
</template>

<style scoped>
.conversation-chat {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  max-height: 420px;
  min-height: 200px;
  padding: var(--spacing-md) var(--spacing-lg);
  overflow-y: auto;
}

.chat-empty {
  margin: auto;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.message-row {
  display: flex;
  justify-content: flex-start;
}

.message-row.mine {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg-hover);
}

.message-row.mine .bubble {
  background: color-mix(in srgb, var(--color-primary) 12%, #fff);
}

.bubble-meta {
  font-size: 11px;
  color: var(--color-text-disabled);
}

.bubble-content {
  margin-top: 2px;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
  word-break: break-word;
  white-space: pre-wrap;
}

.chat-input {
  display: flex;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  border-top: 1px solid var(--color-border);
}

.chat-text {
  flex: 1;
  padding: 8px var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  outline: none;
}

.chat-text:focus {
  border-color: var(--color-primary);
}

.chat-send {
  padding: 8px var(--spacing-lg);
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.chat-send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.chat-readonly {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-top: 1px solid var(--color-border);
  text-align: center;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
