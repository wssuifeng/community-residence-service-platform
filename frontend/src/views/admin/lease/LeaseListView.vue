<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import { createLease, getLeaseList, renewLease, updateLease, updateLeaseStatus } from '@/api/lease'
import { getCommunityList } from '@/api/community'
import type { ILeaseRecord, LeaseStatus } from '@/types/modules/lease'
import { formatDate, formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'

/**
 * 租住管理（设计稿 05）：页头 + 到期提醒条 + 白卡 4 Tab + 单表格。
 * Tab 为客户端分组、单一数据源（全量拉取一次）：在租 = ACTIVE 且无到期标注；
 * 即将到期/已到期 = ACTIVE 且 expiryFlag=EXPIRING/EXPIRED（后端日期派生标注，
 * 非状态值——分组取后端标注，绝不把标注当 status 传参）；历史 = 待审核/已搬出/已归档/已驳回。
 * 分组阈值与后端到期提醒任务口径一致（sys_config lease.reminder_days，默认 30 天）。
 */

const TABS = ['active', 'expiring', 'expired', 'history'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const router = useRouter()

/* ?tab= 深链契约（active|expiring|expired|history）：缺省回退 active，路由不设 redirect */
const activeTab = computed<TabName>(() => {
  const tab = route.query.tab
  return typeof tab === 'string' && (TABS as readonly string[]).includes(tab)
    ? (tab as TabName)
    : 'active'
})

/* Tab 切换走 query 替换：深链可直达、可刷新、不产生历史记录 */
function switchTab(tab: TabName): void {
  if (tab === activeTab.value) return
  router.replace({ query: { ...route.query, tab } })
}

/* ---------- 单一数据源：全量拉取后客户端分组 ---------- */

/** 即将到期窗口（天），对齐后端 LeaseService.EXPIRING_WINDOW_DAYS 与提醒任务默认值 */
const EXPIRING_WINDOW_DAYS = 30

const leases = ref<ILeaseRecord[]>([])
const loading = ref(false)
const communityNames = ref<Record<number, string>>({})

/* 后端分页上限 100：循环翻页取全量，供四 Tab 分组与角标计数（量级为社区级，循环有 50 页保险上限）。
   序号令牌并发防护：动作后未 await 的 reload 与前一次拉取重叠时，仅最新一次的结果落地，
   旧快照在每次 await 后即刻作废，防止覆盖新快照 */
let loadSeq = 0

async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  try {
    const collected: ILeaseRecord[] = []
    let current = 1
    for (;;) {
      const result = await getLeaseList({ page: current, size: 100 })
      if (seq !== loadSeq) return
      collected.push(...result.records)
      if (result.records.length === 0 || collected.length >= result.total || current >= 50) break
      current += 1
    }
    leases.value = collected
  } catch {
    if (seq === loadSeq) leases.value = []
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

/* 社区名映射（VO 只带 communityId）：房屋列按「社区 + 楼栋单元房号」两行真实组合 */
async function loadCommunityNames(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 100 })
    const map: Record<number, string> = {}
    for (const item of result.records) map[item.id] = item.name
    communityNames.value = map
  } catch {
    /* 映射失败不阻塞列表，社区名显示占位符 */
  }
}

function communityName(row: ILeaseRecord): string {
  return communityNames.value[row.communityId] ?? '—'
}

/* 剩余天数：按本地零点对齐计算（与后端 expiryFlag 判定口径一致） */
function remainingDays(endDate: string): number {
  const end = new Date(`${formatDate(endDate)}T00:00:00`).getTime()
  const today = new Date(`${formatDate(new Date().toISOString())}T00:00:00`).getTime()
  return Math.round((end - today) / 86400000)
}

/* 分组口径：到期归属完全信任后端 expiryFlag（请求时判定，保证前后端一致） */
const activeLeases = computed(() =>
  leases.value.filter((row) => row.status === 'ACTIVE' && !row.expiryFlag)
)
const expiringLeases = computed(() =>
  leases.value
    .filter((row) => row.status === 'ACTIVE' && row.expiryFlag === 'EXPIRING')
    .sort((a, b) => a.endDate.localeCompare(b.endDate))
)
const expiredLeases = computed(() =>
  leases.value
    .filter((row) => row.status === 'ACTIVE' && row.expiryFlag === 'EXPIRED')
    .sort((a, b) => a.endDate.localeCompare(b.endDate))
)
/* 历史 = 非在租存量：已搬出/已归档/已驳回；待审核（未生效登记）同归此组并提供状态筛选（四 Tab 契约固定） */
const HISTORY_STATUSES: LeaseStatus[] = ['PENDING', 'MOVED_OUT', 'ARCHIVED', 'REJECTED']
const historyLeases = computed(() =>
  leases.value.filter((row) => HISTORY_STATUSES.includes(row.status))
)

const expiringCount = computed(() => expiringLeases.value.length)
const expiredCount = computed(() => expiredLeases.value.length)

/* ---------- 提醒条：N/M 由列表真实派生，为 0 整条隐藏 ---------- */

const bannerVisible = computed(() => expiringCount.value > 0 || expiredCount.value > 0)
const bannerTarget = computed<TabName>(() => (expiringCount.value > 0 ? 'expiring' : 'expired'))

function handleBannerView(): void {
  switchTab(bannerTarget.value)
}

/* ---------- 当前 Tab 行 + 客户端分页 ---------- */

const page = ref(1)
const size = ref(10)
const historyStatus = ref<LeaseStatus | ''>('')

const tabRows = computed<ILeaseRecord[]>(() => {
  let rows: ILeaseRecord[]
  switch (activeTab.value) {
    case 'expiring':
      rows = expiringLeases.value
      break
    case 'expired':
      rows = expiredLeases.value
      break
    case 'history':
      rows = historyStatus.value
        ? historyLeases.value.filter((row) => row.status === historyStatus.value)
        : historyLeases.value
      break
    default:
      rows = activeLeases.value
  }
  return rows
})

const pagedRows = computed(() =>
  tabRows.value.slice((page.value - 1) * size.value, page.value * size.value)
)

watch([activeTab, historyStatus], () => {
  page.value = 1
})

const emptyText = computed(
  () =>
    ({
      active: '暂无在租租约',
      expiring: '暂无即将到期租约',
      expired: '暂无已到期租约',
      history: '暂无历史租约'
    })[activeTab.value]
)

/* ---------- 展示辅助 ---------- */

/** 租约号：主键的确定性展示格式（ZL + 4 位补零） */
function leaseNo(id: number): string {
  return `ZL${String(id).padStart(4, '0')}`
}

function rentText(value?: number): string {
  return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}`
}

interface TagView {
  label: string
  type: 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled' | 'info'
}

/** 状态 → 标签语义色：ACTIVE 细分到期标注（即将到期黄 / 已到期红 / 在租绿），其余按状态机 */
function statusTagView(row: ILeaseRecord): TagView {
  if (row.status === 'ACTIVE') {
    if (row.expiryFlag === 'EXPIRING') return { label: '即将到期', type: 'pending' }
    if (row.expiryFlag === 'EXPIRED') return { label: '已到期', type: 'rejected' }
    return { label: '在租', type: 'completed' }
  }
  const map: Record<Exclude<LeaseStatus, 'ACTIVE'>, TagView> = {
    PENDING: { label: '待审核', type: 'pending' },
    MOVED_OUT: { label: '已搬出', type: 'canceled' },
    ARCHIVED: { label: '已归档', type: 'canceled' },
    REJECTED: { label: '已驳回', type: 'rejected' }
  }
  return map[row.status]
}

function expiryFlagText(row: ILeaseRecord): string {
  if (row.expiryFlag === 'EXPIRING') return `即将到期（${EXPIRING_WINDOW_DAYS} 天内）`
  if (row.expiryFlag === 'EXPIRED') return '已到期'
  return '—'
}

/* 剩余天数进度条：绿 >90 天 / 橙 30~90 天 / 红 <30 天与已到期负数；刻度按一年封顶 */
type BarTone = 'is-green' | 'is-orange' | 'is-red'

function barTone(row: ILeaseRecord): BarTone {
  const days = remainingDays(row.endDate)
  if (days > 90) return 'is-green'
  if (days >= 30) return 'is-orange'
  return 'is-red'
}

function barWidth(row: ILeaseRecord): string {
  const days = remainingDays(row.endDate)
  const pct = (Math.min(Math.max(days, 0), 365) / 365) * 100
  return `${Math.max(pct, 3)}%`
}

function barText(row: ILeaseRecord): string {
  return `${remainingDays(row.endDate)}天`
}

/* ---------- 状态流转动作（全部二次确认，备注可选经 prompt 收集） ---------- */

/* 归档仅对已搬出行提供入口（在租行走「退租→归档」常规路径；状态机保留 ACTIVE→ARCHIVED） */
async function transitionStatus(
  row: ILeaseRecord,
  status: LeaseStatus,
  successText: string
): Promise<void> {
  try {
    await updateLeaseStatus(row.id, { status })
    ElMessage.success(successText)
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function handleApprove(row: ILeaseRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定通过与「${row.tenantName}」的租约 ${leaseNo(row.id)}？生效后开始计租。`,
      '租约审批通过',
      { type: 'info', confirmButtonText: '通过', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await transitionStatus(row, 'ACTIVE', '租约已生效')
}

async function handleReject(row: ILeaseRecord): Promise<void> {
  let remark: string
  try {
    const result = await ElMessageBox.prompt(
      `确定驳回「${row.tenantName}」的租约 ${leaseNo(row.id)}？可填写驳回原因。`,
      '驳回租约',
      {
        type: 'warning',
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（选填）'
      }
    )
    remark = result.value.trim()
  } catch {
    return
  }
  try {
    await updateLeaseStatus(row.id, { status: 'REJECTED', remark: remark || undefined })
    ElMessage.success('租约已驳回')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function handleMoveOut(row: ILeaseRecord): Promise<void> {
  let remark: string
  try {
    const result = await ElMessageBox.prompt(
      `确定办理「${row.tenantName} · ${row.houseLocation}」退租（搬出）？可填写备注。`,
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
  try {
    await updateLeaseStatus(row.id, { status: 'MOVED_OUT', remark: remark || undefined })
    ElMessage.success('已办理退租')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function handleArchive(row: ILeaseRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定归档租约 ${leaseNo(row.id)}（${row.tenantName} · ${row.houseLocation}）？归档后不可再变更。`,
      '归档租约',
      { type: 'info', confirmButtonText: '归档', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await transitionStatus(row, 'ARCHIVED', '租约已归档')
}

/* ---------- 续租（需求 E3：止期顺延，仅已生效租约） ---------- */

const renewVisible = ref(false)
const renewTarget = ref<ILeaseRecord | null>(null)
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
        if (value && renewTarget.value && value <= renewTarget.value.endDate) {
          callback(new Error('新结束日期必须晚于原结束日期'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ],
  monthlyRent: [{ required: true, message: '请输入月租金', trigger: 'blur' }],
  deposit: [{ required: true, message: '请输入押金', trigger: 'blur' }]
}

function openRenew(row: ILeaseRecord): void {
  renewTarget.value = row
  Object.assign(renewForm, {
    newEndDate: '',
    monthlyRent: row.monthlyRent,
    deposit: row.deposit ?? undefined,
    remark: ''
  })
  renewVisible.value = true
}

async function handleRenewSubmit(): Promise<void> {
  const valid = await renewFormRef.value?.validate().catch(() => false)
  if (!valid || !renewTarget.value) return
  try {
    await renewLease(renewTarget.value.id, {
      newEndDate: renewForm.newEndDate,
      monthlyRent: renewForm.monthlyRent!,
      deposit: renewForm.deposit!,
      remark: renewForm.remark.trim() || undefined
    })
    ElMessage.success('租约已续租')
    renewVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '续租失败')
  }
}

/* ---------- 新建/编辑对话框（后端 CreateLeaseDTO 契约；合同附件字段后端 VO 不回显，界面不提供） ---------- */

interface LeaseForm {
  residentId: number | undefined
  houseId: number | undefined
  startDate: string
  endDate: string
  monthlyRent: number | undefined
  deposit: number | undefined
  remark: string
}

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<LeaseForm>({
  residentId: undefined,
  houseId: undefined,
  startDate: '',
  endDate: '',
  monthlyRent: undefined,
  deposit: undefined,
  remark: ''
})

const formRules: FormRules = {
  residentId: [{ required: true, message: '请输入租客 ID', trigger: 'blur' }],
  houseId: [{ required: true, message: '请输入房屋 ID', trigger: 'blur' }],
  startDate: [{ required: true, message: '请选择租期开始日期', trigger: 'change' }],
  endDate: [{ required: true, message: '请选择租期结束日期', trigger: 'change' }],
  monthlyRent: [{ required: true, message: '请输入月租金', trigger: 'blur' }]
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, {
    residentId: undefined,
    houseId: undefined,
    startDate: '',
    endDate: '',
    monthlyRent: undefined,
    deposit: undefined,
    remark: ''
  })
  dialogVisible.value = true
}

function openEdit(row: ILeaseRecord): void {
  editingId.value = row.id
  Object.assign(form, {
    residentId: row.tenantId,
    houseId: row.houseId,
    startDate: formatDate(row.startDate),
    endDate: formatDate(row.endDate),
    monthlyRent: row.monthlyRent,
    deposit: row.deposit ?? undefined,
    remark: row.remark ?? ''
  })
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (form.endDate <= form.startDate) {
    ElMessage.error('租期结束日期必须晚于开始日期')
    return
  }
  const payload = {
    residentId: form.residentId!,
    houseId: form.houseId!,
    startDate: form.startDate,
    endDate: form.endDate,
    monthlyRent: form.monthlyRent!,
    deposit: form.deposit ?? undefined,
    remark: form.remark.trim() || undefined
  }
  try {
    if (editingId.value === null) {
      await createLease(payload)
      ElMessage.success('租约已创建，待审核')
    } else {
      await updateLease(editingId.value, payload)
      ElMessage.success('租约已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------- 详情 ---------- */

const detailVisible = ref(false)
const detailRow = ref<ILeaseRecord | null>(null)

function openDetail(row: ILeaseRecord): void {
  detailRow.value = row
  detailVisible.value = true
}

onMounted(() => {
  load()
  loadCommunityNames()
})
</script>

<template>
  <section class="admin-lease">
    <AdminPageHeader title="租住管理" subtitle="租约全生命周期">
      <el-button
        v-permission="['ADMIN', 'SUPER_ADMIN']"
        type="primary"
        @click="openCreate"
      >
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        新建租住记录
      </el-button>
    </AdminPageHeader>

    <!-- 到期提醒条：数字由列表真实派生，为 0 时整条隐藏 -->
    <div v-if="bannerVisible" class="expiry-banner" role="status">
      <svg class="banner-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M12 3a6 6 0 0 0-6 6c0 7-3 9-3 9h18s-3-2-3-9a6 6 0 0 0-6-6zM10.3 21a1.94 1.94 0 0 0 3.4 0"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <p class="banner-text">
        <template v-if="expiringCount > 0">
          有 <strong>{{ expiringCount }}</strong> 份租约即将到期（{{ EXPIRING_WINDOW_DAYS }} 天内）<template v-if="expiredCount > 0">，</template>
        </template>
        <template v-if="expiredCount > 0">
          <strong>{{ expiredCount }}</strong> 份已到期未处理
        </template>
      </p>
      <button type="button" class="banner-link" @click="handleBannerView">
        查看
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
    </div>

    <!-- 白卡 Tab 条：客户端分组计数（与列表口径同源） -->
    <nav class="tab-bar" role="tablist" aria-label="租住管理视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'active' }"
        :aria-selected="activeTab === 'active'"
        @click="switchTab('active')"
      >
        在租租约
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'expiring' }"
        :aria-selected="activeTab === 'expiring'"
        @click="switchTab('expiring')"
      >
        即将到期
        <span v-if="expiringCount > 0" class="tab-badge is-warning">{{ expiringCount }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'expired' }"
        :aria-selected="activeTab === 'expired'"
        @click="switchTab('expired')"
      >
        已到期
        <span v-if="expiredCount > 0" class="tab-badge is-danger">{{ expiredCount }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'history' }"
        :aria-selected="activeTab === 'history'"
        @click="switchTab('history')"
      >
        历史租约
      </button>
    </nav>

    <div class="table-panel">
      <!-- 历史 Tab 状态筛选：客户端过滤同源分组（后端列表无关键字参数） -->
      <div v-if="activeTab === 'history'" class="history-toolbar">
        <span class="toolbar-label">状态</span>
        <el-select v-model="historyStatus" clearable placeholder="全部状态" class="toolbar-select">
          <el-option label="待审核" value="PENDING" />
          <el-option label="已搬出" value="MOVED_OUT" />
          <el-option label="已归档" value="ARCHIVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </div>

      <el-table v-loading="loading" :data="pagedRows">
        <el-table-column label="租约号" width="110">
          <template #default="{ row }">{{ leaseNo(row.id) }}</template>
        </el-table-column>
        <el-table-column label="租户" min-width="120">
          <template #default="{ row }">
            <div class="two-line-cell">
              <span class="cell-primary">{{ row.tenantName || '—' }}</span>
              <span class="cell-secondary">ID {{ row.tenantId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="房屋" min-width="190">
          <template #default="{ row }">
            <div class="two-line-cell">
              <span class="cell-primary">{{ communityName(row) }}</span>
              <span class="cell-secondary">{{ row.houseLocation || '—' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="起止日期" width="150">
          <template #default="{ row }">
            <div class="two-line-cell">
              <span class="cell-primary">{{ formatDate(row.startDate) }}</span>
              <span class="cell-secondary">至 {{ formatDate(row.endDate) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="月租金" width="100" align="right">
          <template #default="{ row }">{{ rentText(row.monthlyRent) }}</template>
        </el-table-column>
        <el-table-column label="剩余天数" width="190">
          <template #default="{ row }">
            <div v-if="row.status === 'ACTIVE'" class="remain-cell">
              <div class="remain-track">
                <span class="remain-fill" :class="barTone(row)" :style="{ width: barWidth(row) }" />
              </div>
              <span class="remain-days">{{ barText(row) }}</span>
            </div>
            <span v-else class="remain-none">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag :label="statusTagView(row).label" :type="statusTagView(row).type" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="success"
                size="small"
                @click="handleApprove(row)"
              >
                通过
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="danger"
                size="small"
                @click="handleReject(row)"
              >
                驳回
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="primary"
                size="small"
                @click="openEdit(row)"
              >
                编辑
              </el-button>
            </template>
            <template v-else-if="row.status === 'ACTIVE'">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="primary"
                size="small"
                @click="openRenew(row)"
              >
                续租
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="warning"
                size="small"
                @click="handleMoveOut(row)"
              >
                退租
              </el-button>
            </template>
            <template v-else-if="row.status === 'MOVED_OUT'">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                text
                type="info"
                size="small"
                @click="handleArchive(row)"
              >
                归档
              </el-button>
            </template>
            <el-button text type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="emptyText" :image-size="80" />
        </template>
      </el-table>

      <Pagination v-model:page="page" v-model:size="size" :total="tabRows.length" />
    </div>

    <!-- 新建/编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建租住记录' : '编辑租住记录'"
      width="560px"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="租客 ID" prop="residentId">
          <el-input-number
            v-model="form.residentId"
            :min="1"
            :controls="false"
            placeholder="租客账号 ID（居民列表可查）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="房屋 ID" prop="houseId">
          <el-input-number
            v-model="form.houseId"
            :min="1"
            :controls="false"
            placeholder="房屋 ID（房屋列表可查）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="租期开始" prop="startDate">
          <el-date-picker
            v-model="form.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择开始日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="租期结束" prop="endDate">
          <el-date-picker
            v-model="form.endDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="月租金" prop="monthlyRent">
          <el-input-number
            v-model="form.monthlyRent"
            :min="0"
            :controls="false"
            placeholder="元/月"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number
            v-model="form.deposit"
            :min="0"
            :controls="false"
            placeholder="选填，元"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="选填"
            maxlength="500"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 续租：仅已生效租约，止期顺延 -->
    <el-dialog v-model="renewVisible" :title="`续租 · ${renewTarget ? leaseNo(renewTarget.id) : ''}`" width="480px">
      <el-alert
        v-if="renewTarget"
        :title="`原租期至 ${formatDate(renewTarget.endDate)}，新结束日期必须晚于原日期。`"
        type="info"
        :closable="false"
        show-icon
        class="renew-alert"
      />
      <el-form ref="renewFormRef" :model="renewForm" :rules="renewRules" label-width="100px">
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
            placeholder="元/月"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="押金" prop="deposit">
          <el-input-number
            v-model="renewForm.deposit"
            :min="0"
            :controls="false"
            placeholder="元"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="renewForm.remark"
            type="textarea"
            :rows="2"
            placeholder="续租说明（选填）"
            maxlength="500"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renewVisible = false">取消</el-button>
        <el-button type="primary" @click="handleRenewSubmit">确认续租</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="租约详情" width="640px">
      <el-descriptions v-if="detailRow" :column="2" border>
        <el-descriptions-item label="租约号">{{ leaseNo(detailRow.id) }}（ID {{ detailRow.id }}）</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag :label="statusTagView(detailRow).label" :type="statusTagView(detailRow).type" />
        </el-descriptions-item>
        <el-descriptions-item label="租户">{{ detailRow.tenantName || '—' }}（ID {{ detailRow.tenantId }}）</el-descriptions-item>
        <el-descriptions-item label="社区">{{ communityName(detailRow) }}</el-descriptions-item>
        <el-descriptions-item label="房屋">{{ detailRow.houseLocation || '—' }}</el-descriptions-item>
        <el-descriptions-item label="到期标注">{{ expiryFlagText(detailRow) }}</el-descriptions-item>
        <el-descriptions-item label="租期开始">{{ formatDate(detailRow.startDate) }}</el-descriptions-item>
        <el-descriptions-item label="租期结束">{{ formatDate(detailRow.endDate) }}</el-descriptions-item>
        <el-descriptions-item label="月租金">{{ rentText(detailRow.monthlyRent) }}</el-descriptions-item>
        <el-descriptions-item label="押金">{{ rentText(detailRow.deposit) }}</el-descriptions-item>
        <el-descriptions-item label="登记时间" :span="2">{{ formatDateTime(detailRow.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detailRow.remark || '—' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* 按钮 svg 图标（与工单管理同款） */
.btn-icon {
  width: 14px;
  height: 14px;
  margin-right: var(--spacing-xs);
  vertical-align: -2px;
}

/* 到期提醒条：warning soft 底横条（配色取语义 token，无硬编码 hex） */
.expiry-banner {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  margin-bottom: var(--spacing-lg);
  background-color: var(--color-warning-soft);
  border-radius: var(--radius-md);
}

.banner-icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  color: var(--color-warning);
}

.banner-text {
  flex: 1;
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.banner-text strong {
  color: var(--color-warning);
  font-weight: var(--font-weight-bold);
}

.banner-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-warning);
  cursor: pointer;
}

.banner-link svg {
  width: 14px;
  height: 14px;
}

/* 白卡 Tab 条：观感与居民管理/工单管理一致（激活蓝字 + 底部 2px 下划线） */
.tab-bar {
  display: flex;
  align-items: stretch;
  gap: var(--spacing-xl);
  padding: 0 var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
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

/* Tab 分组计数角标：语义 soft 色对，随分组自动消隐 */
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

.tab-badge.is-danger {
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
}

/* 表格白卡容器（含分页，与居民管理 table-panel 同款） */
.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.history-toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-xs) var(--spacing-sm);
}

.toolbar-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.toolbar-select {
  width: 140px;
}

/* 两行单元格（主行 + 次行） */
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 剩余天数进度条：细条 + 条尾数字（历史行无剩余概念显示占位符） */
.remain-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.remain-track {
  flex: 1;
  height: 6px;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-subtle);
  overflow: hidden;
}

.remain-fill {
  display: block;
  height: 100%;
  min-width: 4px;
  border-radius: var(--radius-pill);
}

.remain-fill.is-green {
  background-color: var(--color-success);
}

.remain-fill.is-orange {
  background-color: var(--color-warning);
}

.remain-fill.is-red {
  background-color: var(--color-danger);
}

.remain-days {
  flex-shrink: 0;
  min-width: 44px;
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.remain-none {
  color: var(--color-text-secondary);
}

.renew-alert {
  margin-bottom: var(--spacing-md);
}
</style>
