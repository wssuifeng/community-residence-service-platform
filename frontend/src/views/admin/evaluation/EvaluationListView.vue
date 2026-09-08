<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { listEvaluations, listUnsatisfiedEvaluations } from '@/api/evaluation'
import type { IEvaluation } from '@/types/modules/evaluation'
import { formatDateTime } from '@/utils/date'

/** 评价列表（UI设计.md §3.4.8）：满意度筛选 + 分页 + 不满意跟进入口 */

type SatisfiedFilter = '' | 'satisfied' | 'unsatisfied' | 'followup'

const router = useRouter()

const filter = ref<SatisfiedFilter>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IEvaluation[]>([])
const loading = ref(false)

const filterOptions: { value: SatisfiedFilter; label: string }[] = [
  { value: '', label: '全部评价' },
  { value: 'satisfied', label: '满意' },
  { value: 'unsatisfied', label: '不满意' },
  { value: 'followup', label: '不满意未跟进' }
]

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    /* 不满意维度走专用接口（接口设计.md 9.8.1.4），其余走通用列表接口 */
    let result
    if (filter.value === 'unsatisfied') {
      result = await listEvaluations({ page: page.value, size: size.value, isSatisfied: false })
    } else if (filter.value === 'followup') {
      result = await listUnsatisfiedEvaluations({ page: page.value, size: size.value, hasFollowup: false })
    } else if (filter.value === 'satisfied') {
      result = await listEvaluations({ page: page.value, size: size.value, isSatisfied: true })
    } else {
      result = await listEvaluations({ page: page.value, size: size.value })
    }
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评价列表加载失败')
  } finally {
    loading.value = false
  }
}

watch(filter, () => {
  page.value = 1
  fetchList()
})

watch([page, size], () => {
  fetchList()
})

function goFollowup(evaluation: IEvaluation): void {
  router.push(`/admin/evaluations/${evaluation.id}/followup`)
}

function goWorkOrder(evaluation: IEvaluation): void {
  router.push(`/admin/work-orders/${evaluation.workOrderId}`)
}

onMounted(fetchList)
</script>

<template>
  <section class="evaluation-list">
    <header class="page-header">
      <h1 class="page-title">评价列表</h1>
    </header>

    <div class="filter-bar">
      <el-radio-group v-model="filter">
        <el-radio-button v-for="item in filterOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <el-table v-loading="loading" :data="records" class="evaluation-table">
      <el-table-column prop="workOrderNo" label="工单号" width="170">
        <template #default="{ row }">
          <el-link type="primary" @click="goWorkOrder(row)">{{ row.workOrderNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="评价人" width="120">
        <template #default="{ row }">{{ row.residentName }}</template>
      </el-table-column>
      <el-table-column prop="assigneeName" label="服务人员" width="110" />
      <el-table-column label="总体评分" width="130">
        <template #default="{ row }">
          <el-rate :model-value="row.rating" disabled size="small" />
        </template>
      </el-table-column>
      <el-table-column label="分项" width="150">
        <template #default="{ row }">
          <span class="sub-rates">
            {{ row.tags ?? '无标签' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="评价内容" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.content ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="满意度" width="90">
        <template #default="{ row }">
          <StatusTag :label="row.isSatisfied ? '满意' : '不满意'" :type="row.isSatisfied ? 'completed' : 'rejected'" />
        </template>
      </el-table-column>
      <el-table-column label="评价时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="!row.isSatisfied"
            text
            type="primary"
            size="small"
            @click="goFollowup(row)"
          >
            跟进
          </el-button>
          <span v-else class="no-action">-</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无评价" :image-size="80" />
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

.filter-bar {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.evaluation-table {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.sub-rates {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.no-action {
  color: var(--color-text-disabled);
}
</style>
