<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listConversationMessages, sendConversationMessage } from '@/api/conversation'
import type { IConversationMessage, IConversationParticipant } from '@/types/modules/conversation'
import { conversationRoleLabels } from '@/types/modules/conversation'
import { subscribe, isWsConnected } from '@/utils/websocket'
import { formatDateTime } from '@/utils/date'
import { useUserStore } from '@/store/user'

/**
 * R63 多方会话窗口（群聊/直通通用）：参与者在同一会话内实时沟通。
 * 与反馈会话/看房会话同构：发送走 HTTP、实时靠 WS 推送（按 id 去重）+
 * HTTP 轮询兜底（WS 在线降频）；非参与者 403 降级只读并停止轮询。
 *
 * R68/V20 会话生命周期：`closed` 为真（conversation.status='CLOSED'）时
 * 停收停发——输入区换成「会话已关闭」提示，不轮询、不订阅、不发送，
 * 历史消息仍完整可读（只读留档）。关闭态由父层传入：父层已从
 * GET /conversations/{id} 拿到 status，组件不重复请求。
 *
 * R74 增量（**仅新增可选能力，不改变既有调用方的渲染与行为**）：
 * - `participants` 传入时在消息区上方展示群成员（居民提交人 / 受理管理员 / 被派服务人员）；
 * - `closedHint` 可覆盖关闭态提示文案（缺省为中性文案「会话已关闭，历史消息只读留档」，
 *   租约/工单等业务口径由调用方自行传入专属文案，OCR DEF-122）；
 * - `title` 传入时渲染面板标题行。
 */

const props = defineProps<{
  conversationId: number
  /** 会话是否已关闭（R68：租约归档或申请取消后关闭，停收停发只读留档） */
  closed?: boolean
  /** 关闭态提示文案覆盖（R74：工单侧为「工单已结束，会话资源已回收…」；租约侧为「租约流程已结束…」） */
  closedHint?: string
  /** 群成员（仅会话详情返回；缺省不渲染成员行，既有调用方不受影响） */
  participants?: IConversationParticipant[]
  /** 面板标题（缺省不渲染标题行） */
  title?: string
}>()

/* closed 未显式传入（undefined）时按"未关闭"处理，但无法确认状态是调用方契约缺口
   （OCR DEF-122）：dev 环境警告提示补传，避免关闭态误判为可发送 */
if (import.meta.env.DEV && props.closed === undefined) {
  console.warn(
    `[ConversationChat] conversationId=${props.conversationId} 未显式传入 closed，` +
      '关闭态将按未关闭处理；调用方应从会话详情传入 status===\'CLOSED\''
  )
}

const DEFAULT_CLOSED_HINT = '会话已关闭，历史消息只读留档'

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
  /* 关闭态由父层传入优先拦截；后端同样拒绝（CONVERSATION_CLOSED 5604）双保险 */
  if (props.closed) {
    ElMessage.warning('会话已关闭，不可再发送消息')
    return
  }
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

/* 轮询兜底：setTimeout 链按 WS 连接状态自适应选间隔；关闭态不轮询 */
function schedulePoll(): void {
  pollTimer = setTimeout(() => {
    void loadMessages().finally(() => {
      if (!readOnly.value && !props.closed) schedulePoll()
    })
  }, isWsConnected() ? POLL_RELAXED_INTERVAL : POLL_FALLBACK_INTERVAL)
}

onMounted(() => {
  void loadMessages()
  /* 关闭态：历史只读即可，无需订阅实时推送与轮询 */
  if (props.closed) return
  unsubscribeWs = subscribe(`/topic/conversation/${props.conversationId}`, handleWsPush)
  if (!readOnly.value) schedulePoll()
})

/* 父层异步拿到会话状态（详情接口在途）→ 状态落定后再决定是否开启实时通道 */
watch(
  () => props.closed,
  (closed) => {
    if (closed) {
      unsubscribeWs?.()
      unsubscribeWs = null
      if (pollTimer !== null) {
        clearTimeout(pollTimer)
        pollTimer = null
      }
      return
    }
    if (unsubscribeWs === null) {
      unsubscribeWs = subscribe(`/topic/conversation/${props.conversationId}`, handleWsPush)
    }
    if (pollTimer === null && !readOnly.value) schedulePoll()
  }
)

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
    <!-- R74：可选标题行与群成员行（未传则不渲染，既有用法渲染结果不变） -->
    <header v-if="title || (participants && participants.length > 0)" class="chat-header">
      <p v-if="title" class="chat-title">{{ title }}</p>
      <p v-if="participants && participants.length > 0" class="chat-members">
        <span class="chat-members-label">群成员</span>
        <span v-for="member in participants" :key="`${member.role}-${member.userId}`" class="chat-member">
          {{ member.name }}（{{ conversationRoleLabels[member.role] ?? member.role }}）
        </span>
      </p>
    </header>

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

    <!-- 输入区三态：会话已关闭（只读留档）> 非参与者只读 > 正常输入 -->
    <footer v-if="closed" class="chat-closed">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <rect x="5" y="11" width="14" height="9" rx="2" />
        <path d="M8.5 11V8a3.5 3.5 0 0 1 7 0v3" />
      </svg>
      <!-- 传入 closedHint 时带业务口径前缀；缺省整体用中性默认文案（避免前缀重复拼接） -->
      {{ closedHint ? `会话已关闭：${closedHint}` : DEFAULT_CLOSED_HINT }}
    </footer>
    <footer v-else-if="!readOnly" class="chat-input">
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

/* R74：标题 + 群成员行（仅传参时渲染） */
.chat-header {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg);
}

.chat-title {
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--color-text-primary);
}

.chat-members {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-xs) var(--spacing-sm);
  margin-top: 4px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.chat-members-label {
  color: var(--color-text-disabled);
}

.chat-member {
  padding: 1px 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
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

/* 已关闭会话提示条（R68）：中性灰，区别于「非参与者」的只读提示 */
.chat-closed {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-lg);
  border-top: 1px solid var(--color-border);
  background: var(--color-bg);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.chat-closed svg {
  flex-shrink: 0;
}
</style>
