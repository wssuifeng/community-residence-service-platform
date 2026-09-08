<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { listNotices } from '@/api/notice'
import { listUnreadNotifications } from '@/api/notification'
import type { INotice } from '@/types/modules/notice'
import { formatRelative } from '@/utils/date'

/** 居民首页：问候 + 个人卡片 + 最新公告 + 快捷入口（面向居民的温度感设计，非仪表盘） */
const router = useRouter()
const userStore = useUserStore()

const realName = computed(() => userStore.user?.realName ?? '邻居')
const notices = ref<INotice[]>([])
const loadingNotices = ref(false)
const unreadCount = ref(0)

/** 按时段问候 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const today = computed(() => {
  const now = new Date()
  const weekdays = ['日', '一', '二', '三', '四', '五', '六']
  return `${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${weekdays[now.getDay()]}`
})

/** 快捷入口：图标为内联 SVG，副标题一句话说明 */
const quickEntries = [
  {
    label: '提交工单',
    subtitle: '报修、保洁等服务需求',
    to: '/resident/work-orders/create',
    icon: 'repair'
  },
  {
    label: '我的反馈',
    subtitle: '建议、投诉与咨询进展',
    to: '/resident/feedbacks',
    icon: 'chat'
  },
  {
    label: '资源预约',
    subtitle: '健身房、活动室等公共资源',
    to: '/resident/reservations/create',
    icon: 'calendar'
  },
  {
    label: '房源浏览',
    subtitle: '看看社区在租的房屋',
    to: '/resident/housings',
    icon: 'home'
  },
  {
    label: '看房预约',
    subtitle: '预约时间实地看房',
    to: '/resident/viewing-appointments',
    icon: 'clock'
  }
]

onMounted(async () => {
  /* 最新公告 5 条（后端已按高优先级置顶排序）与未读消息数并行加载 */
  loadingNotices.value = true
  try {
    const [noticePage, unread] = await Promise.all([
      listNotices({ page: 1, size: 5 }),
      listUnreadNotifications().catch(() => ({ count: 0, notifications: [] }))
    ])
    notices.value = noticePage.records
    unreadCount.value = unread.count
  } catch {
    notices.value = []
  } finally {
    loadingNotices.value = false
  }
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
    <!-- 问候区 -->
    <div class="greeting-card">
      <div class="greeting-text">
        <h1>{{ greeting }}，{{ realName }}</h1>
        <p>今天是 {{ today }}，祝您生活愉快</p>
      </div>
      <button type="button" class="unread-entry" @click="goNotifications">
        <span class="unread-dot" :class="{ 'is-active': unreadCount > 0 }"></span>
        <span>{{ unreadCount > 0 ? `${unreadCount} 条未读消息` : '暂无未读消息' }}</span>
      </button>
    </div>

    <div class="home-grid">
      <!-- 最新公告 -->
      <div class="notice-panel card">
        <div class="panel-head">
          <h2>社区公告</h2>
          <router-link to="/resident/notices" class="panel-more">全部公告 →</router-link>
        </div>
        <div v-if="loadingNotices" class="panel-loading">加载中…</div>
        <ul v-else-if="notices.length > 0" class="notice-list">
          <li v-for="notice in notices" :key="notice.id" @click="goNotice(notice.id)">
            <span
              v-if="notice.priority === 'HIGH' || notice.priority === 'URGENT'"
              class="notice-pin"
            >
              置顶
            </span>
            <span class="notice-title">{{ notice.title }}</span>
            <span class="notice-time">{{ formatRelative(notice.publishTime) }}</span>
          </li>
        </ul>
        <div v-else class="panel-empty">暂无公告</div>
      </div>

      <!-- 个人卡片 -->
      <div class="profile-card card">
        <div class="profile-avatar">{{ realName.slice(0, 1) }}</div>
        <div class="profile-info">
          <p class="profile-name">{{ realName }}</p>
          <p class="profile-username">@{{ userStore.user?.username ?? '-' }}</p>
        </div>
        <router-link to="/resident/profile" class="profile-link">个人中心 →</router-link>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="entries">
      <h2 class="entries-title">快捷服务</h2>
      <div class="entries-grid">
        <router-link
          v-for="entry in quickEntries"
          :key="entry.to"
          :to="entry.to"
          class="entry-card"
        >
          <span class="entry-icon" :data-icon="entry.icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <!-- 工单：扳手 -->
              <template v-if="entry.icon === 'repair'">
                <path d="M14.7 6.3a4 4 0 1 0 5 5L21 21l-3 0-9.7-9.7a4 4 0 1 1 0-5z" />
              </template>
              <!-- 反馈：对话气泡 -->
              <template v-else-if="entry.icon === 'chat'">
                <path d="M21 12a8 8 0 0 1-8 8H4l2-3a8 8 0 1 1 15-5z" />
              </template>
              <!-- 预约：日历 -->
              <template v-else-if="entry.icon === 'calendar'">
                <rect x="3" y="5" width="18" height="16" rx="2" />
                <path d="M8 3v4M16 3v4M3 11h18" />
              </template>
              <!-- 房源：房子 -->
              <template v-else-if="entry.icon === 'home'">
                <path d="M4 11l8-7 8 7" />
                <path d="M6 10v10h12V10" />
              </template>
              <!-- 看房：时钟 -->
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
  </section>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

/* 问候卡片 */
.greeting-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-xl) var(--spacing-lg);
  border-radius: var(--radius-lg);
  background: linear-gradient(120deg, var(--color-primary) 0%, var(--color-primary-light) 100%);
  color: #fff;
  flex-wrap: wrap;
}

.greeting-text h1 {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.greeting-text p {
  margin: 0;
  font-size: var(--font-size-sm);
  opacity: 0.9;
}

.unread-entry {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--radius-pill);
  background-color: rgba(255, 255, 255, 0.15);
  color: #fff;
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.2s;
}

.unread-entry:hover {
  background-color: rgba(255, 255, 255, 0.28);
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
  background-color: rgba(255, 255, 255, 0.5);
}

.unread-dot.is-active {
  background-color: var(--color-warning);
}

/* 主体两栏 */
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

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.panel-head h2 {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
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

.notice-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.notice-list li {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-sm);
  border-bottom: 1px solid var(--color-border);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background-color 0.15s;
}

.notice-list li:last-child {
  border-bottom: none;
}

.notice-list li:hover {
  background-color: var(--color-bg-hover);
}

.notice-pin {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  color: var(--status-rejected);
  background-color: rgba(239, 68, 68, 0.1);
}

.notice-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.notice-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 个人卡片 */
.profile-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  text-align: center;
}

.profile-avatar {
  width: 72px;
  height: 72px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xl);
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
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  text-decoration: none;
}

/* 快捷入口 */
.entries-title {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.entries-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--spacing-md);
}

.entry-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-lg) var(--spacing-md);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  text-decoration: none;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
}

.entry-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
  border-color: var(--color-primary-light);
}

.entry-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: var(--spacing-sm);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.entry-icon svg {
  width: 24px;
  height: 24px;
}

.entry-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.entry-subtitle {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

/* 响应式：窄屏两列入口 + 单栏主体 */
@media (max-width: 991px) {
  .home-grid {
    grid-template-columns: 1fr;
  }

  .entries-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
