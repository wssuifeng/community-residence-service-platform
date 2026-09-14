<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import {
  cancelWorkOrder,
  confirmWorkOrder,
  getWorkOrder,
  getWorkOrderTimeline,
  listWorkOrderAttachments
} from '@/api/workorder'
import { getEvaluation, submitEvaluation } from '@/api/evaluation'
import type {
  IWorkOrder,
  IWorkOrderAttachment,
  IWorkOrderProcess,
  WorkOrderStatus
} from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import type { IEvaluation } from '@/types/modules/evaluation'
import { formatDateTime, formatRelative } from '@/utils/date'

/** 工单详情（UI设计.md §4.1.3）：信息卡片 + 处理时间线 + 状态流转操作 + 评价 */

const route = useRoute()
const router = useRouter()

const orderId = Number(route.params.id)

/* 工单状态 → StatusTag 语义色 */
const statusSemantic: Record<WorkOrderStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  TO_ASSIGN: 'pending',
  TO_CONFIRM: 'pending',
  ASSIGNED: 'processing',
  ACCEPTED: 'processing',
  IN_PROGRESS: 'processing',
  COMPLETED: 'completed',
  CLOSED: 'completed',
  REJECTED: 'rejected',
  CANCELLED: 'canceled'
}

const order = ref<IWorkOrder | null>(null)
const timeline = ref<IWorkOrderProcess[]>([])
const attachments = ref<IWorkOrderAttachment[]>([])
const loading = ref(false)
const actionLoading = ref(false)

/* 评价区：已评价则展示结果，未评价展示表单（仅已完成状态） */
const evaluation = ref<IEvaluation | null>(null)
const evaluationLoading = ref(false)
const evaluationForm = ref({
  rating: 0,
  content: '',
  tags: ''
})
const submittingEvaluation = ref(false)

const imageAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'IMAGE'))
const documentAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'DOCUMENT'))
/* 时间线按时间正序展示 */
const sortedTimeline = computed(() => [...timeline.value].sort((a, b) => a.createdAt.localeCompare(b.createdAt)))
/* 状态语义 → el-timeline-item 颜色 */
const timelineNodeType: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  pending: 'warning',
  processing: 'primary',
  completed: 'success',
  rejected: 'danger',
  canceled: 'info'
}
const canCancel = computed(() => order.value?.status === 'PENDING' || order.value?.status === 'TO_ASSIGN')
const canConfirm = computed(() => order.value?.status === 'TO_CONFIRM')
const canEvaluate = computed(() => order.value?.status === 'COMPLETED')

/* 左侧信息卡双态：「居民提交」（默认）/「处理进展」（服务人员上传内容） */
const infoTab = ref<'submit' | 'progress'>('submit')

/* 处理进展：时间线中服务人员上传的备注/说明（无则空态） */
const staffNotes = computed(() =>
  sortedTimeline.value.filter((node) => node.operatorType === 'STAFF' && node.content)
)

const evaluationCardRef = ref<HTMLElement | null>(null)

/* 底部操作条「评价」：滚动到评价区（仅已完成可用） */
function scrollToEvaluation(): void {
  evaluationCardRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/* ---------- 五步进度条：仅覆盖主流程；已驳回/已取消走状态横幅 ---------- */

const STEP_LABELS = ['提交', '受理', '派单', '处理中', '待确认']

/* 10 状态 → 主流程步序（-1 为异常终态，≥5 为主流程全部完成） */
const statusToStep: Record<WorkOrderStatus, number> = {
  PENDING: 0,
  TO_ASSIGN: 1,
  ASSIGNED: 2,
  ACCEPTED: 2,
  IN_PROGRESS: 3,
  TO_CONFIRM: 4,
  COMPLETED: 5,
  CLOSED: 5,
  REJECTED: -1,
  CANCELLED: -1
}

const isTerminalAbnormal = computed(
  () => order.value?.status === 'REJECTED' || order.value?.status === 'CANCELLED'
)

const currentStep = computed(() => {
  const step = statusToStep[order.value?.status ?? 'PENDING']
  return Math.max(0, Math.min(step, 4))
})

const allStepsDone = computed(() => (order.value ? statusToStep[order.value.status] >= 5 : false))

/* 各步时间取时间线中首次到达该步的记录时间（MM-dd HH:mm） */
const steps = computed(() => {
  return STEP_LABELS.map((label, index) => {
    const node = sortedTimeline.value.find((item) => statusToStep[item.newStatus] === index)
    const time = node ? formatDateTime(node.createdAt).slice(5) : ''
    return { label, time }
  })
})

async function fetchDetail(): Promise<void> {
  loading.value = true
  try {
    const [detail, processes, files] = await Promise.all([
      getWorkOrder(orderId),
      getWorkOrderTimeline(orderId),
      listWorkOrderAttachments(orderId)
    ])
    order.value = detail
    timeline.value = processes
    attachments.value = files
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单详情加载失败')
  } finally {
    loading.value = false
  }
}

/* 已完成工单尝试拉取既有评价（不可重复评价） */
async function fetchEvaluation(): Promise<void> {
  evaluationLoading.value = true
  try {
    evaluation.value = await getEvaluation(orderId)
  } catch {
    evaluation.value = null
  } finally {
    evaluationLoading.value = false
  }
}

async function handleCancel(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('取消后无法恢复，请填写取消原因', '取消工单', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '取消原因（必填）',
      inputValidator: (input: string) => (input.trim() ? true : '请填写取消原因')
    })
    actionLoading.value = true
    await cancelWorkOrder(orderId, { remark: value.trim() })
    ElMessage.success('工单已取消')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '取消失败')
  } finally {
    actionLoading.value = false
  }
}

/* 确认完成：工单流转至已完成，随后可在页面下方提交评价 */
async function handleConfirm(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认服务已完成并符合预期吗？', '确认完成', {
      confirmButtonText: '确认完成',
      cancelButtonText: '再等等'
    })
    actionLoading.value = true
    await confirmWorkOrder(orderId)
    ElMessage.success('已确认完成，欢迎对本次服务做出评价')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

/* 不满意：仍需确认工单完结，原因随确认备注留痕，评价低于 4 星将自动触发跟进 */
async function handleUnsatisfied(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请描述不满意的原因，我们将督促服务人员跟进', '不满意反馈', {
      confirmButtonText: '提交反馈',
      cancelButtonText: '取消',
      inputPlaceholder: '不满意原因（必填）',
      inputValidator: (input: string) => (input.trim() ? true : '请填写不满意原因')
    })
    actionLoading.value = true
    await confirmWorkOrder(orderId, { remark: `居民反馈不满意：${value.trim()}` })
    ElMessage.success('反馈已记录，请在下方评价中打分，低分评价将自动进入跟进')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

async function handleSubmitEvaluation(): Promise<void> {
  const form = evaluationForm.value
  if (!form.rating) {
    ElMessage.warning('请先完成总体评分')
    return
  }
  submittingEvaluation.value = true
  try {
    await submitEvaluation(orderId, {
      rating: form.rating as IEvaluation['rating'],
      content: form.content.trim() || undefined,
      tags: form.tags.trim() || undefined,
      /* rating ≥ 4 判定满意（后端 CreateEvaluationDTO 口径） */
      isSatisfied: form.rating >= 4
    })
    ElMessage.success('评价提交成功，感谢您的反馈')
    fetchEvaluation()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评价提交失败')
  } finally {
    submittingEvaluation.value = false
  }
}

onMounted(() => {
  fetchDetail().then(() => {
    if (canEvaluate.value) fetchEvaluation()
  })
})
</script>

<template>
  <section v-loading="loading" class="work-order-detail">
    <header class="page-header">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/resident/work-orders' }">我的工单</el-breadcrumb-item>
        <el-breadcrumb-item>工单详情</el-breadcrumb-item>
      </el-breadcrumb>
      <el-button text @click="router.push('/resident/work-orders')">返回列表</el-button>
    </header>

    <template v-if="order">
      <!-- 异常终态：进度条不适用，改状态横幅 -->
      <el-alert
        v-if="isTerminalAbnormal"
        :title="`工单${workOrderStatusLabels[order.status]}`"
        :type="order.status === 'REJECTED' ? 'error' : 'info'"
        :closable="false"
        show-icon
        class="terminal-banner"
      />

      <!-- 五步状态进度条：完成步绿勾、当前步蓝色高亮、未来步灰 -->
      <article v-else class="steps-card">
        <div class="steps">
          <template v-for="(step, index) in steps" :key="step.label">
            <div
              class="step"
              :class="{ done: allStepsDone || index < currentStep, current: !allStepsDone && index === currentStep }"
            >
              <span class="step-dot">
                <svg v-if="allStepsDone || index < currentStep" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                <template v-else>{{ index + 1 }}</template>
              </span>
              <span class="step-label">{{ step.label }}</span>
              <span class="step-time">{{ step.time || '—' }}</span>
            </div>
            <span
              v-if="index < steps.length - 1"
              class="step-line"
              :class="{ done: allStepsDone || index < currentStep }"
            ></span>
          </template>
        </div>
      </article>

      <div class="detail-grid">
        <!-- 左：工单信息卡（双态切换：居民提交 / 处理进展） -->
        <article class="info-card">
          <div class="info-card-head">
            <div>
              <span class="order-number">{{ order.orderNo }}</span>
              <h1 class="order-title">{{ order.title }}</h1>
            </div>
            <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
          </div>

          <div class="info-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              class="info-tab"
              :class="{ active: infoTab === 'submit' }"
              @click="infoTab = 'submit'"
            >
              居民提交
            </button>
            <button
              type="button"
              role="tab"
              class="info-tab"
              :class="{ active: infoTab === 'progress' }"
              @click="infoTab = 'progress'"
            >
              处理进展
            </button>
          </div>

          <template v-if="infoTab === 'submit'">
            <div class="info-description">
              <dt>问题描述</dt>
              <dd>{{ order.content }}</dd>
            </div>

            <dl class="info-grid">
              <div class="info-item">
                <dt>服务类别</dt>
                <dd>{{ order.categoryName }}</dd>
              </div>
              <div class="info-item">
                <dt>紧急程度</dt>
                <dd>{{ workOrderPriorityLabels[order.priority] }}</dd>
              </div>
              <div class="info-item">
                <dt>提交时间</dt>
                <dd>{{ formatDateTime(order.createdAt) }}</dd>
              </div>
              <div class="info-item">
                <dt>联系电话</dt>
                <dd>{{ order.contactPhone }}</dd>
              </div>
              <div class="info-item">
                <dt>服务地址</dt>
                <dd>{{ order.address ?? '—' }}</dd>
              </div>
            </dl>

            <div v-if="imageAttachments.length > 0" class="attachment-block">
              <h3 class="block-title">附件照片</h3>
              <!-- 固定位置横向滚动，hover 显示滚动条；点击放大查看 -->
              <div class="image-gallery">
                <el-image
                  v-for="file in imageAttachments"
                  :key="file.id"
                  class="gallery-item"
                  :src="file.fileUrl"
                  :alt="file.fileName"
                  fit="cover"
                  :preview-src-list="imageAttachments.map((item) => item.fileUrl)"
                  :initial-index="imageAttachments.indexOf(file)"
                  preview-teleported
                />
              </div>
            </div>

            <div v-if="documentAttachments.length > 0" class="attachment-block">
              <h3 class="block-title">附件</h3>
              <ul class="file-list">
                <li v-for="file in documentAttachments" :key="file.id">
                  <a :href="file.fileUrl" target="_blank" rel="noopener">{{ file.fileName }}</a>
                </li>
              </ul>
            </div>
          </template>

          <!-- 处理进展：服务人员上传的处理备注/完成说明 -->
          <template v-else>
            <ul v-if="staffNotes.length > 0" class="progress-list">
              <li v-for="(node, index) in staffNotes" :key="index" class="progress-item">
                <p class="progress-content">{{ node.content }}</p>
                <p class="progress-meta">{{ node.operatorName }} · {{ formatDateTime(node.createdAt) }}</p>
              </li>
            </ul>
            <p v-else class="progress-empty">暂无处理进展</p>
          </template>
        </article>

        <div class="side-col">
          <!-- 右上：服务人员卡 -->
          <article class="assignee-card">
            <h3 class="block-title">服务人员</h3>
            <div v-if="order.assigneeName" class="assignee-row">
              <span class="assignee-avatar">{{ order.assigneeName.slice(0, 1) }}</span>
              <div class="assignee-info">
                <p class="assignee-name">{{ order.assigneeName }}</p>
                <p class="assignee-sub">{{ order.categoryName }}</p>
              </div>
            </div>
            <p v-else class="assignee-empty">待派单</p>
          </article>

          <!-- 右下：处理记录时间线 -->
          <article class="timeline-card">
            <h3 class="block-title">处理记录</h3>
            <el-timeline v-if="sortedTimeline.length > 0" class="timeline">
              <el-timeline-item
                v-for="(node, index) in sortedTimeline"
                :key="index"
                :timestamp="formatDateTime(node.createdAt)"
                :type="timelineNodeType[statusSemantic[node.newStatus]]"
              >
                <div class="timeline-head">
                  <span class="timeline-status">{{ workOrderStatusLabels[node.newStatus] }}</span>
                  <span class="timeline-operator">{{ node.operatorName }}</span>
                </div>
                <p v-if="node.content" class="timeline-remark">{{ node.content }}</p>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无处理记录" :image-size="60" />
          </article>
        </div>
      </div>

      <!-- 评价区：已完成且未评价时展示表单 -->
      <article v-if="canEvaluate" ref="evaluationCardRef" class="evaluation-card">
        <h3 class="block-title">服务评价</h3>

        <div v-loading="evaluationLoading">
          <template v-if="evaluation">
            <div class="evaluation-summary">
              <p class="evaluation-meta">
                评价时间：{{ formatRelative(evaluation.createdAt) }}
                <el-tag size="small" :type="evaluation.isSatisfied ? 'success' : 'warning'" class="anonymous-tag">
                  {{ evaluation.isSatisfied ? '满意' : '不满意' }}
                </el-tag>
              </p>
              <div class="rate-row">
                <span class="rate-label">总体评分</span>
                <el-rate :model-value="evaluation.rating" disabled />
              </div>
              <p v-if="evaluation.tags" class="evaluation-tags">{{ evaluation.tags }}</p>
              <p v-if="evaluation.content" class="evaluation-content">{{ evaluation.content }}</p>
            </div>
          </template>

          <el-form v-else class="evaluation-form" label-position="top" @submit.prevent>
            <div class="rate-row">
              <span class="rate-label">总体评分</span>
              <el-rate v-model="evaluationForm.rating" show-score />
            </div>
            <el-form-item label="评价标签（可选，逗号分隔）">
              <el-input
                v-model="evaluationForm.tags"
                maxlength="100"
                placeholder="如：态度好,响应快,专业"
              />
            </el-form-item>
            <el-form-item label="评价内容（可选）">
              <el-input
                v-model="evaluationForm.content"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="说说您对本次服务的感受"
              />
            </el-form-item>
            <div class="evaluation-footer">
              <el-button type="primary" :loading="submittingEvaluation" @click="handleSubmitEvaluation">
                提交评价
              </el-button>
            </div>
            <p class="evaluation-hint">评分低于 4 星将判定为不满意，自动生成跟进记录，由管理员督促处理</p>
          </el-form>
        </div>
      </article>

      <!-- 底部操作条：可用性按状态机置灰，逻辑不变 -->
      <article class="action-bar">
        <div class="action-bar-buttons">
          <el-button text type="danger" :disabled="!canCancel" :loading="actionLoading" @click="handleCancel">
            取消工单
          </el-button>
          <el-button type="danger" plain :disabled="!canConfirm" :loading="actionLoading" @click="handleUnsatisfied">
            不满意
          </el-button>
          <el-button type="primary" :disabled="!canConfirm" :loading="actionLoading" @click="handleConfirm">
            确认完成
          </el-button>
          <el-button plain :disabled="!canEvaluate" @click="scrollToEvaluation">评价</el-button>
        </div>
      </article>
    </template>
  </section>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

/* 双态切换：分段控件 */
.info-tabs {
  display: inline-flex;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs);
  margin-bottom: var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
}

.info-tab {
  padding: var(--spacing-xs) var(--spacing-md);
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.info-tab.active {
  background: #fff;
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
  box-shadow: var(--shadow-sm);
}

/* 处理进展列表 */
.progress-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.progress-item {
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg);
}

.progress-content {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
}

.progress-meta {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.progress-empty {
  margin: 0;
  padding: var(--spacing-xxl) 0;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

/* 底部操作条：按钮右对齐，禁用态置灰 */
.action-bar {
  display: flex;
  justify-content: flex-end;
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md) var(--spacing-lg);
}

.action-bar-buttons {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.info-card,
.timeline-card,
.assignee-card,
.evaluation-card,
.steps-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-md);
}

.terminal-banner {
  margin-bottom: var(--spacing-md);
}

/* 五步进度条 */
.steps {
  display: flex;
  align-items: flex-start;
}

.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
  width: 72px;
  text-align: center;
}

.step-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-circle);
  background: var(--color-bg-hover);
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.step-dot svg {
  width: 16px;
  height: 16px;
}

.step.done .step-dot {
  background: var(--color-success);
  color: #fff;
}

.step.current .step-dot {
  background: var(--color-primary);
  color: #fff;
}

.step-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.step.done .step-label,
.step.current .step-label {
  color: var(--color-text-primary);
}

.step-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.step-line {
  flex: 1;
  height: 2px;
  margin-top: 15px;
  background: var(--color-border);
}

.step-line.done {
  background: var(--color-success);
}

/* 左信息卡 + 右栏（服务人员 / 处理记录）：stretch 等高，处理记录内部竖向滚动 */
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-md);
  align-items: stretch;
}

.detail-grid .info-card,
.detail-grid .timeline-card,
.detail-grid .assignee-card {
  margin-bottom: 0;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* 处理记录卡：占满右栏剩余高度，记录多时容器内竖向滚动（hover 显示滚动条） */
.timeline-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  max-height: 520px;
  overflow: hidden;
}

.timeline-card .timeline {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.timeline-card .timeline:hover {
  scrollbar-color: var(--color-text-disabled) transparent;
}

.timeline-card .timeline::-webkit-scrollbar {
  width: 4px;
}

.timeline-card .timeline::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: var(--radius-pill);
}

.timeline-card .timeline:hover::-webkit-scrollbar-thumb {
  background: var(--color-text-disabled);
}

.assignee-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.assignee-avatar {
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-circle);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  display: flex;
  align-items: center;
  justify-content: center;
}

.assignee-name {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.assignee-sub {
  margin: 2px 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.assignee-empty {
  margin: 0;
  padding: var(--spacing-md) 0;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.info-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.order-number {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.order-title {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--spacing-md);
  margin: 0 0 var(--spacing-md);
}

.info-item dt,
.info-description dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-xs);
}

.info-item dd,
.info-description dd {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.info-description {
  margin: 0 0 var(--spacing-md);
}

.info-description dd {
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
}

.reason-alert {
  margin-bottom: var(--spacing-md);
}

.block-title {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.attachment-block {
  margin-top: var(--spacing-md);
}

/* 附件照片：固定位置横向滚动，hover 显示滚动条 */
.image-gallery {
  display: flex;
  gap: var(--spacing-sm);
  overflow-x: auto;
  padding-bottom: var(--spacing-xs);
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.image-gallery:hover {
  scrollbar-color: var(--color-text-disabled) transparent;
}

.image-gallery::-webkit-scrollbar {
  height: 4px;
}

.image-gallery::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: var(--radius-pill);
}

.image-gallery:hover::-webkit-scrollbar-thumb {
  background: var(--color-text-disabled);
}

.gallery-item {
  flex-shrink: 0;
  width: 96px;
  height: 96px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  cursor: pointer;
}

.file-list {
  margin: 0;
  padding-left: var(--spacing-md);
}

.file-list a {
  color: var(--color-primary);
}

.timeline {
  padding-left: var(--spacing-xs);
}

.timeline-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.timeline-status {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.timeline-operator {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.timeline-remark {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.action-buttons {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.rate-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-sm);
}

.rate-label {
  width: 80px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.evaluation-meta {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  margin: 0 0 var(--spacing-md);
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.anonymous-tag {
  margin-left: var(--spacing-xs);
}

.evaluation-content {
  margin: var(--spacing-md) 0 0;
  padding: var(--spacing-md);
  background-color: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-relaxed);
}

.evaluation-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.evaluation-hint {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 响应式：窄屏降单栏 */
@media (max-width: 991px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
