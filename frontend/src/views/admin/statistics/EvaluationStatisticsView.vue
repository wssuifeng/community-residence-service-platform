<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getDashboardStats } from '@/api/statistics'
import type { IDashboardStats } from '@/types/modules/statistics'

/**
 * 服务评价统计：后端无独立 /statistics/evaluations 端点，
 * 评价数据（总数 + 分档分布）取自看板聚合接口
 */
const loading = ref(false)
const stats = ref<IDashboardStats | null>(null)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getDashboardStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载评价统计失败')
  } finally {
    loading.value = false
  }
}

const ratingOption = computed<ChartOption>(() => {
  const dist = stats.value?.ratingDistribution ?? {}
  const ratings = [1, 2, 3, 4, 5]
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: ratings.map((r) => `${r} 星`) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '评价数',
        type: 'bar',
        barMaxWidth: 40,
        data: ratings.map((r) => dist[String(r)] ?? 0)
      }
    ]
  }
})

/** 满意率口径：4 星及以上为满意（评价体系约定） */
const satisfactionPercent = computed(() => {
  const dist = stats.value?.ratingDistribution ?? {}
  const total = Object.values(dist).reduce((sum, count) => sum + count, 0)
  if (!total) return '-'
  const satisfied = (dist['4'] ?? 0) + (dist['5'] ?? 0)
  return `${((satisfied / total) * 100).toFixed(1)}%`
})

const avgRating = computed(() => {
  const dist = stats.value?.ratingDistribution ?? {}
  const total = Object.values(dist).reduce((sum, count) => sum + count, 0)
  if (!total) return '-'
  const weighted = Object.entries(dist).reduce((sum, [rating, count]) => sum + Number(rating) * count, 0)
  return (weighted / total).toFixed(1)
})
</script>

<template>
  <section v-loading="loading" class="evaluation-stats">
    <div class="stat-grid">
      <StatCard label="评价总数" :value="stats?.evaluationTotal ?? '-'" unit="条" type="primary" />
      <StatCard label="平均评分" :value="avgRating" unit="分" type="success" />
      <StatCard label="满意率（≥4 星）" :value="satisfactionPercent" type="success" />
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>评分分布</h3>
        <EChart
          :option="ratingOption"
          :is-empty="Object.keys(stats?.ratingDistribution ?? {}).length === 0"
          empty-text="暂无评分数据"
        />
      </div>
      <div class="chart-card">
        <h3>分档明细</h3>
        <ul v-if="stats && Object.keys(stats.ratingDistribution).length" class="detail-list">
          <li v-for="(count, rating) in stats.ratingDistribution" :key="rating">
            <span>{{ rating }} 星</span>
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
  grid-template-columns: repeat(3, 1fr);
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
    grid-template-columns: 1fr;
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
