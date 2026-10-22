<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useStaffWorkbench } from '../composables/useStaffWorkbench'
import { useLiveRefresh } from '@/common/web/composables/useLiveRefresh'
import EChart, { type ChartOption } from '@/common/statistics/components/EChart.vue'
import { listNotifications } from '@/common/messaging/api/notification'
import { getStaffWorkloadStats } from '@/common/statistics/api/statistics'
import type { IStaffWorkloadStats } from '@/common/statistics/types/statistics'
import type { IWorkOrder } from '@/common/workorder/types/workorder'
import type { INotification } from '@/common/messaging/types/notification'
import { useUserStore } from '@/common/auth/store/user'
import { formatRelative } from '@/common/web/utils/date'
import WorkbenchSummary from '../components/WorkbenchSummary.vue'
import WorkbenchWorkload from '../components/WorkbenchWorkload.vue'
import WorkbenchTodos from '../components/WorkbenchTodos.vue'

/** 工作台（UI设计.md §4.2.1）：问候条 + 今日概览 + 紧急告警 + 待办工单 + 工单进度 + 最新消息 */

/* 当前负荷与实际事件分开；没有快照不生成负荷历史百分比。 */
const CHART_COLORS = { warning: '#f59e0b', primary: '#3b6dff', success: '#10b981', danger: '#ef4444' } as const

const router = useRouter()
const userStore = useUserStore()


const { summary, loading, error: dashboardError, load: loadDashboard } = useStaffWorkbench()
const todoCount = computed(() => summary.value.todoCount)
const doingCount = computed(() => summary.value.doingCount)
const completedCount = computed(() => summary.value.completedCount)
const urgentCount = computed(() => summary.value.urgentCount)
const todoOrders = computed(() => summary.value.todoOrders)
const latestNotifications = ref<INotification[]>([])

const workload = ref<IStaffWorkloadStats | null>(null)
const workloadLoading = ref(false)
const workloadError = ref('')

const workloadOption = computed<ChartOption>(() => {
  const data = workload.value
  const days = Object.keys(data?.assignedTrend ?? {})
  return {
    tooltip: { trigger: 'axis' },
    color: [CHART_COLORS.primary, CHART_COLORS.success],
    legend: { top: 0, left: 'center', data: ['派入次数', '提交结果次数'] },
    grid: { left: 40, right: 20, top: 40, bottom: 35 },
    xAxis: { type: 'category', data: days, axisLabel: { formatter: (value: string) => value.slice(5) } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '派入次数', type: 'line', data: days.map(day => data?.assignedTrend[day] ?? 0) },
      { name: '提交结果次数', type: 'line', data: days.map(day => data?.submittedTrend[day] ?? 0) }
    ]
  }
})

async function loadWorkload(): Promise<void> {
  workloadLoading.value = true
  workloadError.value = ''
  workload.value = null
  try {
    workload.value = await getStaffWorkloadStats(7)
  } catch (error) {
    workloadError.value = error instanceof Error ? error.message : '事件统计加载失败'
  } finally {
    workloadLoading.value = false
  }
}

/* ---------- 问候条：按时段问候 + 当日日期，取自登录态与本地时间 ---------- */

const realName = computed(() => userStore.user?.realName ?? '师傅')

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

/* 当日日期：M月D日 星期X */
const todayLabel = computed(() => {
  const now = new Date()
  const week = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  return `${now.getMonth() + 1}月${now.getDate()}日 ${week[now.getDay()]}`
})

/** 统计卡图标：纯 path 数据，配 currentColor 随卡片语义色变化 */



/* ---------- 统计卡：数值读取后端当前负荷汇总，未定义的趋势不展示假数据 ---------- */

const statCards = computed(() => {
  const defs = [
    { key: 'todo', tone: 'todo', label: '待接单', value: todoCount.value, hint: '已派给您的工单', tab: 'todo' },
    { key: 'doing', tone: 'doing', label: '处理中', value: doingCount.value, hint: '已接单 / 处理中的工单', tab: 'doing' },
    { key: 'done', tone: 'done', label: '已完成（未关闭）', value: completedCount.value, hint: '居民已确认、尚未终审关闭的工单', tab: 'done' },
    { key: 'urgent', tone: 'urgent', label: '紧急待接单', value: urgentCount.value, hint: '标记为紧急且尚未接单', tab: 'todo' }
  ]
  return defs.map((def) => {
    return def
  })
})

/* ---------- 工单进度环形图：三态构成，任一态为 0 也不会空环 ---------- */

const totalCount = computed(() => todoCount.value + doingCount.value + completedCount.value)

const donePercent = computed(() =>
  totalCount.value === 0 ? 0 : Math.round((completedCount.value / totalCount.value) * 100)
)

const progressOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item', formatter: '{b}：{c}' },
  series: [
    {
      type: 'pie',
      radius: ['66%', '88%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: false,
      label: { show: false },
      labelLine: { show: false },
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      data: [
        { value: completedCount.value, name: '已完成', itemStyle: { color: CHART_COLORS.success } },
        { value: doingCount.value, name: '处理中', itemStyle: { color: CHART_COLORS.primary } },
        { value: todoCount.value, name: '待接单', itemStyle: { color: CHART_COLORS.warning } }
      ]
    }
  ]
}))

/** 最新消息：只读面板，失败静默（不阻塞工作台主体） */
async function loadNotifications(): Promise<void> {
  try {
    const result = await listNotifications({ page: 1, size: 4 })
    latestNotifications.value = result.records
  } catch {
    latestNotifications.value = []
  }
}

function goList(tab: string): void {
  router.push({ path: '/staff/work-orders', query: { tab } })
}

function goDetail(order: IWorkOrder): void {
  router.push(`/staff/work-orders/${order.id}`)
}

function goNotifications(): void {
  router.push('/staff/notifications')
}

function messageIconClass(item: INotification): string {
  return item.sourceType === 'WORK_ORDER' ? 'is-workorder' : 'is-system'
}

useLiveRefresh(loadDashboard, [{ domain: 'workorder' }])

onMounted(() => {
  loadDashboard()
  loadNotifications()
  loadWorkload()
})
</script>

<template>
  <section class="staff-dashboard" :aria-busy="loading">
    <div v-if="dashboardError" role="alert" class="workload-error">工作台加载失败：{{ dashboardError }} <el-button @click="loadDashboard">重新加载工作台</el-button></div>
    <!-- 问候条 -->
    <div class="greeting-bar">
      <span class="greeting-sun" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
        </svg>
      </span>
      <span class="greeting-text">{{ greeting }}，{{ realName }}</span>
      <span class="greeting-divider">·</span>
      <span class="greeting-date">{{ todayLabel }}</span>
    </div>

    <!-- 今日概览：标题与四张统计卡同处一个白色大容器 -->
    <WorkbenchSummary v-if="!dashboardError" v-loading="loading" :cards="statCards" @open="goList" />

    <WorkbenchWorkload :workload="workload" :loading="workloadLoading" :error="workloadError" :option="workloadOption" @retry="loadWorkload" />

    <!-- 紧急告警条 -->
    <div v-if="urgentCount > 0" class="urgent-banner">
      <span class="urgent-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <path d="M12 4l9 16H3z" />
          <path d="M12 10v4M12 17h.01" />
        </svg>
      </span>
      <span class="urgent-text">{{ urgentCount }} 张紧急工单待接单</span>
      <button class="urgent-action" type="button" @click="goList('todo')">
        立即处理
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <path d="M9 6l6 6-6 6" />
        </svg>
      </button>
    </div>

    <div class="dashboard-grid">
      <!-- 待办工单：一个大白容器，标题左上 / 全部右上 -->
      <WorkbenchTodos v-if="!dashboardError" v-loading="loading" :orders="todoOrders" :count="todoCount" @all="goList('todo')" @detail="goDetail" />

      <!-- 右栏：两个容器 + 中间间隔，与左侧待办容器等高 -->
      <div class="side-col">
        <!-- 工单进度 -->
        <article v-if="!dashboardError" class="panel progress-panel">
          <header class="panel-header">
            <h3 class="panel-title">工单进度</h3>
          </header>

          <div class="progress-body">
            <div class="progress-chart">
              <EChart
                :option="progressOption"
                :height="132"
                :is-empty="totalCount === 0"
                empty-text="暂无工单"
              />
              <div v-if="totalCount > 0" class="progress-center">
                <strong>{{ donePercent }}%</strong>
                <span>已完成</span>
              </div>
            </div>

            <ul class="progress-legend">
              <li><i class="dot is-done" />已完成<em>{{ completedCount }}</em></li>
              <li><i class="dot is-doing" />处理中<em>{{ doingCount }}</em></li>
              <li><i class="dot is-todo" />待接单<em>{{ todoCount }}</em></li>
            </ul>
          </div>
        </article>

        <!-- 最新消息 -->
        <article class="panel msg-panel">
          <header class="panel-header">
            <h3 class="panel-title">最新消息</h3>
            <button class="panel-more" type="button" @click="goNotifications">全部</button>
          </header>

          <p v-if="latestNotifications.length === 0" class="msg-empty">暂无新消息</p>

          <ul v-else class="msg-list">
            <li v-for="item in latestNotifications" :key="item.id" class="msg-row">
              <span class="msg-icon" :class="messageIconClass(item)" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                  <path d="M6 4h8l4 4v12H6z" />
                  <path d="M9 12h6M9 16h4" />
                </svg>
              </span>
              <div class="msg-main">
                <p class="msg-title">{{ item.title }}</p>
                <p class="msg-content">{{ item.content }}</p>
              </div>
              <span class="msg-time">{{ formatRelative(item.createdAt) }}</span>
            </li>
          </ul>
        </article>
      </div>
    </div>
  </section>
</template>

<style src="../styles/workbench.css"></style>
