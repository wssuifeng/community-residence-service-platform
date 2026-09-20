<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listViewingMessages, sendViewingMessage } from '@/api/housing'
import type { IViewingMessage } from '@/types/modules/housing'
import { subscribe, isWsConnected } from '@/utils/websocket'
import { formatDateTime } from '@/utils/date'
import { useUserStore } from '@/store/user'

/**
 * 带看会话窗口（R59，v1.3）：预约居民与带看人（服务人员/社区管理员）就单次看房预约沟通。
 * 与反馈会话同构：发送走 HTTP（校验与错误提示链路不变），实时性靠 WS 推送（按 id 去重）
 * + HTTP 轮询兜底（WS 在线时降频）；非会话参与者（403）降级只读并停止轮询。
 */

const props = defineProps<{ appointmentId: number }>()

const POLL_RELAXED_INTERVAL = 15000
const POLL_FALLBACK_INTERVAL = 5000

const userStore = useUserStore()

const messages = ref<IViewingMessage[]>([])
const messageInput = ref('')
const sending = ref(false)
const readOnly = ref(false)
const messageListRef = ref<HTMLElement | null>(null)
let unsubscribeWs: (() => void) | null = null
let pollTimer: ReturnType<typeof setTimeout> | null = null

function isMine(message: IViewingMessage): boolean {
  return message.senderId === userStore.user?.id
}

function scrollToBottom(): void {
  const container = messageListRef.value
  if (container) container.scrollTop = container.scrollHeight
}

async function loadMessages(): Promise<void> {
  try {
    messages.value = await listViewingMessages(props.appointmentId)
    void nextTick(scrollToBottom)
  } catch {
    /* 非参与者/预约不存在：降级只读，停止轮询与订阅重试 */
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
    const message = await sendViewingMessage(props.appointmentId, { content })
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

/** WS 载荷归一：{type, data} 包裹为主，兼容裸 VO（与反馈会话 extractWsMessage 同口径）；
 * createdAt 非字符串（异常序列化形态）时丢弃该推送，交由轮询兜底拉全量，
 * 避免畸形载荷进入列表后渲染链路异常 */
function extractWsMessage(raw: unknown): IViewingMessage | null {
  const event = raw as { type?: string; data?: IViewingMessage }
  const candidate =
    event?.type === 'APPOINTMENT_MESSAGE' && event.data
      ? event.data
      : event?.type === undefined && typeof (raw as IViewingMessage)?.content === 'string'
        ? (raw as IViewingMessage)
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

/* 轮询兜底：setTimeout 链按 WS 连接状态自适应选间隔（反馈会话同模式） */
function schedulePoll(): void {
  pollTimer = setTimeout(() => {
    void loadMessages().finally(() => {
      if (!readOnly.value) schedulePoll()
    })
  }, isWsConnected() ? POLL_RELAXED_INTERVAL : POLL_FALLBACK_INTERVAL)
}

onMounted(() => {
  void loadMessages()
  unsubscribeWs = subscribe(`/topic/appointment/${props.appointmentId}`, handleWsPush)
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
  <section class="appointment-chat">
    <header class="chat-head">
      <h3 class="chat-title">带看沟通</h3>
      <p class="chat-sub">{{ readOnly ? '仅预约人与带看人可参与会话' : '与带看人沟通到场时间与房源问题' }}</p>
    </header>

    <div ref="messageListRef" class="message-list">
      <div v-if="messages.length === 0" class="chat-empty">暂无消息，向带看人打个招呼吧</div>
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
  </section>
</template>

<style scoped>
.appointment-chat {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.chat-head {
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid var(--color-border);
}

.chat-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.chat-sub {
  margin-top: 2px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  max-height: 360px;
  min-height: 160px;
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
</style>
