<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import { assignWorkOrder, listWorkOrders } from '@/api/workorder'
import { getSysUserList } from '@/api/sysuser'
import type { IWorkOrder, WorkOrderStatus, WorkOrderPriority } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import type { ISysUser } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'

/** 工单全量列表（UI设计.md §4.3.4）：多条件筛选 + 表格 + 派单对话框 */

const router = useRouter()

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
const keyword = ref('')
const dateRange = ref<[Date, Date] | null>(null)

const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IWorkOrder[]>([])
const loading = ref(false)

/* 派单对话框 */
const assignDialogVisible = ref(false)
const assignTarget = ref<IWorkOrder | null>(null)
const staffList = ref<ISysUser[]>([])
const staffLoading = ref(false)
const assignForm = ref({ assigneeId: null as number | null, remark: '' })
const assigning = ref(false)

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listWorkOrders({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      priority: priority.value || undefined,
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
  keyword.value = ''
  dateRange.value = null
  page.value = 1
  fetchList()
}

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
    ElMessage.success(`已派单给 ${staffList.value.find((item) => item.id === assignForm.value.assigneeId)?.realName ?? '服务人员'}`)
    assignDialogVisible.value = false
    fetchList()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '派单失败')
  } finally {
    assigning.value = false
  }
}

function goDetail(order: IWorkOrder): void {
  router.push(`/admin/work-orders/${order.id}`)
}

onMounted(fetchList)
</script>

<template>
  <section class="admin-work-order-list">
    <header class="page-header">
      <h1 class="page-title">工单列表</h1>
    </header>

    <FilterPanel resettable @reset="handleReset">
      <el-select v-model="status" placeholder="全部状态" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="priority" placeholder="全部紧急度" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="item in priorityOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        format="YYYY-MM-DD HH:mm"
        @change="handleSearch"
      />
      <SearchBar v-model="keyword" placeholder="搜索工单号 / 标题 / 描述" @search="handleSearch" />
    </FilterPanel>

    <el-table v-loading="loading" :data="records" class="order-table" @row-click="goDetail">
      <el-table-column prop="orderNo" label="工单号" width="170">
        <template #default="{ row }">
          <el-link type="primary" @click.stop="goDetail(row)">{{ row.orderNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="categoryName" label="类别" width="110" />
      <el-table-column prop="title" label="标题" min-width="170" show-overflow-tooltip />
      <el-table-column prop="residentName" label="提交人" width="90" />
      <el-table-column label="紧急程度" width="80">
        <template #default="{ row }">
          <el-tag :type="row.priority === 'URGENT' ? 'danger' : row.priority === 'HIGH' ? 'warning' : 'info'" size="small">
            {{ workOrderPriorityLabels[row.priority as keyof typeof workOrderPriorityLabels] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag :label="workOrderStatusLabels[row.status as WorkOrderStatus]" :type="statusSemantic[row.status as WorkOrderStatus]" />
        </template>
      </el-table-column>
      <el-table-column prop="assigneeName" label="当前处理人" width="100">
        <template #default="{ row }">{{ row.assigneeName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'TO_ASSIGN'"
            text
            type="primary"
            size="small"
            @click.stop="openAssignDialog(row)"
          >
            派单
          </el-button>
          <el-button text type="primary" size="small" @click.stop="goDetail(row)">详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无符合条件的工单" :image-size="80" />
      </template>
    </el-table>

    <Pagination v-model:page="page" v-model:size="size" :total="total" />

    <el-dialog v-model="assignDialogVisible" :title="`派单 · ${assignTarget?.orderNo ?? ''}`" width="480px">
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
        <el-button type="primary" :loading="assigning" @click="handleAssign">确认派单</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-header {
  margin-bottom: var(--spacing-md);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.filter-select {
  width: 140px;
}

.order-table {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

:deep(.el-table__row) {
  cursor: pointer;
}

.staff-select {
  width: 100%;
}
</style>
