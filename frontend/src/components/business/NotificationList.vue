<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listNotifications,
  listUnreadNotifications,
  markAllNotificationsRead,
  markNotificationRead
} from '@/api/notification'
import type { INotification } from '@/types/modules/notification'
import { formatRelative } from '@/utils/date'
import { notificationTarget } from '@/utils/notificationNav'
import { useNotificationStore } from '@/store/notification'
import type { Role } from '@/types/api'

/**
 * 通知列表（三端复用：居民/服务人员/管理端，接口按登录态返回本人通知）。
 * 布局：左分类筛选列 + 右消息列表（加载更多累加）。
 * 分类筛选为前端过滤——通知列表接口仅支持 page/size/isRead（无 sourceType 参数，
 * 前端类型的 sourceType 查询参数为文档漂移），故按「加载更多」累加后在前端分组。
 */
const props = withDefaults(
  defineProps<{
    /** 当前端角色，决定通知点击后的跳转前缀 */
    role: Role
  }>(),
  { role: 'RESIDENT' }
)

/** 通知分类：key → 匹配的 sourceType 集合（SYSTEM 兜底未识别类型） */
const CATEGORY_TYPES: Record<string, string[]> = {
  workorder: ['WORK_ORDER'],
  feedback: ['FEEDBACK'],
  reservation: ['RESERVATION', 'RESOURCE_RESERVATION'],
  notice: ['NOTICE'],
  system: ['SYSTEM', 'LEASE', 'LEASE_RECORD', 'TASK']
}

const categories = [
  { key: 'all', label: '全部', icon: 'grid' },
  { key: 'workorder', label: '工单', icon: 'doc' },
  { key: 'feedback', label: '反馈', icon: 'chat' },
  { key: 'reservation', label: '预约', icon: 'calendar' },
  { key: 'notice', label: '公告', icon: 'megaphone' },
  { key: 'system', label: '系统', icon: 'gear' }
]

/** sourceType → 分类 key（未识别归系统） */
function categoryOf(item: INotification): string {
  for (const [key, types] of Object.entries(CATEGORY_TYPES)) {
    if (types.includes(item.sourceType)) return key
  }
  return 'system'
}

const router = useRouter()
/** 共享通知 store：已读操作同步铃铛角标（否则要等 30s 轮询） */
const notificationStore = useNotificationStore()
const loading = ref(false)
const records = ref<INotification[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const tab = ref<'all' | 'unread'>('all')
const category = ref('all')
const unreadCount = ref(0)

let pollTimer: ReturnType<typeof setInterval> | null = null

/** 分类前端过滤（基于已累加的记录） */
const filteredRecords = computed(() => {
  if (category.value === 'all') return records.value
  return records.value.filter((item) => categoryOf(item) === category.value)
})

const hasMore = computed(() => records.value.length < total.value)

onMounted(() => {
  load()
  refreshUnread()
  pollTimer = setInterval(refreshUnread, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listNotifications({
      page: page.value,
      size: size.value,
      ...(tab.value === 'unread' ? { isRead: false } : {})
    })
    /* 加载更多：首页替换、后续页累加 */
    records.value = page.value === 1 ? result.records : [...records.value, ...result.records]
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载通知失败')
  } finally {
    loading.value = false
  }
}

async function refreshUnread(): Promise<void> {
  try {
    const result = await listUnreadNotifications()
    unreadCount.value = result.count
  } catch {
    /* 轮询失败静默，不打扰用户 */
  }
}

function switchTab(next: 'all' | 'unread'): void {
  tab.value = next
  page.value = 1
  load()
}

function loadMore(): void {
  page.value += 1
  load()
}

/** 点击通知：先标记已读，再按来源类型映射跳转（无目标则停留） */
async function handleClick(item: INotification): Promise<void> {
  if (!item.isRead) {
    try {
      await markNotificationRead(item.id)
      item.isRead = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
      notificationStore.markLocalRead(item.id)
    } catch {
      /* 已读失败不阻塞跳转 */
    }
  }
  const target = notificationTarget(item, props.role)
  if (target) router.push(target)
}

async function handleMarkAll(): Promise<void> {
  try {
    const result = await markAllNotificationsRead()
    /* 后端 data 为 null 时 count 为 null（未回传数量），提示语降级 */
    ElMessage.success(result.count === null ? '已全部标记为已读' : `已将 ${result.count} 条通知标记为已读`)
    unreadCount.value = 0
    notificationStore.markAllLocalRead()
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}
</script>

<template>
  <section class="notification-list">
    <header class="page-head">
      <h1>消息中心</h1>
      <div class="page-head-actions">
        <div class="notification-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'all'"
            :class="{ active: tab === 'all' }"
            @click="switchTab('all')"
          >
            全部
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'unread'"
            :class="{ active: tab === 'unread' }"
            @click="switchTab('unread')"
          >
            未读
            <span v-if="unreadCount > 0" class="notification-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
          </button>
        </div>
        <el-button :disabled="unreadCount === 0" @click="handleMarkAll">全部已读</el-button>
      </div>
    </header>

    <div class="notif-layout">
      <!-- 左：分类筛选列（前端过滤，接口无 sourceType 参数） -->
      <nav class="category-col" aria-label="通知分类">
        <button
          v-for="item in categories"
          :key="item.key"
          type="button"
          class="category-item"
          :class="{ active: category === item.key }"
          @click="category = item.key"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <template v-if="item.icon === 'grid'">
              <rect x="3" y="3" width="7" height="7" rx="1" />
              <rect x="14" y="3" width="7" height="7" rx="1" />
              <rect x="3" y="14" width="7" height="7" rx="1" />
              <rect x="14" y="14" width="7" height="7" rx="1" />
            </template>
            <template v-else-if="item.icon === 'doc'">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <path d="M8 13h8M8 17h5" />
            </template>
            <template v-else-if="item.icon === 'chat'">
              <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
            </template>
            <template v-else-if="item.icon === 'calendar'">
              <rect x="3" y="5" width="18" height="16" rx="2" />
              <path d="M8 3v4M16 3v4M3 11h18" />
            </template>
            <template v-else-if="item.icon === 'megaphone'">
              <path d="M3 11l14-6v14L3 13v-2z" />
              <path d="M11.6 16.8a3 3 0 1 1-5.8-1.6" />
            </template>
            <template v-else>
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.09a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.09a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.09a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </template>
          </svg>
          {{ item.label }}
        </button>
      </nav>

      <!-- 右：消息列表容器 -->
      <div class="notif-card">
        <ul v-loading="loading && page === 1" class="notification-items">
          <li
            v-for="item in filteredRecords"
            :key="item.id"
            :class="{ unread: !item.isRead }"
            @click="handleClick(item)"
          >
            <span class="type-icon" :data-type="categoryOf(item)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <template v-if="categoryOf(item) === 'workorder'">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                  <path d="M8 13h8M8 17h5" />
                </template>
                <template v-else-if="categoryOf(item) === 'feedback'">
                  <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
                </template>
                <template v-else-if="categoryOf(item) === 'reservation'">
                  <rect x="3" y="5" width="18" height="16" rx="2" />
                  <path d="M8 3v4M16 3v4M3 11h18" />
                </template>
                <template v-else-if="categoryOf(item) === 'notice'">
                  <path d="M3 11l14-6v14L3 13v-2z" />
                  <path d="M11.6 16.8a3 3 0 1 1-5.8-1.6" />
                </template>
                <template v-else>
                  <circle cx="12" cy="12" r="3" />
                  <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1" />
                </template>
              </svg>
            </span>
            <span v-if="!item.isRead" class="unread-dot" aria-label="未读"></span>
            <div class="notification-item-main">
              <p class="notification-item-title">{{ item.title }}</p>
              <p class="notification-item-content">{{ item.content }}</p>
            </div>
            <time class="notification-item-time">{{ formatRelative(item.createdAt) }}</time>
          </li>
          <li v-if="!loading && filteredRecords.length === 0" class="notification-empty">
            <img src="/images/empty-state.png" alt="" />
            <p>{{ tab === 'unread' ? '没有未读通知' : category === 'all' ? '暂无通知消息' : '该分类下暂无通知' }}</p>
          </li>
        </ul>

        <div v-if="hasMore" class="load-more">
          <el-button text type="primary" :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.notification-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.page-head-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.notification-tabs {
  display: inline-flex;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.notification-tabs button {
  position: relative;
  padding: var(--spacing-xs) var(--spacing-lg);
  border: none;
  background-color: #fff;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: var(--font-size-sm);
  transition: background-color 0.2s ease, color 0.2s ease;
}

.notification-tabs button.active {
  background-color: var(--color-primary);
  color: #fff;
}

.notification-badge {
  display: inline-block;
  min-width: 18px;
  margin-left: var(--spacing-xs);
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background-color: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  line-height: 18px;
}

/* 左分类列 + 右列表容器 */
.notif-layout {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: var(--spacing-md);
  align-items: start;
}

.category-col {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.category-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border: none;
  border-radius: var(--radius-md);
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.category-item svg {
  width: 16px;
  height: 16px;
}

.category-item:hover {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.category-item.active {
  background: var(--color-primary);
  color: #fff;
}

/* 消息列表：容器内 1px 分隔线，非行卡 */
.notif-card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.notification-items {
  min-height: 200px;
}

.notification-items li {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid #eef0f3;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.notification-items li:last-child {
  border-bottom: none;
}

.notification-items li:hover {
  background-color: var(--color-bg-hover);
}

.notification-items li.unread .notification-item-title {
  font-weight: var(--font-weight-bold);
}

/* 类型彩色圆形图标：工单蓝 / 反馈橙 / 预约绿 / 公告紫 / 系统灰 */
.type-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-circle);
  display: flex;
  align-items: center;
  justify-content: center;
}

.type-icon svg {
  width: 18px;
  height: 18px;
}

.type-icon[data-type='workorder'] { background: var(--color-primary-bg); color: var(--color-primary); }
.type-icon[data-type='feedback'] { background: #fef3e2; color: var(--color-warning); }
.type-icon[data-type='reservation'] { background: #e7f8f1; color: var(--color-success); }
.type-icon[data-type='notice'] { background: #f1ebfd; color: #8b5cf6; }
.type-icon[data-type='system'] { background: var(--color-bg-hover); color: var(--color-text-secondary); }

.unread-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  margin-left: calc(-1 * var(--spacing-md));
  border-radius: var(--radius-circle);
  background: var(--color-primary);
}

.notification-item-main {
  flex: 1;
  min-width: 0;
}

.notification-item-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notification-item-content {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notification-item-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.notification-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xxl) 0;
  cursor: default;
}

.notification-empty img {
  width: 96px;
  height: 96px;
  object-fit: contain;
}

.notification-empty p {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: var(--spacing-sm) 0;
  border-top: 1px solid #eef0f3;
}

/* 响应式：窄屏分类列降横排 */
@media (max-width: 991px) {
  .notif-layout {
    grid-template-columns: 1fr;
  }

  .category-col {
    flex-direction: row;
    flex-wrap: wrap;
  }
}
</style>
