<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getWorkOrderStats, getResidentStats, getResourceStats } from '@/api/statistics'
import { workOrderStatusLabels } from '@/types/modules/workorder'
import { residentStatusLabels } from '@/types/modules/resident'
import { reservationStatusLabels } from '@/types/modules/reservation'
import type { IWorkOrderStats, IResidentStats, IResourceStats } from '@/types/modules/statistics'

/**
 * 详细统计：工单 / 居民 / 资源预约三维度 Tab 合并页。
 * 各 Tab 懒加载——切换时若未加载过则发请求，避免首屏三次并发。
 */
type TabName = 'workorder' | 'resident' | 'resource'

const activeTab = ref<TabName>('workorder')
const loaded = ref<Set<TabName>>(new Set())

const workOrderStats = ref<IWorkOrderStats | null>(null)
const residentStats = ref<IResidentStats | null>(null)
const resourceStats = ref<IResourceStats | null>(null)
const loading = ref(false)

async function loadTab(tab: TabName): Promise<void> {
  if (loaded.value.has(tab)) return
  loading.value = true
  try {
    if (tab === 'workorder') workOrderStats.value = await getWorkOrderStats()
    else if (tab === 'resident') residentStats.value = await getResidentStats()
    else resourceStats.value = await getResourceStats()
    loaded.value.add(tab)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载统计数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => loadTab('workorder'))
watch(activeTab, (tab) => loadTab(tab))

/* ------------------- 工单图表 ------------------- */

function toItems(dist: Record<string, number>, labels?: object) {
  return Object.entries(dist).map(([key, value]) => ({
    name: labels && key in labels ? (labels as Record<string, string>)[key] : key,
    value
  }))
}

const woStatusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: 'scroll' },
  series: [{
    name: '工单状态',
    type: 'pie',
    radius: ['40%', '66%'],
    center: ['50%', '44%'],
    label: { show: false },
    data: toItems(workOrderStats.value?.byStatus ?? {}, workOrderStatusLabels)
  }]
}))

const woPriorityOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 24, bottom: 28 },
  xAxis: { type: 'category', data: Object.keys(workOrderStats.value?.byPriority ?? {}) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [{
    name: '工单数',
    type: 'bar',
    barMaxWidth: 40,
    data: Object.values(workOrderStats.value?.byPriority ?? {})
  }]
}))

/* ------------------- 居民图表 ------------------- */

const residentStatusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0 },
  series: [{
    type: 'pie',
    radius: ['42%', '68%'],
    center: ['50%', '44%'],
    label: { show: false },
    data: Object.entries(residentStats.value?.byStatus ?? {}).map(([s, n]) => ({
      name: residentStatusLabels[s as keyof typeof residentStatusLabels] ?? s,
      value: n
    }))
  }]
}))

/* ------------------- 资源预约图表 ------------------- */

const resourceStatusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: 'scroll' },
  series: [{
    type: 'pie',
    radius: ['42%', '68%'],
    center: ['50%', '44%'],
    label: { show: false },
    data: Object.entries(resourceStats.value?.byStatus ?? {}).map(([s, n]) => ({
      name: reservationStatusLabels[s as keyof typeof reservationStatusLabels] ?? s,
      value: n
    }))
  }]
}))
</script>

<template>
  <section class="statistics-view">
    <header class="page-head">
      <h1>详细统计</h1>
    </header>

    <el-tabs v-model="activeTab" class="stats-tabs">
      <!-- ===== 工单统计 ===== -->
      <el-tab-pane label="工单" name="workorder">
        <div v-loading="loading && activeTab === 'workorder'">
          <div class="stat-grid">
            <StatCard label="工单总数" :value="workOrderStats?.total ?? '-'" unit="单" type="primary" />
          </div>

          <div class="dual-panel">
            <div class="panel-card">
              <h3 class="panel-title">状态分布</h3>
              <ul v-if="workOrderStats && Object.keys(workOrderStats.byStatus).length" class="detail-list">
                <li v-for="(count, status) in workOrderStats.byStatus" :key="status">
                  <span>{{ (workOrderStatusLabels as Record<string, string>)[status] ?? status }}</span>
                  <b>{{ count }}</b>
                </li>
              </ul>
              <p v-else class="empty-text">暂无数据</p>
            </div>
            <div class="panel-card">
              <h3 class="panel-title">优先级分布</h3>
              <ul v-if="workOrderStats && Object.keys(workOrderStats.byPriority).length" class="detail-list">
                <li v-for="(count, priority) in workOrderStats.byPriority" :key="priority">
                  <span>{{ priority }}</span>
                  <b>{{ count }}</b>
                </li>
              </ul>
              <p v-else class="empty-text">暂无数据</p>
            </div>
          </div>

          <div class="chart-grid">
            <div class="chart-card">
              <h3 class="panel-title">状态占比</h3>
              <EChart
                :option="woStatusOption"
                :is-empty="Object.keys(workOrderStats?.byStatus ?? {}).length === 0"
                empty-text="暂无工单数据"
              />
            </div>
            <div class="chart-card">
              <h3 class="panel-title">优先级分布</h3>
              <EChart
                :option="woPriorityOption"
                :is-empty="Object.keys(workOrderStats?.byPriority ?? {}).length === 0"
                empty-text="暂无优先级数据"
              />
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ===== 居民统计 ===== -->
      <el-tab-pane label="居民" name="resident">
        <div v-loading="loading && activeTab === 'resident'">
          <div class="stat-grid">
            <StatCard label="居民总数" :value="residentStats?.total ?? '-'" unit="人" type="primary" />
          </div>

          <div class="chart-grid">
            <div class="chart-card">
              <h3 class="panel-title">账号状态分布</h3>
              <EChart
                :option="residentStatusOption"
                :is-empty="Object.keys(residentStats?.byStatus ?? {}).length === 0"
                empty-text="暂无居民数据"
              />
            </div>
            <div class="chart-card">
              <h3 class="panel-title">状态明细</h3>
              <ul v-if="residentStats && Object.keys(residentStats.byStatus).length" class="detail-list">
                <li v-for="(count, status) in residentStats.byStatus" :key="status">
                  <span>{{ residentStatusLabels[status as keyof typeof residentStatusLabels] ?? status }}</span>
                  <b>{{ count }}</b>
                </li>
              </ul>
              <p v-else class="empty-text">暂无数据</p>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ===== 资源预约统计 ===== -->
      <el-tab-pane label="资源预约" name="resource">
        <div v-loading="loading && activeTab === 'resource'">
          <div class="stat-grid">
            <StatCard label="预约总量" :value="resourceStats?.total ?? '-'" unit="次" type="primary" />
          </div>

          <div class="chart-grid">
            <div class="chart-card">
              <h3 class="panel-title">预约状态分布</h3>
              <EChart
                :option="resourceStatusOption"
                :is-empty="Object.keys(resourceStats?.byStatus ?? {}).length === 0"
                empty-text="暂无预约数据"
              />
            </div>
            <div class="chart-card">
              <h3 class="panel-title">状态明细</h3>
              <ul v-if="resourceStats && Object.keys(resourceStats.byStatus).length" class="detail-list">
                <li v-for="(count, status) in resourceStats.byStatus" :key="status">
                  <span>{{ reservationStatusLabels[status as keyof typeof reservationStatusLabels] ?? status }}</span>
                  <b>{{ count }}</b>
                </li>
              </ul>
              <p v-else class="empty-text">暂无数据</p>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
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

.stats-tabs {
  margin-top: var(--spacing-sm);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.dual-panel {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.chart-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--spacing-md);
}

.panel-card,
.chart-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
}

.panel-title {
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
  .stat-grid,
  .dual-panel,
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
