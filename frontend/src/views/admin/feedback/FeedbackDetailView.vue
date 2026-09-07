<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getFeedbackDetail,
  listFeedbackMessages,
  sendFeedbackMessage,
  listFeedbackAttachments,
  closeFeedback
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
 * 反馈详情 + 会话（管理端视角）：管理员消息靠右、居民靠左；
 * 办结操作带原因确认（closeFeedback）；5 秒轮询居民新消息，卸载清理定时器
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
const closing = ref(false)

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
    router.replace('/admin/feedbacks')
  }
}

async function loadMessages(): Promise<void> {
  try {
    const result = await listFeedbackMessages(feedbackId, {
      page: 1,
      size: 100,
      sortOrder: 'ASC'
    })
    messages.value = result.records
    await nextTick()
    scrollToBottom()
  } catch {
    /* 轮询失败静默跳过 */
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
    /* 发送后刷新：首条回复使 OPEN→IN_PROGRESS */
    await Promise.all([loadMessages(), loadDetail()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败')
  } finally {
    sending.value = false
  }
}

/** 办结：需填写办结原因并二次确认 */
async function handleClose(): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '办结后居民将无法继续回复，请填写办结原因。',
      '办结反馈',
      {
        type: 'warning',
        confirmButtonText: '确认办结',
        cancelButtonText: '取消',
        inputValidator: (value: string) =>
          value.trim().length > 0 ? true : '办结原因不能为空'
      }
    )
    reason = result.value.trim()
  } catch {
    return
  }
  closing.value = true
  try {
    await closeFeedback(feedbackId, { reason })
    ElMessage.success('反馈已办结')
    await loadDetail()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '办结失败')
  } finally {
    closing.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadDetail(), loadMessages(), loadAttachments()])
  loading.value = false
  /* 5 秒轮询居民新消息与状态变化 */
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
  <section class="feedback-detail-admin">
    <header class="page-head">
      <h1>反馈详情</h1>
      <div class="head-actions">
        <el-button
          v-if="feedback && feedback.status !== 'CLOSED'"
          type="warning"
          :loading="closing"
          @click="handleClose"
        >
          办结
        </el-button>
        <el-button text @click="router.push('/admin/feedbacks')">← 返回列表</el-button>
      </div>
    </header>

    <div v-if="loading" class="page-loading">加载中…</div>

    <template v-else-if="feedback">
      <!-- 反馈信息 -->
      <el-descriptions :column="3" border class="info-descriptions">
        <el-descriptions-item label="反馈编号">{{ feedback.feedbackNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag
            :label="feedbackStatusLabels[feedback.status]"
            :type="statusTagType(feedback.status)"
          />
        </el-descriptions-item>
        <el-descriptions-item label="类别">
          {{ feedbackCategoryLabels[feedback.category] }}
        </el-descriptions-item>
        <el-descriptions-item label="居民">
          {{ feedback.isAnonymous ? '匿名' : feedback.residentName }}
        </el-descriptions-item>
        <el-descriptions-item label="社区">{{ feedback.communityName }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">
          {{ feedback.contactPhone ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">
          {{ formatDateTime(feedback.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="受理人">
          {{ feedback.handlerName ?? '未受理' }}
        </el-descriptions-item>
        <el-descriptions-item label="办结时间">
          {{ formatDateTime(feedback.closedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="标题" :span="3">{{ feedback.title }}</el-descriptions-item>
        <el-descriptions-item label="内容" :span="3">
          <span class="info-content">{{ feedback.content }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="attachments.length > 0" label="附件" :span="3">
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
        </el-descriptions-item>
        <el-descriptions-item
          v-if="feedback.status === 'CLOSED' && feedback.closeReason"
          label="办结原因"
          :span="3"
        >
          {{ feedback.closeReason }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 会话区：管理员视角，管理员消息靠右 -->
      <div class="chat-card">
        <div class="chat-head">
          <h2>沟通记录</h2>
          <span class="chat-hint">回复后反馈进入「会话中」，办结前请确认居民问题已解决</span>
        </div>

        <div ref="messageListRef" class="chat-messages">
          <div v-if="messages.length === 0" class="chat-empty">
            暂无沟通记录，回复首条消息即完成受理（状态流转为会话中）
          </div>
          <div
            v-for="message in messages"
            :key="message.id"
            class="chat-row"
            :class="message.senderType === 'ADMIN' ? 'is-self' : 'is-other'"
          >
            <div class="chat-bubble">
              <div class="bubble-sender">
                {{ message.senderName }}（{{ message.senderType === 'ADMIN' ? '管理员' : '居民' }}）
              </div>
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
              placeholder="输入回复内容…"
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
          <div v-else class="chat-closed-tip">该反馈已办结，会话已关闭</div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.feedback-detail-admin {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.head-actions {
  display: flex;
  gap: var(--spacing-sm);
  align-items: center;
}

.page-loading {
  padding: var(--spacing-xxl) 0;
  text-align: center;
  color: var(--color-text-secondary);
}

.info-descriptions {
  background-color: #fff;
}

.info-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.attachment-link {
  color: var(--color-primary);
  text-decoration: none;
  margin-right: var(--spacing-md);
}

.attachment-link:hover {
  text-decoration: underline;
}

/* 会话区 */
.chat-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
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
