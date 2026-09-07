<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getResidentStats } from '@/api/statistics'
import type { IResidentStats } from '@/types/modules/statistics'
import { residentStatusLabels } from '@/types/modules/resident'

/** 居民统计：注册趋势 + 账号状态分布（接口设计.md 9.9.1.3） */
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

const trendOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: stats.value?.registrationTrend.map((item) => item.month) ?? [] },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      type: 'line',
      name: '新增注册',
      smooth: true,
      areaStyle: { opacity: 0.08 },
      data: stats.value?.registrationTrend.map((item) => item.count) ?? []
    }
  ]
}))

const statusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['42%', '68%'],
      data: (stats.value?.byStatus ?? []).map((item) => ({
        name: residentStatusLabels[item.status as keyof typeof residentStatusLabels] ?? item.status,
        value: item.count
      }))
    }
  ]
}))
</script>

<template>
  <section v-loading="loading" class="resident-stats">
    <div class="stat-grid">
      <StatCard label="居民总数" :value="stats?.summary.total ?? '-'" type="primary" />
      <StatCard label="活跃居民" :value="stats?.summary.active ?? '-'" type="success" />
      <StatCard label="冻结账号" :value="stats?.summary.frozen ?? '-'" type="warning" />
      <StatCard label="本月新增" :value="stats?.summary.newThisMonth ?? '-'" />
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>注册趋势（按月）</h3>
        <EChart
          :option="trendOption"
          :is-empty="(stats?.registrationTrend.length ?? 0) === 0"
          empty-text="暂无注册数据"
        />
      </div>
      <div class="chart-card">
        <h3>账号状态分布</h3>
        <EChart
          :option="statusOption"
          :is-empty="(stats?.byStatus.length ?? 0) === 0"
          empty-text="暂无状态数据"
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
  color: var(--color-text-primary);
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
