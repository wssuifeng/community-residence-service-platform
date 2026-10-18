<script setup lang="ts">
import { getMyLeaseSummaries, type LeaseSummary } from '../api/summaries'
import { useLiveRefresh } from '@/common/web/composables/useLiveRefresh'
import LeaseTermsPanel from '@/common/lease/components/LeaseTermsPanel.vue'
import { leaseDisplayLabel } from '@/common/lease/utils/leaseDisplay'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pagination from '@/common/web/components/Pagination.vue'
import LeaseRenewDialog from '@/resident/lease/components/LeaseRenewDialog.vue'
import LeaseFirstPaymentDialog from '@/resident/lease/components/LeaseFirstPaymentDialog.vue'
import LeaseMoveOutDialog from '@/resident/lease/components/LeaseMoveOutDialog.vue'
import AgreementSignPanel from '@/common/agreement/components/AgreementSignPanel.vue'
import LeaseTimeline from '@/common/lease/components/LeaseTimeline.vue'
import {
  getLeaseChanges,
  listDepositRefunds,
  listLeasePayments
} from '@/common/lease/api/lease'
import { formatDateTime } from '@/common/web/utils/date'

import {
  depositRefundStatusLabels,
  depositStatusLabels,
  leasePaymentTypeLabels,
} from '@/common/lease/types/lease'
import { leaseAgreementSignStatusLabels } from '@/common/agreement/types/agreement'
import type {
  DepositStatus,
  IDepositRefund,
  ILeaseChange,
  ILeasePayment,
  ILeaseRecord,
  LeasePaymentType,
  PaymentChannel,
  PaymentStatus
} from '@/common/lease/types/lease'
import type { LeaseAgreementSignStatus } from '@/common/agreement/types/agreement'

/**
 * 我的租约（R61 v1.4 / R66~R71 批次 f / R72~R73 批次 h）：租住签约闭环的居民侧主入口。
 * 「租约｜租约明细」双 tab。租约 tab 按「社区 + 房屋」分组为卡片：
 * 组内取当前租约（在租优先、其次待签约、再次起始日期最新）作主卡，
 * 其余折叠为「历史租约 N 条」（同一房屋多次租住不再重复铺开）。
 *
 * 主卡动作按状态分流（一一对应后端端点，前端不做状态猜测）：
 * - 待签约 TO_SIGN：查看管理方发出的具体协议并确认同意；
 * - 已生效 ACTIVE：支付首期费用（/first-payments）、租期延长申请与合同内缴租、申请退租（/move-out-applications）；
 * - 已搬出 MOVED_OUT：只读 + 押金状态与押金退还记录（R72，居民侧只读不可发起）。
 *
 * 押金与明细（R72/R73）：
 * - 卡片与展开区展示 `deposit_status` 五态徽标（未缴/已缴/退还中/已退还/已抵扣，OFFSET 附抵扣说明）；
 * - 展开区列出本人租约的押金退还流水（金额/渠道/状态/失败原因/发起与完成时间）；
 * - 「租约明细」tab 按租约合并三源（状态流转 + 支付流水 + 押金退还）渲染时间线，见 LeaseTimeline。
 *
 * 深链：`?leaseId=` 由消息中心「租约」类通知跳转落地，本页负责翻页定位该租约、
 * 自动展开并高亮该卡片（完整列表页跳转，定位失败时提示而非静默停首屏）。
 * 到期标注按今天前端计算（仅 ACTIVE：止期 30 天内「即将到期」/已过止期「已到期」），
 * 与后端 expiryFlag 同口径独立判定。tab 状态写入 URL query。
 */

const route = useRoute()
const router = useRouter()

/* ------------------------------ tab 状态（URL query 回显） ------------------------------ */

type ListTab = 'lease' | 'payments'

const tab = ref<ListTab>(route.query.tab === 'payments' ? 'payments' : 'lease')

function handleTabChange(value: string | number | boolean | undefined): void {
  tab.value = value === 'payments' ? 'payments' : 'lease'
  /* replace 免得来回切换污染历史栈；query 仅租约明细 tab 携带 */
  router.replace({
    query: tab.value === 'payments' ? { tab: 'payments' } : undefined
  })
  if (tab.value === 'payments') {
    ensureDetailLoaded()
  } else {
    loadLeases()
  }
}

const headSub = computed(() =>
  tab.value === 'payments'
    ? '首期与续期支付流水，以及押金退还记录与状态流转合并的租约明细时间线'
    : '本人租约的租期与租金：待签约先确认协议、再付首期；成功后正式生效，可续租或申请退租'
)

/* ------------------------------ 租约 tab ------------------------------ */

const query = reactive({ page: 1, size: 10 })
const total = ref(0)
const records = ref<ILeaseRecord[]>([])
const summaries = ref<Record<number, LeaseSummary>>({})
let listVersion=0
onBeforeUnmount(() => { listVersion++ })
const loading = ref(false)
/** 主卡点击展开的租约 id（单开模式） */
const expandedId = ref<number | null>(null)
/** 展开历史折叠的分组 key（单开模式） */
const expandedHistory = ref<string | null>(null)

/** 深链定位目标（`?leaseId=`）：命中后高亮该租约卡片并自动展开 */
const focusLeaseId = ref<number | null>(null)

async function loadLeases(): Promise<void> {
  const current=++listVersion
  loading.value = true
  try {
    const cards = await getMyLeaseSummaries({ page: query.page, size: query.size })
    if(current!==listVersion) return
    const data={...cards,records:cards.records.map(card=>card.lease)}
    summaries.value=Object.fromEntries(cards.records.map(card=>[card.lease.id,card.summary]))
    records.value = data.records
    total.value = data.total
    /* 翻页后展开行可能已不在当前页，统一收起避免错位 */
    if (expandedId.value !== null && !data.records.some((row) => row.id === expandedId.value)) {
      expandedId.value = null
    }

  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载租约列表失败')
  } finally {
    if(current===listVersion) loading.value = false
  }
}

/* ------------------------------ 各租约支付记录（R71） ------------------------------
 * 首期成功使待签约租约生效、不顺延租期；支付凭据取自支付单。
 * 故卡片级「首期已支付 / 待支付首期费用」与展开区支付记录都取自本表。
 */

/** leaseId → 该租约支付单（键存在即表示已取回，用于区分「无成功首期单」与「未取到」） */
const leasePayments = ref<Record<number, ILeasePayment[]>>({})

function paymentsOf(row: ILeaseRecord): ILeasePayment[] {
  return leasePayments.value[row.id] ?? []
}

function paymentsUnknown(row: ILeaseRecord): boolean {
  return summaries.value[row.id] == null
}

/** 已支付的首期单（成功态）：卡片徽标与「首期已支付」文案的唯一依据 */
function firstPaymentOf(row: ILeaseRecord): Pick<ILeasePayment,'amount'|'paidAt'> | null {
  const summary=summaries.value[row.id]
  return summary?.firstPaymentSucceeded ? {amount:summary.firstPaymentPaidAmount ?? 0,paidAt:summary.firstPaymentPaidAt ?? undefined} : null
}

function firstPaymentText(row: ILeaseRecord): string | null {
  const paid = firstPaymentOf(row)
  return paid
    ? `首期已支付 ${money(paid.amount)}${paid.paidAt ? ` · ${formatDateTime(paid.paidAt)}` : ''}`
    : null
}

/**
 * 待支付首期：待签约且SIGNED或旧ACTIVE、支付单已取回、无成功首期单，且**止期未过**（DEF-069）。
 * 止期已过的租约业务上不再收首期费用（首期只对起租有效），故不再提供入口，
 * 由 firstPayBlockedText 说明原因；未取到支付单时不判定，避免误报。
 */
function firstPaymentDue(row: ILeaseRecord): boolean {
  return (
    (row.status === 'ACTIVE' || (row.status === 'TO_SIGN' && row.agreementStatus === 'SIGNED')) &&
    !paymentsUnknown(row) &&
    firstPaymentOf(row) === null &&
    expiryOf(row) !== 'EXPIRED'
  )
}

/**
 * 已到期租约的首期支付拦截说明（DEF-069）：止期已过时卡片不再出现「待支付首期费用」
 * （点了必然与业务口径冲突），改为在动作区说明原因与可行动作；续租/申请退租入口不受影响。
 * 已支付（成功首期单）的历史事实照常由绿色徽标展示，不走本说明。
 */
function firstPayBlockedText(row: ILeaseRecord): string | null {
  if (row.status !== 'ACTIVE' || expiryOf(row) !== 'EXPIRED' || firstPaymentOf(row)) return null
  return '首期费用不可再支付：租约止期已过；如需继续租住请续租，或办理退租。'
}

/** 到期徽标 tooltip：两种日期标注都提示「按止期派生、非状态流转」（DEF-069） */
function expiryBadgeTitle(row: ILeaseRecord): string {
  const flag = expiryOf(row)
  if (flag === null) return ''
  return `${flag === 'EXPIRED' ? '已到期' : '即将到期'}（${expiryText(row)}）：日期标注，按止期与当天计算，非状态流转`
}

/**
 * 「已到期」与租约状态的关系说明（DEF-069）：到期是**按止期与当天派生的日期标注**，
 * 不是状态流转值，租约状态仍为「在租」——两者并列并非自相矛盾，故在卡片上写清楚。
 * 仅在止期已过（用户反馈的并列场景）时展示，避免每张卡都挂一行说明。
 */
function expiryAnnotationText(row: ILeaseRecord): string | null {
  if (expiryOf(row) !== 'EXPIRED') return null
  const base = '「已到期」为日期标注（止期已过），非状态流转，租约状态仍为「在租」'
  return firstPaymentOf(row) ? `${base}；首期费用已支付，不受止期影响。` : `${base}。`
}

/* ------------------------------ 深链定位（?leaseId=） ------------------------------ */

/**
 * 深链定位：通知跳转只带租约 ID，无法保证目标在当前分页，故按页查找
 * （服务端分页上限内翻页，护栏 20 页防死循环）。
 * 命中：切到租约 tab、展开该卡并置高亮；未命中：提示后停在列表首屏（不静默失败）。
 */
let locating = false

async function locateLease(leaseId: number): Promise<void> {
  if (locating) return
  locating = true
  focusLeaseId.value = leaseId
  try {
    if (tab.value !== 'lease') {
      tab.value = 'lease'
      router.replace({ query: { tab: 'lease', leaseId: String(leaseId) } })
    }
    /* 命中当前已加载数据则免请求 */
    if (records.value.some((row) => row.id === leaseId)) {
      revealLease(leaseId)
      return
    }
    const targetPage = await findLeasePage(leaseId)
    if (targetPage === null) {
      ElMessage.warning('未找到该通知对应的租约，可能已归档或不属于当前账号')
      return
    }
    query.page = targetPage
    await loadLeases()
    revealLease(leaseId)
  } finally {
    locating = false
  }
}

/** 逐页查找租约所在页（返回 null 表示未找到）；不复用 loadLeases，避免定位过程反复改写列表状态 */
async function findLeasePage(leaseId: number): Promise<number | null> {
  const size = query.size
  for (let page = 1; page <= 20; page += 1) {
    try {
      const cards = await getMyLeaseSummaries({ page, size })
      const data={...cards,records:cards.records.map(card=>card.lease)}
      if (data.records.some((row) => row.id === leaseId)) return page
      if (data.records.length === 0 || page * size >= data.total) return null
    } catch {
      return null
    }
  }
  return null
}

function revealLease(leaseId: number): void {
  expandedId.value = leaseId
  /* 等卡片渲染后滚动到可视区，长列表下用户不必自己找 */
  void nextTick(() => {
    document.getElementById(`lease-card-${leaseId}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
}

function clearFocus(): void {
  focusLeaseId.value = null
  const query = { ...route.query }
  delete query.leaseId
  router.replace({ query })
}

/** 路由 query 变化即重新定位（Hub 内 Tab 往返不重新挂载本组件，只能靠 watch） */
watch(
  () => route.query.leaseId,
  (value) => {
    const leaseId = Number(value)
    if (Number.isInteger(leaseId) && leaseId > 0) {
      void locateLease(leaseId)
    } else {
      focusLeaseId.value = null
    }
  }
)

function toggleHistory(key: string): void {
  expandedHistory.value = expandedHistory.value === key ? null : key
}

/** 协议签约状态标签（NONE 不展示，避免每张卡都挂一个无信息量的「未发起协议」） */
function agreementLabel(row: ILeaseRecord): string | null {
  const status = row.agreementStatus
  if (!status || status === 'NONE') return null
  return leaseAgreementSignStatusLabels[status as LeaseAgreementSignStatus]
}

/** 需要居民处理的签约状态：待确认 / 单方已确认（另一方还没签） */
function agreementNeedsAction(row: ILeaseRecord): boolean {
  return row.agreementStatus === 'PENDING' || row.agreementStatus === 'PARTIAL'
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

/**
 * 组内主卡排序：在租 0 < 待签约 1 < 其余 2（在租最需日常操作，待签约次之需确认承租），
 * 同级按起始日期新在前——同屋多次租住时主卡始终是当前这一份。
 */
function leaseRank(row: ILeaseRecord): number {
  if (row.status === 'ACTIVE') return 0
  if (row.status === 'TO_SIGN') return 1
  return 2
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

/** 深链高亮：仅在目标租约确实是当前主卡时点亮（避免历史折叠里的同号误亮） */
function isFocused(row: ILeaseRecord): boolean {
  return focusLeaseId.value !== null && focusLeaseId.value === row.id
}

/* ------------------------------ 续租 / 首期支付 / 退租入口 ------------------------------ */

const termsVisible = ref(false)
const termsTarget = ref<ILeaseRecord | null>(null)
const paymentPurpose = ref<'RENT'|'RENEWAL'>('RENT')
const renewVisible = ref(false)
const renewTarget = ref<ILeaseRecord | null>(null)



/** 合同与租期成功后止期延展，时间线新增一条续期条目，故明细缓存一并失效 */
function handleRenewSuccessDetail(): void {
  loadLeases()
  void refreshLeasePayments(renewTarget.value?.id)
  refreshDetail(renewTarget.value?.id)
}

const firstPayVisible = ref(false)
const firstPayTarget = ref<ILeaseRecord | null>(null)

function openFirstPayment(row: ILeaseRecord): void {
  firstPayTarget.value = row
  firstPayVisible.value = true
}

/* ---------- ① 租约群聊入口（R68/R74） ---------- */

/** 进入本租约的沟通群（会话详情路由既有，页面计数不变） */
function openLeaseChat(row: ILeaseRecord): void {
  if (!row.conversationId) return
  router.push(`/resident/conversations/${row.conversationId}`)
}

/* ---------- ② 费用与支付入口（任何状态都可见，不整条消失） ---------- */

interface PaymentEntry {
  /** 按钮文案（保持与既有入口命名一致：待支付首期费用 / 重试加载 / 支付首期费用） */
  label: string
  /** FIRST=首期付费（首月租金+押金一次收）；RETRY=支付单取数失败重试回查；NONE=无可支付项（入口保留为禁用态 + 原因） */
  action: 'FIRST' | 'RETRY' | 'NONE'
  /** 入口旁的说明（可支付时讲口径，不可支付时讲原因） */
  note: string
}

/**
 * 支付入口解析（R71 首期 / R61 续租 + 用户口径「押金/租金就放首期付费里，不做独立支付」）：
 * 首期费用 = 首月租金 + 押金，一次收取并激活租约；续租按所选月数支付租金，不重复激活。
 *
 * 结清口径只认 FIRST（OCR DEF-111）：押金只随首期单收取，续租 RENEWAL 成功不代表首期已收；
 * 原「任一 SUCCESS 即结清」会把只付过续租的在租约误判已结清、隐藏首期入口，连带堵死押金链路。
 *
 * 入口始终显示：TO_SIGN+SIGNED 未结清且未过止期可付首期；旧 ACTIVE 可兼容补缴。
 * - 未确认协议 → 引导阅读具体正文；确认后才可点首期；
 * - 支付单取不回（unknown）→ 不给可点支付动作，改「支付记录加载失败」+ 重试入口
 *   （结清与否无法判定时盲发起只会把错误甩给后端）；
 * - 已结清 / 止期已过 / 未生效 / 终态 → 入口仍在，**置禁用并给出原因**；
 *   续租动作用卡片上既有的「续租」按钮承载（note 指引）。
 */
function paymentEntryOf(row: ILeaseRecord): PaymentEntry {
  const unknown = paymentsUnknown(row)
  const expired = expiryOf(row) === 'EXPIRED'
  /* 结清只认 FIRST 成功单（DEF-111）：与 firstPaymentDue / 首期徽标同一口径 */
  const settled = !unknown && firstPaymentOf(row) !== null

  if (unknown) {
    return {
      label: '重试加载',
      action: 'RETRY',
      note: '支付记录加载失败，暂无法判定首期是否结清；请重试。'
    }
  }
  if (settled) {
    return {
      label: '支付首期费用',
      action: 'NONE',
      note:
        row.status === 'ACTIVE'
          ? '首期已结清；普通缴租不延长租期，需要延长时提交租期延长申请。'
          : '首期费用已结清，无可支付项；历史支付记录仍在下方可查。'
    }
  }
  if (row.status === 'ACTIVE' || (row.status === 'TO_SIGN' && row.agreementStatus === 'SIGNED')) {
    if (summaries.value[row.id]?.actions.firstPayment.enabled === false && !expired) return { label:'支付首期费用', action:'NONE', note:summaries.value[row.id].actions.firstPayment.reason }
    if (!expired) {
      return {
        label: '待支付首期费用',
        action: 'FIRST',
        note: summaries.value[row.id]?.actions.firstPayment.reason ?? '首期按约定首付月数加一次押金，成功后正式生效。' 
      }
    }
    return {
      label: '支付首期费用',
      action: 'NONE',
      note: '止期已过：首期费用不可再支付；如需继续租住请用「续租」，或办理退租。'
    }
  }
  if (row.status === 'TO_SIGN') {
    return {
      label: '支付首期费用',
      action: 'NONE',
      note: '租约尚未生效：请先查看并同意管理方发出的具体协议，再支付首期费用；两者完成后才正式生效。'
    }
  }
  if (row.status === 'PENDING') {
    return {
      label: '支付首期费用',
      action: 'NONE',
      note: '待审核租约暂不可支付：审核通过后可在此支付首期费用。'
    }
  }
  return {
    label: '支付首期费用',
    action: 'NONE',
    note: '租约已结束，无可支付项；历史支付记录仍在下方可查。'
  }
}

/** 支付入口点击：FIRST 打开首期支付弹窗；RETRY 重取支付单恢复结清判定；禁用态不做事 */
function handlePaymentEntry(row: ILeaseRecord): void {
  const entry = paymentEntryOf(row)
  if (entry.action === 'FIRST') {
    openFirstPayment(row)
  } else if (entry.action === 'RETRY') {
    void retryLoadPayments(row)
  }
}

/** unknown 兜底重试：单独重取该租约支付单；失败如实提示，成功后入口按真实结清态重渲 */
async function retryLoadPayments(_row: ILeaseRecord): Promise<void> {
  try {
    await loadLeases()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '支付记录加载失败，请稍后重试')
  }
}

/** 租金支付状态文案：已存在任一成功支付单即视为已支付（首期或续租） */
function rentPaidState(row: ILeaseRecord): string {
  if (paymentsUnknown(row)) return '状态待刷新'
  const summary=summaries.value[row.id]
  return summary?.paidRentMonths != null ? `已缴 ${summary.paidRentMonths} 个月，尚余 ${summary.unpaidRentMonths} 个月` : '历史覆盖待核对'
}

/** 首期成功后回读租约正式生效状态、关系及支付记录 */
function handleFirstPaySuccess(): void {
  loadLeases()
  void refreshLeasePayments(firstPayTarget.value?.id)
  refreshDetail(firstPayTarget.value?.id)
}

/**
 * 弹窗打开回查即判定「首期已支付」（DEF-068）：刷新支付记录并展开该租约卡片，
 * 把用户直接落到卡片内的支付记录区块（弹窗已就自身结论给出说明）。
 */
function handleViewFirstPayments(): void {
  const target = firstPayTarget.value
  if (!target) return
  loadLeases()
  revealLease(target.id)
}

const moveOutVisible = ref(false)
const moveOutTarget = ref<ILeaseRecord | null>(null)

function openMoveOut(row: ILeaseRecord): void {
  moveOutTarget.value = row
  moveOutVisible.value = true
}

/** 退租申请提交成功后租约状态未变（待管理方审核），刷新列表即可 */
function handleMoveOutSuccess(): void {
  loadLeases()
}


/* ------------------------------ 租约明细 tab（押金 + 三源时间线，R72/R73） ------------------------------ */

const paymentChannelLabels: Record<PaymentChannel, string> = {
  ALIPAY: '支付宝',
  WECHAT: '微信支付'
}
const paymentStatusLabels: Record<PaymentStatus, string> = {
  PENDING: '待支付',
  SUCCESS: '支付成功',
  CLOSED: '已关闭'
}

/**
 * 支付单类型标签：后端 V20 起返回 paymentType；
 * 存量数据缺省按「续期」展示（V20 之前仅有合同与租期单，该兜底口径与历史一致）。
 */
function paymentTypeOf(row: ILeasePayment): LeasePaymentType {
  return row.paymentType ?? 'RENEWAL'
}

/**
 * 单租约明细缓存（按租约 ID 归档）：押金退还流水 + 变更历史。
 * 支付流水**不在此处**——卡片徽标与展开区支付记录已由 `leasePayments` 统一持有
 * （见「各租约支付记录」一节），时间线直接复用该表，避免同一端点两条缓存漂移。
 */
interface LeaseDetailState {
  refunds: IDepositRefund[]
  changes: ILeaseChange[]
  loading: boolean
  /** 押金退还流水取数失败：区分「无记录」与「取不到」，避免误报「未退还」 */
  refundsFailed: boolean
}

const detailCache = reactive<Record<number, LeaseDetailState>>({})
/** 明细 tab 当前查看的租约（缺省取首个租约，深链 leaseId 优先） */
const detailLeaseId = ref<number | null>(null)

const detailLease = computed<ILeaseRecord | null>(
  () => records.value.find((row) => row.id === detailLeaseId.value) ?? records.value[0] ?? null
)

const detailState = computed<LeaseDetailState | null>(() => {
  const id = detailLease.value?.id
  return id == null ? null : (detailCache[id] ?? null)
})

async function loadLeaseDetail(leaseId: number): Promise<void> {
  /* 已有缓存不重复拉取（切换频繁，明细为只读快照；支付/退还动作后由 refreshDetail 强制刷新） */
  if (detailCache[leaseId] && !detailCache[leaseId].loading) return
  detailCache[leaseId] = { refunds: [], changes: [], loading: true, refundsFailed: false }
  const [refunds, changes] = await Promise.all([
    listDepositRefunds(leaseId).catch(() => 'FAILED' as const),
    getLeaseChanges(leaseId).catch(() => [] as ILeaseChange[]),
    refreshLeasePayments(leaseId)
  ])
  detailCache[leaseId] = {
    refunds: refunds === 'FAILED' ? [] : refunds,
    changes,
    loading: false,
    refundsFailed: refunds === 'FAILED'
  }
}

/** 明细 tab 进入/切换租约时确保已取数 */
function ensureDetailLoaded(): void {
  const id = detailLease.value?.id
  if (id == null) return
  void loadLeaseDetail(id)
}

function selectDetailLease(id: number): void {
  detailLeaseId.value = id
  void loadLeaseDetail(id)
}

/** 押金支付或退还后强制刷新该租约明细（退还流水与变更历史缓存失效） */
function refreshDetail(leaseId?: number): void {
  const id = leaseId ?? detailLease.value?.id
  if (id == null) return
  delete detailCache[id]
  void loadLeaseDetail(id)
}

/** 时间线三源之一（支付流水）复用卡片级缓存：这里按租约单独补取一次 */
async function refreshLeasePayments(leaseId?: number): Promise<void> {
  const target = leaseId ?? detailLease.value?.id
  if (target == null) return
  try {
    leasePayments.value[target] = await listLeasePayments(target)
  } catch {
    /* 取不到时保留旧值（时间线降级为两源），不落空键以免误报「无支付记录」 */
  }
}

/* --------------------- 押金状态展示（R72 §3.1.1） --------------------- */

/**
 * 押金状态：后端未返回时按「未缴」兜底——不猜「已缴」，
 * 否则会在未真正收到押金时给出「可退还」的错觉。
 */
function depositStatusOf(row: ILeaseRecord): DepositStatus {
  return row.depositStatus ?? 'UNPAID'
}

function depositLabel(row: ILeaseRecord): string {
  return depositStatusLabels[depositStatusOf(row)]
}

function depositTone(row: ILeaseRecord): string {
  return depositStatusOf(row)
}

/** 抵扣说明（仅 OFFSET 有值）：横幅展示，避免徽标「已抵扣」无从解释 */
function depositOffsetRemark(row: ILeaseRecord): string {
  return row.depositOffsetRemark?.trim() ?? ''
}

/** 押金退还流水（明细 tab 与展开区共用）：按发起时间降序，接口层已保证，这里再兜一次 */
function refundsOf(leaseId: number): IDepositRefund[] {
  const list = detailCache[leaseId]?.refunds ?? []
  return [...list].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
}

/**
 * 累计已成功退还金额（R72 §3.1.1 要求可见）：
 * 只累加 `SUCCESS` 的退还单——`FAILED` 表示渠道退款未成功，金额并未退回，
 * 计入会让居民误以为钱已到账。
 */
function refundedAmountOf(leaseId: number): number {
  return refundsOf(leaseId)
    .filter((item) => item.status === 'SUCCESS')
    .reduce((sum, item) => sum + (item.amount ?? 0), 0)
}

function depositRefundTone(status: IDepositRefund['status']): 'completed' | 'pending' | 'failed' {
  if (status === 'SUCCESS') return 'completed'
  if (status === 'PENDING') return 'pending'
  return 'failed'
}

/** 押金退还流水取数失败（区分「无记录」与「取不到」） */
function refundsFailedOf(row: ILeaseRecord): boolean {
  return detailCache[row.id]?.refundsFailed ?? false
}

/** 展开卡片时按需取该租约明细（押金流水与时间线同源，取一次共用） */
function handleExpand(row: ILeaseRecord): void {
  const next = expandedId.value === row.id ? null : row.id
  expandedId.value = next
  if (next !== null) void loadLeaseDetail(row.id)
}

useLiveRefresh(loadLeases, ['lease','agreement','payment'])

onMounted(() => {
  const deepLinkId = Number(route.query.leaseId)
  const hasDeepLink = Number.isInteger(deepLinkId) && deepLinkId > 0
  /* 深链优先级最高：明细 tab 的缺省租约与定位目标都取它 */
  if (hasDeepLink) {
    detailLeaseId.value = deepLinkId
    focusLeaseId.value = deepLinkId
  }
  /* 带 leaseId 深链时先渲染租约 tab（定位目标在租约列表），明细数据随后按需拉取 */
  const wantDetail = tab.value === 'payments' && !hasDeepLink
  void loadLeases().then(() => {
    if (wantDetail) ensureDetailLoaded()
  })
  if (hasDeepLink) {
    void locateLease(deepLinkId)
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
        <el-radio-button value="payments">租约明细</el-radio-button>
      </el-radio-group>
      <!-- 深链定位提示条：仅从通知跳转落地时出现，可一键回到全量列表 -->
      <div v-if="focusLeaseId !== null" class="focus-bar" role="status">
        <span>已定位通知对应的租约（ID {{ focusLeaseId }}）</span>
        <el-button text type="primary" size="small" @click="clearFocus">查看全部租约</el-button>
      </div>
    </div>

    <!-- 租约 tab：社区+房屋分组卡片，同屋历史折叠 -->
    <template v-if="tab === 'lease'">
      <div v-loading="loading" class="lease-board">
        <div v-if="isEmpty" class="empty-state">
          <p>还没有租约记录，入住申请通过后即可在这里查看</p>
        </div>

        <article
          v-for="group in leaseGroups"
          v-else
          :id="`lease-card-${group.current.id}`"
          :key="group.key"
          class="lease-card"
          :class="{ 'is-focused': isFocused(group.current) }"
        >
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
            @click="handleExpand(group.current)"
            @keydown.enter="handleExpand(group.current)"
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
              <!-- 到期标注带 tooltip：说明它按止期与当天派生，不是状态流转值（DEF-069） -->
              <span
                v-if="expiryOf(group.current)"
                class="expiry-badge"
                :data-flag="expiryOf(group.current)"
                :title="expiryBadgeTitle(group.current)"
              >
                {{ expiryOf(group.current) === 'EXPIRING' ? '即将到期' : '已到期' }}
              </span>
              <span
                v-if="agreementLabel(group.current)"
                class="agreement-badge"
                :class="{ 'is-action': agreementNeedsAction(group.current) }"
              >
                协议{{ agreementLabel(group.current) }}
              </span>
              <span class="status-pill" :data-status="group.current.status">
                {{ summaries[group.current.id]?.displayStatus ?? leaseDisplayLabel(group.current) }}
              </span>

              <!-- 押金状态徽标（R72）：未缴/已缴/退还中/已退还/已抵扣，居民侧只读 -->
              <span
                class="deposit-pill"
                :data-deposit="depositTone(group.current)"
                :title="depositOffsetRemark(group.current) || undefined"
              >
                押金{{ depositLabel(group.current) }}
              </span>

              <!-- 首期费用状态（R71）：首期成功激活租约、不顺延租期，
                   故支付结果必须单独在卡片上呈现，否则居民付完款看不到任何反馈 -->
              <span v-if="firstPaymentText(group.current)" class="first-pay-pill">
                {{ firstPaymentText(group.current) }}
              </span>
              <span v-else-if="firstPaymentDue(group.current)" class="first-pay-pill is-due">
                待支付首期费用
              </span>
              <span v-else-if="firstPayBlockedText(group.current)" class="action-blocked-note">
                {{ firstPayBlockedText(group.current) }}
              </span>

              <!-- 「已到期」与「在租」并列的说明（DEF-069）：日期标注 ≠ 状态流转 -->
              <p v-if="expiryAnnotationText(group.current)" class="expiry-note">
                {{ expiryAnnotationText(group.current) }}
              </p>

              <!-- 支付入口区（R71/R61 + DEF-111）：**任何状态都可见**，不再整条消失；
                   可支付时点开首期付费弹窗，取数失败时给重试入口，无可支付项时保留禁用入口 + 原因说明 -->
              <el-button
                class="lease-action payment-entry"
                :type="paymentEntryOf(group.current).action === 'FIRST' ? 'warning' : 'primary'"
                :plain="paymentEntryOf(group.current).action !== 'FIRST'"
                :disabled="paymentEntryOf(group.current).action === 'NONE'"
                round
                size="small"
                @click.stop="handlePaymentEntry(group.current)"
              >
                {{ paymentEntryOf(group.current).label }}
              </el-button>
              <span class="payment-entry-note">{{ paymentEntryOf(group.current).note }}</span>

              <!-- 已生效（R71/R70）：续租、申请退租；首期支付入口已移到上方（所有状态可见） -->
              <template v-if="group.current.status === 'ACTIVE'">
                <el-button
                  class="lease-action"
                  round
                  size="small"
                  @click.stop="termsTarget=group.current; termsVisible=true"
                >
                  续租
                </el-button><el-button v-if="group.current.status==='ACTIVE'" :disabled="!summaries[group.current.id]?.actions.rentPayment.enabled" :title="summaries[group.current.id]?.actions.rentPayment.reason" @click.stop="renewTarget=group.current; paymentPurpose='RENT'; renewVisible=true">缴纳租金</el-button>
                <el-button
                  class="lease-action"
                  type="warning"
                  plain
                  round
                  size="small"
                  @click.stop="openMoveOut(group.current)"
                >
                  申请退租
                </el-button>
              </template>

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
                  {{ leaseDisplayLabel(row) }}
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

            <p v-if="group.current.status === 'TO_SIGN'" class="expand-cta-text">
              等待管理方发出协议后，在“查看协议”中阅读条款并确认同意；协议确认后继续保留房屋，首期费用支付成功后正式生效，无需额外确认承租。
            </p>

            <!-- 首期费用（R71）：首期成功激活租约，在此提供入口与支付结果 -->
            <div v-if="firstPaymentDue(group.current)" class="expand-cta">
              <p class="expand-cta-text">
                首期费用（首月租金 + 押金）尚未支付。协议确认且首期支付成功后正式生效，租期不顺延；
                支付结果记入下方支付记录。
              </p>
              <el-button
                type="warning"
                round
                size="small"
                @click.stop="openFirstPayment(group.current)"
              >
                去支付首期费用
              </el-button>
            </div>

            <!-- 止期已过（DEF-069）：不提供首期支付入口，在此说明原因；续租/退租入口保持可达 -->
            <p v-else-if="firstPayBlockedText(group.current)" class="expand-blocked">
              {{ firstPayBlockedText(group.current) }}
            </p>

            <!-- 押金状态与退还记录（R72）：居民侧只读——发起退还属管理端动作 -->
            <div class="lease-deposit">
              <h3 class="payments-title">
                押金
                <span class="deposit-pill" :data-deposit="depositTone(group.current)">
                  {{ depositLabel(group.current) }}
                </span>
                <span v-if="refundedAmountOf(group.current.id) > 0" class="deposit-refunded">
                  累计已退还 {{ money(refundedAmountOf(group.current.id)) }}
                </span>
              </h3>

              <!-- 抵扣说明（仅已抵扣）：徽标含义必须可解释 -->
              <p v-if="depositOffsetRemark(group.current)" class="deposit-offset">
                抵扣说明：{{ depositOffsetRemark(group.current) }}
              </p>

              <p v-if="refundsFailedOf(group.current)" class="payments-empty">
                押金退还记录暂不可用，请稍后刷新
              </p>
              <p v-else-if="refundsOf(group.current.id).length === 0" class="payments-empty">
                暂无押金退还记录：退租审核通过后由社区管理方发起原路退还
              </p>
              <ul v-else class="refund-list">
                <li v-for="item in refundsOf(group.current.id)" :key="item.id" class="refund-item">
                  <span class="refund-amount mono">{{ money(item.amount) }}</span>
                  <span class="refund-channel">{{ paymentChannelLabels[item.channel] }}</span>
                  <span class="status-pill" :data-status="depositRefundTone(item.status)">
                    {{ depositRefundStatusLabels[item.status] }}
                  </span>
                  <span class="refund-meta">
                    发起 {{ formatDateTime(item.createdAt) }} ·
                    完成 {{ item.refundedAt ? formatDateTime(item.refundedAt) : '—' }}
                  </span>
                  <!-- 失败原因必须可见（渠道失败 / 凭据未配置时管理方与居民都要能对上账） -->
                  <p v-if="item.failReason" class="refund-fail">失败原因：{{ item.failReason }}</p>
                </li>
              </ul>
            </div>

            <!-- 该租约支付记录（GET /leases/{id}/payments）：首期/续期、金额、状态、支付时间 -->
            <div class="lease-payments">
              <h3 class="payments-title">支付记录</h3>
              <p v-if="paymentsUnknown(group.current)" class="payments-empty">
                支付记录暂不可用，请稍后刷新
              </p>
              <p v-else-if="paymentsOf(group.current).length === 0" class="payments-empty">
                暂无支付记录：首期费用与合同与租期都会在此留痕
              </p>
              <ul v-else class="payments-list">
                <li v-for="item in paymentsOf(group.current)" :key="item.paymentNo" class="payment-item">
                  <el-alert v-if="item.settlementIssue" title="收到异常渠道结果，需管理方核对；不会自动恢复租约或重复收费" type="error" :closable="false" />
                  <span class="pay-type-badge" :data-type="paymentTypeOf(item)">
                    {{ leasePaymentTypeLabels[paymentTypeOf(item)] }}
                  </span>
                  <span class="payment-amount mono">{{ money(item.amount) }}</span>
                  <span class="status-pill" :data-status="item.status">
                    {{ paymentStatusLabels[item.status] }}
                  </span>
                  <span class="payment-meta">
                    {{ paymentChannelLabels[item.channel] }} · 创建 {{ formatDateTime(item.createdAt) }} ·
                    支付时间 {{ item.paidAt ? formatDateTime(item.paidAt) : '—' }}
                  </span>
                </li>
              </ul>
            </div>

            <!-- ① 租约群聊入口（R68/R74）：**仅当本租约已建群**（lease_record.conversation_id）时渲染；
                 无群不渲染（与工单「受理前不渲染」同口径），复用既有会话详情路由不新增页面 -->
            <div v-if="group.current.conversationId" class="lease-chat-entry">
              <h3 class="payments-title">租约群聊</h3>
              <p class="chat-entry-note">
                与社区管理方（派单后含服务人员）在本租约的沟通群内留言，消息实时同步。
              </p>
              <el-button type="primary" round size="small" @click.stop="openLeaseChat(group.current)">
                进入租约群聊
              </el-button>
            </div>

            <!-- ② 费用构成与支付入口：把「首期费用」拆成租金/押金两行明示，
                 并给出可点击的支付入口（押金与首月租金由首期支付单一次性收取——
                 后端 lease_payment.payment_type 仅 FIRST/RENEWAL，无单独押金端点） -->
            <div class="lease-charges">
              <h3 class="payments-title">费用与支付</h3>
              <ul class="charge-list">
                <li class="charge-item">
                  <span class="charge-name">月租金</span>
                  <span class="charge-amount mono">{{ money(group.current.monthlyRent) }}</span>
                  <span class="charge-state" :data-paid="(summaries[group.current.id]?.paidRentMonths ?? 0) > 0">
                    {{ rentPaidState(group.current) }}
                  </span>
                </li>
                <li class="charge-item">
                  <span class="charge-name">押金</span>
                  <span class="charge-amount mono">{{ money(group.current.deposit) }}</span>
                  <span class="charge-state" :data-paid="depositTone(group.current) === 'PAID'">
                    {{ depositLabel(group.current) }}
                  </span>
                </li>
              </ul>
              <p class="charge-note">
                {{ paymentEntryOf(group.current).note }}
              </p>
              <div class="charge-actions">
                <!-- 与卡片行同一入口（同一解析口径）：可支付时点开首期付费弹窗，
                     取数失败时重试回查，无独立押金支付——押金只随首期费用一并收取 -->
                <el-button
                  :type="paymentEntryOf(group.current).action === 'FIRST' ? 'warning' : 'primary'"
                  :plain="paymentEntryOf(group.current).action !== 'FIRST'"
                  :disabled="paymentEntryOf(group.current).action === 'NONE'"
                  round
                  size="small"
                  @click.stop="handlePaymentEntry(group.current)"
                >
                  {{ paymentEntryOf(group.current).label }}
                </el-button>
              </div>
            </div>

            <!-- 租赁协议：居民查看当前正文并确认一次，管理方发出时已同意。 -->
            <AgreementSignPanel
              :lease-id="group.current.id"
              :lease-status="group.current.status"
              role="RESIDENT"
              :community-id="group.current.communityId"
              @changed="loadLeases"
            />
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

    <!-- 租约明细 tab（R72/R73）：押金状态 + 按租约合并三源的时间线 -->
    <template v-else>
      <div class="detail-tab">
        <!-- 租约选择：明细以单份租约为单位（退还流水与时间线均按租约收敛） -->
        <div v-if="records.length > 0" class="detail-switch" role="tablist" aria-label="选择租约">
          <button
            v-for="row in records"
            :key="row.id"
            type="button"
            class="detail-switch-item"
            :class="{ active: detailLease?.id === row.id }"
            @click="selectDetailLease(row.id)"
          >
            <span class="switch-house">{{ row.houseLocation }}</span>
            <span class="switch-meta">
              {{ leaseDisplayLabel(row) }} · 押金{{ depositLabel(row) }}
            </span>
          </button>
        </div>

        <div v-if="!detailLease" class="card empty-state-block">
          <p>还没有租约记录，入住申请通过后即可在这里查看押金与明细流水</p>
        </div>

        <template v-else>
          <!-- 押金概览：状态徽标 + 实缴押金 + 累计已退 + 抵扣说明 -->
          <div class="card deposit-card">
            <div class="deposit-head">
              <h3 class="deposit-title">
                押金
                <span class="deposit-pill" :data-deposit="depositTone(detailLease)">
                  {{ depositLabel(detailLease) }}
                </span>
              </h3>
              <span class="deposit-house">{{ detailLease.houseLocation }}</span>
            </div>
            <dl class="deposit-grid">
              <div>
                <dt>押金登记金额</dt>
                <dd class="mono">{{ money(detailLease.deposit) }}</dd>
              </div>
              <div>
                <dt>累计已退还</dt>
                <dd class="mono">{{ money(refundedAmountOf(detailLease.id)) }}</dd>
              </div>
            </dl>
            <p v-if="depositOffsetRemark(detailLease)" class="deposit-offset">
              抵扣说明：{{ depositOffsetRemark(detailLease) }}
            </p>
            <p class="deposit-note">
              押金退还由社区管理方发起原路退回；平台不托管押金资金、不计息、不垫付。
            </p>
          </div>

          <!-- 押金退还记录（R72 §9.20.2）：金额 / 渠道 / 状态 / 失败原因 / 发起与完成时间 -->
          <div class="card refund-card">
            <h3 class="payments-title">押金退还记录</h3>
            <p v-if="detailState?.loading" class="payments-empty">押金退还记录加载中…</p>
            <p v-else-if="detailState?.refundsFailed" class="payments-empty">
              押金退还记录暂不可用，请稍后刷新
            </p>
            <p v-else-if="refundsOf(detailLease.id).length === 0" class="payments-empty">
              暂无押金退还记录：退租审核通过后由社区管理方发起原路退还
            </p>
            <ul v-else class="refund-list">
              <li v-for="item in refundsOf(detailLease.id)" :key="item.id" class="refund-item">
                <span class="refund-amount mono">{{ money(item.amount) }}</span>
                <span class="refund-channel">{{ paymentChannelLabels[item.channel] }}</span>
                <span class="status-pill" :data-status="depositRefundTone(item.status)">
                  {{ depositRefundStatusLabels[item.status] }}
                </span>
                <span class="refund-meta">
                  发起 {{ formatDateTime(item.createdAt) }} ·
                  完成 {{ item.refundedAt ? formatDateTime(item.refundedAt) : '—' }}
                </span>
                <p class="refund-meta mono">请求号 {{ item.outRequestNo }}</p>
                <p v-if="item.failReason" class="refund-fail">失败原因：{{ item.failReason }}</p>
              </li>
            </ul>
          </div>

          <!-- 租约明细时间线（R73）：状态流转 + 支付流水 + 押金退还三源合并、时间倒序 -->
          <div class="card timeline-card">
            <h3 class="payments-title">租约明细时间线</h3>
            <p class="timeline-sub">状态流转 / 支付 / 押金退还三源合并，按发生时间倒序</p>
            <LeaseTimeline
              v-loading="detailState?.loading"
              :changes="detailState?.changes ?? []"
              :payments="paymentsOf(detailLease)"
              :refunds="detailState?.refunds ?? []"
            />
          </div>
        </template>
      </div>
    </template>

    <!-- 合同与租期弹窗（三步：选月数渠道 → 支付 → 确认结果） -->
    <el-dialog v-model="termsVisible" title="合同与租期" width="min(780px,94vw)"><LeaseTermsPanel v-if="termsTarget" :lease="termsTarget" @changed="loadLeases" /></el-dialog>

    <LeaseRenewDialog :purpose="paymentPurpose" v-model="renewVisible" :lease="renewTarget" @success="handleRenewSuccessDetail" />

    <!-- 首期费用支付弹窗（R71：金额=首月租金+押金，服务端计算，与续租同款双渠道；
         重开时由弹窗回查既有首期单续接或明示已支付，DEF-068） -->
    <LeaseFirstPaymentDialog
      v-model="firstPayVisible"
      :lease="firstPayTarget"
      @success="handleFirstPaySuccess"
      @view-payments="handleViewFirstPayments"
    />

    <!-- 申请退租弹窗（R70：搬出日期 + 原因，提交后进管理端审核） -->
    <LeaseMoveOutDialog v-model="moveOutVisible" :lease="moveOutTarget" @success="handleMoveOutSuccess" />
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
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

/* 深链定位提示条（仅从通知跳转落地时出现） */
.focus-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
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

/* 深链定位目标卡：主色描边 + 浅底，与其它卡片一眼可分 */
.lease-card.is-focused {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-bg);
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
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(0, 1.3fr);
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
  overflow-wrap: anywhere;
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
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-sm);
}

.lease-side > .el-button + .el-button {
  margin-left: 0;
}

.lease-side > span {
  max-width: 100%;
  overflow-wrap: anywhere;
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

/* 协议签约徽章：待办态（待确认/单方已确认）用主色强调，已签署为中性绿 */
.agreement-badge {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  background: rgba(52, 211, 153, 0.14);
  color: #047857;
}

.agreement-badge.is-action {
  background: rgba(59, 130, 246, 0.14);
  color: #1d4ed8;
}

/* 状态胶囊：待签约/待审核/待支付黄，在租/支付成功绿，搬出/归档/已关闭灰，驳回红 */
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

.status-pill[data-status='PENDING'],
.status-pill[data-status='TO_SIGN'] {
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

/* 待签约已确认承租的非交互状态提示（避免用禁用按钮造成「可点但点不动」的误解） */
.action-hint-pill {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
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

/* 展开区内的待签约引导：文案 + 主色按钮横向排布 */
.expand-cta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: rgba(245, 158, 11, 0.1);
}

.expand-cta-text {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
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
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

/* 支付单类型徽标（R71）：首期主色、续期中性，与「续租 N 个月」文案互为印证 */
.pay-type-badge {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.pay-type-badge[data-type='FIRST'] {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.pay-type-badge[data-type='RENEWAL'] {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

/* ---------- 押金状态与退还记录（R72） ---------- */

/* 押金徽标五态：未缴红 / 已缴绿 / 退还中橙 / 已退还主色 / 已抵扣紫 */
.deposit-pill {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.deposit-pill[data-deposit='UNPAID'] {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.deposit-pill[data-deposit='PAID'] {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.deposit-pill[data-deposit='REFUNDING'] {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.deposit-pill[data-deposit='REFUNDED'] {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.deposit-pill[data-deposit='OFFSET'] {
  background: #f1ebfd;
  color: #8b5cf6;
}

.lease-deposit {
  margin-top: var(--spacing-md);
}

.deposit-refunded {
  margin-left: var(--spacing-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-success);
}

.deposit-offset {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.refund-list {
  margin: var(--spacing-xs) 0 0;
  padding: 0;
  list-style: none;
}

.refund-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  padding: var(--spacing-xs) 0;
  border-bottom: 1px dashed var(--color-border);
}

.refund-item:last-child {
  border-bottom: none;
}

.refund-amount {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.refund-channel,
.refund-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.refund-fail {
  flex-basis: 100%;
  margin: 2px 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
  line-height: var(--line-height-normal);
}

/* 状态胶囊补充两档：押金退还失败红、退还中橙（与支付单状态共用一套 data-status 语义） */
.status-pill[data-status='failed'] {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

/* ---------- 租约明细 tab（R72/R73） ---------- */

.detail-tab {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* 租约切换条：明细按单份租约收敛，横向可滚 */
.detail-switch {
  display: flex;
  gap: var(--spacing-sm);
  overflow-x: auto;
  padding-bottom: var(--spacing-xs);
}

.detail-switch-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex-shrink: 0;
  min-width: 190px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.detail-switch-item:hover {
  border-color: var(--color-primary-light);
}

.detail-switch-item.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
}

.switch-house {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.switch-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.empty-state-block {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.deposit-card,
.refund-card,
.timeline-card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.deposit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.deposit-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.deposit-house {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.deposit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--spacing-sm) var(--spacing-md);
  margin: var(--spacing-md) 0 0;
}

.deposit-grid dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: 2px;
}

.deposit-grid dd {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.deposit-note {
  margin: var(--spacing-md) 0 0;
  padding-top: var(--spacing-sm);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

.timeline-sub {
  margin: calc(-1 * var(--spacing-xs)) 0 var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
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

/* 首期费用徽标（R71）：已支付=成功绿软底 / 待支付=警示黄软底，与租约状态徽标同排 */
.first-pay-pill {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-success-soft);
  color: var(--color-success);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.first-pay-pill.is-due {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

/* 止期已过时的首期支付拦截说明（DEF-069）：占动作位的一小段文字，不用可点控件避免误点 */
.action-blocked-note {
  max-width: 220px;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-normal);
  text-align: left;
}

/* 「已到期」与「在租」并列的说明：日期标注 ≠ 状态流转 */
.expiry-note {
  max-width: 220px;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-normal);
  text-align: left;
}

/* 展开区同款说明（替换原本会出现「去支付首期费用」的位置） */
.expand-blocked {
  margin: 0 0 var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
}

/* 展开区内该租约支付记录：沿用「支付记录」Tab 的类型徽标与状态色，仅改为紧凑纵排 */
.lease-payments {
  margin-bottom: var(--spacing-md);
}

/* ① 租约群聊入口（仅已建群时渲染） */
.lease-chat-entry {
  margin-bottom: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-bg);
}

.chat-entry-note {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ② 费用构成：租金/押金两行 + 支付入口 */
.lease-charges {
  margin-bottom: var(--spacing-md);
}

.charge-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin: 0 0 var(--spacing-xs);
  padding: 0;
  list-style: none;
}

.charge-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
}

.charge-name {
  min-width: 56px;
  color: var(--color-text-secondary);
}

.charge-amount {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.charge-state {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background-color: rgba(245, 158, 11, 0.12);
  color: var(--status-pending);
  font-size: var(--font-size-xs);
}

.charge-state[data-paid='true'] {
  background-color: rgba(16, 185, 129, 0.12);
  color: var(--status-completed, #10b981);
}

.charge-note {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.charge-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

/* 支付入口旁的说明（可支付时讲口径，不可支付时讲原因——入口不整条消失） */
.payment-entry-note {
  /* 长说明独占一行，不参与地址与操作按钮的横向宽度竞争。 */
  flex-basis: 100%;
  order: 1;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 自愿的交通手段提示（看房/沟通群不是承约前置） */
.expand-optional-hint {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.payments-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.payments-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.payments-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin: 0;
  padding: 0;
  list-style: none;
}

.payment-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
}

.payment-amount {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.payment-meta {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 平板及较窄窗口上下排列，保留地址和租期的可读宽度。 */
@media (max-width: 1199px) {
  .lease-row {
    grid-template-columns: minmax(0, 1fr);
    align-items: start;
  }
}

@media (max-width: 700px) {
  .lease-title {
    white-space: normal;
  }

  .pay-row {
    flex-wrap: wrap;
  }
}
</style>
