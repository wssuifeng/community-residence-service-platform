<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { listWorkOrders } from '@/api/workorder'
import type { IWorkOrder, WorkOrderStatus } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderUrgencyLabels } from '@/types/modules/workorder'
import { formatDateTime } from '@/utils/date'

/** 我的工单（UI设计.md §4.2 / §3.3）：状态 Tab（待接单/处理中/已完成）+ 表格 */

type ListTab = 'todo' | 'doing' | 'done'

const route = useRoute()
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

/* Tab → 查询状态集：待接单=已派单；处理中=已接单+处理中+待确认；已完成=已完成 */
const tabStatuses: Record<ListTab, WorkOrderStatus[]> = {
  todo: ['ASSIGNED'],
  doing: ['ACCEPTED', 'IN_PROGRESS', 'TO_CONFIRM'],
  done: ['COMPLETED']
}

const tabLabels: Record<ListTab, string> = {
  todo: '待接单',
  doing: '处理中',
  done: '已完成'
}

const activeTab = ref<ListTab>(normalizeTab(route.query.tab))
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IWorkOrder[]>([])
const loading = ref(false)

function normalizeTab(value: unknown): ListTab {
  return value === 'doing' || value === 'done' ? value : 'todo'
}

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    /* Tab 对应多个状态时取第一状态查询后在页内过滤（后端 query 仅支持单状态） */
    const statuses = tabStatuses[activeTab.value]
    const results = await Promise.all(
      statuses.map((status) => listWorkOrders({ status, page: page.value, size: size.value }))
    )
    const merged = results.flatMap((result) => result.records)
    /* 合并分页：同页码下各状态记录合并后按时间倒序，截取当前页大小 */
    records.value = merged
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      .slice(0, size.value)
    total.value = results.reduce((sum, result) => sum + result.total, 0)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单列表加载失败')
  } finally {
    loading.value = false
  }
}

watch(activeTab, () => {
  page.value = 1
  fetchList()
})

watch([page, size], () => {
  fetchList()
})

function goDetail(order: IWorkOrder): void {
  router.push(`/staff/work-orders/${order.id}`)
}

onMounted(fetchList)
</script>

<template>
  <section class="staff-work-order-list">
    <header class="page-header">
      <h1 class="page-title">我的工单</h1>
    </header>

    <el-tabs v-model="activeTab" class="status-tabs">
      <el-tab-pane v-for="(label, tab) in tabLabels" :key="tab" :name="tab">
        <template #label>
          {{ label }}
          <el-tag size="small" type="info" class="tab-count">{{ tab === activeTab ? total : '' }}</el-tag>
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-table v-loading="loading" :data="records" class="order-table" @row-click="goDetail">
      <el-table-column prop="orderNumber" label="工单号" width="170">
        <template #default="{ row }">
          <el-link type="primary" @click.stop="goDetail(row)">{{ row.orderNumber }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="categoryName" label="类别" width="110" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="residentName" label="提交人" width="90" />
      <el-table-column label="紧急程度" width="80">
        <template #default="{ row }">
          <el-tag :type="row.urgency === 'URGENT' ? 'danger' : row.urgency === 'HIGH' ? 'warning' : 'info'" size="small">
            {{ workOrderUrgencyLabels[row.urgency as keyof typeof workOrderUrgencyLabels] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag :label="workOrderStatusLabels[row.status as WorkOrderStatus]" :type="statusSemantic[row.status as WorkOrderStatus]" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" size="small" @click.stop="goDetail(row)">
            {{ row.status === 'ASSIGNED' ? '接单' : '查看' }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="activeTab === 'todo' ? '暂无待接单工单' : '暂无工单'" :image-size="80" />
      </template>
    </el-table>

    <Pagination v-model:page="page" v-model:size="size" :total="total" />
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

.status-tabs {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  padding: 0 var(--spacing-md);
}

.tab-count {
  margin-left: var(--spacing-xs);
}

.order-table {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-top: none;
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>
