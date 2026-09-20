<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import LeaseRenewDialog from './LeaseRenewDialog.vue'
import { getLeaseList } from '@/api/lease'
import { listMyPayments } from '@/api/payment'
import { formatDateTime } from '@/utils/date'
import { leaseStatusLabels } from '@/types/modules/lease'
import type { ILeasePayment, ILeaseRecord, PaymentChannel, PaymentStatus } from '@/types/modules/lease'

/**
 * 我的租约（R61，v1.4；2026-09-20 按 mockup-to-page 重设计）：
 * 「租约｜支付记录」双 tab。租约 tab 按「社区 + 房屋」分组为卡片：
 * 组内取当前租约（ACTIVE 优先，其次起始日期最新）作主卡，
 * 其余折叠为「历史租约 N 条」（同一房屋多次租住不再重复铺开）；
 * ACTIVE 主卡可发起在线续租（三步支付弹窗见 LeaseRenewDialog）。
 * 到期标注按今天前端计算（仅 ACTIVE：止期 30 天内「即将到期」/已过止期
 * 「已到期」），与后端 expiryFlag 同口径独立判定。tab 状态写入 URL query。
 */

const route = useRoute()
const router = useRouter()

/* ------------------------------ tab 状态（URL query 回显） ------------------------------ */

type ListTab = 'lease' | 'payments'

const tab = ref<ListTab>(route.query.tab === 'payments' ? 'payments' : 'lease')

function handleTabChange(value: string | number | boolean | undefined): void {
  tab.value = value === 'payments' ? 'payments' : 'lease'
  /* replace 免得来回切换污染历史栈；query 仅支付记录 tab 携带 */
  router.replace({
    query: tab.value === 'payments' ? { tab: 'payments' } : undefined
  })
  if (tab.value === 'payments') {
    payQuery.page = 1
    loadPayments()
  } else {
    loadLeases()
  }
}

const headSub = computed(() =>
  tab.value === 'payments'
    ? '续租支付流水，支付结果以查单确认后为准'
    : '本人租约的租期与租金，在租租约可在线发起续租'
)

/* ------------------------------ 租约 tab ------------------------------ */

const query = reactive({ page: 1, size: 10 })
const total = ref(0)
const records = ref<ILeaseRecord[]>([])
const loading = ref(false)
/** 主卡点击展开的租约 id（单开模式） */
const expandedId = ref<number | null>(null)
/** 展开历史折叠的分组 key（单开模式） */
const expandedHistory = ref<string | null>(null)

async function loadLeases(): Promise<void> {
  loading.value = true
  try {
    const data = await getLeaseList({ page: query.page, size: query.size })
    records.value = data.records
    total.value = data.total
    /* 翻页后展开行可能已不在当前页，统一收起避免错位 */
    if (expandedId.value !== null && !data.records.some((row) => row.id === expandedId.value)) {
      expandedId.value = null
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载租约列表失败')
  } finally {
    loading.value = false
  }
}

function toggleExpand(row: ILeaseRecord): void {
  expandedId.value = expandedId.value === row.id ? null : row.id
}

function toggleHistory(key: string): void {
  expandedHistory.value = expandedHistory.value === key ? null : key
}

/** 距止期天数（负值=已过期） */
function daysToEnd(row: ILeaseRecord): number {
  const end = new Date(`${row.endDate}T00:00:00`)
  if (Number.isNaN(end.getTime())) return Number.NaN
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((end.getTime() - today.getTime()) / 86400000)
}

/** 到期标注（R61 口径）：仅 ACTIVE 判定，止期 30 天内「即将到期」/已过止期「已到期」 */
function expiryOf(row: ILeaseRecord): 'EXPIRING' | 'EXPIRED' | null {
  if (row.status !== 'ACTIVE') return null
  const days = daysToEnd(row)
  if (Number.isNaN(days)) return null
  if (days < 0) return 'EXPIRED'
  if (days <= 30) return 'EXPIRING'
  return null
}

function expiryText(row: ILeaseRecord): string {
  if (row.status !== 'ACTIVE') return '非在租状态，不作到期判定'
  const days = daysToEnd(row)
  if (Number.isNaN(days)) return '—'
  if (days < 0) return `已过期 ${-days} 天`
  if (days === 0) return '今天到期'
  return `距到期还有 ${days} 天`
}

function money(value: number | null | undefined): string {
  return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}`
}

/** 组内当前租约排序：ACTIVE 优先，其次起始日期新（同屋多次租住的展示主卡） */
function leaseRank(row: ILeaseRecord): number {
  if (row.status === 'ACTIVE') return 0
  return 1
}

interface LeaseGroup {
  key: string
  communityName: string
  current: ILeaseRecord
  history: ILeaseRecord[]
}

/**
 * 按「社区 + 房屋」分组（服务端分页口径内分组：同屋记录跨页时历史折叠
 * 仅覆盖当前页，居民租约量级下单页 100 条内完整呈现）
 */
const leaseGroups = computed<LeaseGroup[]>(() => {
  const buckets = new Map<string, ILeaseRecord[]>()
  for (const row of records.value) {
    const key = `${row.communityId ?? 0}:${row.houseId}`
    const bucket = buckets.get(key)
    if (bucket) bucket.push(row)
    else buckets.set(key, [row])
  }
  const groups: LeaseGroup[] = []
  for (const [key, rows] of buckets) {
    const sorted = [...rows].sort(
      (a, b) => leaseRank(a) - leaseRank(b) || b.startDate.localeCompare(a.startDate)
    )
    groups.push({
      key,
      communityName: sorted[0].communityName ?? '',
      current: sorted[0],
      history: sorted.slice(1)
    })
  }
  return groups
})

const isEmpty = computed(() => !loading.value && records.value.length === 0)

/* ------------------------------ 续租入口（三步支付弹窗） ------------------------------ */

const renewVisible = ref(false)
const renewTarget = ref<ILeaseRecord | null>(null)

function openRenew(row: ILeaseRecord): void {
  renewTarget.value = row
  renewVisible.value = true
}

/** 支付成功后租期已延展，刷新列表拿到新止期 */
function handleRenewSuccess(): void {
  loadLeases()
}

/* ------------------------------ 支付记录 tab ------------------------------ */

const paymentChannelLabels: Record<PaymentChannel, string> = {
  ALIPAY: '支付宝',
  WECHAT: '微信支付'
}
const paymentStatusLabels: Record<PaymentStatus, string> = {
  PENDING: '待支付',
  SUCCESS: '支付成功',
  CLOSED: '已关闭'
}

const payQuery = reactive({ page: 1, size: 10 })
const payTotal = ref(0)
const payRecords = ref<ILeasePayment[]>([])
const payLoading = ref(false)

async function loadPayments(): Promise<void> {
  payLoading.value = true
  try {
    const data = await listMyPayments({ page: payQuery.page, size: payQuery.size })
    payRecords.value = data.records
    payTotal.value = data.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载支付记录失败')
  } finally {
    payLoading.value = false
  }
}

const payEmpty = computed(() => !payLoading.value && payRecords.value.length === 0)

onMounted(() => {
  if (tab.value === 'payments') {
    loadPayments()
  } else {
    loadLeases()
  }
})
</script>

<template>
  <section class="lease-list">
    <header class="page-head">
      <div>
        <h1>我的租约</h1>
        <p class="page-head-sub">{{ headSub }}</p>
      </div>
    </header>

    <!-- tab 切换：状态写入 URL query 便于外跳回显 -->
    <div class="tab-bar">
      <el-radio-group :model-value="tab" @update:model-value="handleTabChange">
        <el-radio-button value="lease">租约</el-radio-button>
        <el-radio-button value="payments">支付记录</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 租约 tab：社区+房屋分组卡片，同屋历史折叠 -->
    <template v-if="tab === 'lease'">
      <div v-loading="loading" class="lease-board">
        <div v-if="isEmpty" class="empty-state">
          <p>还没有租约记录，入住申请通过后即可在这里查看</p>
        </div>

        <article v-for="group in leaseGroups" v-else :key="group.key" class="lease-card">
          <!-- 社区徽章行 -->
          <div class="card-community">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 21h18" />
              <path d="M5 21V7l7-4 7 4v14" />
              <path d="M9 21v-6h6v6" />
            </svg>
            <span class="community-name">{{ group.communityName || '社区信息待完善' }}</span>
          </div>

          <!-- 当前租约主行 -->
          <div
            class="lease-row"
            role="button"
            tabindex="0"
            @click="toggleExpand(group.current)"
            @keydown.enter="toggleExpand(group.current)"
          >
            <div class="lease-main">
              <h2 class="lease-title">{{ group.current.houseLocation }}</h2>
              <div class="lease-info">
                <div class="info-item">
                  <span class="info-label">租期</span>
                  <span class="info-value mono">{{ group.current.startDate }} ~ {{ group.current.endDate }}</span>
                </div>
                <div class="info-item">
                  <span class="info-label">月租</span>
                  <span class="info-value">{{ money(group.current.monthlyRent) }}/月</span>
                </div>
                <div class="info-item">
                  <span class="info-label">押金</span>
                  <span class="info-value">{{ money(group.current.deposit) }}</span>
                </div>
              </div>
            </div>
            <div class="lease-side">
              <span v-if="expiryOf(group.current)" class="expiry-badge" :data-flag="expiryOf(group.current)">
                {{ expiryOf(group.current) === 'EXPIRING' ? '即将到期' : '已到期' }}
              </span>
              <span class="status-pill" :data-status="group.current.status">
                {{ leaseStatusLabels[group.current.status] }}
              </span>
              <el-button
                v-if="group.current.status === 'ACTIVE'"
                class="lease-action"
                type="primary"
                round
                size="small"
                @click.stop="openRenew(group.current)"
              >
                续租
              </el-button>
              <svg
                class="lease-arrow"
                :class="{ 'is-open': expandedId === group.current.id }"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <polyline points="6 9 12 15 18 9" />
              </svg>
            </div>
          </div>

          <!-- 同屋历史租约折叠 -->
          <div v-if="group.history.length > 0" class="history-fold">
            <button type="button" class="history-toggle" @click.stop="toggleHistory(group.key)">
              历史租约 {{ group.history.length }} 条
              <svg
                class="history-chevron"
                :class="{ 'is-open': expandedHistory === group.key }"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <polyline points="6 9 12 15 18 9" />
              </svg>
            </button>
            <div v-if="expandedHistory === group.key" class="history-list">
              <div v-for="row in group.history" :key="row.id" class="history-item">
                <span class="history-term mono">{{ row.startDate }} ~ {{ row.endDate }}</span>
                <span class="history-rent">月租 {{ money(row.monthlyRent) }}</span>
                <span class="status-pill" :data-status="row.status">
                  {{ leaseStatusLabels[row.status] }}
                </span>
              </div>
            </div>
          </div>

          <!-- 主卡展开明细 -->
          <div v-if="expandedId === group.current.id" class="lease-expand">
            <dl class="expand-grid">
              <div class="expand-item">
                <dt>租客</dt>
                <dd>{{ group.current.tenantName }}</dd>
              </div>
              <div class="expand-item">
                <dt>登记时间</dt>
                <dd class="mono">{{ formatDateTime(group.current.createdAt) }}</dd>
              </div>
              <div class="expand-item">
                <dt>到期说明</dt>
                <dd>{{ expiryText(group.current) }}</dd>
              </div>
              <div class="expand-item expand-remark">
                <dt>备注</dt>
                <dd>{{ group.current.remark?.trim() || '无' }}</dd>
              </div>
            </dl>
          </div>
        </article>

        <Pagination
          v-if="!isEmpty"
          v-model:page="query.page"
          v-model:size="query.size"
          :total="total"
          @update:page="loadLeases"
          @update:size="loadLeases"
        />
      </div>
    </template>

    <!-- 支付记录 tab：续租支付流水 -->
    <template v-else>
      <div class="card list-card">
        <div v-loading="payLoading" class="list-scroll">
          <div v-if="payEmpty" class="empty-state">
            <p>还没有支付记录，续租支付后会在这里留痕</p>
          </div>

          <article v-for="row in payRecords" v-else :key="row.paymentNo" class="pay-row">
            <div class="pay-main">
              <h2 class="pay-title">{{ paymentChannelLabels[row.channel] }} · 续租 {{ row.months }} 个月</h2>
              <p class="pay-meta">
                <span class="mono">单号 {{ row.paymentNo }}</span>
                <span>创建 {{ formatDateTime(row.createdAt) }}</span>
                <span>支付时间 {{ row.paidAt ? formatDateTime(row.paidAt) : '—' }}</span>
              </p>
            </div>
            <div class="pay-side">
              <span class="pay-amount">{{ money(row.amount) }}</span>
              <span class="status-pill" :data-status="row.status">
                {{ paymentStatusLabels[row.status] }}
              </span>
            </div>
          </article>
        </div>

        <Pagination
          v-model:page="payQuery.page"
          v-model:size="payQuery.size"
          :total="payTotal"
          @update:page="loadPayments"
          @update:size="loadPayments"
        />
      </div>
    </template>

    <!-- 续租支付弹窗（三步：选月数渠道 → 支付 → 确认结果） -->
    <LeaseRenewDialog v-model="renewVisible" :lease="renewTarget" @success="handleRenewSuccess" />
  </section>
</template>

<style scoped>
.lease-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.tab-bar {
  display: flex;
}

.card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.list-card {
  display: flex;
  flex-direction: column;
}

.list-scroll {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-height: 280px;
}

/* 租约卡片板：白卡竖排（mockup v1 口径：大圆角、极浅边框、无重阴影） */
.lease-board {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 280px;
}

.empty-state {
  margin: auto;
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  padding: var(--spacing-xxl) 0;
}

.lease-card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.lease-card:hover {
  border-color: var(--color-primary-light);
  box-shadow: var(--shadow-sm);
}

/* 社区徽章行：小图标 + 社区名（解决租约不显示社区信息） */
.card-community {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-lg);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.card-community svg {
  width: 14px;
  height: 14px;
  color: var(--color-primary);
}

.community-name {
  font-weight: var(--font-weight-medium);
}

.lease-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  cursor: pointer;
}

.lease-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.lease-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

/* 三列信息：租期 / 月租 / 押金 */
.lease-info {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs) var(--spacing-lg);
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.info-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.info-value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.lease-side {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

/* 到期标注：30 天内橙 / 已过期红 */
.expiry-badge {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.expiry-badge[data-flag='EXPIRING'] {
  background: rgba(245, 158, 11, 0.12);
  color: var(--color-warning);
}

.expiry-badge[data-flag='EXPIRED'] {
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

/* 状态胶囊：在租/支付成功绿，待审核/待支付黄，搬出/归档/已关闭灰，驳回红 */
.status-pill {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.status-pill[data-status='ACTIVE'],
.status-pill[data-status='SUCCESS'] {
  background: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.status-pill[data-status='PENDING'] {
  background: rgba(245, 158, 11, 0.12);
  color: var(--color-warning);
}

.status-pill[data-status='MOVED_OUT'],
.status-pill[data-status='ARCHIVED'],
.status-pill[data-status='CLOSED'] {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.status-pill[data-status='REJECTED'] {
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

.lease-action {
  flex-shrink: 0;
}

.lease-arrow {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: var(--color-text-disabled);
  transition: transform 0.2s ease;
}

.lease-arrow.is-open {
  transform: rotate(180deg);
}

/* 同屋历史租约折叠 */
.history-fold {
  border-top: 1px dashed var(--color-border);
}

.history-toggle {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  background: none;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  cursor: pointer;
}

.history-toggle:hover {
  color: var(--color-primary);
}

.history-chevron {
  width: 12px;
  height: 12px;
  transition: transform 0.2s ease;
}

.history-chevron.is-open {
  transform: rotate(180deg);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: 0 var(--spacing-lg) var(--spacing-md);
}

.history-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.history-rent {
  flex: 1;
}

/* 展开明细面板 */
.lease-expand {
  padding: var(--spacing-md) var(--spacing-lg);
  background: var(--color-bg);
  border-top: 1px dashed var(--color-border);
}

.expand-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--spacing-sm) var(--spacing-md);
  margin: 0;
}

.expand-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-xs);
}

.expand-item dd {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.expand-remark {
  grid-column: 1 / -1;
}

.expand-remark dd {
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
  word-break: break-word;
}

.mono {
  font-family: var(--font-family-mono);
}

/* 支付记录行 */
.pay-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.pay-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.pay-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.pay-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.pay-side {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.pay-amount {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  font-family: var(--font-family-mono);
}

/* 响应式：窄屏允许行内元素换行 */
@media (max-width: 700px) {
  .lease-row {
    flex-wrap: wrap;
  }

  .lease-title {
    white-space: normal;
  }

  .pay-row {
    flex-wrap: wrap;
  }
}
</style>
