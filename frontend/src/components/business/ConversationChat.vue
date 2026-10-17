<script setup lang="ts">
import { computed, getCurrentInstance, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listInboxMessages } from '@/api/conversationInbox'
import { listConversationMessages, sendConversationMessage, markConversationRead } from '@/api/conversation'
import { getLeaseAgreement } from '@/api/agreement'
import AgreementSignPanel from './AgreementSignPanel.vue'
import type { IConversationMessage, IConversationParticipant } from '@/types/modules/conversation'
import { conversationRoleLabels } from '@/types/modules/conversation'
import { useChatChannel } from '@/composables/useChatChannel'
import { sendChatImage } from '@/api/chat'
import ChatMessageList from './ChatMessageList.vue'
import ChatComposer from './ChatComposer.vue'
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
  paged?: boolean
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

const emit=defineEmits<{changed:[];openAgreement:[agreementId:number];read:[conversationId:number,lastMessageId:number]}>()
const businessMenuVisible=ref(false)

/* closed 未显式传入（undefined）时按"未关闭"处理，但无法确认状态是调用方契约缺口
   （OCR DEF-122）：dev 环境警告提示补传，避免关闭态误判为可发送 */
if (import.meta.env.DEV && props.closed === undefined) {
  console.warn(
    `[ConversationChat] conversationId=${props.conversationId} 未显式传入 closed，` +
      '关闭态将按未关闭处理；调用方应从会话详情传入 status===\'CLOSED\''
  )
}

const DEFAULT_CLOSED_HINT = '会话已关闭，历史消息只读留档'


const userStore = useUserStore()
const instance = getCurrentInstance()
const agreementRole = computed<'ADMIN' | 'RESIDENT' | null>(() => {
  const role = userStore.user?.role
  if (role === 'RESIDENT') return 'RESIDENT'
  if (role === 'ADMIN' || role === 'SUPER_ADMIN') return 'ADMIN'
  return null
})
const snapshotVisible = ref(false)
const openingSnapshot = ref(false)
const snapshotLeaseId = ref<number | null>(null)
const snapshotAgreementId = ref<number | undefined>()

/* 主会话页已提供完整协议操作；其他嵌入入口复用精确快照的只读面板，
   不依赖缺失的事件监听，也不让服务人员得到正文入口。权限仍由后端校验。 */
async function openAgreementSnapshot(agreementId: number): Promise<void> {
  if (!agreementRole.value || openingSnapshot.value) return
  if (instance?.vnode.props?.onOpenAgreement) {
    emit('openAgreement', agreementId)
    return
  }
  const conversationId = props.conversationId
  const role = agreementRole.value
  openingSnapshot.value = true
  try {
    const agreement = await getLeaseAgreement(agreementId)
    if (props.conversationId !== conversationId || agreementRole.value !== role) return
    snapshotLeaseId.value = agreement.leaseId
    snapshotAgreementId.value = agreementId
    snapshotVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '协议快照加载失败，请稍后重试')
  } finally {
    openingSnapshot.value = false
  }
}

const { messages, text, sending, readOnly, sendText, sendImage, loading, error, reload, hasOlder, loadingOlder, loadOlder } = useChatChannel<IConversationMessage>({
 id:()=>props.conversationId,closed:()=>props.closed??false,load:listConversationMessages,
 window:props.paged?(id,before,after)=>listInboxMessages<IConversationMessage>('CONVERSATION',id,before,after):undefined,
 changed:()=>emit('changed'),
 send:(id,content)=>sendConversationMessage(id,{content}),image:(id,file)=>sendChatImage('conversations',id,file),
 topic:id=>`/topic/conversation/${id}`,eventType:'CONVERSATION_MESSAGE'
})
/* 仅消息列表实际可见的最高ID推进游标；失败不清角标，下一次可见事件重试。 */
const visibleCursor = ref(0)
const acknowledged = new Map<number, number>()
const readRequests = new Set<number>()
let readDisposed = false
async function acknowledgeVisibleMessages(cursor: number): Promise<void> {
  visibleCursor.value = cursor
  const id = props.conversationId
  if (readDisposed || cursor <= (acknowledged.get(id) ?? 0) || readRequests.has(id)) return
  readRequests.add(id)
  try {
    await markConversationRead(id, cursor)
    if (readDisposed) return
    acknowledged.set(id, cursor)
    if (id === props.conversationId) emit('read', id, cursor)
  } catch { /* 未读失败保持真实状态，后续可见事件重试。 */ }
  finally {
    readRequests.delete(id)
    if (!readDisposed && id === props.conversationId && visibleCursor.value > cursor) void acknowledgeVisibleMessages(visibleCursor.value)
  }
}
watch(() => props.conversationId, () => { visibleCursor.value = 0; businessMenuVisible.value = false; snapshotVisible.value = false })
onBeforeUnmount(() => { readDisposed = true })

const renderedMessages=computed(()=>messages.value.map(message=>({...message,
 senderLabel:`${message.senderName}（${conversationRoleLabels[message.senderRole as keyof typeof conversationRoleLabels]??message.senderRole}）`,
 mine:message.senderRole!=='SYSTEM'&&(message.senderRole==='RESIDENT')===(userStore.user?.role==='RESIDENT')&&message.senderId===userStore.user?.id
})))
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

    <p v-if="loading" class="chat-load-state" role="status">正在加载消息…</p>
    <p v-if="error" class="chat-load-state" role="alert">{{ error }} <button type="button" @click="reload">重试</button></p>
    <button v-if="hasOlder" type="button" class="history-button" :disabled="loadingOlder" @click="loadOlder">{{ loadingOlder ? '加载中…' : '加载更早消息' }}</button>
    <ChatMessageList :messages="renderedMessages" :position-key="`${userStore.user?.role}:${userStore.user?.id}:CONVERSATION:${conversationId}`" @visible="acknowledgeVisibleMessages">
      <template #message-actions="{message}">
        <template v-if="message.messageType==='LEASE_AGREEMENT' && message.senderRole==='SYSTEM' && message.agreementId">
          <button v-if="agreementRole" type="button" class="agreement-message" :disabled="openingSnapshot" @click="openAgreementSnapshot(message.agreementId)">租赁协议 #{{message.agreementId}} · 查看协议快照</button>
          <p v-else class="agreement-access-hint">协议正文仅租约居民和管理方可查看</p>
        </template>
      </template>
    </ChatMessageList>
    <ChatComposer v-model="text" :busy="sending" :closed="closed" :read-only="readOnly || loading || (!!error && !messages.length)" :closed-hint="closedHint?`会话已关闭：${closedHint}`:DEFAULT_CLOSED_HINT" @send="sendText" @image="sendImage">
      <template #actions><button v-if="$slots['business-actions']" type="button" class="business-toggle" :aria-expanded="businessMenuVisible" aria-label="会话业务功能" @click="businessMenuVisible=!businessMenuVisible">＋</button></template>
    </ChatComposer>
    <div v-if="businessMenuVisible && !closed && !readOnly" class="business-actions"><slot name="business-actions"/></div>
    <el-dialog v-model="snapshotVisible" title="租赁协议快照（只读）" width="min(720px, 92vw)" destroy-on-close>
      <AgreementSignPanel
        v-if="snapshotVisible && snapshotLeaseId && agreementRole"
        :lease-id="snapshotLeaseId"
        :role="agreementRole"
        :requested-agreement-id="snapshotAgreementId"
        :read-only="true"
        :self-managed="false"
      />
    </el-dialog>
  </section>
</template>

<style scoped>
.chat-load-state{padding:10px 18px;margin:0;font-size:13px;color:var(--color-text-secondary)}.history-button{border:0;background:var(--color-bg);padding:10px;color:var(--color-primary);cursor:pointer}

.agreement-access-hint { margin-top: 10px; color: var(--color-text-secondary); font-size: 12px; }
.business-toggle{width:36px;height:36px;border:1px solid var(--color-border);border-radius:50%;background:transparent;font-size:24px;flex-shrink:0;cursor:pointer}
.business-actions{display:flex;gap:12px;flex-wrap:wrap;padding:12px 18px;border-top:1px solid var(--color-border);background:var(--color-bg)}
.agreement-message{display:block;margin-top:10px;padding:10px 14px;border:1px solid var(--color-primary);border-radius:8px;background:var(--color-bg);color:var(--color-primary);cursor:pointer;max-width:100%;white-space:normal}

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
