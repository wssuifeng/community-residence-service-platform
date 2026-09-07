<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getResourceStats } from '@/api/statistics'
import type { IResourceStats } from '@/types/modules/statistics'
import { reservationStatusLabels } from '@/types/modules/reservation'
import { formatDate, subDays, todayISO } from '@/utils/date'

/** 资源预约统计：默认展示近 90 天（接口设计.md 9.9.1.4） */
const loading = ref(false)
const stats = ref<IResourceStats | null>(null)
const range = ref<[string, string]>([formatDate(subDays(90)), todayISO()])

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getResourceStats({ startDate: range.value[0], endDate: range.value[1] })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资源统计失败')
  } finally {
    loading.value = false
  }
}

const resourceOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: stats.value?.byResource.map((item) => item.resourceName) ?? [] },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      type: 'bar',
      name: '预约量',
      barMaxWidth: 40,
      data: stats.value?.byResource.map((item) => item.count) ?? []
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
        name: reservationStatusLabels[item.status as keyof typeof reservationStatusLabels] ?? item.status,
        value: item.count
      }))
    }
  ]
}))

const completionPercent = computed(() => `${((stats.value?.summary.completionRate ?? 0) * 100).toFixed(1)}%`)
const violationPercent = computed(() => `${((stats.value?.summary.violationRate ?? 0) * 100).toFixed(2)}%`)
</script>

<template>
  <section v-loading="loading" class="resource-stats">
    <div class="stat-grid">
      <StatCard label="预约总量" :value="stats?.summary.totalReservations ?? '-'" type="primary" />
      <StatCard label="完成率" :value="completionPercent" type="success" />
      <StatCard label="违约次数" :value="stats?.summary.violationCount ?? '-'" type="warning" />
      <StatCard label="违约率" :value="violationPercent" />
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>各资源预约量</h3>
        <EChart
          :option="resourceOption"
          :is-empty="(stats?.byResource.length ?? 0) === 0"
          empty-text="暂无预约数据"
        />
      </div>
      <div class="chart-card">
        <h3>预约状态分布</h3>
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
