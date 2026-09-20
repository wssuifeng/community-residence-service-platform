<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import ServiceCategoryDrawer from '@/views/admin/workorder/ServiceCategoryDrawer.vue'
import WorkOrderDispatchCard from '@/views/admin/workorder/WorkOrderDispatchCard.vue'
import StaffPickerDrawer from '@/views/admin/workorder/StaffPickerDrawer.vue'
import { getServiceCategoryTree, listAssignableStaff, listWorkOrders } from '@/api/workorder'
import { getCommunityList } from '@/api/community'
import { getWorkOrderStats } from '@/api/statistics'
import type {
  DispatchFlag,
  IStaffOption,
  IWorkOrder,
  WorkOrderPriority,
  WorkOrderSort,
  WorkOrderStatus
} from '@/types/modules/workorder'
import {
  dispatchFlagColors,
  dispatchFlagLabels,
  workOrderPriorityLabels,
  workOrderStatusLabels
} from '@/types/modules/workorder'
import type { ICommunity } from '@/types/modules/community'
import type { IDispatchStage } from './dispatch'
import {
  DISPATCH_STAGES,
  compareWorkOrders,
  canAssignOrder,
  formatWaited,
  isReassignOrder,
  stageKeyOfStatus
} from './dispatch'
import { formatDateTime } from '@/utils/date'
import { useUserStore } from '@/store/user'

/**
 * 工单调度工作台（UI设计.md §3.4 / 对照 design-mockups/admin/03 重做）。
 * 平铺长表改为「阶段看板」为默认视图：一列一个处置阶段，列头给出该阶段积压量，
 * 列内按调度标记前置（超时红带、紧急加重）；列表视图保留全量检索与翻页作为下钻手段。
 */

const router = useRouter()
const userStore = useUserStore()

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

/** 看板单列条数上限：默认只留最需要处理的前几条，其余靠「查看全部」按需展开 */
const BOARD_LIMIT = 6
const BOARD_LIMIT_EXPANDED = 50

const viewMode = ref<'board' | 'list'>('board')
/** 看板聚焦的阶段（空=五列全展示；统计卡点击切换） */
const focusedStageKey = ref('')

const status = ref<WorkOrderStatus | ''>('')
const priority = ref<WorkOrderPriority | ''>('')
const categoryId = ref<number | null>(null)
const assigneeId = ref<number | null>(null)
const keyword = ref('')
const sort = ref<WorkOrderSort>('DEFAULT')

const statusOptions = (Object.keys(workOrderStatusLabels) as WorkOrderStatus[]).map((value) => ({
  value,
  label: workOrderStatusLabels[value]
}))
const priorityOptions = (Object.keys(workOrderPriorityLabels) as WorkOrderPriority[]).map((value) => ({
  value,
  label: workOrderPriorityLabels[value]
}))
const sortOptions: { value: WorkOrderSort; label: string }[] = [
  { value: 'DEFAULT', label: '默认（最新提交）' },
  { value: 'WAIT_DESC', label: '等待最久在前' },
  { value: 'PRIORITY', label: '紧急优先' }
]

/* ---------------- 阶段统计卡（一屏看清各阶段积压量） ---------------- */

const statusCounts = ref<Partial<Record<WorkOrderStatus, number>>>({})
const statsLoaded = ref(false)

const stageCards = computed(() =>
  DISPATCH_STAGES.map((stage) => ({
    ...stage,
    count: stage.statuses.reduce((sum, item) => sum + (statusCounts.value[item] ?? 0), 0)
  }))
)

/** 统计卡激活态：看板按聚焦阶段、列表按状态所属阶段（两视图过滤手段不同，激活态也各表一把） */
const activeCardKey = computed(() =>
  viewMode.value === 'board' ? focusedStageKey.value : stageKeyOfStatus(status.value as WorkOrderStatus)
)

async function loadStats(): Promise<void> {
  try {
    const result = await getWorkOrderStats()
    statusCounts.value = result.byStatus ?? {}
  } catch {
    /* 统计失败不阻塞列表，卡片计数回落 0 */
  } finally {
    statsLoaded.value = true
  }
}

/** 统计接口只回工单总数口径的状态计数，已关闭/驳回/取消不在阶段卡内，另起一行提示 */
const closedCount = computed(
  () =>
    (statusCounts.value.CLOSED ?? 0) + (statusCounts.value.REJECTED ?? 0) + (statusCounts.value.CANCELLED ?? 0)
)

/* ---------------- 筛选选项 ---------------- */

const categoryOptions = ref<{ id: number; name: string }[]>([])
const staffFilterOptions = ref<IStaffOption[]>([])

/** 分类选项：管理范围社区的服务类别树拍平（列表按 categoryId 过滤） */
async function loadCategoryOptions(): Promise<void> {
  try {
    const communityPage = await getCommunityList({ page: 1, size: 100 })
    const bound = userStore.user?.boundCommunities ?? []
    const ids = bound.length > 0 ? bound : communityPage.records.map((item: ICommunity) => item.id)
    const trees = await Promise.all(ids.map((id) => getServiceCategoryTree(id)))
    const seen = new Set<number>()
    const options: { id: number; name: string }[] = []
    for (const tree of trees) {
      for (const node of tree) {
        if (!seen.has(node.id)) {
          seen.add(node.id)
          options.push({ id: node.id, name: node.name })
        }
        for (const child of node.children ?? []) {
          if (!seen.has(child.id)) {
            seen.add(child.id)
            options.push({ id: child.id, name: child.name })
          }
        }
      }
    }
    categoryOptions.value = options
  } catch {
    /* 分类选项失败不阻塞列表 */
  }
}

/** 处理人筛选项：复用派单候选接口（仅 STAFF 姓名，最小暴露面） */
async function loadStaffOptions(): Promise<void> {
  try {
    staffFilterOptions.value = await listAssignableStaff()
  } catch {
    staffFilterOptions.value = []
  }
}

/* ---------------- 看板 ---------------- */

interface IStageState {
  records: IWorkOrder[]
  total: number
  loading: boolean
  /** 是否已「查看全部」展开该列 */
  expanded: boolean
  failed: boolean
}

const stageStates = reactive<Record<string, IStageState>>(
  Object.fromEntries(
    DISPATCH_STAGES.map((stage) => [stage.key, { records: [], total: 0, loading: false, expanded: false, failed: false }])
  )
)

/** 当前要查询的状态列表（阶段状态 ∩ 状态筛选），空数组表示该阶段无内容需隐藏 */
function statusesOfStage(statuses: WorkOrderStatus[]): WorkOrderStatus[] {
  return statuses.filter((item) => !status.value || item === status.value)
}

/** 依据状态筛选与聚焦，算出当前该展示的阶段列 */
const boardStages = computed(() =>
  stageCards.value.filter(
    (stage) =>
      (!focusedStageKey.value || stage.key === focusedStageKey.value) && statusesOfStage(stage.statuses).length > 0
  )
)

function baseQuery() {
  return {
    priority: priority.value || undefined,
    categoryId: categoryId.value ?? undefined,
    assigneeId: assigneeId.value ?? undefined,
    keyword: keyword.value || undefined,
    sort: sort.value
  }
}

/**
 * 看板取数：一个阶段聚合多个状态时逐状态取回再按当前排序口径合并取前 N——
 * 各状态的 total 相加即该阶段真实积压量，列头计数与列内条目因此不会互相矛盾。
 */
async function fetchBoard(): Promise<void> {
  await Promise.all(
    boardStages.value.map(async (stage) => {
      const state = stageStates[stage.key]
      state.loading = true
      state.failed = false
      try {
        const limit = state.expanded ? BOARD_LIMIT_EXPANDED : BOARD_LIMIT
        const pages = await Promise.all(
          statusesOfStage(stage.statuses).map((item) =>
            listWorkOrders({ ...baseQuery(), status: item, page: 1, size: limit })
          )
        )
        state.records = pages
          .flatMap((result) => result.records)
          .sort((a, b) => compareWorkOrders(sort.value, a, b))
          .slice(0, limit)
        state.total = pages.reduce((sum, result) => sum + result.total, 0)
      } catch (error) {
        state.failed = true
        ElMessage.error(error instanceof Error ? error.message : '工单看板加载失败')
      } finally {
        state.loading = false
      }
    })
  )
}

/* ---------------- 列表 ---------------- */

const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IWorkOrder[]>([])
const loading = ref(false)

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listWorkOrders({
      ...baseQuery(),
      status: status.value || undefined,
      page: page.value,
      size: size.value
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单列表加载失败')
  } finally {
    loading.value = false
  }
}

/* ---------------- 筛选与视图切换 ---------------- */

function refresh(): void {
  if (viewMode.value === 'board') {
    void fetchBoard()
  } else {
    void fetchList()
  }
  void loadStats()
}

function handleFilterChange(): void {
  page.value = 1
  for (const stage of DISPATCH_STAGES) {
    stageStates[stage.key].expanded = false
  }
  refresh()
}

/** 统计卡点击：看板聚焦该阶段列，列表按状态精确筛选（多状态阶段回落全量并提示） */
function handleStageCardClick(stage: IDispatchStage): void {
  if (viewMode.value === 'board') {
    focusedStageKey.value = focusedStageKey.value === stage.key ? '' : stage.key
  } else if (stage.statuses.length === 1) {
    focusedStageKey.value = ''
    status.value = status.value === stage.statuses[0] ? '' : stage.statuses[0]
  } else {
    focusedStageKey.value = ''
    status.value = ''
    ElMessage.info(`「${stage.label}」含 ${stage.statuses.length} 个状态，列表视图请用「状态」下拉精确筛选`)
  }
  page.value = 1
  refresh()
}

function switchView(mode: 'board' | 'list'): void {
  if (viewMode.value === mode) return
  viewMode.value = mode
  focusedStageKey.value = ''
  page.value = 1
  refresh()
}

function handleReset(): void {
  status.value = ''
  priority.value = ''
  categoryId.value = null
  assigneeId.value = null
  keyword.value = ''
  sort.value = 'DEFAULT'
  focusedStageKey.value = ''
  handleFilterChange()
}

function handleSizeChange(): void {
  page.value = 1
  fetchList()
}

/** 查看全部：该列放开条数上限（列内滚动），只影响单列，不打断其它列的节奏 */
function toggleStageExpand(stageKey: string): void {
  const state = stageStates[stageKey]
  state.expanded = !state.expanded
  void fetchBoard()
}

/* ---------------- 派单 ---------------- */

const pickerVisible = ref(false)
const pickerOrder = ref<IWorkOrder | null>(null)

function openPicker(order: IWorkOrder): void {
  pickerOrder.value = order
  pickerVisible.value = true
}

function goDetail(order: IWorkOrder): void {
  router.push(`/admin/work-orders/${order.id}`)
}

/* ---------------- 服务类别抽屉（原独立页收编） ---------------- */

const categoryDrawerVisible = ref(false)

/* 列表内标记徽章渲染（模板内直接调用，避免逐行 computed） */
function flagLabel(flag?: DispatchFlag): string {
  return dispatchFlagLabels[flag ?? 'NORMAL']
}

function flagStyle(flag?: DispatchFlag): Record<string, string> {
  const color = dispatchFlagColors[flag ?? 'NORMAL']
  return { backgroundColor: color.bg, color: color.fg }
}

const headerSubtitle = computed(() => {
  if (!statsLoaded.value) return undefined
  const open = stageCards.value
    .filter((card) => card.key !== 'done')
    .reduce((sum, card) => sum + card.count, 0)
  const done = stageCards.value.find((card) => card.key === 'done')?.count ?? 0
  return `在办 ${open} 单按处置阶段分列 · 已完成 ${done} 单 · 已归档（关闭/驳回/取消）${closedCount.value} 单`
})

onMounted(() => {
  refresh()
  loadCategoryOptions()
  loadStaffOptions()
})
</script>

<template>
  <section class="dispatch-workbench">
    <AdminPageHeader title="工单调度" :subtitle="headerSubtitle">
      <div class="view-switch" role="group" aria-label="视图切换">
        <button
          type="button"
          class="switch-btn"
          :class="{ 'is-active': viewMode === 'board' }"
          @click="switchView('board')"
        >
          看板
        </button>
        <button
          type="button"
          class="switch-btn"
          :class="{ 'is-active': viewMode === 'list' }"
          @click="switchView('list')"
        >
          列表
        </button>
      </div>
      <el-button @click="categoryDrawerVisible = true">
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 6.5h16M4 12h10M4 17.5h7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        服务类别
      </el-button>
    </AdminPageHeader>

    <!-- 阶段统计卡：一屏给出各阶段积压量，点击聚焦/筛选该阶段 -->
    <div class="stage-strip">
      <button
        v-for="card in stageCards"
        :key="card.key"
        type="button"
        class="stage-card"
        :class="[`tone-${card.tone}`, { 'is-active': activeCardKey === card.key }]"
        :title="`${card.label}：${card.hint}`"
        @click="handleStageCardClick(card)"
      >
        <span class="stage-card-head">
          <span class="stage-dot" aria-hidden="true" />
          <span class="stage-label">{{ card.label }}</span>
        </span>
        <span class="stage-count">{{ card.count }}<em>单</em></span>
        <span class="stage-hint">{{ card.hint }}</span>
      </button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <el-select v-model="status" placeholder="全部状态" clearable class="filter-select" @change="handleFilterChange">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select
        v-model="categoryId"
        placeholder="全部分类"
        clearable
        filterable
        class="filter-select"
        @change="handleFilterChange"
      >
        <el-option v-for="item in categoryOptions" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="priority" placeholder="全部紧急度" clearable class="filter-select" @change="handleFilterChange">
        <el-option v-for="item in priorityOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select
        v-model="assigneeId"
        placeholder="全部处理人"
        clearable
        filterable
        class="filter-select"
        @change="handleFilterChange"
      >
        <el-option v-for="item in staffFilterOptions" :key="item.id" :label="item.realName" :value="item.id" />
      </el-select>
      <el-select v-model="sort" class="filter-select" @change="handleFilterChange">
        <el-option v-for="item in sortOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <SearchBar v-model="keyword" placeholder="搜索标题/内容/工单号…" @search="handleFilterChange" />
    </FilterPanel>

    <!-- 看板视图：一列一个处置阶段，列头常驻显示阶段积压量 -->
    <div v-if="viewMode === 'board'" class="board-wrap" :class="{ 'is-focused': !!focusedStageKey }">
      <!-- 已关闭/已驳回/已取消不属于处置阶段，看板无列可呈现，明确指路列表视图 -->
      <el-alert
        v-if="boardStages.length === 0"
        type="info"
        :closable="false"
        show-icon
        title="该状态不属于五个处置阶段"
        description="已关闭 / 已驳回 / 已取消等归档状态不在看板内呈现，请切换到「列表」视图查看。"
      />
      <div v-else class="board">
        <section v-for="stage in boardStages" :key="stage.key" class="board-col" :class="`tone-${stage.tone}`">
          <header class="col-head">
            <span class="col-dot" aria-hidden="true" />
            <h2 class="col-title">{{ stage.label }}</h2>
            <span class="col-count">{{ stageStates[stage.key].total }}</span>
            <span v-if="stageStates[stage.key].failed" class="col-failed" title="该列加载失败">加载失败</span>
          </header>
          <p class="col-hint">{{ stage.hint }}</p>

          <div v-loading="stageStates[stage.key].loading" class="col-body">
            <el-alert
              v-if="stageStates[stage.key].failed"
              type="error"
              :closable="false"
              title="该阶段加载失败，可点列头刷新重试"
              show-icon
              class="col-alert"
            />
            <template v-else>
              <WorkOrderDispatchCard
                v-for="order in stageStates[stage.key].records"
                :key="order.id"
                :order="order"
                @open="goDetail"
                @assign="openPicker"
              />
              <p
                v-if="!stageStates[stage.key].loading && stageStates[stage.key].records.length === 0"
                class="col-empty"
              >
                暂无{{ stage.label }}工单
              </p>
              <button
                v-if="stageStates[stage.key].total > stageStates[stage.key].records.length || stageStates[stage.key].expanded"
                type="button"
                class="col-more"
                @click="toggleStageExpand(stage.key)"
              >
                {{
                  stageStates[stage.key].expanded
                    ? '收起'
                    : `查看全部（共 ${stageStates[stage.key].total} 单）`
                }}
              </button>
            </template>
          </div>
        </section>
      </div>
    </div>

    <!-- 列表视图：全量检索与翻页下钻（看板只给前 N 条，精确找人找单在此） -->
    <div v-else class="table-panel">
      <p class="list-hint">
        列表视图按所选排序全量翻页；调度标记与等待时长与看板同一口径，超时单以红色标签标注。
      </p>
      <el-table v-loading="loading" :data="records" class="order-table" @row-click="goDetail">
        <el-table-column label="调度标记" width="104">
          <template #default="{ row }">
            <span class="flag-pill" :style="flagStyle(row.dispatchFlag)">{{ flagLabel(row.dispatchFlag) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="orderNo" label="工单号" width="170">
          <template #default="{ row }">
            <el-link type="primary" @click.stop="goDetail(row)">{{ row.orderNo }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="170" show-overflow-tooltip />
        <el-table-column prop="residentName" label="提交人" width="88" />
        <el-table-column prop="categoryName" label="分类" width="104" show-overflow-tooltip />
        <el-table-column label="紧急度" width="84">
          <template #default="{ row }">
            <span class="priority-cell" :class="{ 'is-urgent': row.priority === 'URGENT', 'is-high': row.priority === 'HIGH' }">
              <i v-if="row.priority === 'URGENT' || row.priority === 'HIGH'" class="priority-dot" aria-hidden="true" />
              {{ workOrderPriorityLabels[row.priority as WorkOrderPriority] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <StatusTag :label="workOrderStatusLabels[row.status as WorkOrderStatus]" :type="statusSemantic[row.status as WorkOrderStatus]" />
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="150">
          <template #default="{ row }">
            <template v-if="row.assigneeName">
              <span class="assignee-cell">{{ row.assigneeName }}</span>
              <span class="shift-mini">{{ row.assigneeShiftLabel || '未排班' }}</span>
              <span class="load-mini">{{ row.assigneeActiveOrders ?? 0 }} 单</span>
            </template>
            <span v-else class="cell-empty">未指派</span>
          </template>
        </el-table-column>
        <el-table-column label="等待" width="112">
          <template #default="{ row }">{{ formatWaited(row.waitedMinutes) || '—' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="146">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="124" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canAssignOrder(row)" text type="primary" size="small" @click.stop="openPicker(row)">
              {{ isReassignOrder(row) ? '改派' : '派单' }}
            </el-button>
            <el-button text type="primary" size="small" @click.stop="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无符合条件的工单" :image-size="80" />
        </template>
      </el-table>

      <Pagination v-model:page="page" v-model:size="size" :total="total" @update:page="fetchList" @update:size="handleSizeChange" />
    </div>

    <StaffPickerDrawer v-model="pickerVisible" :order="pickerOrder" @assigned="refresh" />
    <ServiceCategoryDrawer v-model="categoryDrawerVisible" />
  </section>
</template>

<style scoped>
.btn-icon {
  width: 15px;
  height: 15px;
  margin-right: var(--spacing-xs);
  vertical-align: -2px;
}

/* 视图切换段控 */
.view-switch {
  display: inline-flex;
  padding: 2px;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-hover);
}

.switch-btn {
  padding: 5px var(--spacing-md);
  border: none;
  border-radius: var(--radius-pill);
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.switch-btn.is-active {
  background-color: var(--admin-card-bg);
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
  box-shadow: var(--shadow-sm);
}

/* 阶段统计卡：五阶段并排，一屏读出积压分布 */
.stage-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.stage-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--spacing-md);
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  background-color: var(--admin-card-bg);
  box-shadow: var(--shadow-card);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.15s ease;
}

.stage-card:hover {
  transform: translateY(-1px);
}

.stage-card.is-active {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.stage-card-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.stage-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
  background-color: var(--color-text-disabled);
}

.tone-pending .stage-dot {
  background-color: var(--status-pending);
}

.tone-processing .stage-dot {
  background-color: var(--status-processing);
}

.tone-completed .stage-dot {
  background-color: var(--status-completed);
}

.tone-info .stage-dot {
  background-color: var(--color-primary);
}

.stage-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.stage-count {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.stage-count em {
  margin-left: 2px;
  font-size: var(--font-size-xs);
  font-style: normal;
  font-weight: var(--font-weight-normal);
  color: var(--color-text-disabled);
}

.stage-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 看板：五列等宽，列窄时整体横向滚动，列体纵向滚动保证列头常驻 */
.board-wrap {
  overflow-x: auto;
}

.board {
  display: grid;
  grid-template-columns: repeat(5, minmax(230px, 1fr));
  gap: var(--spacing-md);
  align-items: start;
}

.board-wrap.is-focused .board {
  grid-template-columns: repeat(1, minmax(260px, 1fr));
  max-width: 380px;
}

.board-hint {
  margin: 0;
  padding: var(--spacing-lg);
  border-radius: var(--radius-lg);
  background-color: var(--admin-card-bg);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.board-col {
  display: flex;
  flex-direction: column;
  padding: var(--spacing-sm) var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-lg);
  background-color: var(--color-bg-subtle);
  border-top: 3px solid var(--color-border);
}

.board-col.tone-pending {
  border-top-color: var(--status-pending);
}

.board-col.tone-processing {
  border-top-color: var(--status-processing);
}

.board-col.tone-completed {
  border-top-color: var(--status-completed);
}

.board-col.tone-info {
  border-top-color: var(--color-primary);
}

.col-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 0 var(--spacing-xs);
}

.col-dot {
  width: 7px;
  height: 7px;
  border-radius: var(--radius-circle);
  background-color: currentColor;
}

.col-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.tone-pending .col-title {
  color: #b45309;
}

.tone-processing .col-title {
  color: #1d4ed8;
}

.tone-completed .col-title {
  color: #047857;
}

.tone-info .col-title {
  color: var(--color-primary-dark);
}

.col-count {
  margin-left: auto;
  min-width: 26px;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--admin-card-bg);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  text-align: center;
}

.col-failed {
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.col-hint {
  margin: var(--spacing-xs) 0 var(--spacing-sm);
  padding: 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.col-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  max-height: calc(100vh - 400px);
  min-height: 120px;
  overflow-y: auto;
  padding: 2px;
}

.col-alert {
  margin-bottom: var(--spacing-sm);
}

.col-empty {
  margin: var(--spacing-md) 0;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.col-more {
  padding: var(--spacing-sm);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  cursor: pointer;
}

.col-more:hover {
  background-color: var(--color-primary-bg);
}

/* 列表视图 */
.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.list-hint {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.order-table {
  --el-table-border-color: var(--color-border);
}

.filter-select {
  width: 140px;
}

.flag-pill {
  display: inline-flex;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
}

.priority-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.priority-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-circle);
  background-color: currentColor;
}

.priority-cell.is-urgent {
  color: var(--color-danger);
  font-weight: var(--font-weight-medium);
}

.priority-cell.is-high {
  color: var(--color-warning);
}

.assignee-cell {
  font-weight: var(--font-weight-medium);
}

.shift-mini,
.load-mini {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.cell-empty {
  color: var(--color-text-disabled);
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 1199px) {
  .stage-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 767px) {
  .stage-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
