<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import {
  assignWorkOrder,
  closeWorkOrder,
  getWorkOrder,
  getWorkOrderTimeline,
  listWorkOrderAttachments,
  rejectWorkOrder
} from '@/api/workorder'
import { getSysUserList } from '@/api/sysuser'
import type {
  IWorkOrder,
  IWorkOrderAttachment,
  IWorkOrderProcess,
  WorkOrderStatus
} from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import type { ISysUser } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'

/** 工单详情（UI设计.md §4.3.5）：全信息 + 处理时间线 + 派单/驳回/关闭操作 */

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

/* 派单对话框 */
const assignDialogVisible = ref(false)
const staffList = ref<ISysUser[]>([])
const staffLoading = ref(false)
const assignForm = ref({ assigneeId: null as number | null, remark: '' })
const assigning = ref(false)

const imageAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'IMAGE'))
const documentAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'DOCUMENT'))
const sortedTimeline = computed(() => [...timeline.value].sort((a, b) => a.createdAt.localeCompare(b.createdAt)))

/* 状态机：待受理/待派单可派单与驳回；已完成可关闭 */
const canAssign = computed(() => order.value?.status === 'PENDING' || order.value?.status === 'TO_ASSIGN')
const canReject = computed(() => order.value?.status === 'PENDING' || order.value?.status === 'TO_ASSIGN')
const canClose = computed(() => order.value?.status === 'COMPLETED')

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

async function openAssignDialog(): Promise<void> {
  assignForm.value = { assigneeId: null, remark: '' }
  assignDialogVisible.value = true

  staffLoading.value = true
  try {
    const result = await getSysUserList({ role: 'STAFF', status: 'ACTIVE', page: 1, size: 100 })
    staffList.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '服务人员列表加载失败')
  } finally {
    staffLoading.value = false
  }
}

async function handleAssign(): Promise<void> {
  if (!assignForm.value.assigneeId) {
    ElMessage.warning('请选择服务人员')
    return
  }
  assigning.value = true
  try {
    await assignWorkOrder(orderId, {
      assigneeId: assignForm.value.assigneeId,
      remark: assignForm.value.remark.trim() || undefined
    })
    ElMessage.success('派单成功')
    assignDialogVisible.value = false
    fetchDetail()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '派单失败')
  } finally {
    assigning.value = false
  }
}

/* 驳回：仅待受理/待派单状态，须填写理由（状态机 9.4.2.11） */
async function handleReject(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('驳回后工单将终止流转，请填写驳回理由', '驳回工单', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
      inputPlaceholder: '驳回理由（必填）',
      inputValidator: (input: string) => (input.trim() ? true : '请填写驳回理由')
    })
    actionLoading.value = true
    await rejectWorkOrder(orderId, { reason: value.trim() })
    ElMessage.success('工单已驳回')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '驳回失败')
  } finally {
    actionLoading.value = false
  }
}

/* 关闭：仅居民确认完成（已完成）后可关闭（状态机 9.4.2.10） */
async function handleClose(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '关闭后工单将归档不可再流转；若居民尚未评价，关闭后仍可补交评价。确认关闭该工单吗？',
      '关闭工单',
      {
        confirmButtonText: '确认关闭',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    actionLoading.value = true
    await closeWorkOrder(orderId)
    ElMessage.success('工单已关闭')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '关闭失败')
  } finally {
    actionLoading.value = false
  }
}

onMounted(fetchDetail)
</script>

<template>
  <section v-loading="loading" class="admin-order-detail">
    <header class="page-header">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/admin/work-orders' }">工单列表</el-breadcrumb-item>
        <el-breadcrumb-item>工单详情</el-breadcrumb-item>
      </el-breadcrumb>
      <el-button text @click="router.push('/admin/work-orders')">返回列表</el-button>
    </header>

    <template v-if="order">
      <article class="info-card">
        <div class="info-card-head">
          <div>
            <span class="order-number">{{ order.orderNo }}</span>
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
            <dd>{{ workOrderPriorityLabels[order.priority] }}</dd>
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
            <dt>当前处理人</dt>
            <dd>{{ order.assigneeName ?? '尚未指派' }}</dd>
          </div>
        </dl>

        <div class="info-description">
          <dt>问题描述</dt>
          <dd>{{ order.content }}</dd>
        </div>


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
        <h3 class="block-title">处理记录</h3>
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
      <article v-if="canAssign || canReject || canClose" class="action-card">
        <h3 class="block-title">管理操作</h3>
        <div class="action-buttons">
          <el-button v-if="canAssign" type="primary" size="large" @click="openAssignDialog">派单</el-button>
          <el-button v-if="canReject" type="danger" plain size="large" :loading="actionLoading" @click="handleReject">
            驳回工单
          </el-button>
          <el-button v-if="canClose" type="warning" plain size="large" :loading="actionLoading" @click="handleClose">
            关闭工单
          </el-button>
        </div>
      </article>
    </template>

    <el-dialog v-model="assignDialogVisible" :title="`派单 · ${order?.orderNo ?? ''}`" width="480px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="服务人员" required>
          <el-select
            v-model="assignForm.assigneeId"
            :loading="staffLoading"
            placeholder="选择服务人员"
            filterable
            class="staff-select"
          >
            <el-option
              v-for="staff in staffList"
              :key="staff.id"
              :label="`${staff.realName}（${staff.username}${staff.phone ? ' · ' + staff.phone : ''}）`"
              :value="staff.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="派单备注">
          <el-input v-model="assignForm.remark" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="派单要求（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="handleAssign">确认派单</el-button>
      </template>
    </el-dialog>
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
.action-card {
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
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
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

.staff-select {
  width: 100%;
}
</style>
