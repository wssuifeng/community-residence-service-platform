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
import { workOrderStatusLabels, workOrderUrgencyLabels } from '@/types/modules/workorder'
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
  serviceAttitude: 0,
  responseSpeed: 0,
  solutionQuality: 0,
  content: '',
  isAnonymous: false
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
    await cancelWorkOrder(orderId, { reason: value.trim() })
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
  if (!form.rating || !form.serviceAttitude || !form.responseSpeed || !form.solutionQuality) {
    ElMessage.warning('请完成全部 4 项评分')
    return
  }
  submittingEvaluation.value = true
  try {
    await submitEvaluation(orderId, {
      rating: form.rating as IEvaluation['rating'],
      serviceAttitude: form.serviceAttitude as IEvaluation['serviceAttitude'],
      responseSpeed: form.responseSpeed as IEvaluation['responseSpeed'],
      solutionQuality: form.solutionQuality as IEvaluation['solutionQuality'],
      content: form.content.trim() || undefined,
      isAnonymous: form.isAnonymous
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
      <article class="info-card">
        <div class="info-card-head">
          <div>
            <span class="order-number">{{ order.orderNumber }}</span>
            <h1 class="order-title">{{ order.title }}</h1>
          </div>
          <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
        </div>

        <dl class="info-grid">
          <div class="info-item">
            <dt>服务类别</dt>
            <dd>{{ order.categoryName }}</dd>
          </div>
          <div class="info-item">
            <dt>紧急程度</dt>
            <dd>{{ workOrderUrgencyLabels[order.urgency] }}</dd>
          </div>
          <div class="info-item">
            <dt>提交时间</dt>
            <dd>{{ formatDateTime(order.createdAt) }}</dd>
          </div>
          <div class="info-item">
            <dt>预约上门</dt>
            <dd>{{ order.appointmentTime ? formatDateTime(order.appointmentTime) : '未预约' }}</dd>
          </div>
          <div class="info-item">
            <dt>联系电话</dt>
            <dd>{{ order.contactPhone }}</dd>
          </div>
          <div class="info-item">
            <dt>当前处理人</dt>
            <dd>{{ order.assigneeName ?? '尚未指派' }}</dd>
          </div>
        </dl>

        <div class="info-description">
          <dt>问题描述</dt>
          <dd>{{ order.description }}</dd>
        </div>

        <el-alert
          v-if="order.rejectReason"
          class="reason-alert"
          type="error"
          :title="`驳回原因：${order.rejectReason}`"
          :closable="false"
        />
        <el-alert
          v-else-if="order.cancelReason"
          class="reason-alert"
          type="info"
          :title="`取消原因：${order.cancelReason}`"
          :closable="false"
        />

        <div v-if="imageAttachments.length > 0" class="attachment-block">
          <h3 class="block-title">现场照片</h3>
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
      </article>

      <article class="timeline-card">
        <h3 class="block-title">处理进度</h3>
        <el-timeline v-if="sortedTimeline.length > 0" class="timeline">
          <el-timeline-item
            v-for="(node, index) in sortedTimeline"
            :key="index"
            :timestamp="formatDateTime(node.createdAt)"
            :type="timelineNodeType[statusSemantic[node.status]]"
          >
            <div class="timeline-head">
              <span class="timeline-status">{{ workOrderStatusLabels[node.status] }}</span>
              <span class="timeline-operator">{{ node.operatorName }}</span>
            </div>
            <p v-if="node.remark" class="timeline-remark">{{ node.remark }}</p>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无处理记录" :image-size="60" />
      </article>

      <!-- 操作区：按状态机仅在合法状态下显示 -->
      <article v-if="canCancel || canConfirm" class="action-card">
        <h3 class="block-title">待办操作</h3>
        <div class="action-buttons">
          <template v-if="canConfirm">
            <el-button type="success" size="large" :loading="actionLoading" @click="handleConfirm">
              确认完成
            </el-button>
            <el-button type="danger" plain size="large" :loading="actionLoading" @click="handleUnsatisfied">
              不满意
            </el-button>
          </template>
          <el-button v-if="canCancel" type="danger" plain size="large" :loading="actionLoading" @click="handleCancel">
            取消工单
          </el-button>
        </div>
      </article>

      <!-- 评价区：已完成且未评价时展示表单 -->
      <article v-if="canEvaluate" class="evaluation-card">
        <h3 class="block-title">服务评价</h3>

        <div v-loading="evaluationLoading">
          <template v-if="evaluation">
            <div class="evaluation-summary">
              <p class="evaluation-meta">
                评价时间：{{ formatRelative(evaluation.createdAt) }}
                <el-tag v-if="evaluation.isAnonymous" size="small" type="info" class="anonymous-tag">匿名</el-tag>
              </p>
              <div class="rate-row">
                <span class="rate-label">总体评分</span>
                <el-rate :model-value="evaluation.rating" disabled />
              </div>
              <div class="rate-row">
                <span class="rate-label">服务态度</span>
                <el-rate :model-value="evaluation.serviceAttitude" disabled />
              </div>
              <div class="rate-row">
                <span class="rate-label">响应速度</span>
                <el-rate :model-value="evaluation.responseSpeed" disabled />
              </div>
              <div class="rate-row">
                <span class="rate-label">解决方案</span>
                <el-rate :model-value="evaluation.solutionQuality" disabled />
              </div>
              <p v-if="evaluation.content" class="evaluation-content">{{ evaluation.content }}</p>
            </div>
          </template>

          <el-form v-else class="evaluation-form" label-position="top" @submit.prevent>
            <div class="rate-row">
              <span class="rate-label">总体评分</span>
              <el-rate v-model="evaluationForm.rating" show-score />
            </div>
            <div class="rate-row">
              <span class="rate-label">服务态度</span>
              <el-rate v-model="evaluationForm.serviceAttitude" />
            </div>
            <div class="rate-row">
              <span class="rate-label">响应速度</span>
              <el-rate v-model="evaluationForm.responseSpeed" />
            </div>
            <div class="rate-row">
              <span class="rate-label">解决方案</span>
              <el-rate v-model="evaluationForm.solutionQuality" />
            </div>
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
              <el-switch v-model="evaluationForm.isAnonymous" active-text="匿名评价" />
              <el-button type="primary" :loading="submittingEvaluation" @click="handleSubmitEvaluation">
                提交评价
              </el-button>
            </div>
            <p class="evaluation-hint">评分低于 4 星将自动生成跟进记录，由管理员督促处理</p>
          </el-form>
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

.info-card,
.timeline-card,
.action-card,
.evaluation-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-md);
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

.image-gallery {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

.gallery-item {
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
</style>
