<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import { listWorkOrders } from '@/api/workorder'
import { listNotifications } from '@/api/notification'
import type { IWorkOrder, WorkOrderStatus } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import type { INotification } from '@/types/modules/notification'
import { useUserStore } from '@/store/user'
import { formatRelative } from '@/utils/date'
import { residentDisplayName, residentInitial, residentRelationLabel } from '@/utils/staffPlaceholder'

/** 工作台（UI设计.md §4.2.1）：问候条 + 今日概览 + 紧急告警 + 待办工单 + 今日进度 + 最新消息 */

/* ===========================================================================
 * 【MOCK 临时占位区块】2026-09-11 —— 后端接口就绪后请整块删除并改接真实数据
 * ───────────────────────────────────────────────────────────────────────────
 * 用户指示：先造数据保证 UI 完整，但必须标识；缺的接口记为待补项。
 * 全局检索标识：MOCK
 *
 * 待补接口 ①：统计卡趋势指数（百分比）与迷你趋势图
 *   需要：STAFF 可访问的「按日聚合历史快照」接口，例如
 *         GET /api/v1/statistics/staff/trend?days=7
 *   现状：StatisticsController 全部端点均限制管理员角色
 *         （StatisticsController.java 第 27/34/41/48/55 行），STAFF 取不到。
 *
 * 待补接口 ②③：居民性别与居住身份（「张女士 / 业主」组合名与身份标签）
 *   取值已抽到 utils/staffPlaceholder.ts，与工单列表、工单详情共用同一口径；
 *   接口就绪后删除该模块并改读真实字段（详见模块内说明）。
 * =========================================================================== */

/** MOCK：趋势数据（delta 为百分比，正数上升 / 负数下降；
 *  按用户口径着色——上升红、下降绿，纯方向判定，不区分指标语义） */
const MOCK_TREND: Record<string, { delta: number; points: number[] }> = {
  todo: { delta: 25, points: [6, 5, 7, 6, 8, 7, 9, 8, 10] },
  doing: { delta: -40, points: [9, 8, 8, 7, 6, 6, 5, 4, 3] },
  done: { delta: 33, points: [3, 4, 4, 5, 6, 6, 7, 8, 8] },
  urgent: { delta: 50, points: [1, 1, 2, 1, 2, 2, 3, 2, 3] }
}

/* ===================== MOCK 区块结束 ===================== */

const router = useRouter()
const userStore = useUserStore()

const statusSemantic: Record<WorkOrderStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  TO_ASSIGN: 'pending',
  TO_CONFIRM: 'pending',
  ASSIGNED: 'processing',
  ACCEPTED: 'processing',
  IN_PROGRESS: 'processing',
  COMPLETED: 'completed',
  CLOSED: 'completed',
  REJECTED: 'rejected',
  CANCELLED: 'canceled'
}

/* 紧急程度排序权重：越紧急越靠前 */
const priorityWeight: Record<string, number> = { URGENT: 3, HIGH: 2, NORMAL: 1, LOW: 0 }

const todoCount = ref(0)
const doingCount = ref(0)
const todayDoneCount = ref(0)
const urgentCount = ref(0)
const todoOrders = ref<IWorkOrder[]>([])
const latestNotifications = ref<INotification[]>([])
const loading = ref(false)

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

/* ---------- 图表：ECharts 不解析 CSS 变量，此处色值与 variables.css T​o​k​e​n 一一对应 ---------- */

const CHART_COLORS = {
  warning: '#f59e0b',
  primary: '#3b6dff',
  success: '#10b981',
  danger: '#ef4444'
} as const

/** 迷你趋势图：藏轴藏提示，只留描边 + 浅填充 */
function buildSpark(points: number[], color: string): ChartOption {
  return {
    tooltip: { show: false },
    grid: { left: 0, right: 0, top: 8, bottom: 2 },
    xAxis: { type: 'category', data: points.map((_, i) => i), show: false, boundaryGap: false },
    yAxis: {
      type: 'value',
      show: false,
      min: Math.min(...points) - 1,
      max: Math.max(...points) + 1
    },
    series: [
      {
        type: 'line',
        data: points,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color },
        areaStyle: { color, opacity: 0.14 }
      }
    ]
  }
}

/* 趋势图与卡片数值无关，初始化时构建一次即可 */
const SPARK_OPTIONS: Record<string, ChartOption> = {
  todo: buildSpark(MOCK_TREND.todo.points, CHART_COLORS.warning),
  doing: buildSpark(MOCK_TREND.doing.points, CHART_COLORS.primary),
  done: buildSpark(MOCK_TREND.done.points, CHART_COLORS.success),
  urgent: buildSpark(MOCK_TREND.urgent.points, CHART_COLORS.danger)
}

/** 统计卡图标：纯 path 数据，配 currentColor 随卡片语义色变化 */
const STAT_ICONS: Record<string, string[]> = {
  todo: ['M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z', 'M9 8h6M9 12h6M9 16h3'],
  doing: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M12 7v5l3 2'],
  done: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M8 12.5l2.5 2.5L16 9.5'],
  urgent: ['M12 4l9 16H3z', 'M12 10v4M12 17h.01']
}

const ARROW_UP = 'M12 19V5M5 12l7-7 7 7'
const ARROW_DOWN = 'M12 5v14M5 12l7 7 7-7'

/* ---------- 统计卡：数值走真实接口，趋势走 MOCK（见上方区块） ---------- */

const statCards = computed(() => {
  const defs = [
    { key: 'todo', tone: 'todo', label: '待接单', value: todoCount.value, hint: '已派给您的工单', tab: 'todo' },
    { key: 'doing', tone: 'doing', label: '处理中', value: doingCount.value, hint: '已接单 / 处理中的工单', tab: 'doing' },
    { key: 'done', tone: 'done', label: '今日完成', value: todayDoneCount.value, hint: '今日提交处理结果的工单', tab: 'done' },
    { key: 'urgent', tone: 'urgent', label: '紧急待接单', value: urgentCount.value, hint: '标记为紧急且尚未接单', tab: 'todo' }
  ]
  return defs.map((def) => {
    const trend = MOCK_TREND[def.key]
    return {
      ...def,
      delta: Math.abs(trend.delta),
      /* 用户口径：上升为红、下降为绿（纯方向，不代表好坏） */
      isUp: trend.delta > 0
    }
  })
})

/* ---------- 今日进度环形图：三态构成，任一态为 0 也不会空环 ---------- */

const totalCount = computed(() => todoCount.value + doingCount.value + todayDoneCount.value)

const donePercent = computed(() =>
  totalCount.value === 0 ? 0 : Math.round((todayDoneCount.value / totalCount.value) * 100)
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
        { value: todayDoneCount.value, name: '已完成', itemStyle: { color: CHART_COLORS.success } },
        { value: doingCount.value, name: '处理中', itemStyle: { color: CHART_COLORS.primary } },
        { value: todoCount.value, name: '待接单', itemStyle: { color: CHART_COLORS.warning } }
      ]
    }
  ]
}))

/** 加载工作台数据：统计卡片与待办列表同源，避免两处口径漂移 */
async function loadDashboard(): Promise<void> {
  loading.value = true
  try {
    /* 统计卡片：待接单 / 处理中（已接单+处理中）/ 完成，均由列表接口 total 汇总；
       紧急待接单走 priority 过滤（后端 WorkOrderController 支持该参数）。
       注意：列表接口无时间范围参数，故"今日完成"实为"累计完成"，
       待补接口 ④：GET /work-orders 增加 startTime/endTime 参数 */
    const [assigned, accepted, inProgress, done, urgent] = await Promise.all([
      listWorkOrders({ status: 'ASSIGNED', page: 1, size: 5 }),
      listWorkOrders({ status: 'ACCEPTED', page: 1, size: 5 }),
      listWorkOrders({ status: 'IN_PROGRESS', page: 1, size: 5 }),
      listWorkOrders({ status: 'COMPLETED', page: 1, size: 1 }),
      listWorkOrders({ status: 'ASSIGNED', priority: 'URGENT', page: 1, size: 1 })
    ])

    todoCount.value = assigned.total
    doingCount.value = accepted.total + inProgress.total
    todayDoneCount.value = done.total
    urgentCount.value = urgent.total

    /* 待办列表：已派单 + 已接单 + 处理中，按紧急程度与时间排序 */
    todoOrders.value = [...assigned.records, ...accepted.records, ...inProgress.records].sort((a, b) => {
      const weight = priorityWeight[b.priority] - priorityWeight[a.priority]
      return weight !== 0 ? weight : b.createdAt.localeCompare(a.createdAt)
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作台数据加载失败')
  } finally {
    loading.value = false
  }
}

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

/* 头像首字 / 展示名 / 居住身份：取自 MOCK 占位模块（待补接口 ②③），
   与工单列表、工单详情页共用同一取值口径 */
const initialOf = residentInitial
const displayName = residentDisplayName
const relationLabel = residentRelationLabel

function isUrgentOrder(order: IWorkOrder): boolean {
  return order.priority === 'URGENT'
}

function messageIconClass(item: INotification): string {
  return item.sourceType === 'WORK_ORDER' ? 'is-workorder' : 'is-system'
}

onMounted(() => {
  loadDashboard()
  loadNotifications()
})
</script>

<template>
  <section v-loading="loading" class="staff-dashboard">
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
    <section class="panel overview-panel">
      <h2 class="panel-title">今日概览</h2>

      <div class="stat-cards">
        <button
          v-for="card in statCards"
          :key="card.key"
          class="stat-card"
          :class="`is-${card.tone}`"
          type="button"
          :title="card.hint"
          @click="goList(card.tab)"
        >
          <span class="stat-top">
            <span class="stat-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in STAT_ICONS[card.key]" :key="i" :d="d" />
              </svg>
            </span>
            <span class="stat-label">{{ card.label }}</span>
          </span>

          <span class="stat-main">
            <span class="stat-figures">
              <span class="stat-value">{{ card.value }}</span>
              <span class="stat-delta" :class="card.isUp ? 'is-up' : 'is-down'">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path :d="card.isUp ? ARROW_UP : ARROW_DOWN" />
                </svg>
                {{ card.delta }}%
              </span>
            </span>
            <span class="stat-spark">
              <EChart :option="SPARK_OPTIONS[card.key]" :height="46" />
            </span>
          </span>
        </button>
      </div>
    </section>

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
      <section class="panel todo-panel">
        <header class="panel-header">
          <h3 class="panel-title">待办工单</h3>
          <button class="panel-more" type="button" @click="goList('todo')">
            全部
            <em v-if="todoCount > 0">{{ todoCount }}</em>
          </button>
        </header>

        <el-empty v-if="todoOrders.length === 0" description="暂无待办工单" :image-size="80" />

        <ul v-else class="todo-list">
          <li
            v-for="order in todoOrders"
            :key="order.id"
            class="todo-row"
            :class="{ 'is-urgent': isUrgentOrder(order) }"
            @click="goDetail(order)"
          >
            <span class="todo-flag">{{ workOrderPriorityLabels[order.priority] }}</span>

            <div class="todo-body">
              <div class="todo-main">
                <div class="todo-title-line">
                  <p class="todo-title">{{ order.title }}</p>
                  <span class="todo-meta">{{ order.orderNo }} · {{ formatRelative(order.createdAt) }}</span>
                </div>

                <div class="todo-person">
                  <span class="todo-avatar" aria-hidden="true">{{ initialOf(order.residentName) }}</span>
                  <span class="todo-person-text">
                    <span class="todo-name" :title="order.residentName">{{ displayName(order) }}</span>
                    <span class="todo-identity">{{ relationLabel(order) }}</span>
                  </span>
                </div>
              </div>

              <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />

              <button class="todo-action" type="button" @click.stop="goDetail(order)">
                {{ order.status === 'ASSIGNED' ? '接单' : '继续处理' }}
              </button>
            </div>
          </li>
        </ul>
      </section>

      <!-- 右栏：两个容器 + 中间间隔，与左侧待办容器等高 -->
      <div class="side-col">
        <!-- 今日进度 -->
        <article class="panel progress-panel">
          <header class="panel-header">
            <h3 class="panel-title">今日进度</h3>
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
              <li><i class="dot is-done" />已完成<em>{{ todayDoneCount }}</em></li>
              <li><i class="dot is-doing" />处理中<em>{{ doingCount }}</em></li>
              <li><i class="dot is-todo" />待接单<em>{{ todoCount }}</em></li>
            </ul>
          </div>

          <footer class="progress-footer">总工单<em>{{ totalCount }}</em></footer>
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

<style scoped>
.staff-dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

/* ---------- 问候条 ---------- */

.greeting-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.greeting-sun {
  display: inline-flex;
  color: var(--color-warning);
}

.greeting-sun svg {
  width: 20px;
  height: 20px;
}

.greeting-text {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.greeting-divider {
  color: var(--color-text-disabled);
}

.greeting-date {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* ---------- 通用白容器 ---------- */

.panel {
  padding: var(--spacing-lg);
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.panel-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.panel-more {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: 0;
  background: none;
  border: none;
  color: var(--color-primary);
  font-family: inherit;
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.panel-more em {
  padding: 0 6px;
  background-color: var(--color-primary-bg);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-style: normal;
}

/* ---------- 今日概览 ---------- */

.overview-panel .panel-title {
  margin-bottom: var(--spacing-md);
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--spacing-md);
}

/* 容器内卡片用浅灰底 + 描边，与白色大容器形成二级层次 */
.stat-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background-color: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: box-shadow 0.2s ease, transform 0.2s ease, border-color 0.2s ease;
}

.stat-card:hover {
  box-shadow: var(--shadow-sm);
  border-color: var(--color-primary-light);
  transform: translateY(-1px);
}

.stat-top {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
}

.stat-icon svg {
  width: 15px;
  height: 15px;
}

.stat-card.is-todo .stat-icon {
  color: var(--status-pending);
  background-color: var(--color-warning-soft);
}

.stat-card.is-doing .stat-icon {
  color: var(--status-processing);
  background-color: var(--color-primary-bg);
}

.stat-card.is-done .stat-icon {
  color: var(--status-completed);
  background-color: var(--color-success-soft);
}

.stat-card.is-urgent .stat-icon {
  color: var(--status-rejected);
  background-color: var(--color-danger-soft);
}

.stat-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.stat-main {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.stat-figures {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

.stat-value {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
}

/* 趋势指数：箭头与文字同色，上升红 / 下降绿（用户口径，纯方向着色） */
.stat-delta {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.stat-delta svg {
  width: 12px;
  height: 12px;
}

.stat-delta.is-up {
  color: var(--color-danger);
}

.stat-delta.is-down {
  color: var(--color-success);
}

.stat-spark {
  flex: 1;
  min-width: 0;
}

/* ---------- 紧急告警条 ---------- */

.urgent-banner {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  background-color: var(--color-danger-soft);
  border-radius: var(--radius-lg);
}

.urgent-icon {
  display: inline-flex;
  color: var(--status-rejected);
}

.urgent-icon svg {
  width: 18px;
  height: 18px;
}

.urgent-text {
  flex: 1;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--status-rejected);
}

.urgent-action {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-md);
  background-color: var(--color-primary);
  border: none;
  border-radius: var(--radius-md);
  color: #fff;
  font-family: inherit;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.urgent-action:hover {
  background-color: var(--color-primary-dark);
}

.urgent-action svg {
  width: 14px;
  height: 14px;
}

/* ---------- 主体两栏：右栏两容器与左栏等高 ---------- */

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.9fr) minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: stretch;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-width: 0;
}

.side-col > .panel {
  flex: 1;
  min-height: 0;
}

/* ---------- 待办工单 ---------- */

.todo-panel {
  min-width: 0;
}

.todo-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin: 0;
  padding: 0;
  list-style: none;
}

.todo-row {
  display: flex;
  align-items: stretch;
  background-color: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.todo-row:hover {
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

/* 左侧竖排紧急程度色条（紧急红 / 普通黄，文案取自后端优先级枚举） */
.todo-flag {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  padding: var(--spacing-sm) 0;
  writing-mode: vertical-rl;
  text-orientation: upright;
  letter-spacing: 3px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: #fff;
  background-color: var(--status-pending);
}

.todo-row.is-urgent .todo-flag {
  background-color: var(--status-rejected);
}

.todo-body {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex: 1;
  min-width: 0;
  padding: var(--spacing-md) var(--spacing-lg);
}

.todo-main {
  flex: 1;
  min-width: 0;
}

.todo-title-line {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.todo-title {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-meta {
  margin-left: auto;
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  white-space: nowrap;
}

/* 发布人：头像 + 「姓名 / 身份」两行 */
.todo-person {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.todo-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  background-color: var(--color-primary-bg);
  border-radius: var(--radius-circle);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.todo-person-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.todo-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.todo-identity {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-tight);
}

.todo-action {
  flex-shrink: 0;
  padding: var(--spacing-sm) var(--spacing-lg);
  background-color: var(--color-primary);
  border: none;
  border-radius: var(--radius-md);
  color: #fff;
  font-family: inherit;
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.todo-action:hover {
  background-color: var(--color-primary-dark);
}

/* ---------- 今日进度 ---------- */

.progress-panel {
  display: flex;
  flex-direction: column;
}

.progress-body {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  flex: 1;
}

.progress-chart {
  position: relative;
  flex-shrink: 0;
  width: 132px;
}

.progress-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  pointer-events: none;
}

.progress-center strong {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.progress-center span {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.progress-legend {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.progress-legend li {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.progress-legend em {
  margin-left: auto;
  font-style: normal;
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
}

.dot.is-done {
  background-color: var(--color-success);
}

.dot.is-doing {
  background-color: var(--color-primary);
}

.dot.is-todo {
  background-color: var(--color-warning);
}

.progress-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-bg-hover);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.progress-footer em {
  font-style: normal;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* ---------- 最新消息 ---------- */

.msg-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.msg-empty {
  margin: 0;
  padding: var(--spacing-lg) 0;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.msg-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) 0;
  border-bottom: 1px solid var(--color-bg-hover);
}

.msg-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.msg-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-circle);
}

.msg-icon svg {
  width: 14px;
  height: 14px;
}

.msg-icon.is-workorder {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.msg-icon.is-system {
  color: var(--color-text-secondary);
  background-color: var(--color-bg-hover);
}

.msg-main {
  flex: 1;
  min-width: 0;
}

.msg-title {
  margin: 0 0 2px;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-content {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* ---------- 响应式 ---------- */

@media (max-width: 1199px) {
  .stat-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 991px) {
  .dashboard-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .side-col > .panel {
    flex: none;
  }
}
</style>
