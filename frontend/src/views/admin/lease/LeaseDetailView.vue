<script setup lang="ts">
import { contractEnd } from '@/utils/leaseTerms'
import LeaseTermsPanel from '@/components/business/LeaseTermsPanel.vue'
import { leaseDisplayLabel } from '@/utils/leaseDisplay'
import { leaseHouseholdNotice } from '@/utils/leaseHousehold'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import AgreementSignPanel from '@/components/business/AgreementSignPanel.vue'
import ConversationChat from '@/components/business/ConversationChat.vue'
import DepositRefundDialog from './DepositRefundDialog.vue'
import { getConversation, ensureLeaseGroupConversation, openDirectConversation } from '@/api/conversation'
import type { IConversation } from '@/types/modules/conversation'
import {
  getLease,
  getLeaseChanges,
  listDepositRefunds,
  listLeasePayments,
  listLeaseViewings,
  updateLease,
  updateLeaseStatus
} from '@/api/lease'
import type {
  DepositStatus,
  IDepositRefund,
  ILeaseChange,
  ILeasePayment,
  ILeaseRecord,
  LeaseChangeOperatorType,
  LeaseChangeType,
  LeaseStatus
} from '@/types/modules/lease'
import {
  depositRefundStatusLabels,
  depositStatusLabels,
  leaseChangeTypeLabels,
} from '@/types/modules/lease'
import type { LeaseAgreementSignStatus } from '@/types/modules/agreement'
import { leaseAgreementSignStatusLabels } from '@/types/modules/agreement'
import type { IViewingAppointment } from '@/types/modules/housing'
import { viewingAppointmentStatusLabels } from '@/types/modules/housing'
import { formatDate, formatDateTime } from '@/utils/date'

/**
 * 租约详情（管理端下钻页，沿用工单详情「面包屑 + 状态条 + 分区面板」范式）：
 * 左主区 = 属性卡（只读身份 + 可编辑属性）+ 变更历史时间线；右侧栏 = 状态操作 + 协议签约面板。
 *
 * 核心口径：管理方的职责是「管租约属性 + 可追溯」——
 *   ① 属性可编：租期起止 / 月租金 / 押金 / 合同附件 / 备注（走 PUT /leases/{id}，全字段提交）；
 *   ② 变更可溯：每次属性编辑、续租、状态流转、协议发起确认都由后端逐条落库，
 *      本页只负责调 GET /leases/{id}/changes 倒序展示；
 *   ③ 已归档租约属性冻结：前端置灰编辑入口并说明原因（后端同样拒绝，双重把关）。
 */

const route = useRoute()
const router = useRouter()

const leaseId = Number(route.params.id)

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

async function fetchDetail(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    /* 主数据必须先到：租约不存在 / 无权查看（403/404）时由后端文案说明原因 */
    lease.value = await getLease(leaseId)
  } catch (error) {
    lease.value = null
    changes.value = []
    loadError.value = error instanceof Error ? error.message : '租约详情加载失败'
  } finally {
    loading.value = false
  }
  if (!lease.value) {
    void fetchViewings()
    void fetchDeposit()
    return
  }
  await fetchChanges()
  void fetchViewings()
  void fetchDeposit()
  void fetchChat()
}

/** 变更历史：独立取数并静默降级（仅提示「变更历史暂不可用」，不阻断详情渲染） */
async function fetchChanges(): Promise<void> {
  changesError.value = ''
  try {
    const history = await getLeaseChanges(leaseId)
    /* 后端按时间倒序返回；此处再排一次防止同秒记录顺序不稳（最新在最上） */
    changes.value = [...history].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
  } catch (error) {
    changes.value = []
    changesError.value = error instanceof Error ? error.message : '变更历史加载失败'
  }
}

/** 关联看房记录：独立取数并静默失败——看房是租约的辅助信息，取不到不应影响主详情 */
async function fetchViewings(): Promise<void> {
  try {
    viewings.value = await listLeaseViewings(leaseId)
  } catch {
    viewings.value = []
  }
}

/* --------------------- 押金状态与退还（R72） --------------------- */

/** 押金退还流水（§9.20.2）：管理端限绑定社区；失败原因需在详情可见 */
const depositRefunds = ref<IDepositRefund[]>([])
/** 该租约支付单：用于推导实缴押金与原支付渠道，供押金退还弹窗只读展示 */
const payments = ref<ILeasePayment[]>([])
const refundDialogVisible = ref(false)

async function fetchDeposit(): Promise<void> {
  const [refunds, paymentList] = await Promise.all([
    listDepositRefunds(leaseId).catch(() => [] as IDepositRefund[]),
    listLeasePayments(leaseId).catch(() => [] as ILeasePayment[])
  ])
  depositRefunds.value = refunds
  payments.value = paymentList
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

/** 押金退还状态 → StatusTag 语义色档（StatusTag 无 failed 档，失败用 rejected 红） */
function depositRefundTone(status: IDepositRefund['status']): 'completed' | 'pending' | 'rejected' {
  if (status === 'SUCCESS') return 'completed'
  if (status === 'PENDING') return 'pending'
  return 'rejected'
}

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

/** 关闭态提示（沿用租约群聊既有口径，仅作组件文案覆盖位） */
const LEASE_CHAT_CLOSED_HINT = '租约流程已结束，历史消息只读留档，不可再发送'

/**
 * 会话详情（群成员与只读态）：仅参与者可见——超管不自动入群，
 * 取不到（403/不存在）时仍渲染面板外壳与「前往会话中心」入口，组件自身降级只读。
 */
const chatConversation = ref<IConversation | null>(null)
const chatClosed = computed(
  () => chatConversation.value?.status === 'CLOSED' || lease.value?.status === 'ARCHIVED'
)
const chatParticipants = computed(() => chatConversation.value?.participants ?? [])
const chatTitle = computed(() => chatConversation.value?.title ?? '租约沟通群')

async function fetchChat(): Promise<void> {
  const conversationId = lease.value?.conversationId
  if (!conversationId) {
    chatConversation.value = null
    return
  }
  try {
    chatConversation.value = await getConversation(conversationId)
  } catch {
    chatConversation.value = null
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
    router.push(`/admin/conversations?id=${conversation.id}`)
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

function leaseNo(id: number): string {
  return `ZL${String(id).padStart(4, '0')}`
}

function moneyText(value?: number | null): string {
  return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}`
}

/* ---------- 变更历史展示 ---------- */

/** 变更类型 → 标签配色：属性变更中性、续租蓝、状态流转紫、协议签约绿、押金退还青、登记蓝灰 */
const CHANGE_TONE: Record<LeaseChangeType, string> = {
  CREATE: 'is-neutral',
  ATTRIBUTE: 'is-neutral',
  RENEW: 'is-blue',
  STATUS: 'is-purple',
  AGREEMENT: 'is-green',
  DEPOSIT_REFUND: 'is-teal'
}

const OPERATOR_LABELS: Record<LeaseChangeOperatorType, string> = {
  ADMIN: '管理方',
  RESIDENT: '居民',
  SYSTEM: '系统'
}

/** 金额类字段补 ¥（字段名或中文名可辨）；空值统一显示「—」 */
function isMoneyField(change: ILeaseChange): boolean {
  const key = `${change.fieldName ?? ''}${change.fieldLabel ?? ''}`
  return /rent|deposit|租金|押金/i.test(key)
}

function valueText(change: ILeaseChange, value?: string | null): string {
  if (value == null || value === '') return '—'
  if (isMoneyField(change) && !value.startsWith('¥')) return `¥ ${value}`
  return value
}

/** 变更描述：字段级变更给「字段中文名」，整体变更给类型说明 */
function changeDescription(change: ILeaseChange): string {
  if (change.fieldLabel) return change.fieldLabel
  return leaseChangeTypeLabels[change.changeType]
}

const operatorText = (change: ILeaseChange): string => {
  const name = change.operatorName || OPERATOR_LABELS[change.operatorType]
  return `${name}（${OPERATOR_LABELS[change.operatorType]}）`
}

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

/** 审核仅到待签约，生效由居民确认具体协议驱动。 */
const canApprove = computed(
  () => lease.value?.status === 'PENDING'
)
const canReject = computed(() => lease.value?.status === 'PENDING')
const canRenew = computed(() => lease.value?.status === 'ACTIVE')
const canMoveOut = computed(() => lease.value?.status === 'ACTIVE')
const canArchive = computed(() => lease.value?.status === 'MOVED_OUT')
const hasStatusAction = computed(
  () =>
    canApprove.value ||
    canReject.value ||
    canRenew.value ||
    canMoveOut.value ||
    canArchive.value
)

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

const termsDialogVisible=ref(false)
function openRenew(): void { termsDialogVisible.value=true }

onMounted(fetchDetail)
</script>

<template>
  <section v-loading="loading" class="admin-lease-detail">
    <nav class="crumb-bar" aria-label="面包屑">
      <router-link class="crumb-link" to="/admin/leases">租住管理</router-link>
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
            登记于 {{ formatDateTime(lease.createdAt) }}
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

      <div class="detail-grid">
        <!-- 左主区：属性卡 + 变更历史 -->
        <div class="col-main">
          <article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M5 4.5h14v15H5z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M8.5 9h7M8.5 12.5h7M8.5 16h4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              租约属性
              <span v-if="!editable" class="title-note">{{ editDisabledReason }}</span>
              <el-button
                v-else
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                class="title-action"
                type="primary"
                plain
                size="small"
                @click="openEdit"
              >
                编辑属性
              </el-button>
            </h2>

            <span class="block-label">登记信息</span>
            <dl class="info-rows">
              <div class="info-row">
                <dt>租约号</dt>
                <dd>{{ leaseNo(lease.id) }}（ID {{ lease.id }}）</dd>
              </div>
              <div class="info-row">
                <dt>租户</dt>
                <dd>{{ lease.tenantName || '—' }}（居民 ID {{ lease.tenantId }}）</dd>
              </div>
              <div class="info-row">
                <dt>房屋</dt>
                <dd>{{ lease.houseLocation || '—' }}（房屋 ID {{ lease.houseId }}）</dd>
              </div>
              <div class="info-row">
                <dt>社区</dt>
                <dd>{{ lease.communityName || `社区 ID ${lease.communityId}` }}</dd>
              </div>
              <div class="info-row">
                <dt>登记时间</dt>
                <dd>{{ formatDateTime(lease.createdAt) }}</dd>
              </div>

            </dl>

            <span class="block-label">
              可编辑属性
              <em class="label-hint">（变更后自动留痕，字段级前后值）</em>
            </span>
            <dl class="info-rows">
              <div class="info-row">
                <dt>租期</dt>
                <dd>
                  {{ formatDate(lease.startDate) }} ~ {{ formatDate(lease.endDate) }}
                  <span v-if="remainText" class="remain-chip inline" :class="remainTone">{{ remainText }}</span>
                </dd>
              </div>
              <div class="info-row">
                <dt>月租金</dt>
                <dd class="money-text">{{ moneyText(lease.monthlyRent) }}</dd>
              </div>
              <div class="info-row">
                <dt>押金</dt>
                <dd class="money-text">{{ moneyText(lease.deposit) }}</dd>
              </div>
              <!-- 押金状态与退还（R72）：状态 + 累计已退 + 抵扣说明 + 发起入口 -->
              <div class="info-row">
                <dt>押金状态</dt>
                <dd class="deposit-cell">
                  <span class="deposit-pill" :data-deposit="depositStatus">{{ depositLabel }}</span>
                  <span class="deposit-meta">累计已退还 {{ moneyText(refundedAmount) }}</span>
                  <template v-if="lease.depositOffsetRemark">
                    <span class="deposit-meta">抵扣说明：{{ lease.depositOffsetRemark }}</span>
                  </template>
                </dd>
              </div>
              <div class="info-row">
                <dt>合同附件</dt>
                <dd>
                  <a
                    v-if="lease.contractUrl"
                    class="file-link"
                    :href="lease.contractUrl"
                    target="_blank"
                    rel="noopener"
                  >
                    查看合同附件
                  </a>
                  <span v-else class="muted-text">未上传</span>
                </dd>
              </div>
              <div class="info-row">
                <dt>备注</dt>
                <dd>{{ lease.remark || '—' }}</dd>
              </div>
            </dl>
          </article>

          <article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 3.2 20 7v6c0 4.2-3.2 7-8 7.8-4.8-.8-8-3.6-8-7.8V7z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M12 8.4v4.2M12 15.4h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              押金与退还（R72）
              <span class="title-note">共 {{ depositRefunds.length }} 条退还流水</span>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                class="title-action"
                type="primary"
                plain
                size="small"
                :disabled="!canRefundDeposit"
                :title="refundDisabledReason || '按实缴渠道原路退还'"
                @click="refundDialogVisible = true"
              >
                发起押金退还
              </el-button>
            </h2>

            <!-- 不可发起时把原因写出来（禁用按钮本身不解释原因） -->
            <p v-if="refundDisabledReason" class="refund-disabled-note">{{ refundDisabledReason }}</p>

            <ul v-if="depositRefunds.length > 0" class="refund-list">
              <li v-for="item in depositRefunds" :key="item.id" class="refund-item">
                <span class="refund-amount">{{ moneyText(item.amount) }}</span>
                <span class="refund-channel">{{ item.channel === 'ALIPAY' ? '支付宝' : '微信支付' }}</span>
                <StatusTag
                  :label="depositRefundStatusLabels[item.status]"
                  :type="depositRefundTone(item.status)"
                />
                <span class="refund-meta">
                  发起 {{ formatDateTime(item.createdAt) }} ·
                  完成 {{ item.refundedAt ? formatDateTime(item.refundedAt) : '—' }}
                </span>
                <p class="refund-meta mono">请求号 {{ item.outRequestNo }}</p>
                <p v-if="item.failReason" class="refund-fail">失败原因：{{ item.failReason }}</p>
              </li>
            </ul>
            <el-empty v-else description="尚无押金退还流水" :image-size="70" />
          </article>

          <article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="4" y="5.5" width="16" height="15" rx="2" stroke="currentColor" stroke-width="1.8" />
                <path d="M4 10.2h16M8.5 3.2v4M15.5 3.2v4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              关联看房（R69）
              <span class="title-note">共 {{ viewings.length }} 条 · 会话内发起的看房记录</span>
            </h2>

            <ul v-if="viewings.length > 0" class="viewing-list">
              <li v-for="item in viewings" :key="item.id" class="viewing-item">
                <div class="viewing-main">
                  <span class="viewing-title">{{ item.housingTitle || `房源 #${item.housingId}` }}</span>
                  <span class="viewing-time mono">
                    {{ formatDate(item.appointmentDate) }} {{ item.startTime.slice(0, 5) }} ~ {{ item.endTime.slice(0, 5) }}
                  </span>
                </div>
                <div class="viewing-side">
                  <span class="viewing-assignee">
                    带看人 {{ item.assigneeName || '未分配' }}
                  </span>
                  <StatusTag
                    :label="viewingAppointmentStatusLabels[item.status]"
                    :type="item.status === 'COMPLETED' ? 'completed' : item.status === 'CANCELLED' || item.status === 'VIOLATED' ? 'canceled' : 'pending'"
                  />
                </div>
              </li>
            </ul>
            <el-empty v-else description="该租约暂无关联看房记录" :image-size="70" />
          </article>

          <article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.8" />
                <path d="M12 7.5V12l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              变更历史
              <span class="title-note">最新在前 · 共 {{ changes.length }} 条</span>
            </h2>

            <ol v-if="changes.length > 0" class="timeline">
              <li v-for="change in changes" :key="change.id" class="tl-item">
                <span class="tl-marker" aria-hidden="true">
                  <span class="tl-dot" :class="CHANGE_TONE[change.changeType]" />
                </span>
                <div class="tl-body">
                  <p class="tl-head">
                    <span class="change-tag" :class="CHANGE_TONE[change.changeType]">
                      {{ leaseChangeTypeLabels[change.changeType] }}
                    </span>
                    <span class="tl-field">{{ changeDescription(change) }}</span>
                    <span class="tl-time">{{ formatDateTime(change.createdAt) }}</span>
                  </p>
                  <p v-if="change.fieldLabel" class="tl-diff">
                    <span class="diff-old">{{ valueText(change, change.oldValue) }}</span>
                    <span class="diff-arrow">→</span>
                    <span class="diff-new">{{ valueText(change, change.newValue) }}</span>
                  </p>
                  <p v-else-if="change.newValue" class="tl-note">{{ change.newValue }}</p>
                  <p class="tl-operator">
                    操作人 {{ operatorText(change) }}
                    <template v-if="change.reason">
                      <span class="meta-sep">·</span>
                      说明：{{ change.reason }}
                    </template>
                  </p>
                </div>
              </li>
            </ol>
            <el-empty v-else description="尚无变更记录" :image-size="70" />
            <!-- 变更历史为辅助信息：取不到只提示，不影响详情主体（原来会连带整页空白） -->
            <p v-if="changesError" class="changes-error">
              变更历史暂不可用：{{ changesError }}
              <el-button text type="primary" size="small" @click="fetchChanges">重试</el-button>
            </p>
          </article>
        </div>

        <!-- 右侧栏：状态操作 + 协议签约 -->
        <div class="col-side">
          <article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M4 6.5h16v11H4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M7.5 10.5h9M7.5 13.8h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              状态操作
            </h2>

            <div class="action-stack">
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
                续租
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

            <p v-if="!hasStatusAction" class="action-hint">
              当前状态「{{ leaseDisplayLabel(lease) }}」无需状态操作。
            </p>
            <p class="action-note">
              属性变更（月租/押金/租期等）请用「租约属性 → 编辑属性」；状态流转与属性变更都会写入变更历史。
            </p>
          </article>

          <AgreementSignPanel
            :lease-id="lease.id"
              :lease-status="lease.status"
            role="ADMIN"
            :community-id="lease.communityId"
            @changed="refreshAll"
          />

          <!-- ③ 会话相关操作界面：租约群聊（R68，会话由「入住申请审核通过」自动建立）。
               有群时内嵌复用 ConversationChat（含只读/关闭态），并给会话中心入口；
               无群（存量租约）时说明来源与去往会话中心，不在前端自造会话 -->
          <article class="panel lease-chat-panel">
            <h2 class="panel-title">租约群聊</h2>
            <template v-if="lease.conversationId">
              <p class="chat-panel-note">
                本租约的沟通群（居民 + 社区管理方，派单后含服务人员）；租约终态后会话回收为只读。
              </p>
              <ConversationChat
                :conversation-id="lease.conversationId"
                :closed="chatClosed"
                :closed-hint="LEASE_CHAT_CLOSED_HINT"
                :participants="chatParticipants"
                :title="chatTitle"
              />
            </template>
            <template v-else>
              <p class="action-hint">
                该租约尚未建立沟通群：入住申请审核通过后由系统自动建群；存量或旁路数据可由下方按钮补建。
              </p>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="primary"
                size="small"
                :loading="chatBuilding"
                @click="handleEnsureLeaseGroup"
              >
                建立/进入租约沟通群
              </el-button>
            </template>
            <div class="chat-panel-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="primary"
                size="small"
                :loading="directOpening"
                @click="handleOpenDirectWithTenant"
              >
                与租户直通会话
              </el-button>
              <el-button text type="primary" size="small" @click="router.push('/admin/conversations')">
                前往会话中心
              </el-button>
            </div>
          </article>
        </div>
      </div>
    </template>

    <!-- 详情取不到时的显式兜底（原来只有面包屑：表现为"页面打不开"却看不到原因） -->
    <article v-else-if="!loading" class="panel load-failed">
      <el-empty :description="loadError || '租约详情暂无数据'">
        <div class="load-failed-actions">
          <el-button type="primary" @click="fetchDetail">重新加载</el-button>
          <el-button @click="router.push('/admin/housing-lease?tab=leases')">返回租约列表</el-button>
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
    <el-drawer v-model="editVisible" title="编辑租约属性" size="480px">
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

    <!-- 续租经新增合同段确认与首付生效，原合同不被静默覆盖 -->
    <el-dialog v-model="termsDialogVisible" title="合同与续租管理" width="min(780px,94vw)"><LeaseTermsPanel v-if="lease" :lease="lease" admin /></el-dialog>
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

/* 顶部状态条：房屋位置主标题 + 元信息 + 状态标签组 */
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

.strip-main {
  flex: 1;
  min-width: 0;
}

.status-title {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.strip-community {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
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

.strip-tags {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
  flex-wrap: wrap;
}

/* 剩余天数胶囊：≤30 天红（含超期）、≤90 天橙、其余中性 */
.remain-chip {
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
}

.remain-chip.inline {
  margin-left: var(--spacing-sm);
}

.remain-chip.is-danger {
  color: var(--color-danger);
  background-color: var(--color-danger-soft);
  font-weight: var(--font-weight-medium);
}

.remain-chip.is-warning {
  color: var(--color-warning);
  background-color: var(--color-warning-soft);
}

.remain-chip.is-normal {
  color: var(--color-text-secondary);
  background-color: var(--color-bg-subtle);
}

/* 两栏：左主区（属性 + 变更历史）/ 右侧栏（状态操作 + 协议面板） */
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  align-items: start;
  gap: var(--spacing-lg);
}

.col-main,
.col-side {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-width: 0;
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
}

/* 标题右侧说明与动作（说明挤压省略，动作靠右） */
.title-note {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-disabled);
}

.title-action {
  margin-left: auto;
}

.block-label {
  display: inline-block;
  margin-bottom: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.label-hint {
  font-style: normal;
  color: var(--color-text-disabled);
}

/* 信息行：分隔虚线，字段名固定宽 */
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
  word-break: break-word;
  white-space: pre-wrap;
}

.money-text {
  font-variant-numeric: tabular-nums;
  font-weight: var(--font-weight-medium);
}

.file-link {
  color: var(--color-primary);
}

.muted-text {
  color: var(--color-text-disabled);
}


/* 关联看房（R69）：紧凑列表，一行一预约 */
.viewing-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.viewing-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) 0;
  border-bottom: 1px dashed var(--color-border);
}

.viewing-item:last-child {
  border-bottom: none;
}

.viewing-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.viewing-title {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.viewing-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.viewing-side {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
}

.viewing-assignee {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ---------- 押金状态与退还（R72） ---------- */

/* 押金徽标五态：未缴红 / 已缴绿 / 退还中橙 / 已退还主色 / 已抵扣紫 */
.deposit-pill {
  flex-shrink: 0;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  background-color: var(--color-bg-subtle);
  color: var(--color-text-secondary);
}

.deposit-pill[data-deposit='UNPAID'] {
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
}

.deposit-pill[data-deposit='PAID'] {
  background-color: var(--color-success-soft);
  color: var(--color-success);
}

.deposit-pill[data-deposit='REFUNDING'] {
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
}

.deposit-pill[data-deposit='REFUNDED'] {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.deposit-pill[data-deposit='OFFSET'] {
  background-color: #f1ebfd;
  color: #8b5cf6;
}

.deposit-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-xs);
}

.deposit-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.refund-disabled-note {
  margin: 0 0 var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-warning-soft);
  font-size: var(--font-size-xs);
  color: var(--color-warning);
  line-height: var(--line-height-normal);
}

.refund-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.refund-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  padding: var(--spacing-sm) 0;
  border-bottom: 1px dashed var(--color-border);
}

.refund-item:last-child {
  border-bottom: none;
}

.refund-amount {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  font-variant-numeric: tabular-nums;
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

.mono {
  font-family: var(--font-family-mono);
}

/* 变更历史时间线：自绘节点（圆点按变更类型着色） */
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 560px;
  overflow-y: auto;
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
  top: 16px;
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

.tl-dot.is-blue {
  background-color: var(--color-info);
}

.tl-dot.is-purple {
  background-color: var(--chart-c4);
}

.tl-dot.is-green {
  background-color: var(--color-success);
}

/* 押金退还（R72 留痕）：青色，与状态流转紫 / 协议绿区分 */
.tl-dot.is-teal {
  background-color: #0891b2;
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

.change-tag {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  color: var(--color-text-secondary);
  background-color: var(--color-bg-subtle);
}

.change-tag.is-blue {
  color: var(--color-info);
  background-color: var(--color-primary-bg);
}

.change-tag.is-purple {
  color: var(--chart-c4);
  background-color: var(--color-bg-subtle);
  font-weight: var(--font-weight-medium);
}

.change-tag.is-green {
  color: var(--color-success);
  background-color: var(--color-success-soft);
}

.change-tag.is-teal {
  color: #0891b2;
  background-color: #e6f6f8;
}

.tl-field {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.tl-time {
  margin-left: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  font-variant-numeric: tabular-nums;
}

/* 字段级前后值：旧值灰、箭头分隔、新值强调 */
.tl-diff {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  flex-wrap: wrap;
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

.tl-note {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
}

.tl-operator {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 状态操作：纵向动作条 */
.action-stack {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.action-btn {
  width: 100%;
  margin: 0;
}

.action-hint {
  margin: var(--spacing-md) 0 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.action-note {
  margin: var(--spacing-md) 0 0;
  padding-top: var(--spacing-sm);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

.form-alert {
  margin-bottom: var(--spacing-md);
}

@media (max-width: 1279px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .status-strip {
    flex-direction: column;
    align-items: flex-start;
  }

  .tl-time {
    margin-left: 0;
  }
}

/* 详情取数失败的显式兜底（避免"打不开"表现为空白页） */
.load-failed {
  margin-top: var(--spacing-lg);
  text-align: center;
}

.load-failed-actions {
  display: flex;
  justify-content: center;
  gap: var(--spacing-sm);
}

.load-failed-hint {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.changes-error {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--status-pending);
}

/* ③ 租约群聊面板：与右侧栏其它面板同宽，内嵌复用会话组件 */
.lease-chat-panel {
  margin-top: var(--spacing-md);
}

.chat-panel-note {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.chat-panel-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--spacing-sm);
}
</style>
