<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import { cancelWorkOrder, listWorkOrders } from '@/api/workorder'
import type { IWorkOrder, WorkOrderStatus } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import { formatRelative } from '@/utils/date'

/** 我的工单（UI设计.md §4.1.2）：状态筛选 + 关键字搜索 + 卡片列表 + 分页 */

/* 工单状态 → StatusTag 语义色（状态机映射：待*黄 / 处理蓝 / 完成绿 / 驳回红 / 取消灰） */
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

/* 紧急程度 → el-tag 语义色 */
const priorityTagType: Record<string, 'info' | 'primary' | 'warning' | 'danger'> = {
  LOW: 'info',
  NORMAL: 'primary',
  HIGH: 'warning',
  URGENT: 'danger'
}

const router = useRouter()

const statusFilter = ref<WorkOrderStatus | ''>('')
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IWorkOrder[]>([])
const loading = ref(false)

const statusOptions = (Object.keys(workOrderStatusLabels) as WorkOrderStatus[]).map((value) => ({
  value,
  label: workOrderStatusLabels[value]
}))

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listWorkOrders({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined
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

watch(statusFilter, () => {
  page.value = 1
  fetchList()
})

watch([page, size], () => {
  fetchList()
})

/* 仅提交人可取消，且限待受理/待派单阶段（状态机） */
async function handleCancel(order: IWorkOrder): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('取消后无法恢复，请填写取消原因', `取消工单 ${order.orderNo}`, {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '取消原因（必填）',
      inputValidator: (input: string) => (input.trim() ? true : '请填写取消原因')
    })
    await cancelWorkOrder(order.id, { reason: value.trim() })
    ElMessage.success('工单已取消')
    fetchList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '取消失败')
  }
}

function goDetail(order: IWorkOrder): void {
  router.push(`/resident/work-orders/${order.id}`)
}

onMounted(fetchList)
</script>

<template>
  <section class="work-order-list">
    <header class="page-header">
      <div>
        <h1 class="page-title">我的工单</h1>
        <p class="page-subtitle">提交报修与服务申请，随时跟踪处理进度</p>
      </div>
      <el-button type="primary" size="large" @click="router.push('/resident/work-orders/create')">
        + 提交工单
      </el-button>
    </header>

    <div class="list-toolbar">
      <el-select v-model="statusFilter" class="status-select" placeholder="全部状态">
        <el-option label="全部状态" value="" />
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <SearchBar v-model="keyword" placeholder="搜索标题 / 描述" @search="handleSearch" />
    </div>

    <div v-loading="loading" class="card-list">
      <el-empty v-if="!loading && records.length === 0" description="暂无工单，点击右上角「提交工单」发起服务申请" />

      <article v-for="order in records" :key="order.id" class="order-card" @click="goDetail(order)">
        <div class="order-card-head">
          <span class="order-number">{{ order.orderNo }}</span>
          <div class="order-card-tags">
            <el-tag :type="priorityTagType[order.priority]" effect="light" size="small">
              {{ workOrderPriorityLabels[order.priority] }}
            </el-tag>
            <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
          </div>
        </div>

        <h2 class="order-title">{{ order.title }}</h2>
        <p class="order-meta">
          <span class="order-category">{{ order.categoryName }}</span>
          <span class="order-dot">·</span>
          <span>提交于 {{ formatRelative(order.createdAt) }}</span>
          <template v-if="order.assigneeName">
            <span class="order-dot">·</span>
            <span>当前处理人：{{ order.assigneeName }}</span>
          </template>
        </p>

        <div class="order-card-actions" @click.stop>
          <el-button text type="primary" @click="goDetail(order)">查看详情</el-button>
          <el-button
            v-if="order.status === 'PENDING' || order.status === 'TO_ASSIGN'"
            text
            type="danger"
            @click="handleCancel(order)"
          >
            取消工单
          </el-button>
          <el-button v-if="order.status === 'TO_CONFIRM'" text type="success" @click="goDetail(order)">
            去确认
          </el-button>
          <el-button v-if="order.status === 'COMPLETED'" text type="warning" @click="goDetail(order)">
            去评价
          </el-button>
        </div>
      </article>
    </div>

    <Pagination v-model:page="page" v-model:size="size" :total="total" />
  </section>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.page-subtitle {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  flex-wrap: wrap;
}

.status-select {
  width: 160px;
}

.card-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 200px;
}

.order-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.order-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.order-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.order-number {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.order-card-tags {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.order-title {
  margin: var(--spacing-sm) 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.order-meta {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.order-category {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  border-radius: var(--radius-pill);
  padding: 2px var(--spacing-sm);
  font-size: var(--font-size-xs);
}

.order-dot {
  color: var(--color-text-disabled);
}

.order-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-sm);
  border-top: 1px solid var(--color-border);
  padding-top: var(--spacing-sm);
}
</style>
