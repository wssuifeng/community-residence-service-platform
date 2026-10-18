<script setup lang="ts">
import { computed } from 'vue'
import type {
  IDepositRefund,
  ILeaseChange,
  ILeasePayment,
  ILeaseTimelineEntry,
  PaymentChannel
} from '@/common/lease/types/lease'
import { depositRefundStatusLabels, leaseChangeTypeLabels } from '@/common/lease/types/lease'
import { formatDate, formatDateTime } from '@/common/web/utils/date'

/**
 * 租约明细时间线（R73，接口设计 §9.20.3）：把三源合并为一条时间倒序的流水。
 *
 * 三源与字段口径（照文档，不自造）：
 * - 状态流转：`GET /leases/{id}/changes`（变更类型 / 字段 / 前后值 / 操作人 / 时间）；
 * - 支付流水：`GET /leases/{id}/payments`（支付项目、金额构成 `rentAmount`/`depositAmount`、
 *   覆盖租期 `periodStart~periodEnd`、业务单号 `paymentNo`、渠道流水号 `channelTradeNo`）；
 * - 押金退还：`GET /leases/{id}/deposit-refunds`（金额 / 渠道 / 状态 / **失败原因** /
 *   业务单号 `outRequestNo` / 渠道退款单号 `channelRefundNo`）。
 *
 * 排序与时间非空约束（§9.20.3 明文）：**排序以「发生时间」为准**——
 * 支付用 `paidAt ?? createdAt`，退款用 `refundedAt ?? createdAt`，流转用变更时间；
 * 完成时间仅已完成/已退还条目有值。
 */

const props = defineProps<{
  changes?: ILeaseChange[]
  payments?: ILeasePayment[]
  refunds?: IDepositRefund[]
}>()

const channelLabels: Record<PaymentChannel, string> = {
  ALIPAY: '支付宝',
  WECHAT: '微信支付'
}

function money(value: number | null | undefined): string {
  return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}`
}

const OPERATOR_LABELS: Record<string, string> = {
  ADMIN: '管理方',
  RESIDENT: '居民',
  SYSTEM: '系统'
}

/** 变更描述：字段级变更给字段中文名，整体变更给类型说明 */
function changeTitle(change: ILeaseChange): string {
  return change.fieldLabel || leaseChangeTypeLabels[change.changeType] || '变更'
}

function operatorText(change: ILeaseChange): string {
  const name = change.operatorName || OPERATOR_LABELS[change.operatorType] || change.operatorType
  return `${name}（${OPERATOR_LABELS[change.operatorType] ?? change.operatorType}）`
}

/**
 * 支付项目文案：按类型与金额构成判定——
 * 首期含押金部分 => 「首期费用（首月租金 + 押金）」；续期 => 「续期租金」。
 */
function paymentItemOf(payment: ILeasePayment): string {
  if ((payment.paymentType ?? 'RENEWAL') === 'FIRST') {
    return payment.depositAmount != null && payment.depositAmount > 0
      ? '首期费用（首月租金 + 押金）'
      : '首期费用（首月租金）'
  }
  return `续期租金（${payment.months} 个月）`
}

const entries = computed<ILeaseTimelineEntry[]>(() => {
  const list: ILeaseTimelineEntry[] = []

  for (const change of props.changes ?? []) {
    list.push({
      key: `STATUS-${change.id}`,
      type: 'STATUS',
      occurredAt: change.createdAt,
      changeType: change.changeType,
      fieldLabel: changeTitle(change),
      oldValue: change.oldValue ?? null,
      newValue: change.newValue ?? null,
      operatorText: operatorText(change),
      remark: change.reason ?? null,
      statusText: changeTitle(change),
      statusTone: 'neutral'
    })
  }

  for (const payment of props.payments ?? []) {
    list.push({
      key: `PAYMENT-${payment.paymentNo}`,
      type: 'PAYMENT',
      /* 发生时间：已支付取 paidAt，未支付回落到创建时间 */
      occurredAt: payment.paidAt ?? payment.createdAt,
      completedAt: payment.paidAt ?? null,
      bizNo: payment.paymentNo,
      channelTradeNo: payment.channelTradeNo ?? null,
      channel: payment.channel,
      amount: payment.amount,
      rentAmount: payment.rentAmount ?? null,
      depositAmount: payment.depositAmount ?? null,
      periodStart: payment.periodStart ?? null,
      periodEnd: payment.periodEnd ?? null,
      paymentItem: paymentItemOf(payment),
      statusText:
        payment.status === 'SUCCESS' ? '支付成功' : payment.status === 'PENDING' ? '待支付' : '已关闭',
      statusTone:
        payment.status === 'SUCCESS' ? 'completed' : payment.status === 'PENDING' ? 'pending' : 'failed'
    })
  }

  for (const refund of props.refunds ?? []) {
    list.push({
      key: `DEPOSIT_REFUND-${refund.id}`,
      type: 'DEPOSIT_REFUND',
      /* 发生时间：已完成取 refundedAt，否则回落到发起时间 */
      occurredAt: refund.refundedAt ?? refund.createdAt,
      completedAt: refund.refundedAt ?? null,
      bizNo: refund.outRequestNo,
      channelRefundNo: refund.channelRefundNo ?? null,
      channel: refund.channel,
      amount: refund.amount,
      paymentItem: '押金退还',
      statusText: depositRefundStatusLabels[refund.status],
      statusTone:
        refund.status === 'SUCCESS' ? 'completed' : refund.status === 'PENDING' ? 'pending' : 'failed',
      failReason: refund.failReason ?? null,
      remark: refund.operatorName ? `操作人 ${refund.operatorName}` : null
    })
  }

  /* 时间倒序：同刻按类型稳定排序（支付 → 退款 → 流转），避免同秒记录抖动 */
  const order: Record<string, number> = { PAYMENT: 0, DEPOSIT_REFUND: 1, STATUS: 2 }
  return list.sort(
    (a, b) =>
      (b.occurredAt ?? '').localeCompare(a.occurredAt ?? '') ||
      order[a.type] - order[b.type] ||
      a.key.localeCompare(b.key)
  )
})

/** 类型标识（时间线左侧徽标） */
const TYPE_LABELS: Record<string, string> = {
  STATUS: '状态',
  PAYMENT: '支付',
  DEPOSIT_REFUND: '退款'
}

function typeLabel(entry: ILeaseTimelineEntry): string {
  return TYPE_LABELS[entry.type] ?? entry.type
}

function changeTagLabel(entry: ILeaseTimelineEntry): string {
  return entry.changeType ? leaseChangeTypeLabels[entry.changeType] : '状态流转'
}
</script>

<template>
  <div class="lease-timeline">
    <p v-if="entries.length === 0" class="timeline-empty">
      暂无租约明细流水，支付或状态变更后会在这里按时间倒序留痕
    </p>

    <ol v-else class="timeline">
      <li v-for="entry in entries" :key="entry.key" class="tl-item" :data-type="entry.type">
        <span class="tl-marker" aria-hidden="true">
          <span class="tl-dot" :data-type="entry.type" />
        </span>

        <div class="tl-body">
          <p class="tl-head">
            <span class="tl-type" :data-type="entry.type">{{ typeLabel(entry) }}</span>
            <span class="tl-title">
              <template v-if="entry.type === 'PAYMENT'">{{ entry.paymentItem }}</template>
              <template v-else-if="entry.type === 'DEPOSIT_REFUND'">押金退还</template>
              <template v-else>{{ changeTagLabel(entry) }}</template>
            </span>
            <span v-if="entry.statusText" class="tl-status" :data-tone="entry.statusTone">
              {{ entry.statusText }}
            </span>
            <time class="tl-time">{{ formatDateTime(entry.occurredAt) }}</time>
          </p>

          <!-- 支付条目：金额构成拆分 + 覆盖租期区间 + 业务单号 + 渠道流水号 -->
          <template v-if="entry.type === 'PAYMENT'">
            <p class="tl-amount">
              <span class="amount-total">{{ money(entry.amount) }}</span>
              <span v-if="entry.rentAmount != null || entry.depositAmount != null" class="amount-split">
                <span v-if="entry.rentAmount != null">租金部分 {{ money(entry.rentAmount) }}</span>
                <span v-if="entry.depositAmount != null">押金部分 {{ money(entry.depositAmount) }}</span>
              </span>
            </p>
            <p v-if="entry.periodStart && entry.periodEnd" class="tl-meta">
              覆盖租期 <span class="mono">{{ formatDate(entry.periodStart) }} ~ {{ formatDate(entry.periodEnd) }}</span>
            </p>
            <p class="tl-meta mono">
              业务单号 {{ entry.bizNo }}
              <template v-if="entry.channelTradeNo"> · 渠道流水号 {{ entry.channelTradeNo }}</template>
            </p>
            <p class="tl-meta">
              渠道 {{ entry.channel ? channelLabels[entry.channel] : '—' }}
              <template v-if="entry.completedAt"> · 支付时间 {{ formatDateTime(entry.completedAt) }}</template>
            </p>
          </template>

          <!-- 退款条目：金额 + 渠道 + 失败原因（必展示）+ 两个单号 -->
          <template v-else-if="entry.type === 'DEPOSIT_REFUND'">
            <p class="tl-amount">
              <span class="amount-total">{{ money(entry.amount) }}</span>
              <span class="amount-split">渠道 {{ entry.channel ? channelLabels[entry.channel] : '—' }}</span>
            </p>
            <p class="tl-meta mono">
              请求号 {{ entry.bizNo }}
              <template v-if="entry.channelRefundNo"> · 渠道退款单号 {{ entry.channelRefundNo }}</template>
            </p>
            <p class="tl-meta">
              发起 {{ formatDateTime(entry.occurredAt) }}
              <template v-if="entry.completedAt"> · 完成 {{ formatDateTime(entry.completedAt) }}</template>
            </p>
            <p v-if="entry.failReason" class="tl-fail">失败原因：{{ entry.failReason }}</p>
          </template>

          <!-- 状态流转条目：字段级前后值 + 操作人 + 说明 -->
          <template v-else>
            <p v-if="entry.fieldLabel" class="tl-field">{{ entry.fieldLabel }}</p>
            <p v-if="entry.oldValue != null || entry.newValue != null" class="tl-diff">
              <span class="diff-old">{{ entry.oldValue ?? '—' }}</span>
              <span class="diff-arrow">→</span>
              <span class="diff-new">{{ entry.newValue ?? '—' }}</span>
            </p>
            <p class="tl-operator">
              操作人 {{ entry.operatorText }}
              <template v-if="entry.remark"> · 说明：{{ entry.remark }}</template>
            </p>
          </template>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.lease-timeline {
  min-height: 80px;
}

.timeline-empty {
  margin: 0;
  padding: var(--spacing-lg) 0;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.tl-item {
  position: relative;
  display: flex;
  gap: var(--spacing-md);
  padding-bottom: var(--spacing-md);
}

.tl-item:last-child {
  padding-bottom: 0;
}

/* 竖线：除最后一项外连通 */
.tl-item::before {
  content: '';
  position: absolute;
  top: 14px;
  bottom: 0;
  left: 5px;
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
  justify-content: center;
  flex-shrink: 0;
  width: 11px;
  padding-top: 4px;
}

.tl-dot {
  display: block;
  width: 11px;
  height: 11px;
  border-radius: var(--radius-circle);
  background-color: var(--color-border);
}

.tl-dot[data-type='PAYMENT'] {
  background-color: var(--color-success);
}

.tl-dot[data-type='DEPOSIT_REFUND'] {
  background-color: #0891b2;
}

.tl-dot[data-type='STATUS'] {
  background-color: var(--color-primary);
}

.tl-body {
  min-width: 0;
  flex: 1;
}

.tl-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-xs);
  flex-wrap: wrap;
}

/* 类型徽标：状态蓝 / 支付绿 / 退款青 */
.tl-type {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.tl-type[data-type='PAYMENT'] {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.tl-type[data-type='DEPOSIT_REFUND'] {
  background: #e6f6f8;
  color: #0891b2;
}

.tl-type[data-type='STATUS'] {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.tl-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.tl-status {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.tl-status[data-tone='completed'] {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.tl-status[data-tone='pending'] {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.tl-status[data-tone='failed'] {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.tl-time {
  margin-left: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  font-variant-numeric: tabular-nums;
}

.tl-amount {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  margin: 0 0 var(--spacing-xs);
}

.amount-total {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  font-family: var(--font-family-mono);
}

.amount-split {
  display: flex;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.tl-meta,
.tl-field,
.tl-operator {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
  word-break: break-word;
}

.tl-field {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 字段级前后值：旧值灰划线、新值强调 */
.tl-diff {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
}

.diff-old {
  color: var(--color-text-secondary);
  text-decoration: line-through;
}

.diff-arrow {
  color: var(--color-text-disabled);
}

.diff-new {
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.tl-fail {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
  line-height: var(--line-height-normal);
}

.mono {
  font-family: var(--font-family-mono);
}
</style>
