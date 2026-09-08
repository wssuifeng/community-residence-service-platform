<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getResourceStats } from '@/api/statistics'
import { reservationStatusLabels } from '@/types/modules/reservation'
import type { IResourceStats } from '@/types/modules/statistics'

/** 资源预约统计：总量 + 预约状态分布（后端扁平契约：total/byStatus） */
const loading = ref(false)
const stats = ref<IResourceStats | null>(null)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getResourceStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资源统计失败')
  } finally {
    loading.value = false
  }
}

const statusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: 'scroll' },
  series: [
    {
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: Object.entries(stats.value?.byStatus ?? {}).map(([status, count]) => ({
        name: reservationStatusLabels[status as keyof typeof reservationStatusLabels] ?? status,
        value: count
      }))
    }
  ]
}))
</script>

<template>
  <section v-loading="loading" class="resource-stats">
    <div class="stat-grid">
      <StatCard label="预约总量" :value="stats?.total ?? '-'" unit="次" type="primary" />
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>预约状态分布</h3>
        <EChart
          :option="statusOption"
          :is-empty="Object.keys(stats?.byStatus ?? {}).length === 0"
          empty-text="暂无预约数据"
        />
      </div>
      <div class="chart-card">
        <h3>状态明细</h3>
        <ul v-if="stats && Object.keys(stats.byStatus).length" class="detail-list">
          <li v-for="(count, status) in stats.byStatus" :key="status">
            <span>{{ reservationStatusLabels[status as keyof typeof reservationStatusLabels] ?? status }}</span>
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
