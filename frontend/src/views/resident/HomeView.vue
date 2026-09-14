<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { listNotices } from '@/api/notice'
import { listUnreadNotifications } from '@/api/notification'
import { listWorkOrders } from '@/api/workorder'
import { listReservations } from '@/api/reservation'
import { listFeedbacks } from '@/api/feedback'
import { getResidentResidenceList } from '@/api/resident'
import StatusTag from '@/components/common/StatusTag.vue'
import type { INotice } from '@/types/modules/notice'
import { workOrderStatusLabels, type IWorkOrder, type WorkOrderStatus } from '@/types/modules/workorder'
import { reservationStatusLabels, type IResourceReservation, type ReservationStatus } from '@/types/modules/reservation'
import { feedbackStatusLabels, type IFeedback, type FeedbackStatus } from '@/types/modules/feedback'
import { formatDate, formatRelative } from '@/utils/date'

/** 居民首页：晨色问候 + 图文公告 + 个人卡/快捷入口 + 我的待办（面向居民的温度感设计） */
const router = useRouter()
const userStore = useUserStore()

const realName = computed(() => userStore.user?.realName ?? '邻居')
const notices = ref<INotice[]>([])
const loadingNotices = ref(false)
const unreadCount = ref(0)
const address = ref('')

/** 按时段问候 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

/** 快捷入口：四色 2x2 宫格（报修蓝/反馈橙/预约绿/房源紫）。
 *  「预约」跳公共资源列表（三段预约流：资源列表 → 详情 → 预约子页）；
 *  看房预约能力在房源详情页「预约看房」按钮，避免第 5 个入口撑高右侧容器 */
const quickEntries = [
  { label: '报修', subtitle: '一键报修', to: '/resident/work-orders/create', icon: 'repair', color: 'blue' },
  { label: '反馈', subtitle: '建议投诉', to: '/resident/feedbacks', icon: 'chat', color: 'orange' },
  { label: '预约', subtitle: '场地预约', to: '/resident/resources', icon: 'calendar', color: 'green' },
  { label: '房源', subtitle: '在租查询', to: '/resident/housings', icon: 'home', color: 'purple' }
]

/* ---------- 我的待办：三个「我的」列表各取进行中前 2 条合成，不造假数据 ---------- */

interface TodoItem {
  kind: 'workorder' | 'reservation' | 'feedback'
  id: number
  code: string
  title: string
  statusLabel: string
  statusType: 'pending' | 'processing'
  time: string
  to: string
}

const todos = ref<TodoItem[]>([])
const loadingTodos = ref(false)
const todoScroller = ref<HTMLElement | null>(null)

/* 左右箭头：按视口宽度 80% 平滑滚动一屏 */
function scrollTodos(direction: 1 | -1): void {
  const el = todoScroller.value
  if (!el) return
  el.scrollBy({ left: direction * el.clientWidth * 0.8, behavior: 'smooth' })
}

const ACTIVE_WORK_ORDER: WorkOrderStatus[] = ['PENDING', 'TO_ASSIGN', 'ASSIGNED', 'ACCEPTED', 'IN_PROGRESS', 'TO_CONFIRM']
const ACTIVE_RESERVATION: ReservationStatus[] = ['PENDING', 'CONFIRMED']
const ACTIVE_FEEDBACK: FeedbackStatus[] = ['PENDING', 'IN_SESSION']

/* 进行中的状态在两类对象上都映射为「处理中」语义色，待响应映射为「待处理」 */
const PENDING_LIKE = new Set<string>(['PENDING', 'TO_ASSIGN'])

function toTodo(kind: TodoItem['kind'], raw: IWorkOrder | IResourceReservation | IFeedback): TodoItem {
  if (kind === 'workorder') {
    const order = raw as IWorkOrder
    return {
      kind,
      id: order.id,
      code: order.orderNo,
      title: order.title,
      statusLabel: workOrderStatusLabels[order.status],
      statusType: PENDING_LIKE.has(order.status) ? 'pending' : 'processing',
      time: formatRelative(order.createdAt),
      to: `/resident/work-orders/${order.id}`
    }
  }
  if (kind === 'reservation') {
    const reservation = raw as IResourceReservation
    return {
      kind,
      id: reservation.id,
      code: reservation.resourceName,
      title: `${reservation.reserveDate ?? reservation.reservationDate} ${reservation.startTime.slice(0, 5)}`,
      statusLabel: reservationStatusLabels[reservation.status],
      statusType: PENDING_LIKE.has(reservation.status) ? 'pending' : 'processing',
      time: formatRelative(reservation.createdAt),
      to: '/resident/reservations'
    }
  }
  const feedback = raw as IFeedback
  return {
    kind,
    id: feedback.id,
    code: feedback.feedbackNumber ?? `#${feedback.id}`,
    title: feedback.title,
    statusLabel: feedbackStatusLabels[feedback.status],
    statusType: PENDING_LIKE.has(feedback.status) ? 'pending' : 'processing',
    time: formatRelative(feedback.createdAt),
    to: `/resident/feedbacks/${feedback.id}`
  }
}

async function loadTodos(): Promise<void> {
  loadingTodos.value = true
  try {
    const [orders, reservations, feedbacks] = await Promise.all([
      listWorkOrders({ page: 1, size: 5 }).catch(() => ({ records: [] as IWorkOrder[], total: 0 })),
      listReservations({ page: 1, size: 5 }).catch(() => ({ records: [] as IResourceReservation[], total: 0 })),
      listFeedbacks({ page: 1, size: 5 }).catch(() => ({ records: [] as IFeedback[], total: 0 }))
    ])
    todos.value = [
      ...orders.records.filter((o) => ACTIVE_WORK_ORDER.includes(o.status)).slice(0, 2).map((o) => toTodo('workorder', o)),
      ...reservations.records.filter((r) => ACTIVE_RESERVATION.includes(r.status)).slice(0, 2).map((r) => toTodo('reservation', r)),
      ...feedbacks.records.filter((f) => ACTIVE_FEEDBACK.includes(f.status)).slice(0, 2).map((f) => toTodo('feedback', f))
    ]
  } finally {
    loadingTodos.value = false
  }
}

/* 住址取生效中的居住关系（无居住关系时回落用户名，不阻塞首屏） */
async function loadAddress(): Promise<void> {
  const residentId = userStore.user?.id
  if (!residentId) return
  try {
    const result = await getResidentResidenceList(residentId, { page: 1, size: 1, status: 'ACTIVE' })
    address.value = result.records[0]?.houseLocation ?? ''
  } catch {
    address.value = ''
  }
}

onMounted(async () => {
  /* 最新公告（后端已按高优先级置顶排序）与未读消息数并行加载 */
  loadingNotices.value = true
  try {
    const [noticePage, unread] = await Promise.all([
      listNotices({ page: 1, size: 2 }),
      listUnreadNotifications().catch(() => ({ count: 0, notifications: [] }))
    ])
    notices.value = noticePage.records
    unreadCount.value = unread.count
  } catch {
    notices.value = []
  } finally {
    loadingNotices.value = false
  }
  void loadTodos()
  void loadAddress()
})

function goNotice(id: number): void {
  router.push(`/resident/notices/${id}`)
}

function goNotifications(): void {
  router.push('/resident/notifications')
}
</script>

<template>
  <section class="home">
    <!-- 问候横幅：晨色渐变（暖杏 → 浅蓝），深字压浅底 -->
    <div class="greeting-card">
      <div class="greeting-text">
        <h1>
          <svg class="greeting-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
          </svg>
          {{ greeting }}，{{ realName }}
        </h1>
        <p>今天小区有 {{ notices.length }} 条新公告</p>
      </div>
      <button type="button" class="unread-entry" @click="goNotifications">
        <span class="unread-dot" :class="{ 'is-active': unreadCount > 0 }"></span>
        <span>{{ unreadCount > 0 ? `${unreadCount} 条未读消息` : '暂无未读消息' }}</span>
      </button>
    </div>

    <div class="home-grid">
      <!-- 社区公告：白色大容器（notice 无封面字段，统一用占位插画封面） -->
      <div class="notice-panel card">
        <div class="panel-head">
          <h2 class="section-title">
            <svg class="section-title-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 11l14-6v14L3 13v-2z" />
              <path d="M11.6 16.8a3 3 0 1 1-5.8-1.6" />
            </svg>
            社区公告
          </h2>
          <router-link to="/resident/notices" class="panel-more">更多 →</router-link>
        </div>
        <div v-if="loadingNotices" class="panel-loading">加载中…</div>
        <template v-else-if="notices.length > 0">
          <article
            v-for="notice in notices"
            :key="notice.id"
            class="notice-card"
            @click="goNotice(notice.id)"
          >
            <img class="notice-cover" src="/images/notice-cover-default.png" :alt="notice.title" />
            <div class="notice-body">
              <h3 class="notice-title">{{ notice.title }}</h3>
              <p class="notice-excerpt">{{ notice.content }}</p>
              <p class="notice-date">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 7v5l3 3" />
                </svg>
                {{ formatDate(notice.publishTime) }}
              </p>
            </div>
          </article>
        </template>
        <div v-else class="panel-empty">暂无公告</div>
      </div>

      <!-- 右栏：个人信息 + 快捷入口合并为一个白色大容器（与左侧等高） -->
      <div class="side-card card">
        <div class="profile-row">
          <div class="profile-avatar">{{ realName.slice(0, 1) }}</div>
          <div class="profile-info">
            <p class="profile-name">{{ realName }}</p>
            <p class="profile-username">{{ address || `@${userStore.user?.username ?? '-'}` }}</p>
          </div>
          <router-link to="/resident/profile" class="profile-link">个人中心 →</router-link>
        </div>

        <div class="entries">
          <h2 class="section-title">快捷入口</h2>
          <div class="entries-grid">
            <router-link
              v-for="entry in quickEntries"
              :key="entry.to"
              :to="entry.to"
              class="entry-card"
              :data-color="entry.color"
            >
              <span class="entry-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <template v-if="entry.icon === 'repair'">
                    <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
                  </template>
                  <template v-else-if="entry.icon === 'chat'">
                    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
                  </template>
                  <template v-else-if="entry.icon === 'calendar'">
                    <rect x="3" y="5" width="18" height="16" rx="2" />
                    <path d="M8 3v4M16 3v4M3 11h18" />
                  </template>
                  <template v-else-if="entry.icon === 'home'">
                    <path d="M4 11l8-7 8 7" />
                    <path d="M6 10v10h12V10" />
                  </template>
                  <template v-else>
                    <circle cx="12" cy="12" r="9" />
                    <path d="M12 7v5l3 3" />
                  </template>
                </svg>
              </span>
              <span class="entry-label">{{ entry.label }}</span>
              <span class="entry-subtitle">{{ entry.subtitle }}</span>
            </router-link>
          </div>
        </div>
      </div>
    </div>

    <!-- 我的待办：白色大容器 + 横向滚动卡条（无聚合待办页，「全部待办」跳我的工单列表） -->
    <div class="todo-panel card">
      <div class="panel-head">
        <h2 class="section-title">我的待办</h2>
        <router-link to="/resident/work-orders" class="panel-more">全部待办 →</router-link>
      </div>
      <div v-loading="loadingTodos" class="todo-wrap">
        <button type="button" class="todo-arrow" aria-label="向左滚动" @click="scrollTodos(-1)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <div ref="todoScroller" class="todo-row">
          <router-link
            v-for="todo in todos"
            :key="`${todo.kind}-${todo.id}`"
            :to="todo.to"
            class="todo-card"
            :data-kind="todo.kind"
          >
            <span class="todo-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <template v-if="todo.kind === 'workorder'">
                  <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
                </template>
                <template v-else-if="todo.kind === 'reservation'">
                  <rect x="3" y="5" width="18" height="16" rx="2" />
                  <path d="M8 3v4M16 3v4M3 11h18" />
                </template>
                <template v-else>
                  <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
                </template>
              </svg>
            </span>
            <span class="todo-main">
              <span class="todo-head">
                <span class="todo-code">{{ todo.kind === 'workorder' ? '工单' : todo.kind === 'reservation' ? '预约' : '反馈' }} {{ todo.code }}</span>
                <StatusTag :label="todo.statusLabel" :type="todo.statusType" />
              </span>
              <span class="todo-title">{{ todo.title }}</span>
              <span class="todo-time">{{ todo.time }}</span>
            </span>
          </router-link>
          <div v-if="!loadingTodos && todos.length === 0" class="panel-empty todo-empty">
            暂无待办事项，工单 / 预约 / 反馈有新进展时会出现在这里
          </div>
        </div>
        <button type="button" class="todo-arrow" aria-label="向右滚动" @click="scrollTodos(1)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

/* 问候横幅：晨色渐变（暖杏 → 浅蓝），深字压浅底 */
.greeting-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-xl) var(--spacing-lg);
  border-radius: var(--radius-lg);
  background: linear-gradient(120deg, #fde8d7 0%, #dbeafe 100%);
  color: var(--color-text-primary);
  flex-wrap: wrap;
}

.greeting-text h1 {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.greeting-sun {
  width: 26px;
  height: 26px;
  color: var(--color-warning);
}

.greeting-text p {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.unread-entry {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid rgba(59, 109, 255, 0.35);
  border-radius: var(--radius-pill);
  background-color: rgba(255, 255, 255, 0.65);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.2s;
}

.unread-entry:hover {
  background-color: rgba(255, 255, 255, 0.9);
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
  background-color: var(--color-text-disabled);
}

.unread-dot.is-active {
  background-color: var(--color-warning);
}

/* 主体两栏：公告大容器 + 右栏大容器，stretch 等高 */
.home-grid {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: var(--spacing-md);
  align-items: stretch;
}

.card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.section-title::before {
  content: '';
  width: 4px;
  height: 16px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.panel-more {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  text-decoration: none;
}

.panel-loading,
.panel-empty {
  padding: var(--spacing-xl) 0;
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

/* 公告大容器内两条等大横向行：左图右文，均分剩余高度 */
.notice-panel {
  display: flex;
  flex-direction: column;
}

.notice-panel .section-title-icon {
  width: 18px;
  height: 18px;
  color: var(--color-primary);
}

.notice-card {
  flex: 1;
  display: grid;
  grid-template-columns: 200px 1fr;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.notice-card + .notice-card {
  margin-top: var(--spacing-md);
}

.notice-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.notice-cover {
  width: 100%;
  height: 100%;
  min-height: 120px;
  object-fit: cover;
}

.notice-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md) var(--spacing-lg);
  min-width: 0;
}

.notice-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notice-excerpt {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notice-date {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: auto 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.notice-date svg {
  width: 13px;
  height: 13px;
}

/* 右栏大容器：个人信息行 + 快捷入口 */
.side-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.profile-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.profile-info {
  flex: 1;
  min-width: 0;
}

.profile-avatar {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  display: flex;
  align-items: center;
  justify-content: center;
}

.profile-name {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.profile-username {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.profile-link {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  text-decoration: none;
}

/* 快捷入口四色宫格 */
.entries .section-title {
  margin-bottom: var(--spacing-md);
}

.entries-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-sm);
}

.entry-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--spacing-md) var(--spacing-sm);
  border-radius: var(--radius-md);
  text-decoration: none;
  text-align: center;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.entry-card:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}

.entry-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: var(--spacing-xs);
}

.entry-icon svg {
  width: 20px;
  height: 20px;
}

.entry-card[data-color='blue'] { background: var(--color-primary-bg); }
.entry-card[data-color='blue'] .entry-icon { color: var(--color-primary); }
.entry-card[data-color='orange'] { background: #fef3e2; }
.entry-card[data-color='orange'] .entry-icon { color: var(--color-warning); }
.entry-card[data-color='green'] { background: #e7f8f1; }
.entry-card[data-color='green'] .entry-icon { color: var(--color-success); }
.entry-card[data-color='purple'] { background: #f1ebfd; }
.entry-card[data-color='purple'] .entry-icon { color: #8b5cf6; }

.entry-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.entry-subtitle {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 我的待办：白色大容器 + 横向滚动卡条（原生滚动条隐藏，用箭头滚动） */
.todo-panel .section-title {
  margin-bottom: 0;
}

.todo-wrap {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-height: 96px;
}

.todo-row {
  flex: 1;
  display: flex;
  gap: var(--spacing-md);
  overflow-x: auto;
  scrollbar-width: none;
  padding: var(--spacing-xs) 0;
}

.todo-row::-webkit-scrollbar {
  display: none;
}

.todo-arrow {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-circle);
  background: #fff;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.todo-arrow:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

.todo-arrow svg {
  width: 16px;
  height: 16px;
}

.todo-card {
  flex: 0 0 300px;
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  text-decoration: none;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.todo-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.todo-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
}

.todo-icon svg {
  width: 20px;
  height: 20px;
}

.todo-card[data-kind='workorder'] .todo-icon { background: var(--color-primary-bg); color: var(--color-primary); }
.todo-card[data-kind='reservation'] .todo-icon { background: #e7f8f1; color: var(--color-success); }
.todo-card[data-kind='feedback'] .todo-icon { background: #fef3e2; color: var(--color-warning); }

.todo-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.todo-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.todo-code {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.todo-empty {
  flex: 1;
}

/* 响应式：窄屏单栏 */
@media (max-width: 991px) {
  .home-grid {
    grid-template-columns: 1fr;
  }
}
</style>
