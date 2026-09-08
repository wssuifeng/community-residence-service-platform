<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import {
  listNotifications,
  listUnreadNotifications,
  markAllNotificationsRead,
  markNotificationRead
} from '@/api/notification'
import type { INotification } from '@/types/modules/notification'
import { formatRelative } from '@/utils/date'
import type { Role } from '@/types/api'

/**
 * 通知列表（三端复用：居民/服务人员/管理端，接口按登录态返回本人通知）。
 * P1 阶段无 WebSocket，进入页面拉一次未读数，10 秒轮询刷新角标。
 */
const props = withDefaults(
  defineProps<{
    /** 当前端角色，决定通知点击后的跳转前缀 */
    role: Role
  }>(),
  { role: 'RESIDENT' }
)

const router = useRouter()
const loading = ref(false)
const records = ref<INotification[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const tab = ref<'all' | 'unread'>('all')
const unreadCount = ref(0)

let pollTimer: ReturnType<typeof setInterval> | null = null

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
    records.value = result.records
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

/** 点击通知：标记已读并按来源跳转对应详情页 */
async function handleClick(item: INotification): Promise<void> {
  if (!item.isRead) {
    try {
      await markNotificationRead(item.id)
      item.isRead = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch {
      /* 已读失败不阻塞跳转 */
    }
  }
  if (item.sourceId === null) return
  const prefix = props.role === 'STAFF' ? '/staff/work-orders' : props.role === 'RESIDENT' ? '/resident/work-orders' : '/admin/work-orders'
  router.push(`${prefix}/${item.sourceId}`)
}

async function handleMarkAll(): Promise<void> {
  try {
    const result = await markAllNotificationsRead()
    ElMessage.success(`已将 ${result.count} 条通知标记为已读`)
    unreadCount.value = 0
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}
</script>

<template>
  <section class="notification-list">
    <div class="notification-toolbar">
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

    <ul v-loading="loading" class="notification-items">
      <li
        v-for="item in records"
        :key="item.id"
        :class="{ unread: !item.isRead }"
        @click="handleClick(item)"
      >
        <div class="notification-item-main">
          <p class="notification-item-title">{{ item.title }}</p>
          <p class="notification-item-content">{{ item.content }}</p>
        </div>
        <div class="notification-item-meta">
          <StatusTag v-if="!item.isRead" label="未读" type="pending" />
          <StatusTag v-else label="已读" type="canceled" />
          <time>{{ formatRelative(item.createdAt) }}</time>
        </div>
      </li>
      <li v-if="!loading && records.length === 0" class="notification-empty">
        <img src="/images/empty-state.png" alt="" />
        <p>{{ tab === 'unread' ? '没有未读通知' : '暂无通知消息' }}</p>
      </li>
    </ul>

    <Pagination v-model:page="page" v-model:size="size" :total="total" @update:page="load" @update:size="load" />
  </section>
</template>

<style scoped>
.notification-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
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

.notification-items {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  min-height: 200px;
}

.notification-items li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid var(--color-border);
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

.notification-item-title {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.notification-item-content {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notification-item-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

.notification-item-meta time {
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
</style>
