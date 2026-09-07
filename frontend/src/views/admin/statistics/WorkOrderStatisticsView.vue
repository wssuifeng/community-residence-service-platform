<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import StatCard from '@/components/common/StatCard.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { getWorkOrderStats } from '@/api/statistics'
import type { IWorkOrderStats, StatisticsGroupBy } from '@/types/modules/statistics'
import { workOrderStatusLabels } from '@/types/modules/workorder'

/** 工单统计详情页：时间范围/分组维度筛选 + 汇总卡片 + 趋势折线 + 状态分布饼图 + 类别分布表格 */

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const CHART_COLORS = ['#3b6dff', '#6b8fff', '#10b981', '#f59e0b', '#ef4444', '#6b7280']

function toDateInput(date: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const groupByOptions: { value: StatisticsGroupBy; label: string }[] = [
  { value: 'DAY', label: '按日' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' }
]

const query = reactive({
  dateRange: [toDateInput(new Date(Date.now() - 29 * 24 * 60 * 60 * 1000)), toDateInput(new Date())] as [string, string],
  groupBy: 'DAY' as StatisticsGroupBy
})

const stats = ref<IWorkOrderStats | null>(null)
const loading = ref(false)

async function loadStats(): Promise<void> {
  loading.value = true
  try {
    stats.value = await getWorkOrderStats({
      startDate: query.dateRange[0],
      endDate: query.dateRange[1],
      groupBy: query.groupBy
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载工单统计失败')
  } finally {
    loading.value = false
  }
}

function handleReset(): void {
  query.dateRange = [toDateInput(new Date(Date.now() - 29 * 24 * 60 * 60 * 1000)), toDateInput(new Date())]
  query.groupBy = 'DAY'
  loadStats()
}

/** 工单状态码 → 中文标签（byStatus.status 为后端状态枚举值） */
function statusLabel(status: string): string {
  return workOrderStatusLabels[status as keyof typeof workOrderStatusLabels] ?? status
}

/** 状态 → StatusTag 语义色 */
function statusTagType(status: string): 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled' | 'info' {
  switch (status) {
    case 'PENDING':
      return 'pending'
    case 'REJECTED':
      return 'rejected'
    case 'CANCELLED':
    case 'CLOSED':
      return 'canceled'
    case 'COMPLETED':
      return 'completed'
    case 'DISPATCHED':
    case 'ACCEPTED':
    case 'IN_PROGRESS':
    case 'WAITING_CONFIRM':
      return 'processing'
    default:
      return 'info'
  }
}

const trendOption = computed(() => {
  const trend = stats.value?.trend ?? []
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

const statusOption = computed(() => {
  const byStatus = stats.value?.byStatus ?? []
  return {
    color: CHART_COLORS,
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', right: 8, top: 'center', type: 'scroll' },
    series: [
      {
        name: '工单状态',
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['38%', '50%'],
        data: byStatus.map((item) => ({ name: statusLabel(item.status), value: item.count })),
        label: { show: false }
      }
    ]
  }
})

const hasTrend = computed(() => (stats.value?.trend ?? []).length > 0)
const hasStatus = computed(() => (stats.value?.byStatus ?? []).length > 0)

const categoryRows = computed(() => stats.value?.byCategory ?? [])
const categoryTotal = computed(() => categoryRows.value.reduce((sum, row) => sum + row.count, 0))

const rateText = (rate: number | undefined): string =>
  rate === undefined ? '-' : `${(rate * 100).toFixed(1)}%`

onMounted(loadStats)
</script>

<template>
  <section class="workorder-stats">
    <header class="page-head">
      <h1>工单统计</h1>
      <p class="page-head-sub">按时间范围与分组维度查看工单量趋势、状态与类别分布</p>
    </header>

    <FilterPanel resettable @reset="handleReset">
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        :clearable="false"
        style="width: 280px"
        @change="loadStats"
      />
      <el-radio-group v-model="query.groupBy" @change="loadStats">
        <el-radio-button v-for="option in groupByOptions" :key="option.value" :value="option.value">
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
    </FilterPanel>

    <div v-loading="loading">
      <!-- 汇总 -->
      <div v-if="stats" class="card-grid">
        <StatCard label="工单总数" :value="stats.summary.total" unit="单" type="primary" />
        <StatCard label="平均完成时长" :value="stats.summary.avgCompletionTime" unit="小时" />
        <StatCard label="满意率" :value="rateText(stats.summary.satisfactionRate)" type="success" />
      </div>

      <!-- 图表 -->
      <div class="chart-grid">
        <div class="chart-card">
          <h3 class="chart-title">工单量趋势</h3>
          <VChart v-if="hasTrend" :option="trendOption" autoresize class="chart" />
          <div v-else class="chart-empty">所选范围内暂无工单数据</div>
        </div>
        <div class="chart-card">
          <h3 class="chart-title">状态分布</h3>
          <VChart v-if="hasStatus" :option="statusOption" autoresize class="chart" />
          <div v-else class="chart-empty">所选范围内暂无工单数据</div>
        </div>
      </div>

      <!-- 类别分布表格 -->
      <div class="table-card">
        <h3 class="chart-title">类别分布明细</h3>
        <el-table :data="categoryRows" stripe>
          <el-table-column prop="categoryName" label="服务类别" min-width="180" />
          <el-table-column prop="count" label="工单量" width="120" align="right" />
          <el-table-column label="占比" min-width="200">
            <template #default="{ row }">
              <div class="ratio-cell">
                <div class="ratio-bar">
                  <div class="ratio-fill" :style="{ width: categoryTotal > 0 ? `${(row.count / categoryTotal) * 100}%` : '0%' }" />
                </div>
                <span class="ratio-text">
                  {{ categoryTotal > 0 ? ((row.count / categoryTotal) * 100).toFixed(1) : '0.0' }}%
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!loading && categoryRows.length === 0" class="table-empty-hint">
          所选范围内暂无类别分布数据
        </div>
      </div>

      <!-- 状态分布明细 -->
      <div v-if="hasStatus" class="table-card">
        <h3 class="chart-title">状态分布明细</h3>
        <div class="status-chips">
          <span v-for="item in stats?.byStatus ?? []" :key="item.status" class="status-chip-item">
            <StatusTag :label="statusLabel(item.status)" :type="statusTagType(item.status)" />
            <span class="chip-count">{{ item.count }} 单</span>
          </span>
        </div>
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

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.chart-card,
.table-card {
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
  height: 320px;
}

.chart-empty {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.ratio-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.ratio-bar {
  flex: 1;
  height: 8px;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-hover);
  overflow: hidden;
}

.ratio-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background-color: var(--color-primary);
}

.ratio-text {
  width: 52px;
  text-align: right;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.table-empty-hint {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.status-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.status-chip-item {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.chip-count {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

@media (max-width: 1023px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
