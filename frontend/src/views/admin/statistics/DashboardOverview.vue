<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import EChart, { type ChartOption } from '@/components/common/EChart.vue'
import { getDashboardStats } from '@/api/statistics'
import { listWorkOrders } from '@/api/workorder'
import { listNotices } from '@/api/notice'
import { listReservations } from '@/api/reservation'
import { workOrderStatusLabels } from '@/types/modules/workorder'
import type { WorkOrderStatus } from '@/types/modules/workorder'
import { houseStatusLabels } from '@/types/modules/community'
import type { HouseStatus } from '@/types/modules/community'
import { reservationStatusLabels } from '@/types/modules/reservation'
import type { IDashboardStats } from '@/types/modules/statistics'
import { formatDateTime } from '@/utils/date'

/**
 * 运营看板·概览 Tab（对照设计稿 design-mockups/admin/02-运营看板.png 重排）：
 * 第一行 4 统计卡 / 第二行 趋势折线 + 工单状态环图 / 第三行 房屋状态 + 最新动态。
 * 数据源裁决见各区块注释（无接口支撑的元素一律 MOCK 标注或换真实指标，不造假）。
 */

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent])

const props = defineProps<{
  /** 社区筛选：null/缺省 = 全部口径；父层切换时经 :key 重挂载整体刷新 */
  communityId?: number | null
}>()

const router = useRouter()

/* ECharts 不解析 CSS 变量：运行时从 variables.css 读取色板，色值单一来源在 token 层 */
function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

const C = {
  c1: cssVar('--chart-c1'),
  c2: cssVar('--chart-c2'),
  c3: cssVar('--chart-c3'),
  c4: cssVar('--chart-c4'),
  c5: cssVar('--chart-c5'),
  c6: cssVar('--chart-c6'),
  danger: cssVar('--color-danger'),
  card: cssVar('--admin-card-bg')
}

/* ===========================================================================
 * 【MOCK 临时占位区块】2026-09-12 —— 后端接口就绪后请整块删除并改接真实数据
 * ───────────────────────────────────────────────────────────────────────────
 * 全局检索标识：MOCK（staff 工作台同款约定）
 *
 * 待补接口：统计卡「较上月」环比与迷你趋势线
 *   需要：按日聚合的历史快照序列（如 GET /api/v1/statistics/dashboard/trend?days=60，
 *         逐日工单量 / 待处理量 / 入住率 / 平均评分），前端据此算环比与迷你线。
 *   现状：StatisticsController 仅返回 workOrderTrend7d（近 7 日工单量，无更早历史），
 *         其余三项无按日序列，「较上月」无法计算。
 *   口径：趋势配色为纯方向语义——上升红、下降绿（staff 工作台先例；
 *         设计稿按指标好坏着色，以计划简报口径为准）。
 * =========================================================================== */
const MOCK_TREND: Record<string, { delta: string; points: number[] }> = {
  weekOrders: { delta: '+12.5%', points: [3, 4, 3, 5, 4, 6, 5, 7, 6] },
  pending: { delta: '+28.6%', points: [9, 10, 8, 11, 10, 12, 11, 13, 12] },
  occupancy: { delta: '+2.1%', points: [80, 81, 80, 82, 83, 82, 84, 85, 85] },
  rating: { delta: '+0.3', points: [4.2, 4.3, 4.2, 4.4, 4.3, 4.5, 4.4, 4.6, 4.6] }
}

/* ===================== MOCK 区块结束 ===================== */

/* 迷你趋势线：藏轴藏提示，只留描边 + 浅填充（staff 工作台同款做法） */
function buildSpark(points: number[], color: string): ChartOption {
  return {
    tooltip: { show: false },
    grid: { left: 0, right: 0, top: 6, bottom: 2 },
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

/* 趋势线为 MOCK 静态数据，初始化构建一次即可；配色与卡片语义一致 */
const SPARK_OPTIONS = {
  weekOrders: buildSpark(MOCK_TREND.weekOrders.points, C.c1),
  pending: buildSpark(MOCK_TREND.pending.points, C.c3)
}

/* ---------- 统计卡图标（24×24 stroke path，随语义色着色） ---------- */

const ICONS = {
  order: ['M8 4h8a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M10 9h4M10 13h4M10 17h2'],
  clock: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M12 7v5l3 2'],
  home: ['M3 11l9-8 9 8', 'M5 9.7V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.7', 'M10 21v-6h4v6'],
  star: ['M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z']
}

const PANEL_ICONS = {
  trend: ['M3 17l5-6 4 3 6-8', 'M14 6h4v4'],
  pie: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M12 3v9h9'],
  home: ICONS.home,
  bell: ['M6 9a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6', 'M10 20a2 2 0 0 0 4 0']
}

const ACTIVITY_ICONS: Record<string, string[]> = {
  workorder: ['M6 3h9l4 4v14H6z', 'M9 12h6M9 16h4'],
  notice: ['M4 10v4h3l7 5V5l-7 5H4z', 'M17 9a5 5 0 0 1 0 6'],
  reservation: ['M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M16 2v4M8 2v4M3 10h18']
}

/* ---------- 看板聚合数据（与原运营看板同源同口径） ---------- */

const dashboard = ref<IDashboardStats | null>(null)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    dashboard.value = await getDashboardStats(
      typeof props.communityId === 'number' ? { communityId: props.communityId } : undefined
    )
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载看板数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ---------- 统计卡数值：全部走看板真实接口 ---------- */

/* 设计稿「本月工单」无按月统计接口 → 用真实可得的近 7 日口径（趋势序列求和），标签如实标注 */
const weekOrderCount = computed(() =>
  Object.values(dashboard.value?.workOrderTrend7d ?? {}).reduce((sum, n) => sum + n, 0)
)

const pendingCount = computed(() => dashboard.value?.workOrderPending ?? 0)

/* 房屋入住率：已入住 / 房屋总数（无房屋时无意义，卡片环与数值一并隐藏） */
const occupancyPercentNum = computed(() => {
  const total = dashboard.value?.houseCount ?? 0
  if (!total) return null
  return ((dashboard.value?.occupiedHouseCount ?? 0) / total) * 100
})

const occupancyLabel = computed(() =>
  occupancyPercentNum.value === null ? '-' : occupancyPercentNum.value.toFixed(1)
)

/* 设计稿「满意度」无接口 → 换现有真实指标：评价加权平均分（简报明示的候选口径） */
const avgRating = computed(() => {
  const dist = dashboard.value?.ratingDistribution ?? {}
  const total = Object.values(dist).reduce((sum, n) => sum + n, 0)
  if (!total) return '-'
  const weighted = Object.entries(dist).reduce((sum, [rating, n]) => sum + Number(rating) * n, 0)
  return (weighted / total).toFixed(1)
})

/* ---------- 趋势折线：近 7 日真实数据（设计稿为 30 天，接口仅有 7 日序列，
   已如实改标题并记入报告差异，不造假补齐 30 天） ---------- */

const trendOption = computed<ChartOption>(() => {
  const trend = dashboard.value?.workOrderTrend7d ?? {}
  const days: { date: string; count: number }[] = []
  for (let i = 6; i >= 0; i--) {
    const date = new Date(Date.now() - i * 86400000)
    const key = `${date.getFullYear()}-${(date.getMonth() + 1).toString().padStart(2, '0')}-${date.getDate().toString().padStart(2, '0')}`
    days.push({ date: key.slice(5), count: trend[key] ?? 0 })
  }
  return {
    color: [C.c1],
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: days.map((d) => d.date), boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '工单量',
        type: 'line',
        smooth: true,
        symbol: 'none',
        areaStyle: { opacity: 0.15 },
        data: days.map((d) => d.count)
      }
    ]
  }
})

/* ---------- 工单状态环图：真实状态分布，中心为分布合计；
   图例为右侧自定义 HTML（色点 + 名称 + 数值 + 百分比，对照设计稿） ---------- */

/* 状态 → 色板映射：待受理族蓝 / 处理族绿 / 已完成橙 / 已关闭紫 / 已取消灰 / 已驳回红 */
const STATUS_COLORS: Record<string, string> = {
  PENDING: C.c1,
  TO_ASSIGN: C.c1,
  ASSIGNED: C.c2,
  ACCEPTED: C.c2,
  IN_PROGRESS: C.c2,
  TO_CONFIRM: C.c2,
  COMPLETED: C.c3,
  CLOSED: C.c4,
  CANCELLED: C.c5,
  REJECTED: C.danger
}

const statusItems = computed(() => {
  const dist = dashboard.value?.workOrderStatusDistribution ?? {}
  const entries = Object.entries(dist).map(([key, value]) => ({
    key,
    name: workOrderStatusLabels[key as WorkOrderStatus] ?? key,
    value,
    color: STATUS_COLORS[key] ?? C.c5
  }))
  /* 百分比依赖合计，二段计算避免每项重复求和；按占比降序（设计稿最大扇区/图例在前） */
  const total = entries.reduce((sum, it) => sum + it.value, 0)
  return entries
    .map((it) => ({ ...it, pct: total ? Math.round((it.value / total) * 100) : 0 }))
    .sort((a, b) => b.value - a.value)
})

const statusTotal = computed(() => statusItems.value.reduce((sum, it) => sum + it.value, 0))

const statusOption = computed<ChartOption>(() => ({
  tooltip: { trigger: 'item' },
  series: [
    {
      name: '工单状态',
      type: 'pie',
      radius: ['64%', '88%'],
      center: ['50%', '50%'],
      label: { show: false },
      labelLine: { show: false },
      itemStyle: { borderColor: C.card, borderWidth: 2 },
      data: statusItems.value.map((it) => ({
        name: it.name,
        value: it.value,
        itemStyle: { color: it.color }
      }))
    }
  ]
}))

/* ---------- 房屋状态：设计稿为按楼栋分组堆叠条，无按楼栋统计接口
   → 退化为单条总览（真实 houseStatusDistribution）+ 分状态明细行，记入报告差异 ---------- */

const HOUSE_COLORS: Record<string, string> = {
  OCCUPIED: C.c2,
  VACANT: C.c6,
  RESERVED: C.c4,
  MAINTENANCE: C.c3
}

const houseItems = computed(() => {
  const dist = dashboard.value?.houseStatusDistribution ?? {}
  const total = Object.values(dist).reduce((sum, n) => sum + n, 0)
  return Object.entries(dist).map(([key, value]) => ({
    key,
    name: houseStatusLabels[key as HouseStatus] ?? key,
    value,
    color: HOUSE_COLORS[key] ?? C.c5,
    pct: total ? Math.round((value / total) * 100) : 0
  }))
})

const houseTotal = computed(() => houseItems.value.reduce((sum, it) => sum + it.value, 0))
const occupiedCount = computed(() => dashboard.value?.occupiedHouseCount ?? 0)

/* ---------- 最新动态：无统一动态接口 → 拼接现有列表接口
   （最近工单 + 已发布公告 + 最近资源预约），单源失败静默降级 ---------- */

interface ActivityItem {
  key: string
  type: 'workorder' | 'notice' | 'reservation'
  /** 工单详情跳转用；公告/预约落列表页，暂不消费（后续可带深链参数） */
  relatedId: number
  title: string
  sub: string
  time: string
}

const activities = ref<ActivityItem[]>([])

async function loadActivities(): Promise<void> {
  const [orders, notices, reservations] = await Promise.allSettled([
    listWorkOrders({ page: 1, size: 5 }),
    listNotices({ page: 1, size: 5 }),
    listReservations({ page: 1, size: 5 })
  ])

  const items: ActivityItem[] = []

  if (orders.status === 'fulfilled') {
    for (const order of orders.value.records) {
      items.push({
        key: `wo-${order.id}`,
        type: 'workorder',
        relatedId: order.id,
        title: order.title,
        sub: [order.address || order.orderNo, workOrderStatusLabels[order.status]].filter(Boolean).join(' · '),
        time: order.createdAt
      })
    }
  }

  if (notices.status === 'fulfilled') {
    for (const notice of notices.value.records) {
      /* 管理端列表含草稿/已撤回/已过期：动态流仅收已发布（草稿无发布时间一并拦下） */
      if (notice.status !== 'PUBLISHED' || !notice.publishTime) continue
      items.push({
        key: `notice-${notice.id}`,
        type: 'notice',
        relatedId: notice.id,
        title: notice.title,
        sub: notice.content,
        time: notice.publishTime
      })
    }
  }

  if (reservations.status === 'fulfilled') {
    for (const reservation of reservations.value.records) {
      items.push({
        key: `resv-${reservation.id}`,
        type: 'reservation',
        relatedId: reservation.id,
        title: reservation.resourceName,
        sub: [
          reservation.reserveDate,
          `${reservation.startTime.slice(0, 5)}-${reservation.endTime.slice(0, 5)}`,
          reservationStatusLabels[reservation.status]
        ]
          .filter(Boolean)
          .join(' · '),
        time: reservation.createdAt
      })
    }
  }

  /* 按时间倒序取前 6；某类型被密集的新工单整体挤出时补入该类型最新一条。
     驱逐对象 = 当前非保底条目中时间最旧者：保底条目（本循环他类刚补入）受保护，
     否则两类同时缺席时后者会把前者顶掉（A4 审查发现的失效场景），公告再次丢失 */
  const sorted = items.sort((a, b) => b.time.localeCompare(a.time))
  const picked = sorted.slice(0, 6)
  const guaranteed = new Set<string>()
  for (const type of ['workorder', 'notice', 'reservation'] as const) {
    if (picked.some((it) => it.type === type)) continue
    const newest = sorted.find((it) => it.type === type)
    if (!newest) continue
    if (picked.length >= 6) {
      let evictIdx = -1
      for (let i = 0; i < picked.length; i++) {
        if (guaranteed.has(picked[i].key)) continue
        if (evictIdx === -1 || picked[i].time < picked[evictIdx].time) evictIdx = i
      }
      if (evictIdx !== -1) picked.splice(evictIdx, 1)
    }
    picked.push(newest)
    guaranteed.add(newest.key)
  }
  activities.value = picked.sort((a, b) => b.time.localeCompare(a.time))
}

onMounted(loadActivities)

/* 动态行落点（按类型映射）：工单进详情（无 id 退列表），公告/预约落列表页 */
const ACTIVITY_TARGETS: Record<ActivityItem['type'], (item: ActivityItem) => string> = {
  workorder: (item) => (item.relatedId ? `/admin/work-orders/${item.relatedId}` : '/admin/work-orders'),
  notice: () => '/admin/notices',
  reservation: () => '/admin/reservations'
}

function goActivity(item: ActivityItem): void {
  router.push(ACTIVITY_TARGETS[item.type](item))
}

/* 「查看全部」统一落消息中心：最新动态本质是通知流，完整列表在消息中心
   （验收第三轮 C2：原随首条动态类型跳对应列表，落点不稳定易误导）；
   动态行逐项跳转（goActivity）保持按类型映射不变 */
function goActivityList(): void {
  router.push('/admin/notifications')
}
</script>

<template>
  <section v-loading="loading" class="dashboard-overview">
    <!-- 第一行：4 张等宽统计卡（数值真实 / 趋势 MOCK，见区块注释） -->
    <div class="stat-row">
      <AdminStatCard
        label="近 7 日工单"
        :value="weekOrderCount"
        unit="单"
        :icon="ICONS.order"
        accent="primary"
        :trend="`较上月 ${MOCK_TREND.weekOrders.delta}`"
        trend-dir="up"
      >
        <template #extra>
          <div class="stat-spark"><EChart :option="SPARK_OPTIONS.weekOrders" :height="42" /></div>
        </template>
      </AdminStatCard>

      <AdminStatCard
        label="待处理工单"
        :value="pendingCount"
        unit="单"
        :icon="ICONS.clock"
        accent="warning"
        :trend="`较上月 ${MOCK_TREND.pending.delta}`"
        trend-dir="up"
      >
        <template #extra>
          <div class="stat-spark"><EChart :option="SPARK_OPTIONS.pending" :height="42" /></div>
        </template>
      </AdminStatCard>

      <AdminStatCard
        label="房屋入住率"
        :value="occupancyLabel"
        unit="%"
        :icon="ICONS.home"
        accent="success"
        :trend="`较上月 ${MOCK_TREND.occupancy.delta}`"
        trend-dir="up"
      >
        <template #extra>
          <!-- 迷你环为真实入住率（非 MOCK）：conic-gradient 按百分比着色 -->
          <span
            v-if="occupancyPercentNum !== null"
            class="occupancy-donut"
            :style="{ '--pct': occupancyPercentNum }"
            :title="`已入住 ${dashboard?.occupiedHouseCount ?? 0} / 共 ${dashboard?.houseCount ?? 0} 套`"
          />
        </template>
      </AdminStatCard>

      <AdminStatCard
        label="平均评分"
        :value="avgRating"
        unit="分"
        :icon="ICONS.star"
        accent="warning"
        :trend="`较上月 ${MOCK_TREND.rating.delta}`"
        trend-dir="up"
      />
    </div>

    <!-- 第二行：趋势折线（2/3） + 工单状态环图（1/3） -->
    <div class="trend-row">
      <article class="panel">
        <header class="panel-header">
          <h3 class="panel-title">
            <span class="panel-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in PANEL_ICONS.trend" :key="i" :d="d" />
              </svg>
            </span>
            近 7 日工单趋势
          </h3>
          <span class="legend-chip"><i />工单数量</span>
        </header>
        <EChart :option="trendOption" :height="272" />
      </article>

      <article class="panel">
        <header class="panel-header">
          <h3 class="panel-title">
            <span class="panel-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in PANEL_ICONS.pie" :key="i" :d="d" />
              </svg>
            </span>
            工单状态分布
          </h3>
        </header>

        <div class="donut-body">
          <div class="donut-wrap">
            <EChart
              :option="statusOption"
              :height="236"
              :is-empty="statusItems.length === 0"
              empty-text="暂无工单数据"
            />
            <div v-if="statusTotal > 0" class="donut-center">
              <strong>{{ statusTotal }}</strong>
              <span>总工单</span>
            </div>
          </div>

          <ul v-if="statusItems.length" class="donut-legend">
            <li v-for="it in statusItems" :key="it.key">
              <i class="dot" :style="{ backgroundColor: it.color }" />
              <span class="legend-name" :title="it.name">{{ it.name }}</span>
              <b class="legend-value">{{ it.value }}</b>
              <span class="legend-pct">{{ it.pct }}%</span>
            </li>
          </ul>
        </div>
      </article>
    </div>

    <!-- 第三行：房屋状态（3/5） + 最新动态（2/5） -->
    <div class="bottom-row">
      <article class="panel">
        <header class="panel-header">
          <h3 class="panel-title">
            <span class="panel-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in PANEL_ICONS.home" :key="i" :d="d" />
              </svg>
            </span>
            房屋状态
          </h3>
          <span v-if="houseTotal > 0" class="house-summary">
            已入住 {{ occupiedCount }} / 共 {{ houseTotal }} 套 · 入住率 {{ occupancyLabel }}%
          </span>
        </header>

        <template v-if="houseTotal > 0">
          <div class="house-bar" role="img" aria-label="房屋状态构成">
            <div
              v-for="it in houseItems"
              :key="it.key"
              class="house-seg"
              :style="{ width: `${it.pct}%`, backgroundColor: it.color }"
              :title="`${it.name} ${it.value} 套`"
            />
          </div>

          <ul class="house-rows">
            <li v-for="it in houseItems" :key="it.key">
              <i class="dot" :style="{ backgroundColor: it.color }" />
              <span class="house-name">{{ it.name }}</span>
              <div class="house-row-track">
                <div class="house-row-fill" :style="{ width: `${it.pct}%`, backgroundColor: it.color }" />
              </div>
              <b class="house-count">{{ it.value }} 套</b>
              <span class="house-pct">{{ it.pct }}%</span>
            </li>
          </ul>
        </template>
        <p v-else class="empty-text">暂无房屋数据</p>
      </article>

      <article class="panel">
        <header class="panel-header">
          <h3 class="panel-title">
            <span class="panel-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in PANEL_ICONS.bell" :key="i" :d="d" />
              </svg>
            </span>
            最新动态
          </h3>
          <button class="panel-more" type="button" @click="goActivityList">查看全部</button>
        </header>

        <p v-if="activities.length === 0" class="empty-text">暂无动态</p>
        <ul v-else class="activity-list">
          <li
            v-for="item in activities"
            :key="item.key"
            class="activity-row"
            :title="`查看${item.type === 'workorder' ? '工单' : item.type === 'notice' ? '公告' : '预约'}`"
            @click="goActivity(item)"
          >
            <span
              class="activity-icon"
              :class="
                item.type === 'workorder'
                  ? 'is-workorder'
                  : item.type === 'reservation'
                    ? 'is-reservation'
                    : 'is-notice'
              "
              aria-hidden="true"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in ACTIVITY_ICONS[item.type]" :key="i" :d="d" />
              </svg>
            </span>
            <div class="activity-main">
              <p class="activity-title">{{ item.title }}</p>
              <p class="activity-sub">{{ item.sub }}</p>
            </div>
            <span class="activity-time">{{ formatDateTime(item.time) }}</span>
          </li>
        </ul>
      </article>
    </div>
  </section>
</template>

<style scoped>
.dashboard-overview {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-height: 320px;
}

/* ---------- 通用白卡容器 ---------- */

.panel {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.panel-icon {
  display: inline-flex;
  color: var(--color-primary);
}

.panel-icon svg {
  width: 16px;
  height: 16px;
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

.empty-text {
  margin: 0;
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

/* ---------- 第一行：统计卡 ---------- */

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--spacing-lg);
}

.stat-spark {
  width: 96px;
  min-width: 0;
}

/* 迷你入住率环：conic-gradient 按 token 着色，mask 挖出环带 */
.occupancy-donut {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-circle);
  background: conic-gradient(var(--color-primary) calc(var(--pct) * 1%), var(--color-bg-subtle) 0);
  -webkit-mask: radial-gradient(closest-side, transparent 60%, currentColor 61%);
  mask: radial-gradient(closest-side, transparent 60%, currentColor 61%);
}

/* ---------- 第二行：趋势 + 环图 ---------- */

.trend-row {
  display: grid;
  grid-template-columns: minmax(0, 2.1fr) minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: stretch;
}

.legend-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.legend-chip i {
  width: 16px;
  height: 3px;
  border-radius: var(--radius-pill);
  background-color: var(--chart-c1);
}

.donut-body {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.donut-wrap {
  position: relative;
  flex: 1.1;
  min-width: 0;
}

.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  pointer-events: none;
}

.donut-center strong {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.donut-center span {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.donut-legend {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: 0;
}

.donut-legend li {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
}

.legend-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend-value {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.legend-pct {
  flex-shrink: 0;
  width: 34px;
  text-align: right;
  color: var(--color-text-disabled);
}

/* ---------- 第三行：房屋状态 + 最新动态 ---------- */

.bottom-row {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: stretch;
}

.house-summary {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.house-bar {
  display: flex;
  height: 14px;
  overflow: hidden;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-subtle);
}

.house-rows {
  margin: var(--spacing-md) 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.house-rows li {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.house-name {
  flex-shrink: 0;
  width: 52px;
}

.house-row-track {
  flex: 1;
  height: 8px;
  overflow: hidden;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-subtle);
}

.house-row-fill {
  height: 100%;
  border-radius: var(--radius-pill);
}

.house-count {
  flex-shrink: 0;
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.house-pct {
  flex-shrink: 0;
  width: 38px;
  text-align: right;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.activity-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.activity-row {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) 0;
  border-bottom: 1px solid var(--color-bg-hover);
  border-radius: var(--radius-sm);
  margin: 0 calc(var(--spacing-sm) * -1);
  padding-inline: var(--spacing-sm);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.activity-row:hover {
  background-color: var(--color-bg-hover);
}

.activity-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.activity-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-md);
}

.activity-icon svg {
  width: 15px;
  height: 15px;
}

.activity-icon.is-workorder {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.activity-icon.is-notice {
  color: var(--color-warning);
  background-color: var(--color-warning-soft);
}

.activity-icon.is-reservation {
  color: var(--color-info);
  background-color: color-mix(in srgb, var(--color-info) 12%, var(--admin-card-bg));
}

.activity-main {
  flex: 1;
  min-width: 0;
}

.activity-title {
  margin: 0 0 2px;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-sub {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* ---------- 响应式 ---------- */

@media (max-width: 1199px) {
  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .trend-row,
  .bottom-row {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .donut-body {
    flex-direction: column;
  }
}
</style>
