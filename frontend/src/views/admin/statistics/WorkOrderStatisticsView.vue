<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getWorkOrderStats } from '@/api/statistics'
import { workOrderStatusLabels } from '@/types/modules/workorder'
import type { IWorkOrderStats } from '@/types/modules/statistics'

/** 工单统计：总量卡片 + 状态/优先级分布（后端扁平契约：total/byStatus/byPriority） */
const loading = ref(false)
const stats = ref<IWorkOrderStats | null>(null)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getWorkOrderStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载工单统计失败')
  } finally {
    loading.value = false
  }
}

/** 状态码 → 中文标签（labels 为具体状态键的映射，用 in 收窄后索引） */
function toItems(dist: Record<string, number>, labels?: object) {
  return Object.entries(dist).map(([key, value]) => ({
    name: labels && key in labels ? (labels as Record<string, string>)[key] : key,
    value
  }))
}

const statusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: 'scroll' },
  series: [
    {
      name: '工单状态',
      type: 'pie',
      radius: ['40%', '66%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: toItems(stats.value?.byStatus ?? {}, workOrderStatusLabels)
    }
  ]
}))

const priorityOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 24, bottom: 28 },
  xAxis: { type: 'category', data: Object.keys(stats.value?.byPriority ?? {}) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: '工单数',
      type: 'bar',
      barMaxWidth: 40,
      data: Object.values(stats.value?.byPriority ?? {})
    }
  ]
}))
</script>

<template>
  <section v-loading="loading" class="work-order-stats">
    <div class="stat-grid">
      <StatCard label="工单总数" :value="stats?.total ?? '-'" unit="单" type="primary" />
    </div>

    <div class="stat-rows">
      <div class="stat-row">
        <h3>状态分布</h3>
        <ul v-if="stats && Object.keys(stats.byStatus).length">
          <li v-for="(count, status) in stats.byStatus" :key="status">
            <span>{{ (workOrderStatusLabels as Record<string, string>)[status] ?? status }}</span>
            <b>{{ count }}</b>
          </li>
        </ul>
        <p v-else class="empty-text">暂无数据</p>
      </div>
      <div class="stat-row">
        <h3>优先级分布</h3>
        <ul v-if="stats && Object.keys(stats.byPriority).length">
          <li v-for="(count, priority) in stats.byPriority" :key="priority">
            <span>{{ priority }}</span>
            <b>{{ count }}</b>
          </li>
        </ul>
        <p v-else class="empty-text">暂无数据</p>
      </div>
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>状态占比</h3>
        <EChart
          :option="statusOption"
          :is-empty="Object.keys(stats?.byStatus ?? {}).length === 0"
          empty-text="暂无工单数据"
        />
      </div>
      <div class="chart-card">
        <h3>优先级分布</h3>
        <EChart
          :option="priorityOption"
          :is-empty="Object.keys(stats?.byPriority ?? {}).length === 0"
          empty-text="暂无优先级数据"
        />
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

.stat-rows {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.stat-row {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
}

.stat-row h3 {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  margin-bottom: var(--spacing-md);
}

.stat-row li {
  display: flex;
  justify-content: space-between;
  padding: var(--spacing-xs) 0;
  border-bottom: 1px solid var(--color-border);
  font-size: var(--font-size-sm);
}

.stat-row li b {
  color: var(--color-primary);
}

.empty-text {
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
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

@media (max-width: 1023px) {
  .stat-grid,
  .stat-rows,
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
