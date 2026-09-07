<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import ImageUploader from '@/components/common/ImageUploader.vue'
import {
  acceptWorkOrder,
  completeWorkOrder,
  getWorkOrder,
  getWorkOrderTimeline,
  listWorkOrderAttachments,
  processWorkOrder,
  uploadWorkOrderAttachment
} from '@/api/workorder'
import type {
  IWorkOrder,
  IWorkOrderAttachment,
  IWorkOrderProcess,
  WorkOrderStatus
} from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderUrgencyLabels } from '@/types/modules/workorder'
import { formatDateTime } from '@/utils/date'

/** 工单详情（UI设计.md §4.2.2）：信息卡片 + 时间线 + 接单/开始处理/提交处理结果 */

const route = useRoute()
const router = useRouter()

const orderId = Number(route.params.id)

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

/* 状态语义 → el-timeline-item 颜色 */
const timelineNodeType: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  pending: 'warning',
  processing: 'primary',
  completed: 'success',
  rejected: 'danger',
  canceled: 'info'
}

const order = ref<IWorkOrder | null>(null)
const timeline = ref<IWorkOrderProcess[]>([])
const attachments = ref<IWorkOrderAttachment[]>([])
const loading = ref(false)
const actionLoading = ref(false)

/* 提交处理结果表单（处理中状态显示） */
const resultForm = ref({
  solution: '',
  remark: '',
  images: [] as string[]
})
const submittingResult = ref(false)

const imageAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'IMAGE'))
const documentAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'DOCUMENT'))
const sortedTimeline = computed(() => [...timeline.value].sort((a, b) => a.createdAt.localeCompare(b.createdAt)))

const canAccept = computed(() => order.value?.status === 'ASSIGNED')
const canProcess = computed(() => order.value?.status === 'ACCEPTED')
const canComplete = computed(() => order.value?.status === 'IN_PROGRESS')

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

/* 接单：已派单状态下确认后流转至已接单（状态机 9.4.2.6） */
async function handleAccept(): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认接下工单 ${order.value?.orderNumber ?? ''} 吗？`, '接单', {
      confirmButtonText: '确认接单',
      cancelButtonText: '取消'
    })
    actionLoading.value = true
    await acceptWorkOrder(orderId)
    ElMessage.success('接单成功，请尽快开始处理')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '接单失败')
  } finally {
    actionLoading.value = false
  }
}

/* 开始处理：已接单状态下流转至处理中（状态机 9.4.2.7） */
async function handleProcess(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认开始处理该工单吗？', '开始处理', {
      confirmButtonText: '开始处理',
      cancelButtonText: '取消'
    })
    actionLoading.value = true
    await processWorkOrder(orderId)
    ElMessage.success('已开始处理')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

/* ImageUploader 产出通用上传 URL；工单附件须回捞转存到工单附件接口 */
async function attachImages(urls: string[]): Promise<number> {
  let failed = 0
  for (const url of urls) {
    try {
      const blob = await (await fetch(url)).blob()
      const fileName = url.split('/').pop() ?? 'result.png'
      await uploadWorkOrderAttachment(orderId, new File([blob], fileName, { type: blob.type || 'image/png' }))
    } catch {
      failed += 1
    }
  }
  return failed
}

/* 提交处理结果：填写解决方案后流转至待确认（状态机 9.4.2.8） */
async function handleSubmitResult(): Promise<void> {
  if (!resultForm.value.solution.trim()) {
    ElMessage.warning('请填写处理结果说明')
    return
  }
  submittingResult.value = true
  try {
    await completeWorkOrder(orderId, {
      solution: resultForm.value.solution.trim(),
      remark: resultForm.value.remark.trim() || undefined
    })
    const failed = await attachImages(resultForm.value.images)
    if (failed > 0) ElMessage.warning(`${failed} 张结果图片关联失败`)
    ElMessage.success('处理结果已提交，等待居民确认')
    resultForm.value = { solution: '', remark: '', images: [] }
    fetchDetail()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交失败')
  } finally {
    submittingResult.value = false
  }
}

onMounted(fetchDetail)
</script>

<template>
  <section v-loading="loading" class="staff-order-detail">
    <header class="page-header">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/staff/work-orders' }">工单列表</el-breadcrumb-item>
        <el-breadcrumb-item>工单详情</el-breadcrumb-item>
      </el-breadcrumb>
      <el-button text @click="router.push('/staff/work-orders')">返回列表</el-button>
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
            <dt>提交人</dt>
            <dd>{{ order.residentName }}</dd>
          </div>
          <div class="info-item">
            <dt>联系电话</dt>
            <dd>{{ order.contactPhone }}</dd>
          </div>
          <div class="info-item">
            <dt>提交时间</dt>
            <dd>{{ formatDateTime(order.createdAt) }}</dd>
          </div>
          <div class="info-item">
            <dt>预约上门</dt>
            <dd>{{ order.appointmentTime ? formatDateTime(order.appointmentTime) : '未预约' }}</dd>
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
        <h3 class="block-title">处理时间线</h3>
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
      <article v-if="canAccept || canProcess" class="action-card">
        <h3 class="block-title">待办操作</h3>
        <div class="action-buttons">
          <el-button v-if="canAccept" type="primary" size="large" :loading="actionLoading" @click="handleAccept">
            接单
          </el-button>
          <el-button v-if="canProcess" type="primary" size="large" :loading="actionLoading" @click="handleProcess">
            开始处理
          </el-button>
        </div>
      </article>

      <article v-if="canComplete" class="result-card">
        <h3 class="block-title">提交处理结果</h3>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="处理结果说明" required>
            <el-input
              v-model="resultForm.solution"
              type="textarea"
              :rows="4"
              maxlength="1000"
              show-word-limit
              placeholder="请填写问题原因与解决方式，居民确认后工单完结"
            />
          </el-form-item>
          <el-form-item label="备注（可选）">
            <el-input v-model="resultForm.remark" type="textarea" :rows="2" maxlength="200" placeholder="补充说明，如遗留问题或建议" />
          </el-form-item>
          <el-form-item label="处理后照片（可选，最多 6 张）">
            <ImageUploader v-model="resultForm.images" :limit="6" />
          </el-form-item>
          <div class="result-actions">
            <el-button type="primary" size="large" :loading="submittingResult" @click="handleSubmitResult">
              提交处理结果
            </el-button>
          </div>
        </el-form>
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
.result-card {
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
}

.result-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
