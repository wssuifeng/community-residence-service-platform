<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getResidentStats } from '@/api/statistics'
import { residentStatusLabels } from '@/types/modules/resident'
import type { IResidentStats } from '@/types/modules/statistics'

/** 居民统计：总量 + 账号状态分布（后端扁平契约：total/byStatus） */
const loading = ref(false)
const stats = ref<IResidentStats | null>(null)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getResidentStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载居民统计失败')
  } finally {
    loading.value = false
  }
}

const statusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: Object.entries(stats.value?.byStatus ?? {}).map(([status, count]) => ({
        name: residentStatusLabels[status as keyof typeof residentStatusLabels] ?? status,
        value: count
      }))
    }
  ]
}))
</script>

<template>
  <section v-loading="loading" class="resident-stats">
    <div class="stat-grid">
      <StatCard label="居民总数" :value="stats?.total ?? '-'" unit="人" type="primary" />
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>账号状态分布</h3>
        <EChart
          :option="statusOption"
          :is-empty="Object.keys(stats?.byStatus ?? {}).length === 0"
          empty-text="暂无状态数据"
        />
      </div>
      <div class="chart-card">
        <h3>状态明细</h3>
        <ul v-if="stats && Object.keys(stats.byStatus).length" class="detail-list">
          <li v-for="(count, status) in stats.byStatus" :key="status">
            <span>{{ residentStatusLabels[status as keyof typeof residentStatusLabels] ?? status }}</span>
            <b>{{ count }}</b>
          </li>
        </ul>
        <p v-else class="empty-text">暂无数据</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.chart-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--spacing-md);
}

.chart-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
}

.chart-card h3 {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  margin-bottom: var(--spacing-md);
}

.detail-list li {
  display: flex;
  justify-content: space-between;
  padding: var(--spacing-sm) 0;
  border-bottom: 1px solid var(--color-border);
  font-size: var(--font-size-sm);
}

.detail-list li b {
  color: var(--color-primary);
}

.empty-text {
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

@media (max-width: 1023px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
