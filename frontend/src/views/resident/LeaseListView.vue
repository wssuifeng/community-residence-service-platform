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
 * 我的租约（R61，v1.4）：「租约｜支付记录」双 tab。
 * 租约 tab：本人租约卡列表（居民数据权限由后端承载，不传 tenantId），行点击展开明细；
 * ACTIVE 租约可发起在线续租（三步支付弹窗见 LeaseRenewDialog）。到期标注按今天
 * 前端计算（仅 ACTIVE：止期 30 天内「即将到期」/已过止期「已到期」），与后端
 * expiryFlag 同口径独立判定。支付记录 tab：listMyPayments 分页流水。
 * tab 状态写入 URL query（?tab=payments）便于外跳回显。
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
/** 行点击展开的租约 id（单开模式） */
const expandedId = ref<number | null>(null)

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

    <!-- 租约 tab：行点击展开明细，ACTIVE 可续租 -->
    <template v-if="tab === 'lease'">
      <div class="card list-card">
        <div v-loading="loading" class="list-scroll">
          <div v-if="isEmpty" class="empty-state">
            <p>还没有租约记录，入住申请通过后即可在这里查看</p>
          </div>

          <article v-for="row in records" v-else :key="row.id" class="lease-item">
            <div
              class="lease-row"
              role="button"
              tabindex="0"
              @click="toggleExpand(row)"
              @keydown.enter="toggleExpand(row)"
            >
              <div class="lease-main">
                <h2 class="lease-title">{{ row.houseLocation }}</h2>
                <p class="lease-meta">
                  <span class="lease-term">{{ row.startDate }} ~ {{ row.endDate }}</span>
                  <span>月租 {{ money(row.monthlyRent) }}/月</span>
                  <span>押金 {{ money(row.deposit) }}</span>
                </p>
              </div>
              <span v-if="expiryOf(row)" class="expiry-badge" :data-flag="expiryOf(row)">
                {{ expiryOf(row) === 'EXPIRING' ? '即将到期' : '已到期' }}
              </span>
              <span class="status-pill" :data-status="row.status">
                {{ leaseStatusLabels[row.status] }}
              </span>
              <el-button
                v-if="row.status === 'ACTIVE'"
                class="lease-action"
                type="primary"
                plain
                size="small"
                round
                @click.stop="openRenew(row)"
              >
                续租
              </el-button>
              <svg
                class="lease-arrow"
                :class="{ 'is-open': expandedId === row.id }"
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

            <div v-if="expandedId === row.id" class="lease-expand">
              <dl class="expand-grid">
                <div class="expand-item">
                  <dt>租客</dt>
                  <dd>{{ row.tenantName }}</dd>
                </div>
                <div class="expand-item">
                  <dt>登记时间</dt>
                  <dd class="mono">{{ formatDateTime(row.createdAt) }}</dd>
                </div>
                <div class="expand-item">
                  <dt>到期说明</dt>
                  <dd>{{ expiryText(row) }}</dd>
                </div>
                <div class="expand-item expand-remark">
                  <dt>备注</dt>
                  <dd>{{ row.remark?.trim() || '无' }}</dd>
                </div>
              </dl>
            </div>
          </article>
        </div>

        <Pagination
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

.empty-state {
  margin: auto;
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  padding: var(--spacing-xxl) 0;
}

/* 租约卡：行 + 展开面板 */
.lease-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.lease-item:hover {
  border-color: var(--color-primary-light);
  box-shadow: var(--shadow-sm);
}

.lease-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  cursor: pointer;
}

.lease-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.lease-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lease-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.lease-term {
  font-family: var(--font-family-mono);
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

/* 展开明细面板 */
.lease-expand {
  padding: var(--spacing-md);
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
