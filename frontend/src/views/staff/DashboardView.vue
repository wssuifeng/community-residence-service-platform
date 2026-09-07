<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { listWorkOrders } from '@/api/workorder'
import type { IWorkOrder, WorkOrderStatus } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderUrgencyLabels } from '@/types/modules/workorder'
import { formatRelative } from '@/utils/date'

/** 工作台（UI设计.md §4.2.1）：统计卡片（列表接口统计）+ 待办工单快速入口 */

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

/* 紧急程度排序权重：越紧急越靠前 */
const urgencyWeight: Record<string, number> = { URGENT: 3, HIGH: 2, NORMAL: 1, LOW: 0 }

const todoCount = ref(0)
const doingCount = ref(0)
const todayDoneCount = ref(0)
const todoOrders = ref<IWorkOrder[]>([])
const loading = ref(false)

/** 本地日期 → 查询起始时间字符串（YYYY-MM-DD 00:00:00） */
function todayStart(): string {
  const now = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} 00:00:00`
}

async function loadDashboard(): Promise<void> {
  loading.value = true
  try {
    /* 统计卡片：待接单 / 处理中（已接单+处理中）/ 今日完成，均由列表接口 total 汇总 */
    const [assigned, accepted, inProgress, todayDone] = await Promise.all([
      listWorkOrders({ status: 'ASSIGNED', page: 1, size: 5 }),
      listWorkOrders({ status: 'ACCEPTED', page: 1, size: 1 }),
      listWorkOrders({ status: 'IN_PROGRESS', page: 1, size: 5 }),
      listWorkOrders({ status: 'COMPLETED', page: 1, size: 1, startTime: todayStart() })
    ])

    todoCount.value = assigned.total
    doingCount.value = accepted.total + inProgress.total
    todayDoneCount.value = todayDone.total

    /* 待办列表：已派单 + 已接单 + 处理中，按紧急程度与时间排序 */
    todoOrders.value = [...assigned.records, ...accepted.records, ...inProgress.records].sort((a, b) => {
      const weight = urgencyWeight[b.urgency] - urgencyWeight[a.urgency]
      return weight !== 0 ? weight : b.createdAt.localeCompare(a.createdAt)
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作台数据加载失败')
  } finally {
    loading.value = false
  }
}

function goList(tab: string): void {
  router.push({ path: '/staff/work-orders', query: { tab } })
}

function goDetail(order: IWorkOrder): void {
  router.push(`/staff/work-orders/${order.id}`)
}

onMounted(loadDashboard)
</script>

<template>
  <section v-loading="loading" class="staff-dashboard">
    <h1 class="page-title">工作台</h1>

    <div class="stat-cards">
      <button class="stat-card is-todo" type="button" @click="goList('todo')">
        <span class="stat-value">{{ todoCount }}</span>
        <span class="stat-label">待接单</span>
        <span class="stat-hint">已派给您的工单</span>
      </button>
      <button class="stat-card is-doing" type="button" @click="goList('doing')">
        <span class="stat-value">{{ doingCount }}</span>
        <span class="stat-label">处理中</span>
        <span class="stat-hint">已接单 / 处理中的工单</span>
      </button>
      <button class="stat-card is-done" type="button" @click="goList('done')">
        <span class="stat-value">{{ todayDoneCount }}</span>
        <span class="stat-label">今日完成</span>
        <span class="stat-hint">今日提交处理结果的工单</span>
      </button>
    </div>

    <article class="todo-card">
      <header class="todo-header">
        <h2 class="block-title">待办工单</h2>
        <el-button text type="primary" @click="goList('todo')">查看全部</el-button>
      </header>

      <el-empty v-if="todoOrders.length === 0" description="暂无待办工单" :image-size="80" />

      <el-table v-else :data="todoOrders" row-class-name="todo-row" @row-click="goDetail">
        <el-table-column prop="orderNumber" label="工单号" width="170" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="residentName" label="提交人" width="100" />
        <el-table-column label="紧急程度" width="90">
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
        <el-table-column label="提交时间" width="120">
          <template #default="{ row }">{{ formatRelative(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click.stop="goDetail(row)">
              {{ row.status === 'ASSIGNED' ? '接单处理' : '继续处理' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </article>
  </section>
</template>

<style scoped>
.page-title {
  margin: 0 0 var(--spacing-lg);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.stat-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-xs);
  padding: var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.stat-card.is-todo .stat-value {
  color: var(--status-pending);
}

.stat-card.is-doing .stat-value {
  color: var(--status-processing);
}

.stat-card.is-done .stat-value {
  color: var(--status-completed);
}

.stat-value {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
}

.stat-label {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.stat-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.todo-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.todo-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.block-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

:deep(.todo-row) {
  cursor: pointer;
}
</style>
