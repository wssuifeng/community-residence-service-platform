<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getFeedbackDetail,
  listFeedbackMessages,
  sendFeedbackMessage,
  listFeedbackAttachments
} from '@/api/feedback'
import type {
  IFeedback,
  IFeedbackMessage,
  IFeedbackAttachment
} from '@/types/modules/feedback'
import {
  feedbackStatusLabels,
  feedbackCategoryLabels
} from '@/types/modules/feedback'
import { formatDateTime, formatRelative } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'

/**
 * 反馈详情 + 会话（居民端）：居民消息靠右、管理员靠左；
 * P1 HTTP 轮询——每 5 秒全量刷新消息（API 无 since 增量参数），卸载时清理定时器
 */
const route = useRoute()
const router = useRouter()

const feedbackId = Number(route.params.id)
const feedback = ref<IFeedback | null>(null)
const messages = ref<IFeedbackMessage[]>([])
const attachments = ref<IFeedbackAttachment[]>([])
const loading = ref(true)
const messageInput = ref('')
const sending = ref(false)

/** 消息轮询间隔（ms） */
const POLL_INTERVAL = 5000
let pollTimer: ReturnType<typeof setInterval> | null = null

const messageListRef = ref<HTMLElement | null>(null)

/** 反馈状态 → StatusTag 语义色 */
function statusTagType(
  status: IFeedback['status']
): 'pending' | 'processing' | 'completed' {
  if (status === 'OPEN') return 'pending'
  if (status === 'IN_PROGRESS') return 'processing'
  return 'completed'
}

async function loadDetail(): Promise<void> {
  try {
    feedback.value = await getFeedbackDetail(feedbackId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '反馈加载失败')
    router.replace('/resident/feedbacks')
  }
}

async function loadMessages(): Promise<void> {
  try {
    // 后端返回全量数组（默认时间升序），前端不再分页
    messages.value = await listFeedbackMessages(feedbackId)
    await nextTick()
    scrollToBottom()
  } catch {
    /* 轮询失败静默跳过，下次轮询重试 */
  }
}

async function loadAttachments(): Promise<void> {
  try {
    attachments.value = await listFeedbackAttachments(feedbackId)
  } catch {
    attachments.value = []
  }
}

function scrollToBottom(): void {
  const container = messageListRef.value
  if (container) container.scrollTop = container.scrollHeight
}

async function handleSend(): Promise<void> {
  const content = messageInput.value.trim()
  if (!content) return
  sending.value = true
  try {
    await sendFeedbackMessage(feedbackId, { content })
    messageInput.value = ''
    /* 发送后立即刷新：消息与会话状态（首条消息触发 OPEN→IN_PROGRESS） */
    await Promise.all([loadMessages(), loadDetail()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadDetail(), loadMessages(), loadAttachments()])
  loading.value = false
  /* 5 秒轮询新消息与状态变化（管理员回复/办结） */
  pollTimer = setInterval(() => {
    loadMessages()
    loadDetail()
  }, POLL_INTERVAL)
})

onUnmounted(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <section class="feedback-detail">
    <button type="button" class="back-link" @click="router.push('/resident/feedbacks')">
      ← 返回我的反馈
    </button>

    <div v-if="loading" class="page-loading">加载中…</div>

    <template v-else-if="feedback">
      <!-- 反馈信息卡 -->
      <div class="info-card">
        <div class="info-head">
          <StatusTag
            :label="feedbackStatusLabels[feedback.status]"
            :type="statusTagType(feedback.status)"
          />
          <span class="info-category">{{ feedbackCategoryLabels[feedback.category] }}</span>
          <span class="info-number">{{ feedback.feedbackNumber }}</span>
        </div>
        <h1 class="info-title">{{ feedback.title }}</h1>
        <div class="info-content">{{ feedback.content }}</div>
        <div class="info-meta">
          <span>提交于 {{ formatDateTime(feedback.createdAt) }}</span>
          <span v-if="feedback.isAnonymous" class="meta-anonymous">匿名反馈</span>
        </div>

        <div v-if="attachments.length > 0" class="info-attachments">
          <span class="attachments-label">附件：</span>
          <a
            v-for="attachment in attachments"
            :key="attachment.id"
            :href="attachment.fileUrl"
            target="_blank"
            rel="noopener"
            class="attachment-link"
          >
            {{ attachment.fileName }}
          </a>
        </div>

        <div v-if="feedback.status === 'CLOSED' && feedback.closeReason" class="info-closed">
          <span class="closed-label">办结</span>{{ feedback.closeReason }}
        </div>
      </div>

      <!-- 会话区 -->
      <div class="chat-card">
        <div class="chat-head">
          <h2>沟通记录</h2>
          <span class="chat-hint">管理人员工作时段内回复</span>
        </div>

        <div ref="messageListRef" class="chat-messages">
          <div v-if="messages.length === 0" class="chat-empty">
            暂无沟通记录，工作人员受理后会在这里与您对话
          </div>
          <div
            v-for="message in messages"
            :key="message.id"
            class="chat-row"
            :class="message.senderType === 'RESIDENT' ? 'is-self' : 'is-other'"
          >
            <div class="chat-bubble">
              <div class="bubble-sender">{{ message.senderName }}</div>
              <div class="bubble-content">{{ message.content }}</div>
              <div class="bubble-time">{{ formatRelative(message.createdAt) }}</div>
            </div>
          </div>
        </div>

        <div class="chat-input">
          <template v-if="feedback.status !== 'CLOSED'">
            <el-input
              v-model="messageInput"
              type="textarea"
              :rows="2"
              resize="none"
              placeholder="输入内容，与工作人员沟通…"
              maxlength="500"
              @keydown.enter.exact.prevent="handleSend"
            />
            <el-button
              type="primary"
              round
              :loading="sending"
              :disabled="!messageInput.trim()"
              @click="handleSend"
            >
              发送
            </el-button>
          </template>
          <div v-else class="chat-closed-tip">
            该反馈已办结，如仍有问题可提交新的反馈
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.feedback-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.back-link {
  align-self: flex-start;
  border: none;
  background: none;
  padding: 0;
  color: var(--color-primary);
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.back-link:hover {
  opacity: 0.8;
}

.page-loading {
  padding: var(--spacing-xxl) 0;
  text-align: center;
  color: var(--color-text-secondary);
}

/* 反馈信息卡 */
.info-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
}

.info-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.info-category {
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
}

.info-number {
  margin-left: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  font-family: var(--font-family-mono);
}

.info-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.info-content {
  padding: var(--spacing-md);
  background-color: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.info-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.meta-anonymous {
  color: var(--color-text-secondary);
}

.info-attachments {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-xs);
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.attachments-label {
  color: var(--color-text-disabled);
}

.attachment-link {
  color: var(--color-primary);
  text-decoration: none;
}

.attachment-link:hover {
  text-decoration: underline;
}

.info-closed {
  margin-top: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-left: 3px solid var(--status-completed);
  background-color: rgba(16, 185, 129, 0.08);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.closed-label {
  font-weight: var(--font-weight-bold);
  color: var(--status-completed);
  margin-right: var(--spacing-sm);
}

/* 会话区 */
.chat-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid var(--color-border);
}

.chat-head h2 {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.chat-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.chat-messages {
  height: 360px;
  overflow-y: auto;
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  background-color: var(--color-bg);
}

.chat-empty {
  margin: auto;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.chat-row {
  display: flex;
}

.chat-row.is-self {
  justify-content: flex-end;
}

.chat-row.is-other {
  justify-content: flex-start;
}

.chat-bubble {
  max-width: 72%;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
}

.is-self .chat-bubble {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}

.is-other .chat-bubble {
  background-color: #fff;
}

.bubble-sender {
  font-size: var(--font-size-xs);
  margin-bottom: var(--spacing-xs);
  color: var(--color-text-secondary);
}

.is-self .bubble-sender {
  color: rgba(255, 255, 255, 0.8);
}

.bubble-content {
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble-time {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: right;
}

.is-self .bubble-time {
  color: rgba(255, 255, 255, 0.7);
}

.chat-input {
  display: flex;
  align-items: flex-end;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  border-top: 1px solid var(--color-border);
}

.chat-input .el-input {
  flex: 1;
}

.chat-closed-tip {
  width: 100%;
  text-align: center;
  padding: var(--spacing-sm) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}
</style>
