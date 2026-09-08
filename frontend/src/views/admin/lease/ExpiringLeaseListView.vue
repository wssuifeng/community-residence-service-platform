<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getExpiringLeaseList } from '@/api/lease'
import type { ILeaseRecord, LeaseStatus } from '@/types/modules/lease'
import { leaseStatusLabels } from '@/types/modules/lease'
import { formatDate } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 即将到期租住列表：默认 30 天窗口，按到期日升序，行内剩余天数预警 */
const leases = ref<ILeaseRecord[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const daysWindow = ref(30)

/** 租住状态 → StatusTag 语义色（生效 completed / 到期、终止 canceled） */
function statusTagType(status: LeaseStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

/** 距到期剩余天数（负数 = 已逾期） */
function remainingDays(endDate: string): number {
  const end = new Date(`${formatDate(endDate)}T00:00:00`).getTime()
  const today = new Date(
    `${formatDate(new Date().toISOString())}T00:00:00`
  ).getTime()
  return Math.round((end - today) / 86400000)
}

/** 剩余天数展示文案 */
function remainingText(days: number): string {
  if (days < 0) return `已逾期 ${Math.abs(days)} 天`
  if (days === 0) return '今日到期'
  return `剩 ${days} 天`
}

const summaryText = computed(() => {
  const overdue = leases.value.filter((item) => remainingDays(item.leaseEndDate) < 0).length
  return `共 ${total.value} 条即将到期${overdue > 0 ? `，其中已逾期 ${overdue} 条` : ''}`
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getExpiringLeaseList({
      page: page.value,
      size: size.value,
      days: daysWindow.value
    })
    /* 后端排序不可依赖，前端按到期日升序兜底（同页内排序） */
    leases.value = [...result.records].sort(
      (a, b) =>
        new Date(a.leaseEndDate).getTime() - new Date(b.leaseEndDate).getTime()
    )
    total.value = result.total
  } catch {
    leases.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleFilterChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  daysWindow.value = 30
  page.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <section class="expiring-lease-list">
    <div class="list-toolbar">
      <h2 class="list-title">即将到期</h2>
      <span class="list-summary">{{ summaryText }}</span>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">到期窗口</span>
      <el-select
        v-model="daysWindow"
        style="width: 160px"
        @change="handleFilterChange"
      >
        <el-option label="未来 7 天" :value="7" />
        <el-option label="未来 15 天" :value="15" />
        <el-option label="未来 30 天" :value="30" />
        <el-option label="未来 60 天" :value="60" />
        <el-option label="未来 90 天" :value="90" />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="leases" stripe>
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="residentName" label="居民" min-width="100" show-overflow-tooltip />
      <el-table-column prop="houseAddress" label="房屋" min-width="180" show-overflow-tooltip />
      <el-table-column label="租期" min-width="200">
        <template #default="{ row }">
          {{ formatDate(row.leaseStartDate) }} ~ {{ formatDate(row.leaseEndDate) }}
        </template>
      </el-table-column>
      <el-table-column label="到期日" width="110" sortable sort-by="leaseEndDate">
        <template #default="{ row }">{{ formatDate(row.leaseEndDate) }}</template>
      </el-table-column>
      <el-table-column label="剩余天数" width="120">
        <template #default="{ row }">
          <span
            :style="{
              color:
                remainingDays(row.leaseEndDate) < 0
                  ? 'var(--color-danger)'
                  : remainingDays(row.leaseEndDate) < 30
                    ? 'var(--color-warning)'
                    : 'var(--color-text-primary)'
            }"
          >
            {{ remainingText(remainingDays(row.leaseEndDate)) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag
            :label="leaseStatusLabels[row.status as LeaseStatus]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
    </el-table>

    <Pagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      @update:page="load"
      @update:size="load"
    />
  </section>
</template>

<style scoped>
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.list-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.list-summary {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
