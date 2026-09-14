<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import ServiceCategoryDrawer from '@/views/admin/workorder/ServiceCategoryDrawer.vue'
import { assignWorkOrder, getServiceCategoryTree, listWorkOrders } from '@/api/workorder'
import { getCommunityList } from '@/api/community'
import { getWorkOrderStats } from '@/api/statistics'
import { getSysUserList } from '@/api/sysuser'
import type { IWorkOrder, WorkOrderStatus, WorkOrderPriority } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import type { ISysUser } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'
import { useUserStore } from '@/store/user'

/** 工单管理（UI设计.md §3.4，对照 design-mockups/admin/03）：统计卡 + 多条件筛选 + 表格 + 派单对话框 + 服务类别抽屉 */

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

const statusOptions = (Object.keys(workOrderStatusLabels) as WorkOrderStatus[]).map((value) => ({
  value,
  label: workOrderStatusLabels[value]
}))
const priorityOptions = (Object.keys(workOrderPriorityLabels) as WorkOrderPriority[]).map((value) => ({
  value,
  label: workOrderPriorityLabels[value]
}))

const status = ref<WorkOrderStatus | ''>('')
const priority = ref<WorkOrderPriority | ''>('')
const categoryId = ref<number | null>(null)
const keyword = ref('')

const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IWorkOrder[]>([])
const loading = ref(false)

/* ---------- 统计卡（真实口径：/statistics/work-orders 专项接口 byStatus 计数；
   后端工单列表无时间过滤参数，「今日完成」不造假 → 已完成卡为 COMPLETED 累计值） ---------- */
const ICONS = {
  clock: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M12 7v5l3 2'],
  send: ['M22 2L11 13', 'M22 2l-7 20-4-9-9-4 20-7z'],
  tool: [
    'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z'
  ],
  check: ['M22 11.1V12a10 10 0 1 1-5.93-9.14', 'M22 4L12 14.01l-3-3']
}

const statsTotal = ref<number | null>(null)
const statusCounts = ref<Partial<Record<WorkOrderStatus, number>>>({})

const statCards = computed(() => [
  { key: 'PENDING' as WorkOrderStatus, label: '待受理', value: statusCounts.value.PENDING ?? 0, icon: ICONS.clock, accent: 'warning' as const },
  { key: 'TO_ASSIGN' as WorkOrderStatus, label: '待派单', value: statusCounts.value.TO_ASSIGN ?? 0, icon: ICONS.send, accent: 'primary' as const },
  { key: 'IN_PROGRESS' as WorkOrderStatus, label: '处理中', value: statusCounts.value.IN_PROGRESS ?? 0, icon: ICONS.tool, accent: 'primary' as const },
  { key: 'COMPLETED' as WorkOrderStatus, label: '已完成', value: statusCounts.value.COMPLETED ?? 0, icon: ICONS.check, accent: 'success' as const }
])

const headerSubtitle = computed(() => (statsTotal.value === null ? undefined : `共 ${statsTotal.value} 单（累计）`))

async function loadStats(): Promise<void> {
  try {
    const result = await getWorkOrderStats()
    statsTotal.value = result.total ?? 0
    statusCounts.value = result.byStatus ?? {}
  } catch {
    /* 统计加载失败不阻塞列表，卡片显示 0 */
  }
}

/* 统计卡点击 → 按对应状态过滤列表（再点一次取消过滤） */
function handleStatClick(key: WorkOrderStatus): void {
  status.value = status.value === key ? '' : key
  page.value = 1
  fetchList()
}

/* ---------- 分类筛选项：管理范围社区的服务类别树拍平（后端列表按 categoryId 过滤） ---------- */
const categoryOptions = ref<{ id: number; name: string }[]>([])

async function loadCategoryOptions(): Promise<void> {
  try {
    const communityPage = await getCommunityList({ page: 1, size: 100 })
    const bound = userStore.user?.boundCommunities ?? []
    const ids = bound.length > 0 ? bound : communityPage.records.map((item) => item.id)
    const trees = await Promise.all(ids.map((id) => getServiceCategoryTree(id)))
    const seen = new Set<number>()
    const options: { id: number; name: string }[] = []
    for (const tree of trees) {
      for (const node of tree) {
        if (!seen.has(node.id)) {
          seen.add(node.id)
          options.push({ id: node.id, name: node.name })
        }
        for (const child of node.children) {
          if (!seen.has(child.id)) {
            seen.add(child.id)
            options.push({ id: child.id, name: child.name })
          }
        }
      }
    }
    categoryOptions.value = options
  } catch {
    /* 分类选项加载失败不阻塞列表，仅下拉暂无选项 */
  }
}

/* ---------- 列表 ---------- */
async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listWorkOrders({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      priority: priority.value || undefined,
      categoryId: categoryId.value ?? undefined,
      keyword: keyword.value || undefined,
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单列表加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  fetchList()
}

function handleReset(): void {
  status.value = ''
  priority.value = ''
  categoryId.value = null
  keyword.value = ''
  page.value = 1
  fetchList()
}

/* 分页（v-model 先回写、监听后发请求——修复原翻页不发请求缺陷） */
function handleSizeChange(): void {
  page.value = 1
  fetchList()
}

/* ---------- 派单 / 改派（后端 assign 允许 待受理/待派单/已派单，已派单即改派） ---------- */
const assignDialogVisible = ref(false)
const assignTarget = ref<IWorkOrder | null>(null)
const staffList = ref<ISysUser[]>([])
const staffLoading = ref(false)
const assignForm = ref({ assigneeId: null as number | null, remark: '' })
const assigning = ref(false)

const isReassign = computed(() => assignTarget.value?.status === 'ASSIGNED')

async function openAssignDialog(order: IWorkOrder): Promise<void> {
  assignTarget.value = order
  assignForm.value = { assigneeId: null, remark: '' }
  assignDialogVisible.value = true

  /* 服务人员选项：sysuser 列表接口（社区管理员视角由后端按数据权限过滤） */
  staffLoading.value = true
  try {
    const result = await getSysUserList({ role: 'STAFF', status: 'ACTIVE', page: 1, size: 100 })
    staffList.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '服务人员列表加载失败')
  } finally {
    staffLoading.value = false
  }
}

async function handleAssign(): Promise<void> {
  if (!assignTarget.value) return
  if (!assignForm.value.assigneeId) {
    ElMessage.warning('请选择服务人员')
    return
  }
  assigning.value = true
  try {
    await assignWorkOrder(assignTarget.value.id, {
      assigneeId: assignForm.value.assigneeId,
      remark: assignForm.value.remark.trim() || undefined
    })
    ElMessage.success(
      `${isReassign.value ? '已改派给' : '已派单给'} ${staffList.value.find((item) => item.id === assignForm.value.assigneeId)?.realName ?? '服务人员'}`
    )
    assignDialogVisible.value = false
    fetchList()
    loadStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '派单失败')
  } finally {
    assigning.value = false
  }
}

/* ---------- 服务类别抽屉（原独立页收编，CRUD 全套见抽屉组件） ---------- */
const categoryDrawerVisible = ref(false)

function goDetail(order: IWorkOrder): void {
  router.push(`/admin/work-orders/${order.id}`)
}

onMounted(() => {
  fetchList()
  loadStats()
  loadCategoryOptions()
})
</script>

<template>
  <section class="admin-work-order-list">
    <AdminPageHeader title="工单管理" :subtitle="headerSubtitle">
      <el-button @click="categoryDrawerVisible = true">
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 6.5h16M4 12h10M4 17.5h7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        服务类别
      </el-button>
    </AdminPageHeader>

    <!-- 统计卡：点击按状态过滤列表（真实 byStatus 计数） -->
    <div class="stat-row">
      <button
        v-for="card in statCards"
        :key="card.key"
        type="button"
        class="stat-card-btn"
        :class="{ 'is-active': status === card.key }"
        :title="`按「${card.label}」过滤列表`"
        @click="handleStatClick(card.key)"
      >
        <AdminStatCard :label="card.label" :value="card.value" unit="单" :icon="card.icon" :accent="card.accent" />
      </button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <el-select v-model="status" placeholder="全部状态" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="categoryId" placeholder="全部分类" clearable filterable class="filter-select" @change="handleSearch">
        <el-option v-for="item in categoryOptions" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="priority" placeholder="全部紧急度" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="item in priorityOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <SearchBar v-model="keyword" placeholder="搜索工单标题/内容…" @search="handleSearch" />
    </FilterPanel>

    <div class="table-panel">
      <el-table v-loading="loading" :data="records" class="order-table" @row-click="goDetail">
        <el-table-column prop="orderNo" label="工单号" width="170">
          <template #default="{ row }">
            <el-link type="primary" @click.stop="goDetail(row)">{{ row.orderNo }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="residentName" label="提交人" width="90" />
        <el-table-column prop="categoryName" label="分类" width="110" show-overflow-tooltip />
        <el-table-column label="紧急度" width="90">
          <template #default="{ row }">
            <span
              class="priority-cell"
              :class="{
                'is-urgent': row.priority === 'URGENT',
                'is-high': row.priority === 'HIGH'
              }"
            >
              <i v-if="row.priority === 'URGENT' || row.priority === 'HIGH'" class="priority-dot" aria-hidden="true" />
              {{ workOrderPriorityLabels[row.priority as keyof typeof workOrderPriorityLabels] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag :label="workOrderStatusLabels[row.status as WorkOrderStatus]" :type="statusSemantic[row.status as WorkOrderStatus]" />
          </template>
        </el-table-column>
        <el-table-column prop="assigneeName" label="服务人员" width="100">
          <template #default="{ row }">{{ row.assigneeName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'TO_ASSIGN'"
              text
              type="primary"
              size="small"
              @click.stop="openAssignDialog(row)"
            >
              派单
            </el-button>
            <el-button v-else-if="row.status === 'ASSIGNED'" text type="primary" size="small" @click.stop="openAssignDialog(row)">
              改派
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

    <el-dialog v-model="assignDialogVisible" :title="`${isReassign ? '改派' : '派单'} · ${assignTarget?.orderNo ?? ''}`" width="480px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="服务人员" required>
          <el-select
            v-model="assignForm.assigneeId"
            :loading="staffLoading"
            placeholder="选择服务人员"
            filterable
            class="staff-select"
          >
            <el-option
              v-for="staff in staffList"
              :key="staff.id"
              :label="`${staff.realName}（${staff.username}${staff.phone ? ' · ' + staff.phone : ''}）`"
              :value="staff.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="派单备注">
          <el-input v-model="assignForm.remark" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="派单要求（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="handleAssign">{{ isReassign ? '确认改派' : '确认派单' }}</el-button>
      </template>
    </el-dialog>

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

/* 统计卡行：整卡为过滤按钮，激活态描边强调 */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}

.stat-card-btn {
  display: block;
  width: 100%;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  background: none;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.stat-card-btn:hover {
  transform: translateY(-2px);
}

.stat-card-btn.is-active {
  border-color: var(--color-primary);
}

@media (max-width: 1199px) {
  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 767px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 表格白卡容器（含分页） */
.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.order-table {
  --el-table-border-color: var(--color-border);
}

.filter-select {
  width: 140px;
}

/* 紧急度：紧急红点红字 / 高橙色点橙字，其余普通字色 */
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

:deep(.el-table__row) {
  cursor: pointer;
}

.staff-select {
  width: 100%;
}
</style>
