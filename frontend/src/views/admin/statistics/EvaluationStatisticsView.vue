<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getEvaluationStats } from '@/api/statistics'
import type { IEvaluationStats } from '@/types/modules/statistics'
import { formatDate, subDays, todayISO } from '@/utils/date'

/** 服务评价统计：默认近 90 天（接口设计.md 9.9.1.5） */
const loading = ref(false)
const stats = ref<IEvaluationStats | null>(null)
const range = ref<[string, string]>([formatDate(subDays(90)), todayISO()])

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getEvaluationStats({ startDate: range.value[0], endDate: range.value[1] })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载评价统计失败')
  } finally {
    loading.value = false
  }
}

const ratingOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', name: '星级', data: (stats.value?.ratingDistribution ?? []).map((item) => `${item.rating} 星`) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      type: 'bar',
      name: '评价数',
      barMaxWidth: 48,
      data: (stats.value?.ratingDistribution ?? []).map((item) => item.count)
    }
  ]
}))

const assigneeOption = computed<ChartOption>(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: (params: unknown) => {
      const list = params as { name: string; value: number; seriesName: string }[]
      const target = stats.value?.byAssignee.find((item) => item.assigneeName === list[0]?.name)
      const avg = target ? `均分 ${target.avgRating.toFixed(1)}` : ''
      return `${list[0]?.name}<br/>${list[0]?.seriesName}：${list[0]?.value} 条<br/>${avg}`
    }
  },
  xAxis: { type: 'category', data: (stats.value?.byAssignee ?? []).map((item) => item.assigneeName) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      type: 'bar',
      name: '被评价次数',
      barMaxWidth: 40,
      data: (stats.value?.byAssignee ?? []).map((item) => item.count)
    }
  ]
}))

const satisfactionPercent = computed(() => `${((stats.value?.summary.satisfactionRate ?? 0) * 100).toFixed(1)}%`)
</script>

<template>
  <section v-loading="loading" class="evaluation-stats">
    <div class="stat-grid">
      <StatCard label="评价总数" :value="stats?.summary.total ?? '-'" type="primary" />
      <StatCard label="平均评分" :value="stats?.summary.avgRating?.toFixed(1) ?? '-'" unit="分" type="success" />
      <StatCard label="满意率" :value="satisfactionPercent" type="success" />
      <StatCard label="不满意待跟进" :value="stats?.summary.unsatisfiedCount ?? '-'" type="warning" />
    </div>

    <div class="chart-grid">
      <div class="chart-card">
        <h3>评分分布</h3>
        <EChart
          :option="ratingOption"
          :is-empty="(stats?.ratingDistribution.length ?? 0) === 0"
          empty-text="暂无评分数据"
        />
      </div>
      <div class="chart-card">
        <h3>服务人员被评价情况</h3>
        <EChart
          :option="assigneeOption"
          :is-empty="(stats?.byAssignee.length ?? 0) === 0"
          empty-text="暂无人员评价数据"
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
