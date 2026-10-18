<script setup lang="ts">
import { contractEnd } from '@/common/lease/utils/leaseTerms'
import LeaseTermsPanel from '@/common/lease/components/LeaseTermsPanel.vue'
import { leaseDisplayLabel } from '@/common/lease/utils/leaseDisplay'
import { leaseHouseholdNotice } from '@/common/lease/utils/leaseHousehold'
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import StatusTag from '@/common/web/components/StatusTag.vue'
import AgreementSignPanel from '@/common/agreement/components/AgreementSignPanel.vue'
import ConversationEntry from '@/common/conversation/components/ConversationEntry.vue'
import LeaseOverviewPanel from '@/admin/lease/components/detail/LeaseOverviewPanel.vue'
import LeaseFeeRecords from '@/admin/lease/components/detail/LeaseFeeRecords.vue'
import LeaseBusinessRecords from '@/admin/lease/components/detail/LeaseBusinessRecords.vue'
import { leaseNo } from '@/admin/lease/utils/detail/display'
import DepositRefundDialog from '@/admin/lease/components/DepositRefundDialog.vue'
import { getConversation, ensureLeaseGroupConversation, openDirectConversation } from '@/common/conversation/api/conversation'
import type { IConversation } from '@/common/conversation/types/conversation'
import {
  getLease,
  getLeaseChanges,
  listDepositRefunds,
  listLeasePayments,
  listLeaseViewings,
  updateLease,
  updateLeaseStatus
} from '@/common/lease/api/lease'
import type {
  DepositStatus,
  IDepositRefund,
  ILeaseChange,
  ILeasePayment,
  ILeaseRecord,
  LeaseStatus
} from '@/common/lease/types/lease'
import {
  depositStatusLabels,
} from '@/common/lease/types/lease'
import type { LeaseAgreementSignStatus } from '@/common/agreement/types/agreement'
import { leaseAgreementSignStatusLabels } from '@/common/agreement/types/agreement'
import type { IViewingAppointment } from '@/common/housing/types/housing'
import { formatDate } from '@/common/web/utils/date'

/**
 * 租约详情（管理端下钻页，沿用工单详情「面包屑 + 状态条 + 分区面板」范式）：
 * 顶部汇总状态与操作，四分区按需读取；完整聊天转统一会话页，不改变业务动作。
 *
 * 核心口径：管理方的职责是「管租约属性 + 可追溯」——
 *   ① 属性可编：租期起止 / 月租金 / 押金 / 合同附件 / 备注（走 PUT /leases/{id}，全字段提交）；
 *   ② 变更可溯：每次属性编辑、续租、状态流转、协议发起确认都由后端逐条落库，
 *      本页只负责调 GET /leases/{id}/changes 倒序展示；
 *   ③ 已归档租约属性冻结：前端置灰编辑入口并说明原因（后端同样拒绝，双重把关）。
 */

const route = useRoute()
const router = useRouter()
const leaseListReturnTo = computed(() => typeof route.query.returnTo === 'string' && (route.query.returnTo === '/admin/housing-lease' || route.query.returnTo.startsWith('/admin/housing-lease?')) ? route.query.returnTo : '/admin/housing-lease?tab=leases')

let leaseId = Number(route.params.id)
let generation = 0

const lease = ref<ILeaseRecord | null>(null)
const changes = ref<ILeaseChange[]>([])
/** 关联看房预约（R69 双向关联）：会话内发起的看房带上本租约，房源页发起的为空 */
const viewings = ref<IViewingAppointment[]>([])
const loading = ref(false)
const actionLoading = ref(false)

/* ---------- 取数 ---------- */

/**
 * 加载失败原因（详情为空态时展示，避免"打不开＝空白页"无法自证）。
 * 主数据（GET /leases/{id}）失败才阻断页面；变更历史是**辅助信息**，
 * 取不到只记提示，不再把整个详情页打成空白（与 viewings / deposit 同口径）。
 */
const loadError = ref('')
const changesError = ref('')
const viewingsError = ref('')
const feesError = ref(''), feesLoading = ref(false), recordsLoading = ref(false)
type LeaseSection = 'overview' | 'contract' | 'fees' | 'records'
const sections: Array<{ id: LeaseSection; label: string }> = [{ id: 'overview', label: '概览' }, { id: 'contract', label: '合同与协议' }, { id: 'fees', label: '费用记录' }, { id: 'records', label: '业务记录' }]
function sectionFromRoute(): LeaseSection { return sections.some(item => item.id === route.query.section) ? route.query.section as LeaseSection : route.query.panel === 'terms' ? 'contract' : 'overview' }
const activeSection = ref<LeaseSection>(sectionFromRoute())
const sectionsLoaded = new Set<LeaseSection>()
async function loadSection(section: LeaseSection) {
 if (!lease.value || sectionsLoaded.has(section)) return
 sectionsLoaded.add(section)
 if (section === 'fees') await fetchDeposit()
 if (section === 'records') { const version = generation; recordsLoading.value = true; await Promise.all([fetchChanges(), fetchViewings()]); if (version === generation) recordsLoading.value = false }
}
watch(activeSection, section => {
 void loadSection(section)
 if ((route.query.section ?? 'overview') !== section) void router.replace({ query: { ...route.query, section: section === 'overview' ? undefined : section, panel: undefined } })
})
watch(() => route.query.section, () => { activeSection.value = sectionFromRoute() })

async function fetchDetail(): Promise<void> {
  const id = leaseId, version = ++generation
  loading.value = true; loadError.value = ''
  try {
    const result = await getLease(id)
    if (version !== generation) return
    lease.value = result
    sectionsLoaded.clear()
    void loadSection(activeSection.value)
    void fetchChat()
  } catch (error) {
    if (version !== generation) return
    lease.value = null; changes.value = []
    loadError.value = error instanceof Error ? error.message : '租约详情加载失败'
  } finally { if (version === generation) loading.value = false }
}

/** 变更历史：独立取数并静默降级（仅提示「变更历史暂不可用」，不阻断详情渲染） */
async function fetchChanges(): Promise<void> {
  const id = leaseId, version = generation
  changesError.value = ''
  try {
    const history = await getLeaseChanges(id)
    if (version !== generation) return
    /* 后端按时间倒序返回；此处再排一次防止同秒记录顺序不稳（最新在最上） */
    changes.value = [...history].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
  } catch (error) {
    if (version !== generation) return
    changes.value = []
    changesError.value = error instanceof Error ? error.message : '变更历史加载失败'
  }
}

/** 关联看房记录：独立取数并静默失败——看房是租约的辅助信息，取不到不应影响主详情 */
async function fetchViewings(): Promise<void> {
  const id = leaseId, version = generation
  viewingsError.value = ''
  try {
    const rows = await listLeaseViewings(id)
    if (version === generation) viewings.value = rows
  } catch (error) {
    if (version !== generation) return
    viewingsError.value = error instanceof Error ? error.message : '看房记录加载失败'
  }
}

/* --------------------- 押金状态与退还（R72） --------------------- */

/** 押金退还流水（§9.20.2）：管理端限绑定社区；失败原因需在详情可见 */
const depositRefunds = ref<IDepositRefund[]>([])
/** 该租约支付单：用于推导实缴押金与原支付渠道，供押金退还弹窗只读展示 */
const payments = ref<ILeasePayment[]>([])
const refundDialogVisible = ref(false)

async function fetchDeposit(): Promise<void> {
  const id = leaseId, version = generation
  feesLoading.value = true; feesError.value = ''
  const [refunds, paymentList] = await Promise.allSettled([listDepositRefunds(id), listLeasePayments(id)])
  if (version !== generation) return
  const errors: string[] = []
  if (refunds.status === 'fulfilled') depositRefunds.value = refunds.value
  else errors.push('押金退还记录读取失败')
  if (paymentList.status === 'fulfilled') payments.value = paymentList.value
  else errors.push('支付记录读取失败')
  feesError.value = errors.join('；'); feesLoading.value = false
}

/** 押金状态：后端未返回时按「未缴」兜底（不猜「已缴」，避免给出错误的可退错觉） */
const depositStatus = computed<DepositStatus>(
  () => lease.value?.depositStatus ?? 'UNPAID'
)

const depositLabel = computed(() => depositStatusLabels[depositStatus.value])

/** 累计已成功退还金额（只计 SUCCESS：失败单的钱并未退回） */
const refundedAmount = computed(() =>
  depositRefunds.value
    .filter((item) => item.status === 'SUCCESS')
    .reduce((sum, item) => sum + (item.amount ?? 0), 0)
)

/**
 * 是否可发起押金退还（§9.20.1 前置）：租约已搬出 且 押金状态为 `PAID`。
 * 仅做前端可见性收敛，最终仍由后端判定（5701/5702/5703）。
 */
const canRefundDeposit = computed(
  () => lease.value?.status === 'MOVED_OUT' && depositStatus.value === 'PAID'
)

/** 不可发起时的原因说明（按钮禁用需给理由，而不是静默置灰） */
const refundDisabledReason = computed(() => {
  if (!lease.value) return ''
  if (lease.value.status !== 'MOVED_OUT') {
    return '退租审核通过后（租约已搬出）方可发起押金退还'
  }
  if (depositStatus.value !== 'PAID') {
    return `当前押金状态为「${depositLabel.value}」，仅「已缴」状态可发起退还`
  }
  return ''
})


/** 押金退还成功后刷新详情（OCR DEF-123）：fetchDetail 内部已含 fetchDeposit（流水+支付单），
 *  不再并发补一发 fetchDeposit——双请求各取数两遍且完成时序不定，旧响应可能后写回覆盖新状态 */
async function handleRefundSuccess(): Promise<void> {
  await fetchDetail()
}

/** 属性保存/状态流转后的统一刷新：属性卡与变更历史同时刷新，用户即时看到新记录 */
async function refreshAll(): Promise<void> {
  await fetchDetail()
}

/* ---------- ③ 租约群聊（R68；会话 id 来自 lease_record.conversation_id） ---------- */


/**
 * 会话详情（群成员与只读态）：仅参与者可见——超管不自动入群，
 * 取不到（403/不存在）时仍渲染面板外壳与「前往会话中心」入口，组件自身降级只读。
 */
const chatConversation = ref<IConversation | null>(null)

async function fetchChat(): Promise<void> {
  const version = generation
  const conversationId = lease.value?.conversationId
  if (!conversationId) {
    chatConversation.value = null
    return
  }
  try {
    const result = await getConversation(conversationId)
    if (version === generation) chatConversation.value = result
  } catch {
    if (version === generation) chatConversation.value = null
  }
}

/* ---------- DEF-076 补建租约沟通会话 / 代居民发起直通会话 ---------- */

const chatBuilding = ref(false)
const directOpening = ref(false)

/**
 * 补建/直取租约沟通会话（`POST /conversations/lease-groups`，ADMIN/SUPER_ADMIN，幂等）：
 * 存量或旁路数据（审批内核未建群）下管理端自救建群；后端同事务回写
 * `lease_record.conversation_id`，故成功后重拉详情 + 会话即可渲染面板，
 * 同时解除居民「确认承租」的 5608 前置。
 */
async function handleEnsureLeaseGroup(): Promise<void> {
  if (!lease.value) return
  chatBuilding.value = true
  try {
    const conversation = await ensureLeaseGroupConversation(lease.value.id)
    ElMessage.success('租约沟通群已就绪')
    await fetchDetail()
    chatConversation.value = conversation
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '建立租约沟通群失败')
  } finally {
    chatBuilding.value = false
  }
}

/** 代本租约居民发起直通会话（DEF-076，幂等）并在会话中心打开该会话 */
async function handleOpenDirectWithTenant(): Promise<void> {
  const row = lease.value
  if (!row) return
  directOpening.value = true
  try {
    const conversation = await openDirectConversation({
      communityId: row.communityId,
      residentId: row.tenantId
    })
    ElMessage.success('已开通与租户的直通会话')
    router.push({ path: '/admin/conversations', query: { id: conversation.id, source: 'CONVERSATION', returnTo: route.fullPath } })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发起会话失败')
  } finally {
    directOpening.value = false
  }
}

/* ---------- 状态与标注 ---------- */

type TagType = 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled' | 'info'

function statusTagView(row: ILeaseRecord): { label: string; type: TagType } {
  if (row.status === 'ACTIVE') {
    if (row.expiryFlag === 'EXPIRING') return { label: '即将到期', type: 'pending' }
    if (row.expiryFlag === 'EXPIRED') return { label: '已到期', type: 'rejected' }
    return { label: '在租', type: 'completed' }
  }
  const map: Record<Exclude<LeaseStatus, 'ACTIVE'>, { label: string; type: TagType }> = {
    PENDING: { label: '待审核', type: 'pending' },
    TO_SIGN: { label: leaseDisplayLabel(row), type: 'pending' },
    MOVED_OUT: { label: '已搬出', type: 'canceled' },
    ARCHIVED: { label: '已归档', type: 'canceled' },
    REJECTED: { label: '已驳回', type: 'rejected' }
  }
  return map[row.status]
}

const AGREEMENT_TAG_TYPE: Record<LeaseAgreementSignStatus, TagType> = {
  NONE: 'canceled',
  PENDING: 'pending',
  PARTIAL: 'processing',
  SIGNED: 'completed',
  CANCELLED: 'canceled'
}

const agreementStatus = computed<LeaseAgreementSignStatus>(
  () => lease.value?.agreementStatus ?? 'NONE'
)

function remainingDays(endDate: string): number {
  const end = new Date(`${formatDate(endDate)}T00:00:00`).getTime()
  const today = new Date(`${formatDate(new Date().toISOString())}T00:00:00`).getTime()
  return Math.round((end - today) / 86400000)
}

const remainText = computed(() => {
  if (!lease.value || lease.value.status !== 'ACTIVE') return ''
  const days = remainingDays(lease.value.endDate)
  if (days < 0) return `已超期 ${Math.abs(days)} 天`
  if (days === 0) return '今天到期'
  return `剩余 ${days} 天`
})

const remainTone = computed<'is-danger' | 'is-warning' | 'is-normal'>(() => {
  if (!lease.value) return 'is-normal'
  const days = remainingDays(lease.value.endDate)
  if (days <= 30) return 'is-danger'
  if (days <= 90) return 'is-warning'
  return 'is-normal'
})

/** 已归档租约属性冻结（后端拒绝更新，前端同步置灰并说明原因） */
const editable = computed(() => !!lease.value && lease.value.status !== 'ARCHIVED')
const editDisabledReason = computed(() =>
  lease.value?.status === 'ARCHIVED' ? '租约已归档，属性冻结不可再变更' : ''
)


/* ---------- 变更历史展示 ---------- */

/* ---------- 属性编辑抽屉（全字段提交，缺字段后端视为清空） ---------- */

const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editSaving = ref(false)
const editForm = reactive<{
  contractMonths: number | undefined
  firstPaymentMonths: number | undefined
  paymentCycleMonths: number | undefined
  startDate: string
  endDate: string
  monthlyRent: number | undefined
  deposit: number | undefined
  contractUrl: string
  remark: string
}>({
  contractMonths: undefined, firstPaymentMonths: undefined, paymentCycleMonths: undefined,
  startDate: '',
  endDate: '',
  monthlyRent: undefined,
  deposit: undefined,
  contractUrl: '',
  remark: ''
})

watch(() => [editForm.startDate, editForm.contractMonths], () => { if(editForm.contractMonths) editForm.endDate=contractEnd(editForm.startDate,editForm.contractMonths) })

const editRules: FormRules = {
  startDate: [{ required: true, message: '请选择租期开始日期', trigger: 'change' }],
  endDate: [
    { required: true, message: '请选择租期结束日期', trigger: 'change' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && editForm.startDate && value <= editForm.startDate) {
          callback(new Error('租期结束日期必须晚于开始日期'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ],
  monthlyRent: [
    {
      required: true,
      validator: (_rule, value: number | undefined, callback) => {
        if (value == null) callback(new Error('请输入月租金'))
        else if (value < 0) callback(new Error('月租金不能为负数'))
        else callback()
      },
      trigger: 'blur'
    }
  ],
  deposit: [
    {
      validator: (_rule, value: number | undefined, callback) => {
        if (value != null && value < 0) callback(new Error('押金不能为负数'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

function openEdit(): void {
  if (!lease.value || !editable.value) return
  Object.assign(editForm, {
    contractMonths: lease.value.contractMonths, firstPaymentMonths: lease.value.firstPaymentMonths, paymentCycleMonths: lease.value.paymentCycleMonths,
    startDate: formatDate(lease.value.startDate),
    endDate: formatDate(lease.value.endDate),
    monthlyRent: lease.value.monthlyRent,
    deposit: lease.value.deposit ?? undefined,
    contractUrl: lease.value.contractUrl ?? '',
    remark: lease.value.remark ?? ''
  })
  editVisible.value = true
}

async function handleEditSubmit(): Promise<void> {
  if (!lease.value) return
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  editSaving.value = true
  try {
    /* 全字段提交：租客/房屋为登记主体原样回传（后端 UpdateLeaseDTO 要求齐全） */
    await updateLease(lease.value.id, {
      residentId: lease.value.tenantId,
      houseId: lease.value.houseId,
      contractMonths: editForm.contractMonths, firstPaymentMonths: editForm.firstPaymentMonths, paymentCycleMonths: editForm.paymentCycleMonths,
      startDate: editForm.startDate,
      endDate: editForm.endDate,
      monthlyRent: editForm.monthlyRent!,
      deposit: editForm.deposit ?? undefined,
      contractUrl: editForm.contractUrl.trim() || undefined,
      remark: editForm.remark.trim() || undefined
    })
    ElMessage.success('租约属性已更新，本次变更已记入变更历史')
    editVisible.value = false
    await refreshAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    editSaving.value = false
  }
}

/* ---------- 状态流转（与列表页同一状态机口径） ---------- */

/** 审核仅到待签约，协议确认且首期费用成功后才生效。 */
const canApprove = computed(
  () => lease.value?.status === 'PENDING'
)
const canReject = computed(() => lease.value?.status === 'PENDING')
const canRenew = computed(() => lease.value?.status === 'ACTIVE')
const canMoveOut = computed(() => lease.value?.status === 'ACTIVE')
const canArchive = computed(() => lease.value?.status === 'MOVED_OUT')

async function handleApprove(): Promise<void> {
  if (!lease.value || lease.value.status !== 'PENDING') return
  try {
    await ElMessageBox.confirm('审核通过后租约进入待签约；管理员发出协议，居民同意且首期费用支付成功后才会生效。',
      '租约审批通过', { type: 'info', confirmButtonText: '确认', cancelButtonText: '取消' })
  } catch { return }
  await doTransition('TO_SIGN', '已审核通过，租约进入待签约')
}

async function handleReject(): Promise<void> {
  if (!lease.value) return
  let remark: string
  try {
    const result = await ElMessageBox.prompt('驳回后租约终止流转，可填写驳回原因。', '驳回租约', {
      type: 'warning',
      confirmButtonText: '驳回',
      cancelButtonText: '取消',
      inputPlaceholder: '驳回原因（选填）'
    })
    remark = result.value.trim()
  } catch {
    return
  }
  await doTransition('REJECTED', '租约已驳回', remark)
}

async function handleMoveOut(): Promise<void> {
  if (!lease.value) return
  if (!lease.value) return
  let notice: Awaited<ReturnType<typeof leaseHouseholdNotice>>
  try { notice=await leaseHouseholdNotice(lease.value) } catch(error) { ElMessage.error(error instanceof Error ? error.message : '同住人员核对失败，请重试'); return }
  let remark: string
  try {
    const result = await ElMessageBox.prompt(
      `确定办理「${lease.value.tenantName} · ${lease.value.houseLocation}」退租（搬出）？可填写备注。${notice.text}`,
      '办理退租',
      {
        type: 'warning',
        confirmButtonText: notice.confirmDependents ? '确认整户退租' : '退租',
        cancelButtonText: '取消',
        inputPlaceholder: '退租备注（选填）'
      }
    )
    remark = result.value.trim()
  } catch {
    return
  }
  await doTransition('MOVED_OUT', '已办理退租', remark, notice.confirmDependents)
}

async function handleArchive(): Promise<void> {
  if (!lease.value) return
  try {
    await ElMessageBox.confirm(
      '归档后租约不可再变更（属性冻结、状态终态），确认归档该租约吗？',
      '归档租约',
      { type: 'info', confirmButtonText: '归档', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await doTransition('ARCHIVED', '租约已归档')
}

async function doTransition(target: LeaseStatus, successText: string, remark?: string, confirmDependents?: boolean): Promise<void> {
  if (!lease.value) return
  actionLoading.value = true
  try {
    await updateLeaseStatus(lease.value.id, { status: target, remark: remark || undefined, confirmDependents })
    ElMessage.success(successText)
    await refreshAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

/* ---------- 续租 ---------- */

function openRenew(): void { activeSection.value = 'contract' }

watch(() => route.params.id, value => {
 leaseId = Number(value); generation++; lease.value = null; changes.value = []; viewings.value = []; payments.value = []; depositRefunds.value = []
 chatConversation.value = null; feesError.value = ''; changesError.value = ''; viewingsError.value = ''; feesLoading.value = false; recordsLoading.value = false
 editVisible.value = false; refundDialogVisible.value = false; sectionsLoaded.clear(); activeSection.value = sectionFromRoute(); void fetchDetail()
})
onBeforeUnmount(() => { generation++ })
onMounted(fetchDetail)
</script>

<template>
  <section v-loading="loading" class="admin-lease-detail">
    <nav class="crumb-bar" aria-label="面包屑">
      <router-link class="crumb-link" :to="leaseListReturnTo">租住管理</router-link>
      <span class="crumb-sep">/</span>
      <span class="crumb-current">
        {{ lease ? `${leaseNo(lease.id)} 租约详情` : '租约详情' }}
      </span>
    </nav>

    <template v-if="lease">
      <!-- 状态条：房屋位置为主标题，社区/租户/创建时间为元信息，右侧三枚状态标签 -->
      <article class="status-strip">
        <div class="strip-main">
          <h1 class="status-title">
            {{ lease.houseLocation || '未命名房屋' }}
            <span class="strip-community">{{ lease.communityName || `社区 ID ${lease.communityId}` }}</span>
          </h1>
          <p class="strip-meta">
            租户 {{ lease.tenantName || '—' }}
            <span class="meta-sep">·</span>
            租约号 {{ leaseNo(lease.id) }}
            <span class="meta-sep">·</span>
            合同 {{ formatDate(lease.startDate) }} ～ {{ formatDate(lease.endDate) }}
          </p>
        </div>
        <div class="strip-tags">
          <StatusTag
            :label="leaseAgreementSignStatusLabels[agreementStatus]"
            :type="AGREEMENT_TAG_TYPE[agreementStatus]"
          />
          <!-- 押金状态（R72）：五态徽标，与租约状态并列 -->
          <span class="deposit-pill" :data-deposit="depositStatus">押金{{ depositLabel }}</span>
          <StatusTag :label="statusTagView(lease).label" :type="statusTagView(lease).type" />
          <span v-if="remainText" class="remain-chip" :class="remainTone">{{ remainText }}</span>
        </div>
      </article>


      <div class="lease-summary-actions"><div class="action-stack">
              <el-button
                v-if="canApprove"
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="primary"
                size="large"
                class="action-btn"
                :loading="actionLoading"
                @click="handleApprove"
              >
                审核通过
              </el-button>
              <el-button
                v-if="canReject"
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="danger"
                plain
                size="large"
                class="action-btn"
                :loading="actionLoading"
                @click="handleReject"
              >
                驳回租约
              </el-button>
              <el-button
                v-if="canRenew"
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="primary"
                plain
                size="large"
                class="action-btn"
                @click="openRenew"
              >
                合同与租期
              </el-button>
              <el-button
                v-if="canMoveOut"
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="warning"
                plain
                size="large"
                class="action-btn"
                :loading="actionLoading"
                @click="handleMoveOut"
              >
                办理退租
              </el-button>
              <el-button
                v-if="canArchive"
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="info"
                plain
                size="large"
                class="action-btn"
                :loading="actionLoading"
                @click="handleArchive"
              >
                归档租约
              </el-button>
            </div>
        <ConversationEntry v-if="lease.conversationId" compact :id="lease.conversationId" :closed="chatConversation?.status === 'CLOSED'" />
        <el-button v-else v-permission="['ADMIN','SUPER_ADMIN']" :loading="chatBuilding" @click="handleEnsureLeaseGroup">建立/进入租约沟通群</el-button>
        <el-button v-permission="['ADMIN','SUPER_ADMIN']" text :loading="directOpening" @click="handleOpenDirectWithTenant">与租户直通会话</el-button>
      </div>
      <p v-if="!lease.conversationId" class="action-hint">该租约尚未建立沟通群；入住申请审核通过会自动建群，历史租约可显式补建。</p>
      <nav class="lease-section-nav" aria-label="租约详情分区">
       <button v-for="item in sections" :key="item.id" type="button" :aria-pressed="activeSection === item.id" :class="{ active: activeSection === item.id }" @click="activeSection = item.id">{{ item.label }}</button>
      </nav>
      <section class="lease-section-content" :aria-label="sections.find(item => item.id === activeSection)?.label">
       <LeaseOverviewPanel v-if="activeSection === 'overview'" :lease="lease" :editable="editable" :edit-disabled-reason="editDisabledReason" :remain-text="remainText" :remain-tone="remainTone" @edit="openEdit" />
       <div v-else-if="activeSection === 'contract'" class="contract-sections"><LeaseTermsPanel :lease="lease" admin @changed="refreshAll" /><AgreementSignPanel :lease-id="lease.id" :lease-status="lease.status" role="ADMIN" :community-id="lease.communityId" @changed="refreshAll" /></div>
       <LeaseFeeRecords v-else-if="activeSection === 'fees'" :deposit-refunds="depositRefunds" :payments="payments" :can-refund-deposit="canRefundDeposit" :refund-disabled-reason="refundDisabledReason" :loading="feesLoading" :error="feesError" @retry="fetchDeposit" @refund="refundDialogVisible = true" />
       <LeaseBusinessRecords v-else :changes="changes" :viewings="viewings" :changes-error="changesError" :viewings-error="viewingsError" :loading="recordsLoading" @retry-changes="fetchChanges" @retry-viewings="fetchViewings" />
      </section>
    </template>

    <!-- 详情取不到时的显式兜底（原来只有面包屑：表现为"页面打不开"却看不到原因） -->
    <article v-else-if="!loading" class="panel load-failed">
      <el-empty :description="loadError || '租约详情暂无数据'">
        <div class="load-failed-actions">
          <el-button type="primary" @click="fetchDetail">重新加载</el-button>
          <el-button @click="router.push(leaseListReturnTo)">返回租约列表</el-button>
        </div>
      </el-empty>
      <p class="load-failed-hint">
        若持续失败：请确认该租约属于当前账号绑定的社区（社区管理员）或改用超管账号查看；
        接口返回 404 表示租约不存在，403 表示无数据权限。
      </p>
    </article>

    <!-- 押金退还弹窗（R72）：金额默认全额可改、渠道只读原渠道、失败原因可见可重试 -->
    <DepositRefundDialog
      v-model="refundDialogVisible"
      :lease="lease"
      :first-payments="payments"
      :refunded-amount="refundedAmount"
      @success="handleRefundSuccess"
    />

    <!-- 属性编辑抽屉：仅列可变更字段 -->
    <el-drawer class="lease-property-editor" v-model="editVisible" title="编辑租约属性" size="480px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="form-alert"
        title="保存后自动记入变更历史"
        description="系统按字段记录变更前值 → 变更后值、操作人与时间，可在本页变更历史中查看。租客与房屋为登记主体，不支持变更。"
      />
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="96px">
        <el-form-item label="租期开始" prop="startDate">
          <el-date-picker
            v-model="editForm.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择开始日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-alert v-if="!editForm.contractMonths" title="历史付款安排未知：留空保留原日期，补录前请核对合同。" type="warning" :closable="false" />
        <el-form-item label="合同总月数"><el-input-number v-model="editForm.contractMonths" :min="1" :max="1200" /></el-form-item>
        <el-form-item label="首付月数"><el-input-number v-model="editForm.firstPaymentMonths" :min="1" :max="editForm.contractMonths" /></el-form-item>
        <el-form-item label="后续周期"><el-input-number v-model="editForm.paymentCycleMonths" :min="1" :max="editForm.contractMonths" /></el-form-item>
        <el-form-item label="租期结束" prop="endDate">
          <el-date-picker
            v-model="editForm.endDate" :disabled="!!editForm.contractMonths"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="须晚于开始日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="月租金" prop="monthlyRent">
          <el-input-number
            v-model="editForm.monthlyRent"
            :min="0"
            :controls="false"
            placeholder="元/月（不可为负）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="押金" prop="deposit">
          <el-input-number
            v-model="editForm.deposit"
            :min="0"
            :controls="false"
            placeholder="元（不可为负）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="合同附件">
          <el-input
            v-model="editForm.contractUrl"
            placeholder="线下合同链接（上传/粘贴，选填）"
            clearable
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="editForm.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="handleEditSubmit">保存</el-button>
      </template>
    </el-drawer>

    <!-- 租期延长经居民申请与管理方审批生效，缴租不改期限 -->
  </section>
</template>

<style src="@/admin/lease/components/detail/lease-detail.css"></style>
