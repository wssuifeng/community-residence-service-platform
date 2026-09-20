<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { IWorkOrder, WorkOrderStatus } from '@/types/modules/workorder'
import {
  dispatchFlagColors,
  dispatchFlagLabels,
  workOrderPriorityLabels,
  workOrderStatusLabels
} from '@/types/modules/workorder'
import { canAssignOrder, formatWaited, isReassignOrder } from './dispatch'
import { formatDateTime } from '@/utils/date'

/**
 * 调度工单卡（看板列与列表视图共用一张卡，避免两套展示口径）。
 * 层级固定为四行：标记行（超时/紧急/新单 + 工单号）→ 标题 → 地址与分类 → 提交人与等待时长，
 * 已派单再加一行处理人与班次，保证扫一眼即能判断「要不要现在处理」。
 */
const props = defineProps<{
  order: IWorkOrder
}>()

const emit = defineEmits<{
  open: [order: IWorkOrder]
  assign: [order: IWorkOrder]
}>()

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

const flag = computed(() => props.order.dispatchFlag ?? 'NORMAL')
const flagColor = computed(() => dispatchFlagColors[flag.value])
const waitedText = computed(() => formatWaited(props.order.waitedMinutes))
const assignable = computed(() => canAssignOrder(props.order))
const reassign = computed(() => isReassignOrder(props.order))
/** 处理人当日班次：后端空值=未排班，需明示而非留白 */
const shiftLabel = computed(() => props.order.assigneeShiftLabel || '未排班')
const shiftTone = computed(() => {
  const label = props.order.assigneeShiftLabel
  if (!label) return 'none'
  if (label === '休息') return 'rest'
  return 'on'
})
</script>

<template>
  <article
    class="dispatch-card"
    :class="[`flag-${flag}`, { 'is-urgent': order.priority === 'URGENT', 'is-assignable': assignable }]"
    :style="{ '--card-band': flagColor.fg, '--card-tint': flagColor.bg }"
    @click="emit('open', order)"
  >
    <header class="card-flags">
      <span class="flag-badge" :class="`is-${flag.toLowerCase()}`">
        {{ dispatchFlagLabels[flag] }}
        <template v-if="flag === 'OVERDUE' && waitedText">· 已等 {{ waitedText }}</template>
      </span>
      <span v-if="order.priority === 'URGENT'" class="prio-badge is-urgent">紧急</span>
      <span v-else-if="order.priority === 'HIGH'" class="prio-badge is-high">高</span>
      <span class="order-no">{{ order.orderNo }}</span>
    </header>

    <h3 class="card-title">{{ order.title }}</h3>

    <p class="card-line">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M4 10.5 12 4l8 6.5V20H4z" />
        <path d="M9.5 20v-5.5h5V20" />
      </svg>
      <span class="line-text">{{ order.address || '未填写地址' }}</span>
      <span class="cate-chip">{{ order.categoryName }}</span>
    </p>

    <p class="card-line is-muted">
      <span>{{ order.residentName }}</span>
      <span class="dot">·</span>
      <span>{{ formatDateTime(order.createdAt) }}</span>
      <span v-if="waitedText" class="waited">已等待 {{ waitedText }}</span>
    </p>

    <p v-if="order.assigneeName" class="card-line assignee-line">
      <span class="assignee-name">{{ order.assigneeName }}</span>
      <span class="shift-chip" :class="`is-${shiftTone}`">{{ shiftLabel }}</span>
      <span class="load-chip" :class="{ 'is-busy': (order.assigneeActiveOrders ?? 0) >= 5 }">
        在手工单 {{ order.assigneeActiveOrders ?? 0 }}
      </span>
    </p>

    <footer class="card-foot">
      <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
      <span class="prio-text">{{ workOrderPriorityLabels[order.priority] }}优先级</span>
      <span class="foot-actions">
        <el-button v-if="assignable" text type="primary" size="small" @click.stop="emit('assign', order)">
          {{ reassign ? '改派' : '派单' }}
        </el-button>
        <el-button text size="small" @click.stop="emit('open', order)">详情</el-button>
      </span>
    </footer>
  </article>
</template>

<style scoped>
/* 卡片左侧色带由调度标记驱动：超时红 / 紧急橙 / 新单蓝 / 常规透明 */
.dispatch-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-md) var(--spacing-sm);
  padding-left: calc(var(--spacing-md) + 4px);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.dispatch-card::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 4px;
  border-radius: var(--radius-md) 0 0 var(--radius-md);
  background-color: var(--card-band);
  opacity: 0.35;
}

.dispatch-card:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

/* 超时：整卡淡红底纹 + 实色色带，扫视时先撞到它 */
.dispatch-card.flag-OVERDUE {
  background-color: var(--color-danger-soft);
}

.dispatch-card.flag-OVERDUE::before {
  opacity: 1;
}

/* 紧急未完结：淡橙底纹（比超时浅一档，两者同时命中时超时优先） */
.dispatch-card.flag-URGENT {
  background-color: var(--card-tint);
}

.dispatch-card.flag-URGENT::before {
  opacity: 0.9;
}

.dispatch-card.flag-OVERDUE.flag-URGENT {
  background-color: var(--color-danger-soft);
}

.dispatch-card.flag-NORMAL::before {
  opacity: 0.18;
}

/* 标记行 */
.card-flags {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.flag-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  white-space: nowrap;
}

.flag-badge.is-overdue {
  background-color: var(--status-rejected);
  color: #fff;
}

.flag-badge.is-urgent {
  background-color: #c2410c;
  color: #fff;
}

.flag-badge.is-new {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.flag-badge.is-normal {
  background-color: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
}

/* 紧急度加重徽章（紧急实心橙红，高描边） */
.prio-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
}

.prio-badge.is-urgent {
  background-color: var(--color-danger);
  color: #fff;
}

.prio-badge.is-high {
  border: 1px solid var(--color-warning);
  color: var(--color-warning);
}

.order-no {
  margin-left: auto;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  white-space: nowrap;
}

.card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.flag-OVERDUE .card-title {
  color: #b91c1c;
}

.card-line {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  min-width: 0;
}

.card-line svg {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  color: var(--color-text-disabled);
}

.line-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cate-chip {
  flex-shrink: 0;
  margin-left: auto;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-subtle);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  white-space: nowrap;
}

.card-line.is-muted {
  color: var(--color-text-disabled);
}

.dot {
  color: var(--color-text-disabled);
}

/* 等待时长：超时卡红字加粗，普通卡灰字 */
.waited {
  margin-left: auto;
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-hover);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.flag-OVERDUE .waited {
  background-color: rgba(239, 68, 68, 0.14);
  color: #b91c1c;
  font-weight: var(--font-weight-bold);
}

.assignee-line {
  padding-top: var(--spacing-xs);
  border-top: 1px dashed var(--color-border);
}

.assignee-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 班次标签：休息灰、在岗绿、未排班浅灰 */
.shift-chip {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  white-space: nowrap;
}

.shift-chip.is-on {
  background-color: var(--color-success-soft);
  color: #047857;
}

.shift-chip.is-rest {
  background-color: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.shift-chip.is-none {
  background-color: var(--color-warning-soft);
  color: #b45309;
}

.load-chip {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--color-text-disabled);
  white-space: nowrap;
}

.load-chip.is-busy {
  color: var(--color-warning);
  font-weight: var(--font-weight-medium);
}

/* 卡脚：具体状态 + 优先级 + 动作 */
.card-foot {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.prio-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.foot-actions {
  display: flex;
  align-items: center;
  margin-left: auto;
}

.foot-actions :deep(.el-button) {
  padding: 0 var(--spacing-xs);
}
</style>
