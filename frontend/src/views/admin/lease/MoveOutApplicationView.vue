<script setup lang="ts">
import { useLiveRefresh } from '@/common/web/composables/useLiveRefresh'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StatusTag from '@/common/web/components/StatusTag.vue'
import Pagination from '@/common/web/components/Pagination.vue'
import SearchBar from '@/common/web/components/SearchBar.vue'
import FilterPanel from '@/common/web/components/FilterPanel.vue'
import DepositRefundDialog from './DepositRefundDialog.vue'
import { getHouseResidentList } from '@/common/resident/api/resident'
import { loadAllPages } from '@/common/web/utils/paged'
import type { IHouseResident } from '@/common/resident/types/resident'
import { approveMoveOutApplication, listMoveOutApplications, rejectMoveOutApplication } from '@/common/lease/api/leaseMoveOut'
import { getLease, listLeasePayments } from '@/common/lease/api/lease'
import { getCommunityList } from '@/common/community/api/community'
import type {
  DepositStatus,
  IMoveOutApplication,
  ILeasePayment,
  ILeaseRecord,
  MoveOutApplicationStatus
} from '@/common/lease/types/lease'
import { depositStatusLabels, moveOutApplicationStatusLabels } from '@/common/lease/types/lease'
import { formatDate, formatDateTime } from '@/common/web/utils/date'

/**
 * 退租申请审核（R70/V20，管理端）：居民自助退租申请的待办与审核入口。
 *
 * 独立页而非租住管理页内的 Dialog：审核是"改状态 + 终止居住关系 + 房屋/房源联动"
 * 的重动作，需要在操作前把租约、房屋、搬出日期、原因一次性看清（列表页行内空间不足）。
 *
 * 口径：
 * - Tab 与 status 透传后端筛选（服务端分页），非客户端分组——退租申请是待办队列，
 *   数据量随社区增长，全量拉取不成立；
 * - 通过时要求管理方选择房源回收方式（在架 / 下架），对应后端 housingStatus；
 * - 驳回理由必填（后端同样强校验），通过/驳回均由后端联动租约→已搬出、居住关系终止、
 *   房屋置空置，前端不做本地状态预判。
 */

type TabName = 'PENDING' | 'APPROVED' | 'REJECTED' | 'all'

const TABS: Array<{ value: TabName; label: string }> = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'all', label: '全部' }
]

const TAG_TYPE: Record<MoveOutApplicationStatus, 'pending' | 'completed' | 'rejected'> = {
  PENDING: 'pending',
  APPROVED: 'completed',
  REJECTED: 'rejected'
}

const activeTab = ref<TabName>('PENDING')
const records = ref<IMoveOutApplication[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ page: 1, size: 10 })

/* 社区筛选：后端按社区数据权限收敛，此处仅提供显式收窄（VO 只带 communityId） */
const communityId = ref<number | undefined>(undefined)
const communityOptions = ref<{ id: number; name: string }[]>([])
const communityNames = ref<Record<number, string>>({})

/** 行内二次筛选（租客/房号/单号），翻页后仍按当前页过滤，故仅作辅助 */
const keyword = ref('')

const filteredRecords = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return records.value
  return records.value.filter(
    (row) =>
      (row.residentName ?? '').toLowerCase().includes(kw) ||
      (row.houseLocation ?? '').toLowerCase().includes(kw) ||
      String(row.leaseId).includes(kw)
  )
})

const pendingOnPage = computed(() => records.value.filter((row) => row.status === 'PENDING').length)

/** 租约号展示：后端可能返回 leaseNo，否则按 id 确定性兜底（与租住管理同格式） */
function leaseNo(row: IMoveOutApplication): string {
  return row.leaseNo ?? `ZL${String(row.leaseId).padStart(4, '0')}`
}

function communityName(row: IMoveOutApplication): string {
  return communityNames.value[row.communityId] ?? `社区 #${row.communityId}`
}

function switchTab(next: TabName): void {
  activeTab.value = next
  query.page = 1
  load()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listMoveOutApplications({
      page: query.page,
      size: query.size,
      status: activeTab.value === 'all' ? undefined : (activeTab.value as MoveOutApplicationStatus),
      communityId: communityId.value
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    records.value = []
    total.value = 0
    ElMessage.error(error instanceof Error ? error.message : '退租申请列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 100 })
    const map: Record<number, string> = {}
    for (const item of result.records) map[item.id] = item.name
    communityNames.value = map
    communityOptions.value = result.records.map((item) => ({ id: item.id, name: item.name }))
  } catch {
    /* 社区映射失败不阻塞列表，社区名显示占位符 */
  }
}

/* ------------------------------ 审核通过（选房源回收方式） ------------------------------ */

const approveVisible = ref(false)
const approveMembers=ref<IHouseResident[]>([])
const membersLoading=ref(false), membersError=ref('')
let approveVersion=0
const approveTarget = ref<IMoveOutApplication | null>(null)
const approveHousingStatus = ref<'AVAILABLE' | 'OFFLINE'>('AVAILABLE')
const submitting = ref(false)

async function openApprove(row: IMoveOutApplication): Promise<void> {
  approveTarget.value = row
  approveHousingStatus.value = 'AVAILABLE'
  approveVisible.value = true
  const version=++approveVersion;approveMembers.value=[];membersError.value='';membersLoading.value=true
  try {
    const rows=await loadAllPages(q=>getHouseResidentList(row.houseId,{...q,status:'ACTIVE'}),()=>version===approveVersion&&approveVisible.value)
    const roots=rows.filter(r=>r.residentId===row.residentId&&(r.leaseId===row.leaseId||r.leaseId==null))
    if(version===approveVersion)approveMembers.value=rows.filter(r=>r.anchorRelationId!=null&&roots.some(root=>root.id===r.anchorRelationId))
  }catch(e){if(version===approveVersion)membersError.value=e instanceof Error?e.message:'同住人员核对失败'}
  finally{if(version===approveVersion)membersLoading.value=false}
}

async function handleApproveSubmit(): Promise<void> {
  const target = approveTarget.value
  if (!target || membersLoading.value || membersError.value) return
  submitting.value = true
  try {
    await approveMoveOutApplication(target.id, { housingStatus: approveHousingStatus.value, confirmDependents: true })
    ElMessage.success('退租申请已通过：租约已搬出，居住关系终止，房屋置空置')
    approveVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审核通过失败')
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 驳回（理由必填） ------------------------------ */

async function handleReject(row: IMoveOutApplication): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      `驳回「${row.residentName || '居民'} · ${row.houseLocation || leaseNo(row)}」的退租申请？\n驳回后租约保持已生效。`,
      '驳回退租申请',
      {
        type: 'warning',
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回理由（必填）',
        inputValidator: (value: string) => (value.trim() ? true : '请填写驳回理由')
      }
    )
    reason = result.value.trim()
  } catch {
    return
  }
  submitting.value = true
  try {
    await rejectMoveOutApplication(row.id, { reason })
    ElMessage.success('退租申请已驳回，租约保持已生效')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '驳回失败')
  } finally {
    submitting.value = false
  }
}

/* --------------------- 押金状态与退还（R72） --------------------- */

/**
 * 本次会话内发起过退还的租约：用于「发起押金退还」按钮的即时收口。
 * 押金状态权威值来自租约详情（`GET /leases/{id}` 的 `depositStatus`），
 * 但列表接口（`GET /lease-move-out-applications`）不带该字段，逐行打详情接口过重，
 * 故这里只做「发起后本页立即置为退还中/已退还」的本地标记，跨会话以详情页为准。
 * 标记值按 `depositMarkerOf` 三态映射写入：null 表示清除（服务端状态已回落 PAID 等）。
 */
const refundedLeaseIds = reactive<Record<number, 'PENDING' | 'SUCCESS' | null>>({})

/** 押金状态展示：本地发起结果优先，其余按「未知」呈现（不臆断已缴/未缴） */
function depositStatusOf(row: IMoveOutApplication): DepositStatus | null {
  const local = refundedLeaseIds[row.leaseId]
  if (local === 'SUCCESS') return 'REFUNDED'
  if (local === 'PENDING') return 'REFUNDING'
  return null
}

function depositLabelOf(row: IMoveOutApplication): string {
  const status = depositStatusOf(row)
  return status ? depositStatusLabels[status] : '待核对'
}

/**
 * 可发起押金退还（§9.20.1 前置）：退租申请已通过（租约此时应为已搬出）。
 * 押金是否「已缴」由后端门禁判定（否则 5701），前端不做本地否定，
 * 避免因列表接口缺字段而把合法入口全部置灰。
 */
function canRefund(row: IMoveOutApplication): boolean {
  return row.status === 'APPROVED' && refundedLeaseIds[row.leaseId] !== 'SUCCESS'
}

const refundDialogVisible = ref(false)
const refundTargetLeaseId = ref<number | null>(null)
const refundTargetLease = ref<ILeaseRecord | null>(null)
/** 目标租约的支付单：押金退还弹窗据此推导实缴押金与原渠道 */
const refundTargetPayments = ref<ILeasePayment[]>([])

async function openRefund(row: IMoveOutApplication): Promise<void> {
  refundTargetLeaseId.value = row.leaseId
  const [lease, payments] = await Promise.all([
    getLease(row.leaseId).catch(() => null),
    listLeasePayments(row.leaseId).catch(() => [] as ILeasePayment[])
  ])
  /* 取不到租约或支付单时仍打开弹窗：弹窗会明示「渠道未知」并禁用提交，不让管理方白点 */
  refundTargetLease.value = lease
  refundTargetPayments.value = payments
  refundDialogVisible.value = true
}

/**
 * 服务端押金状态 → 本地标记三态映射（OCR DEF-112）：
 * REFUNDED→SUCCESS / REFUNDING→PENDING / 其余（PAID 等）返回 null 清除本地标记——
 * 渠道失败后端回落 PAID、部分退款成功也保持 PAID 可继续退余额，
 * 若按「非 REFUNDED 即 PENDING」映射，失败后本页会永远显示「退还中」。
 */
function depositMarkerOf(status: DepositStatus | null | undefined): 'SUCCESS' | 'PENDING' | null {
  if (status === 'REFUNDED') return 'SUCCESS'
  if (status === 'REFUNDING') return 'PENDING'
  return null
}

/** 退还发起成功（含 PENDING/SUCCESS）：本页立即反映押金状态，并回读一次租约详情 */
async function handleRefundSuccess(): Promise<void> {
  const leaseId = refundTargetLeaseId.value
  if (leaseId == null) return
  try {
    const fresh = await getLease(leaseId)
    refundTargetLease.value = fresh
    refundedLeaseIds[leaseId] = depositMarkerOf(fresh.depositStatus)
  } catch {
    if (refundTargetLease.value) {
      refundedLeaseIds[leaseId] = depositMarkerOf(refundTargetLease.value.depositStatus)
    }
  }
}

function resetFilters(): void {
  communityId.value = undefined
  keyword.value = ''
  query.page = 1
  load()
}

watch(communityId, () => {
  query.page = 1
  load()
})

onMounted(() => {
  load()
  loadCommunities()
})

useLiveRefresh(() => load())
</script>

<template>
  <section class="move-out-page">
    <AdminPageHeader title="退租申请" subtitle="居民自助退租申请审核 · 通过后租约搬出并联动居住关系与房屋" />

    <!-- Tab 与状态走服务端筛选（待办队列，不做全量拉取） -->
    <nav class="tab-bar" role="tablist" aria-label="退租申请状态切换">
      <button
        v-for="item in TABS"
        :key="item.value"
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === item.value }"
        :aria-selected="activeTab === item.value"
        @click="switchTab(item.value)"
      >
        {{ item.label }}
        <span
          v-if="item.value === 'PENDING' && activeTab === 'PENDING' && pendingOnPage > 0"
          class="tab-badge is-warning"
        >
          {{ pendingOnPage }}
        </span>
      </button>
    </nav>

    <FilterPanel resettable @reset="resetFilters">
      <el-select v-model="communityId" placeholder="全部社区" clearable filterable class="filter-select">
        <el-option v-for="item in communityOptions" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <SearchBar v-model="keyword" placeholder="本页内搜索租客/房号/租约号…" />
    </FilterPanel>

    <div class="table-panel">
      <el-table v-loading="loading" :data="filteredRecords">
        <el-table-column label="租约号" width="110">
          <template #default="{ row }">
            <el-link type="primary" @click="$router.push(`/admin/leases/${row.leaseId}`)">
              {{ leaseNo(row) }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="申请人" min-width="120">
          <template #default="{ row }">
            <div class="two-line-cell">
              <span class="cell-primary">{{ row.residentName || '—' }}</span>
              <span class="cell-secondary">居民 ID {{ row.residentId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="房屋位置" min-width="180">
          <template #default="{ row }">
            <div class="two-line-cell">
              <span class="cell-primary">{{ row.houseLocation || '—' }}</span>
              <span class="cell-secondary">{{ communityName(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="计划搬出日期" width="130">
          <template #default="{ row }">
            <span class="mono">{{ formatDate(row.moveOutDate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="退租原因" min-width="180">
          <template #default="{ row }">
            <span class="cell-secondary wrap">{{ row.reason || '未填写' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag
              :label="moveOutApplicationStatusLabels[row.status as MoveOutApplicationStatus]"
              :type="TAG_TYPE[row.status as MoveOutApplicationStatus]"
            />
          </template>
        </el-table-column>
        <!-- 押金状态列（R72）：退租审核通过后管理方在此发起原路退还 -->
        <el-table-column label="押金" width="110">
          <template #default="{ row }">
            <StatusTag
              :label="depositLabelOf(row)"
              :type="depositStatusOf(row) === 'REFUNDED'
                ? 'completed'
                : depositStatusOf(row) === 'REFUNDING'
                  ? 'pending'
                  : 'info'"
            />
          </template>
        </el-table-column>
        <el-table-column label="审核信息" min-width="180">
          <template #default="{ row }">
            <div v-if="row.status !== 'PENDING'" class="two-line-cell">
              <span class="cell-primary">
                {{ row.reviewerName || '管理方' }} · {{ row.reviewTime ? formatDateTime(row.reviewTime) : '—' }}
              </span>
              <span v-if="row.rejectReason" class="cell-secondary wrap">驳回理由：{{ row.rejectReason }}</span>
            </div>
            <span v-else class="cell-secondary">
              提交于 {{ formatDateTime(row.createdAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="215" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="success"
                size="small"
                @click="openApprove(row)"
              >
                通过
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="danger"
                size="small"
                :loading="submitting"
                @click="handleReject(row)"
              >
                驳回
              </el-button>
            </template>
            <!-- 通过后进入押金退还环节（R72）：金额默认全额、渠道只读原渠道 -->
            <template v-else-if="canRefund(row)">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="primary"
                size="small"
                @click="openRefund(row)"
              >
                发起押金退还
              </el-button>
            </template>
            <span v-else class="cell-secondary">已办结</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="activeTab === 'PENDING' ? '暂无待审核退租申请' : '暂无退租申请'" :image-size="80" />
        </template>
      </el-table>

      <Pagination v-model:page="query.page" v-model:size="query.size" :total="total" @update:page="load" @update:size="load" />
    </div>

    <!-- 通过：必须选择房源回收方式（后端联动房屋置空置 + 房源状态） -->
    <el-dialog v-model="approveVisible" title="通过退租申请" width="520px">
      <template v-if="approveTarget">
        <p v-if="membersLoading">正在核对同住人员…</p>
        <el-alert v-if="membersError" :title="membersError" type="error" :closable="false" />
        <el-alert v-if="approveMembers.length" type="warning" :closable="false" :title="`同时结束以下同住人员的依赖关系：${approveMembers.map(m=>m.residentName).join('、')}。账号和历史保留。`" />
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="form-alert"
          title="通过后不可撤销"
          description="租约将流转为「已搬出」并记入变更历史，居住关系按搬出日期终止，房屋置为空置；房源按下方选择回收。"
        />
        <dl class="summary">
          <div class="summary-row">
            <dt>租约</dt>
            <dd>{{ leaseNo(approveTarget) }}（ID {{ approveTarget.leaseId }}）</dd>
          </div>
          <div class="summary-row">
            <dt>申请人</dt>
            <dd>{{ approveTarget.residentName || '—' }}（居民 ID {{ approveTarget.residentId }}）</dd>
          </div>
          <div class="summary-row">
            <dt>房屋</dt>
            <dd>{{ approveTarget.houseLocation || '—' }}</dd>
          </div>
          <div class="summary-row">
            <dt>搬出日期</dt>
            <dd class="mono">{{ formatDate(approveTarget.moveOutDate) }}</dd>
          </div>
          <div class="summary-row">
            <dt>退租原因</dt>
            <dd>{{ approveTarget.reason || '未填写' }}</dd>
          </div>
        </dl>

        <p class="field-label">房源回收方式</p>
        <el-radio-group v-model="approveHousingStatus">
          <el-radio value="AVAILABLE">重新在架（可继续对外招租）</el-radio>
          <el-radio value="OFFLINE">下架（暂不对外展示）</el-radio>
        </el-radio-group>
      </template>

      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="membersLoading || !!membersError" @click="handleApproveSubmit">确认退租并结束关联居住关系</el-button>
      </template>
    </el-dialog>

    <!-- 押金退还弹窗（R72）：金额默认全额可改（≤ 实缴）、渠道只读原渠道、失败原因可见可重试 -->
    <DepositRefundDialog
      v-model="refundDialogVisible"
      :lease="refundTargetLease"
      :first-payments="refundTargetPayments"
      @success="handleRefundSuccess"
    />
  </section>
</template>

<style scoped>
.move-out-page {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* 白卡 Tab 条：与租住管理/居民管理同口径（激活蓝字 + 底部 2px 下划线） */
.tab-bar {
  display: flex;
  align-items: stretch;
  gap: var(--spacing-xl);
  padding: 0 var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow-x: auto;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
  height: 56px;
  padding: 0 var(--spacing-xs);
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-md);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease;
}

.tab-item:hover {
  color: var(--color-text-primary);
}

.tab-item.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1;
}

.tab-badge.is-warning {
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
}

.filter-select {
  width: 180px;
}

.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.two-line-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-primary {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.cell-secondary {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.cell-secondary.wrap {
  white-space: normal;
  word-break: break-word;
  line-height: var(--line-height-normal);
}

.mono {
  font-family: var(--font-family-mono);
}

.form-alert {
  margin-bottom: var(--spacing-md);
}

.summary {
  margin: 0 0 var(--spacing-md);
  padding: 0;
}

.summary-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-md);
  padding: var(--spacing-xs) 0;
  border-bottom: 1px dashed var(--color-border);
}

.summary-row:last-child {
  border-bottom: none;
}

.summary-row dt {
  flex-shrink: 0;
  width: 88px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.summary-row dd {
  margin: 0;
  min-width: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-word;
}

.field-label {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
