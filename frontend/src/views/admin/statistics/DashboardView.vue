<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import StatCard from '@/components/common/StatCard.vue'
import EChart from '@/components/common/EChart.vue'
import type { ChartOption } from '@/components/common/EChart.vue'
import { getDashboardStats, getCommunityOptions } from '@/api/statistics'
import { workOrderStatusLabels } from '@/types/modules/workorder'
import { houseStatusLabels } from '@/types/modules/community'
import type { ICommunityOption, IDashboardStats } from '@/types/modules/statistics'
import { useUserStore } from '@/store/user'

/** 运营看板：看板聚合接口一次提供统计卡片 + 四图表数据（后端扁平契约） */

use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const userStore = useUserStore()

const dashboard = ref<IDashboardStats | null>(null)
const loading = ref(false)

/* 社区筛选：SUPER_ADMIN 跨社区切换；ADMIN 仅绑定社区（后端数据级权限保证）
   且 dashboard 的 communityId 过滤对 ADMIN 一律收窄，无需前端额外过滤 */
const communityOptions = ref<ICommunityOption[]>([])
const selectedCommunityId = ref<number | null>(null)
const isSuperAdmin = computed(() => userStore.role === 'SUPER_ADMIN')

async function loadCommunityOptions(): Promise<void> {
  try {
    communityOptions.value = await getCommunityOptions()
    /* ADMIN 默认选中首个绑定社区，使卡片与图表带社区过滤口径 */
    if (!isSuperAdmin.value && communityOptions.value.length > 0) {
      selectedCommunityId.value = communityOptions.value[0].id
    }
  } catch {
    /* 下拉加载失败不阻塞看板，仍按全量口径展示 */
  }
}

/** Record<string, number> → ECharts 名值对，标签走各模块中文映射 */
function toItems(dist: Record<string, number> | undefined, labels?: Record<string, string>) {
  return Object.entries(dist ?? {}).map(([key, value]) => ({
    name: labels?.[key] ?? key,
    value
  }))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    dashboard.value = await getDashboardStats(
      selectedCommunityId.value !== null ? { communityId: selectedCommunityId.value } : undefined
    )
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载看板数据失败')
  } finally {
    loading.value = false
  }
}

function handleCommunityChange(): void {
  load()
}

onMounted(async () => {
  await loadCommunityOptions()
  load()
})

/* ------------------------------ 图表配置 ------------------------------ */

/** 近 7 日工单趋势：补齐无数据日期为 0，保证折线连续 */
const trendOption = computed<ChartOption>(() => {
  const trend = dashboard.value?.workOrderTrend7d ?? {}
  const days: { date: string; count: number }[] = []
  for (let i = 6; i >= 0; i--) {
    const date = new Date(Date.now() - i * 86400000)
    const key = `${date.getFullYear()}-${(date.getMonth() + 1).toString().padStart(2, '0')}-${date.getDate().toString().padStart(2, '0')}`
    days.push({ date: key.slice(5), count: trend[key] ?? 0 })
  }
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: days.map((d) => d.date) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '工单量',
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.12 },
        data: days.map((d) => d.count)
      }
    ]
  }
})

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
      data: toItems(dashboard.value?.workOrderStatusDistribution, workOrderStatusLabels)
    }
  ]
}))

const houseStatusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: 'scroll' },
  series: [
    {
      name: '房屋状态',
      type: 'pie',
      radius: ['40%', '66%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: toItems(dashboard.value?.houseStatusDistribution, houseStatusLabels)
    }
  ]
}))

const ratingOption = computed<ChartOption>(() => {
  const ratings = [1, 2, 3, 4, 5]
  const dist = dashboard.value?.ratingDistribution ?? {}
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: ratings.map((r) => `${r} 星`) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '评价数',
        type: 'bar',
        barMaxWidth: 36,
        data: ratings.map((r) => dist[String(r)] ?? 0)
      }
    ]
  }
})

/* 评价摘要——复用看板接口数据，不额外发请求 */
/** 房屋占用率：已入住 / 房屋总数（无房屋时无意义，显示 -） */
const occupancyPercent = computed(() => {
  const total = dashboard.value?.houseCount ?? 0
  if (!total) return '-'
  return `${((dashboard.value?.occupiedHouseCount ?? 0) / total * 100).toFixed(1)}%`
})

const avgRating = computed(() => {
  const dist = dashboard.value?.ratingDistribution ?? {}
  const total = Object.values(dist).reduce((s, n) => s + n, 0)
  if (!total) return '-'
  const weighted = Object.entries(dist).reduce((s, [r, n]) => s + Number(r) * n, 0)
  return (weighted / total).toFixed(1)
})

/** 满意率口径：4 星及以上（与独立评价统计页保持一致） */
const satisfactionPercent = computed(() => {
  const dist = dashboard.value?.ratingDistribution ?? {}
  const total = Object.values(dist).reduce((s, n) => s + n, 0)
  if (!total) return '-'
  return `${(((dist['4'] ?? 0) + (dist['5'] ?? 0)) / total * 100).toFixed(1)}%`
})
</script>

<template>
  <section class="dashboard">
    <header class="page-head">
      <div>
        <h1>运营看板</h1>
        <p class="page-head-sub">社区运营总览 · 趋势图为近 7 日口径</p>
      </div>
      <el-select
        v-model="selectedCommunityId"
        clearable
        placeholder="全部社区"
        class="community-filter"
        @change="handleCommunityChange"
      >
        <el-option
          v-for="community in communityOptions"
          :key="community.id"
          :label="community.name"
          :value="community.id"
        />
      </el-select>
    </header>

    <div v-loading="loading">
      <!-- 概览统计卡片（后端看板接口扁平字段） -->
      <template v-if="dashboard">
        <h2 class="group-title">居民与房屋</h2>
        <div class="card-grid">
          <StatCard label="社区数" :value="dashboard.communityCount" unit="个" type="primary" />
          <StatCard label="楼栋数" :value="dashboard.buildingCount" unit="栋" />
          <StatCard label="房屋总数" :value="dashboard.houseCount" unit="套" />
          <StatCard label="已入住房屋" :value="dashboard.occupiedHouseCount" unit="套" />
          <StatCard label="房屋占用率" :value="occupancyPercent" type="primary" />
          <StatCard label="居民总数" :value="dashboard.residentCount" unit="人" type="primary" />
        </div>

        <h2 class="group-title">租住与工单</h2>
        <div class="card-grid">
          <StatCard label="生效租约" :value="dashboard.activeLeaseCount" unit="份" />
          <StatCard label="即将到期租约" :value="dashboard.expiringLeaseCount" unit="份" type="warning" />
          <StatCard label="工单总数" :value="dashboard.workOrderTotal" unit="单" type="primary" />
          <StatCard label="待处理工单" :value="dashboard.workOrderPending" unit="单" type="warning" />
          <StatCard label="处理中工单" :value="dashboard.workOrderProcessing" unit="单" />
          <StatCard label="已完成工单" :value="dashboard.workOrderCompleted" unit="单" type="success" />
        </div>

        <h2 class="group-title">预约与评价</h2>
        <div class="card-grid">
          <StatCard label="预约总数" :value="dashboard.reservationTotal" unit="次" type="primary" />
          <StatCard label="待审核预约" :value="dashboard.reservationPending" unit="次" type="warning" />
          <StatCard label="违约记录" :value="dashboard.violationCount" unit="次" type="danger" />
          <StatCard label="评价总数" :value="dashboard.evaluationTotal" unit="条" />
          <StatCard label="平均评分" :value="avgRating" unit="分" type="success" />
          <StatCard label="满意率（≥4 星）" :value="satisfactionPercent" type="success" />
        </div>
      </template>

      <!-- 图表区（看板接口自带四图表数据） -->
      <h2 class="group-title">趋势与分布</h2>
      <div class="chart-grid">
        <div class="chart-card">
          <h3 class="chart-title">近 7 日工单趋势</h3>
          <EChart :option="trendOption" :height="300" />
        </div>
        <div class="chart-card">
          <h3 class="chart-title">工单状态分布</h3>
          <EChart
            :option="statusOption"
            :height="300"
            :is-empty="Object.keys(dashboard?.workOrderStatusDistribution ?? {}).length === 0"
            empty-text="暂无工单数据"
          />
        </div>
        <div class="chart-card">
          <h3 class="chart-title">房屋状态分布</h3>
          <EChart
            :option="houseStatusOption"
            :height="300"
            :is-empty="Object.keys(dashboard?.houseStatusDistribution ?? {}).length === 0"
            empty-text="暂无房屋数据"
          />
        </div>
        <div class="chart-card">
          <h3 class="chart-title">评价分档分布</h3>
          <EChart
            :option="ratingOption"
            :height="300"
            :is-empty="Object.keys(dashboard?.ratingDistribution ?? {}).length === 0"
            empty-text="暂无评价数据"
          />
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.page-head {
  margin-bottom: var(--spacing-md);
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.community-filter {
  width: 200px;
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

@media (max-width: 1023px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
