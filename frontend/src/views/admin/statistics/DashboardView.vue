<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import StatCard from '@/components/common/StatCard.vue'
import { getDashboardStats, getWorkOrderStats, getEvaluationStats, getResourceStats } from '@/api/statistics'
import type {
  IDashboardStats,
  IWorkOrderStats,
  IEvaluationStats,
  IResourceStats
} from '@/types/modules/statistics'

/** 运营看板：概览统计卡片 + 工单趋势/类别分布/评价分布/预约趋势 四图表 */

use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const CHART_COLORS = ['#3b6dff', '#6b8fff', '#10b981', '#f59e0b', '#ef4444', '#6b7280']

function toDateInput(date: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 图表统计口径统一取近 30 天 */
const rangeStart = toDateInput(new Date(Date.now() - 29 * 24 * 60 * 60 * 1000))
const rangeEnd = toDateInput(new Date())

const dashboard = ref<IDashboardStats | null>(null)
const workOrderStats = ref<IWorkOrderStats | null>(null)
const evaluationStats = ref<IEvaluationStats | null>(null)
const resourceStats = ref<IResourceStats | null>(null)

async function loadDashboard(): Promise<void> {
  dashboard.value = await getDashboardStats()
}

async function loadCharts(): Promise<void> {
  const [workOrders, evaluations, resources] = await Promise.all([
    getWorkOrderStats({ startDate: rangeStart, endDate: rangeEnd, groupBy: 'DAY' }),
    getEvaluationStats({ startDate: rangeStart, endDate: rangeEnd }),
    getResourceStats({ startDate: rangeStart, endDate: rangeEnd })
  ])
  workOrderStats.value = workOrders
  evaluationStats.value = evaluations
  resourceStats.value = resources
}

onMounted(() => {
  loadDashboard().catch((error: unknown) =>
    ElMessage.error(error instanceof Error ? error.message : '加载看板数据失败')
  )
  loadCharts().catch((error: unknown) =>
    ElMessage.error(error instanceof Error ? error.message : '加载图表数据失败')
  )
})

/* ------------------------------ 图表配置 ------------------------------ */

const trendOption = computed(() => {
  const trend = workOrderStats.value?.trend ?? []
  return {
    color: CHART_COLORS,
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 32, bottom: 32 },
    xAxis: { type: 'category', data: trend.map((point) => point.date) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '工单量',
        type: 'line',
        smooth: true,
        data: trend.map((point) => point.count),
        areaStyle: { opacity: 0.12 }
      }
    ]
  }
})

const categoryOption = computed(() => {
  const byCategory = workOrderStats.value?.byCategory ?? []
  return {
    color: CHART_COLORS,
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', right: 8, top: 'center', type: 'scroll' },
    series: [
      {
        name: '工单类别',
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['38%', '50%'],
        data: byCategory.map((item) => ({ name: item.categoryName, value: item.count })),
        label: { show: false }
      }
    ]
  }
})

const ratingOption = computed(() => {
  const distribution = evaluationStats.value?.ratingDistribution ?? []
  return {
    color: CHART_COLORS,
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 32, bottom: 32 },
    xAxis: { type: 'category', data: distribution.map((item) => `${item.rating} 星`) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '评价数',
        type: 'bar',
        barMaxWidth: 36,
        data: distribution.map((item) => item.count)
      }
    ]
  }
})

/** 资源预约趋势：接口按资源维度返回预约量，按资源序列绘制 */
const reservationOption = computed(() => {
  const byResource = resourceStats.value?.byResource ?? []
  return {
    color: CHART_COLORS,
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 32, bottom: 32 },
    xAxis: { type: 'category', data: byResource.map((item) => item.resourceName), axisLabel: { interval: 0, rotate: 18 } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '预约量',
        type: 'line',
        smooth: true,
        data: byResource.map((item) => item.count),
        areaStyle: { opacity: 0.12 }
      }
    ]
  }
})

const hasTrend = computed(() => (workOrderStats.value?.trend ?? []).length > 0)
const hasCategory = computed(() => (workOrderStats.value?.byCategory ?? []).length > 0)
const hasRating = computed(() => (evaluationStats.value?.ratingDistribution ?? []).length > 0)
const hasReservation = computed(() => (resourceStats.value?.byResource ?? []).length > 0)

const rateText = (rate: number | undefined): string =>
  rate === undefined ? '-' : `${(rate * 100).toFixed(1)}%`
</script>

<template>
  <section class="dashboard">
    <header class="page-head">
      <h1>运营看板</h1>
      <p class="page-head-sub">社区运营总览 · 图表统计口径为近 30 天</p>
    </header>

    <!-- 概览统计卡片 -->
    <template v-if="dashboard">
      <h2 class="group-title">居民与房屋</h2>
      <div class="card-grid">
        <StatCard label="居民总数" :value="dashboard.overview.totalResidents" unit="人" type="primary" />
        <StatCard label="活跃居民" :value="dashboard.overview.activeResidents" unit="人" />
        <StatCard label="房屋总数" :value="dashboard.overview.totalHouses" unit="套" />
        <StatCard label="已入住房屋" :value="dashboard.overview.occupiedHouses" unit="套" />
        <StatCard label="入住率" :value="rateText(dashboard.overview.occupancyRate)" type="success" />
      </div>

      <h2 class="group-title">工单服务</h2>
      <div class="card-grid">
        <StatCard label="工单总数" :value="dashboard.workOrders.total" unit="单" type="primary" />
        <StatCard label="待处理工单" :value="dashboard.workOrders.pending" unit="单" type="warning" />
        <StatCard label="处理中工单" :value="dashboard.workOrders.inProgress" unit="单" />
        <StatCard label="已完成工单" :value="dashboard.workOrders.completed" unit="单" type="success" />
        <StatCard label="平均完成时长" :value="dashboard.workOrders.avgCompletionTime" unit="小时" />
        <StatCard label="满意率" :value="rateText(dashboard.workOrders.satisfactionRate)" type="success" />
      </div>

      <div class="group-row">
        <div>
          <h2 class="group-title">资源预约</h2>
          <div class="card-grid">
            <StatCard label="预约总数" :value="dashboard.resources.totalReservations" unit="次" type="primary" />
            <StatCard label="预约完成率" :value="rateText(dashboard.resources.completionRate)" type="success" />
            <StatCard label="违约次数" :value="dashboard.resources.violationCount" unit="次" type="danger" />
          </div>
        </div>
        <div>
          <h2 class="group-title">居民反馈</h2>
          <div class="card-grid">
            <StatCard label="反馈总数" :value="dashboard.feedbacks.total" unit="条" type="primary" />
            <StatCard label="待受理" :value="dashboard.feedbacks.open" unit="条" type="warning" />
            <StatCard label="处理中" :value="dashboard.feedbacks.inProgress" unit="条" />
            <StatCard label="已办结" :value="dashboard.feedbacks.closed" unit="条" type="success" />
            <StatCard label="平均响应时长" :value="dashboard.feedbacks.avgResponseTime" unit="小时" />
          </div>
        </div>
      </div>
    </template>
    <div v-else v-loading="true" class="cards-loading" />

    <!-- 图表区 -->
    <h2 class="group-title">趋势与分布（近 30 天）</h2>
    <div class="chart-grid">
      <div class="chart-card">
        <h3 class="chart-title">工单趋势</h3>
        <VChart v-if="hasTrend" :option="trendOption" autoresize class="chart" />
        <div v-else class="chart-empty">暂无工单趋势数据</div>
      </div>
      <div class="chart-card">
        <h3 class="chart-title">工单类别分布</h3>
        <VChart v-if="hasCategory" :option="categoryOption" autoresize class="chart" />
        <div v-else class="chart-empty">暂无类别分布数据</div>
      </div>
      <div class="chart-card">
        <h3 class="chart-title">评价分布</h3>
        <VChart v-if="hasRating" :option="ratingOption" autoresize class="chart" />
        <div v-else class="chart-empty">暂无评价数据</div>
      </div>
      <div class="chart-card">
        <h3 class="chart-title">资源预约趋势</h3>
        <VChart v-if="hasReservation" :option="reservationOption" autoresize class="chart" />
        <div v-else class="chart-empty">暂无资源预约数据</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  margin-bottom: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.group-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  margin: var(--spacing-lg) 0 var(--spacing-md);
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: var(--spacing-md);
}

.cards-loading {
  height: 200px;
}

.group-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: var(--spacing-md);
  align-items: start;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-md);
}

.chart-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
}

.chart-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-sm);
}

.chart {
  height: 300px;
}

.chart-empty {
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

@media (max-width: 1023px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
