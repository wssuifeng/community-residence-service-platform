<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import AgreementSignPanel from '@/components/business/AgreementSignPanel.vue'
import { getLease, getLeaseChanges, renewLease, updateLease, updateLeaseStatus } from '@/api/lease'
import type {
  ILeaseChange,
  ILeaseRecord,
  LeaseChangeOperatorType,
  LeaseChangeType,
  LeaseStatus
} from '@/types/modules/lease'
import { leaseChangeTypeLabels, leaseStatusLabels } from '@/types/modules/lease'
import type { LeaseAgreementSignStatus } from '@/types/modules/agreement'
import { leaseAgreementSignStatusLabels } from '@/types/modules/agreement'
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

const leaseId = Number(route.params.id)

const lease = ref<ILeaseRecord | null>(null)
const changes = ref<ILeaseChange[]>([])
const loading = ref(false)
const actionLoading = ref(false)

/* ---------- 取数 ---------- */

async function fetchDetail(): Promise<void> {
  loading.value = true
  try {
    const [detail, history] = await Promise.all([getLease(leaseId), getLeaseChanges(leaseId)])
    lease.value = detail
    /* 后端按时间倒序返回；此处再排一次防止同秒记录顺序不稳（最新在最上） */
    changes.value = [...history].sort((a, b) =>
      (b.createdAt ?? '').localeCompare(a.createdAt ?? '')
    )
  } catch (error) {
    lease.value = null
    changes.value = []
    ElMessage.error(error instanceof Error ? error.message : '租约详情加载失败')
  } finally {
    loading.value = false
  }
}

/** 属性保存/状态流转后的统一刷新：属性卡与变更历史同时刷新，用户即时看到新记录 */
async function refreshAll(): Promise<void> {
  await fetchDetail()
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

/** 变更类型 → 标签配色：属性变更中性、续租蓝、状态流转紫、协议签约绿、登记蓝灰 */
const CHANGE_TONE: Record<LeaseChangeType, string> = {
  CREATE: 'is-neutral',
  ATTRIBUTE: 'is-neutral',
  RENEW: 'is-blue',
  STATUS: 'is-purple',
  AGREEMENT: 'is-green'
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
  startDate: string
  endDate: string
  monthlyRent: number | undefined
  deposit: number | undefined
  contractUrl: string
  remark: string
}>({
  startDate: '',
  endDate: '',
  monthlyRent: undefined,
  deposit: undefined,
  contractUrl: '',
  remark: ''
})

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

const canApprove = computed(() => lease.value?.status === 'PENDING')
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
  if (!lease.value) return
  try {
    await ElMessageBox.confirm(
      `确定通过该租约（${lease.value.tenantName} · ${lease.value.houseLocation}）？通过后租约生效并开始计租。`,
      '租约审批通过',
      { type: 'info', confirmButtonText: '通过', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await doTransition('ACTIVE', '租约已生效')
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
  let remark: string
  try {
    const result = await ElMessageBox.prompt(
      `确定办理「${lease.value.tenantName} · ${lease.value.houseLocation}」退租（搬出）？可填写备注。`,
      '办理退租',
      {
        type: 'warning',
        confirmButtonText: '退租',
        cancelButtonText: '取消',
        inputPlaceholder: '退租备注（选填）'
      }
    )
    remark = result.value.trim()
  } catch {
    return
  }
  await doTransition('MOVED_OUT', '已办理退租', remark)
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

async function doTransition(target: LeaseStatus, successText: string, remark?: string): Promise<void> {
  if (!lease.value) return
  actionLoading.value = true
  try {
    await updateLeaseStatus(lease.value.id, { status: target, remark: remark || undefined })
    ElMessage.success(successText)
    await refreshAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

/* ---------- 续租 ---------- */

const renewVisible = ref(false)
const renewFormRef = ref<FormInstance>()
const renewForm = reactive<{
  newEndDate: string
  monthlyRent: number | undefined
  deposit: number | undefined
  remark: string
}>({ newEndDate: '', monthlyRent: undefined, deposit: undefined, remark: '' })

const renewRules: FormRules = {
  newEndDate: [
    { required: true, message: '请选择新结束日期', trigger: 'change' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && lease.value && value <= formatDate(lease.value.endDate)) {
          callback(new Error('新结束日期必须晚于原结束日期'))
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
      required: true,
      validator: (_rule, value: number | undefined, callback) => {
        if (value == null) callback(new Error('请输入押金'))
        else if (value < 0) callback(new Error('押金不能为负数'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

function openRenew(): void {
  if (!lease.value) return
  Object.assign(renewForm, {
    newEndDate: '',
    monthlyRent: lease.value.monthlyRent,
    deposit: lease.value.deposit ?? undefined,
    remark: ''
  })
  renewVisible.value = true
}

async function handleRenewSubmit(): Promise<void> {
  if (!lease.value) return
  const valid = await renewFormRef.value?.validate().catch(() => false)
  if (!valid) return
  actionLoading.value = true
  try {
    await renewLease(lease.value.id, {
      newEndDate: renewForm.newEndDate,
      monthlyRent: renewForm.monthlyRent!,
      deposit: renewForm.deposit!,
      remark: renewForm.remark.trim() || undefined
    })
    ElMessage.success('租约已续租，续租记录已记入变更历史')
    renewVisible.value = false
    await refreshAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '续租失败')
  } finally {
    actionLoading.value = false
  }
}

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
              当前状态「{{ leaseStatusLabels[lease.status] }}」无需状态操作。
            </p>
            <p class="action-note">
              属性变更（月租/押金/租期等）请用「租约属性 → 编辑属性」；状态流转与属性变更都会写入变更历史。
            </p>
          </article>

          <AgreementSignPanel
            :lease-id="lease.id"
            role="ADMIN"
            :community-id="lease.communityId"
            @changed="refreshAll"
          />
        </div>
      </div>
    </template>

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
        <el-form-item label="租期结束" prop="endDate">
          <el-date-picker
            v-model="editForm.endDate"
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

    <!-- 续租：仅已生效租约，止期顺延 -->
    <el-dialog v-model="renewVisible" title="续租" width="480px">
      <el-alert
        v-if="lease"
        :title="`原租期至 ${formatDate(lease.endDate)}，新结束日期必须晚于原日期。`"
        type="info"
        :closable="false"
        show-icon
        class="form-alert"
      />
      <el-form ref="renewFormRef" :model="renewForm" :rules="renewRules" label-width="96px">
        <el-form-item label="新结束日期" prop="newEndDate">
          <el-date-picker
            v-model="renewForm.newEndDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择新结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="月租金" prop="monthlyRent">
          <el-input-number
            v-model="renewForm.monthlyRent"
            :min="0"
            :controls="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="押金" prop="deposit">
          <el-input-number
            v-model="renewForm.deposit"
            :min="0"
            :controls="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="renewForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="续租说明（选填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renewVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="handleRenewSubmit">确认续租</el-button>
      </template>
    </el-dialog>
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
</style>
