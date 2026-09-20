<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import StaffPickerDrawer from '@/views/admin/workorder/StaffPickerDrawer.vue'
import { closeWorkOrder, getWorkOrder, getWorkOrderTimeline, listWorkOrderAttachments, rejectWorkOrder } from '@/api/workorder'
import type {
  DispatchFlag,
  IWorkOrder,
  IWorkOrderAttachment,
  IWorkOrderProcess,
  WorkOrderPriority,
  WorkOrderStatus
} from '@/types/modules/workorder'
import { dispatchFlagColors, dispatchFlagLabels, workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import { canAssignOrder, formatWaited, isReassignOrder } from './dispatch'
import { formatDateTime } from '@/utils/date'

/**
 * 工单详情（UI设计.md §4.3.5）：面包屑 + 状态条 + 工单信息 / 处理时间线 / 管理操作三栏。
 * 状态条与列表看板共用调度口径（dispatchFlag / waitedMinutes）：超时工单在详情页同样一眼可见，
 * 派单走与列表同一个候选抽屉组件，档位与班次呈现两处完全一致。
 */

const route = useRoute()

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

const order = ref<IWorkOrder | null>(null)
const timeline = ref<IWorkOrderProcess[]>([])
const attachments = ref<IWorkOrderAttachment[]>([])
const loading = ref(false)
const actionLoading = ref(false)

/* 派单/改派：候选抽屉与列表共用（候选、备注、提交全在组件内） */
const pickerVisible = ref(false)

const imageAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'IMAGE'))
const documentAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'DOCUMENT'))
const sortedTimeline = computed(() => [...timeline.value].sort((a, b) => a.createdAt.localeCompare(b.createdAt)))

/* 调度标记与等待时长（列表看板同口径，超时工单在详情页同样显眼） */
const dispatchFlag = computed<DispatchFlag>(() => order.value?.dispatchFlag ?? 'NORMAL')
const flagColor = computed(() => dispatchFlagColors[dispatchFlag.value])
const waitedText = computed(() => formatWaited(order.value?.waitedMinutes))
/** 处理人当日班次：后端空值=未排班，需明示而非留白 */
const assigneeShift = computed(() => order.value?.assigneeShiftLabel || '未排班')

/* 状态机：待受理/待派单可派单与驳回；已派单可改派（后端 assign 同一端点）；已完成可关闭 */
const canAssign = computed(() => !!order.value && canAssignOrder(order.value))
const canReassign = computed(() => !!order.value && isReassignOrder(order.value))
const canReject = computed(() => order.value?.status === 'PENDING' || order.value?.status === 'TO_ASSIGN')
const canClose = computed(() => order.value?.status === 'COMPLETED')
const hasAction = computed(() => canAssign.value || canReject.value || canClose.value)

/* 紧急程度 → 状态条配色档，与 staff 端工单详情同一口径 */
function toneOf(priority: WorkOrderPriority): 'urgent' | 'normal' | 'low' {
  if (priority === 'URGENT') return 'urgent'
  return priority === 'LOW' ? 'low' : 'normal'
}

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
    await rejectWorkOrder(orderId, { remark: value.trim() })
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
    <nav class="crumb-bar" aria-label="面包屑">
      <router-link class="crumb-link" to="/admin/work-orders">工单管理</router-link>
      <span class="crumb-sep">/</span>
      <span class="crumb-current">{{ order?.orderNo ?? '工单详情' }}</span>
    </nav>

    <template v-if="order">
      <!-- 顶部状态条：调度标记 + 优先级胶囊 + 标题 + 关键元信息 + 状态标签 -->
      <article class="status-strip" :class="[`is-${toneOf(order.priority)}`, `flag-${dispatchFlag}`]">
        <span class="status-priority">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
            <path d="M12 7.5v6M12 16.4v.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          {{ workOrderPriorityLabels[order.priority] }}
        </span>
        <div class="strip-main">
          <h1 class="status-title">{{ order.title }}</h1>
          <p class="strip-meta">
            提交人 {{ order.residentName }}
            <span class="meta-sep">·</span>
            {{ formatDateTime(order.createdAt) }}
            <template v-if="waitedText">
              <span class="meta-sep">·</span>
              <span class="strip-waited" :class="{ 'is-overdue': dispatchFlag === 'OVERDUE' }">
                已等待 {{ waitedText }}
              </span>
            </template>
          </p>
        </div>
        <span class="strip-flags">
          <span class="flag-badge" :style="{ backgroundColor: flagColor.bg, color: flagColor.fg }">
            {{ dispatchFlagLabels[dispatchFlag] }}
          </span>
          <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
        </span>
      </article>

      <div class="detail-grid">
        <!-- 工单信息：全部字段 + 描述 + 附件保留 -->
        <article class="panel info-panel">
          <h2 class="panel-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M6 3.5h8.5L19 8v12.5H6z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
              <path d="M9 12h6M9 15.5h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            工单信息
          </h2>

          <dl class="info-rows">
            <div class="info-row">
              <dt>服务类别</dt>
              <dd>{{ order.categoryName }}</dd>
            </div>
            <div class="info-row">
              <dt>联系电话</dt>
              <dd>{{ order.contactPhone || '未填写' }}</dd>
            </div>
            <div class="info-row">
              <dt>地址</dt>
              <dd>{{ order.address || '未填写' }}</dd>
            </div>
            <div class="info-row">
              <dt>当前处理人</dt>
              <dd>
                <template v-if="order.assigneeName">
                  {{ order.assigneeName }}
                  <span class="shift-chip">{{ assigneeShift }}</span>
                  <span class="load-chip" :class="{ 'is-busy': (order.assigneeActiveOrders ?? 0) >= 5 }">
                    在手工单 {{ order.assigneeActiveOrders ?? 0 }}
                  </span>
                </template>
                <span v-else>尚未指派</span>
              </dd>
            </div>
          </dl>

          <div class="desc-block">
            <span class="block-label">问题描述</span>
            <p class="desc-text">{{ order.content }}</p>
          </div>

          <div v-if="imageAttachments.length > 0" class="photo-block">
            <span class="block-label">现场照片</span>
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

          <div v-if="documentAttachments.length > 0" class="photo-block">
            <span class="block-label">附件</span>
            <ul class="file-list">
              <li v-for="file in documentAttachments" :key="file.id">
                <a :href="file.fileUrl" target="_blank" rel="noopener">{{ file.fileName }}</a>
              </li>
            </ul>
          </div>
        </article>

        <!-- 处理时间线：灰点历史 / 蓝勾当前（与 staff 端同视觉） -->
        <article class="panel timeline-panel">
          <h2 class="panel-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.8" />
              <path d="M12 7.5V12l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            处理时间线
          </h2>
          <ol v-if="sortedTimeline.length > 0" class="timeline">
            <li
              v-for="(node, index) in sortedTimeline"
              :key="index"
              class="tl-item"
              :class="index === sortedTimeline.length - 1 ? 'is-current' : 'is-done'"
            >
              <span class="tl-marker" aria-hidden="true">
                <svg v-if="index === sortedTimeline.length - 1" viewBox="0 0 24 24" fill="none">
                  <path d="m6 12.5 4 4 8-9" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <div class="tl-body">
                <p class="tl-time">
                  {{ formatDateTime(node.createdAt) }}
                  <span class="tl-operator">{{ node.operatorName }}</span>
                </p>
                <p class="tl-status">{{ workOrderStatusLabels[node.newStatus] }}</p>
                <p v-if="node.content" class="tl-note">{{ node.content }}</p>
              </div>
            </li>
          </ol>
          <el-empty v-else description="暂无处理记录" :image-size="60" />
        </article>

        <!-- 管理操作：按状态机仅在合法状态显示对应动作，全部动作与二次确认逻辑与原实现一致 -->
        <article class="panel action-panel">
          <h2 class="panel-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M4 6.5h16v11H4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
              <path d="M7.5 10.5h9M7.5 13.8h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            管理操作
          </h2>

          <el-button v-if="canAssign" type="primary" size="large" class="action-btn" @click="pickerVisible = true">
            {{ canReassign ? '改派工单' : '派单' }}
          </el-button>
          <el-button v-if="canReject" type="danger" plain size="large" class="action-btn" :loading="actionLoading" @click="handleReject">
            驳回工单
          </el-button>
          <el-button v-if="canClose" type="warning" plain size="large" class="action-btn" :loading="actionLoading" @click="handleClose">
            关闭工单
          </el-button>

          <p v-if="!hasAction" class="action-hint">
            当前状态「{{ workOrderStatusLabels[order.status] }}」无需管理操作，工单由居民或服务人员侧继续流转。
          </p>

          <p class="action-note">动作说明：派单/改派须选择服务人员；驳回须填写理由；关闭为归档终态操作。</p>
        </article>
      </div>
    </template>

    <!-- 派单/改派：与工单调度列表共用同一候选抽屉，档位与班次呈现保持一致 -->
    <StaffPickerDrawer v-model="pickerVisible" :order="order" @assigned="fetchDetail" />
  </section>
</template>

<style scoped>
.crumb-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.crumb-link {
  color: var(--color-text-secondary);
  text-decoration: none;
}

.crumb-link:hover {
  color: var(--color-primary);
}

.crumb-sep {
  color: var(--color-text-disabled);
}

.crumb-current {
  font-family: var(--font-family-mono);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 顶部状态条：优先级胶囊 + 标题 + 元信息 + 状态标签 */
.status-strip {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.status-priority {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 6px var(--spacing-md);
  border-radius: var(--radius-pill);
  background-color: var(--status-pending);
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.status-priority svg {
  width: 16px;
  height: 16px;
}

.status-strip.is-urgent .status-priority {
  background-color: var(--status-rejected);
}

.status-strip.is-low .status-priority {
  background-color: var(--color-text-disabled);
}

/* 超时/紧急：状态条左侧色带 + 淡底，进详情第一眼就看到 */
.status-strip.flag-OVERDUE {
  background-color: var(--color-danger-soft);
  box-shadow: inset 4px 0 0 var(--status-rejected), var(--shadow-card);
}

.status-strip.flag-URGENT {
  box-shadow: inset 4px 0 0 var(--color-warning), var(--shadow-card);
}

.strip-main {
  flex: 1;
  min-width: 0;
}

.status-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.status-strip.flag-OVERDUE .status-title {
  color: #b91c1c;
}

.strip-meta {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.meta-sep {
  margin: 0 var(--spacing-xs);
  color: var(--color-text-disabled);
}

/* 等待时长：常规灰字，超时红字加粗 */
.strip-waited {
  color: var(--color-text-secondary);
}

.strip-waited.is-overdue {
  color: #b91c1c;
  font-weight: var(--font-weight-bold);
}

.strip-flags {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
}

.flag-badge {
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
  white-space: nowrap;
}

/* 处理人班次与在手负载（详情信息行内联展示） */
.shift-chip {
  margin-left: var(--spacing-sm);
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-success-soft);
  color: #047857;
  font-size: var(--font-size-xs);
}

.load-chip {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.load-chip.is-busy {
  color: var(--color-warning);
  font-weight: var(--font-weight-medium);
}

/* 主区三栏：工单信息 / 处理时间线 / 管理操作，等高对齐 */
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr) minmax(0, 0.8fr);
  align-items: stretch;
  gap: var(--spacing-lg);
}

.panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-lg);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-lg);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.panel-title svg {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  color: var(--color-text-primary);
}

/* 信息行 */
.info-rows {
  margin: 0 0 var(--spacing-lg);
  padding: 0;
}

.info-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) 0;
  border-bottom: 1px dashed var(--color-border);
}

.info-row:last-child {
  border-bottom: none;
}

.info-row dt {
  flex-shrink: 0;
  width: 84px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-row dd {
  margin: 0;
  min-width: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
  word-break: break-all;
}

.block-label {
  display: inline-block;
  margin-bottom: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.desc-text {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
}

.photo-block {
  margin-top: var(--spacing-lg);
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
  font-size: var(--font-size-sm);
}

.file-list a {
  color: var(--color-primary);
}

/* 时间线：自绘节点（灰点=历史，蓝勾=当前），避免 el-timeline 默认形态 */
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.tl-item {
  position: relative;
  display: flex;
  gap: var(--spacing-md);
  padding-bottom: var(--spacing-lg);
}

.tl-item:last-child {
  padding-bottom: 0;
}

.tl-item::before {
  content: '';
  position: absolute;
  top: 18px;
  bottom: 0;
  left: 8px;
  width: 1px;
  background-color: var(--color-border);
}

.tl-item:last-child::before {
  display: none;
}

.tl-marker {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  margin-top: 3px;
  border-radius: var(--radius-circle);
  background-color: var(--color-border);
}

.tl-marker svg {
  width: 13px;
  height: 13px;
  color: #fff;
}

.tl-item.is-current .tl-marker {
  background-color: var(--color-primary);
}

.tl-body {
  min-width: 0;
}

.tl-time {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.tl-operator {
  color: var(--color-text-secondary);
}

.tl-status {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.tl-item.is-current .tl-status {
  color: var(--color-primary);
}

.tl-note {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

/* 管理操作：纵向动作条 */
.action-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.action-btn {
  width: 100%;
  margin: 0;
}

.action-hint {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.action-note {
  margin: auto 0 0;
  padding-top: var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

@media (max-width: 1199px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .action-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 767px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .action-panel {
    grid-column: auto;
  }

  .status-strip {
    flex-wrap: wrap;
  }

  .strip-flags {
    margin-left: 0;
  }
}
</style>
