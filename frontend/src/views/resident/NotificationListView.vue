<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listNotifications,
  listUnreadNotifications,
  markNotificationRead,
  markAllNotificationsRead
} from '@/api/notification'
import type {
  INotification,
  IUnreadNotification
} from '@/types/modules/notification'
import { formatRelative } from '@/utils/date'
import Pagination from '@/components/common/Pagination.vue'

/**
 * 居民消息中心：全部/未读 Tab；
 * 点击单条先标记已读再按 sourceType 跳转（工单 → 工单详情，其余暂留列表）
 */
const router = useRouter()

const activeTab = ref<'all' | 'unread'>('all')

const notifications = ref<INotification[]>([])
const loadingAll = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const unread = ref<IUnreadNotification[]>([])
const unreadCount = ref(0)
const loadingUnread = ref(false)

const markingAll = ref(false)
/** 防止点击处理期间重复触发 */
const clickingId = ref<number | null>(null)

async function loadAll(): Promise<void> {
  loadingAll.value = true
  try {
    const result = await listNotifications({ page: page.value, size: size.value })
    notifications.value = result.records
    total.value = result.total
  } catch {
    notifications.value = []
    total.value = 0
  } finally {
    loadingAll.value = false
  }
}

async function loadUnread(): Promise<void> {
  loadingUnread.value = true
  try {
    const result = await listUnreadNotifications()
    unread.value = result.notifications
    unreadCount.value = result.count
  } catch {
    unread.value = []
    unreadCount.value = 0
  } finally {
    loadingUnread.value = false
  }
}

function handleTabChange(): void {
  if (activeTab.value === 'all') {
    page.value = 1
    loadAll()
  } else {
    loadUnread()
  }
}

/** 通知来源 → 跳转地址（工单 → 详情，其余暂回列表页） */
function resolveTarget(sourceType: string, sourceId: number | null): string | null {
  if (sourceType === 'WORK_ORDER' && sourceId !== null) {
    return `/resident/work-orders/${sourceId}`
  }
  return null
}

async function handleClickAll(item: INotification): Promise<void> {
  if (clickingId.value !== null) return
  clickingId.value = item.id
  try {
    if (!item.isRead) {
      await markNotificationRead(item.id)
    }
    const target = resolveTarget(item.sourceType, item.sourceId)
    if (target) {
      router.push(target)
      return
    }
    /* 无对应详情页：留在列表并刷新已读状态 */
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    clickingId.value = null
  }
}

async function handleClickUnread(item: IUnreadNotification): Promise<void> {
  if (clickingId.value !== null) return
  clickingId.value = item.id
  try {
    await markNotificationRead(item.id)
    const target = resolveTarget(item.sourceType, item.sourceId)
    if (target) {
      router.push(target)
      return
    }
    await loadUnread()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    clickingId.value = null
  }
}

async function handleMarkAll(): Promise<void> {
  markingAll.value = true
  try {
    const result = await markAllNotificationsRead()
    ElMessage.success(`已将 ${result.count} 条通知标记为已读`)
    await Promise.all([loadAll(), loadUnread()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    markingAll.value = false
  }
}

onMounted(() => {
  loadAll()
  loadUnread()
})
</script>

<template>
  <section class="notification-page">
    <header class="page-head">
      <div>
        <h1>消息中心</h1>
        <p>{{ unreadCount > 0 ? `您有 ${unreadCount} 条未读消息` : '消息都已读，真棒' }}</p>
      </div>
      <el-button
        round
        :loading="markingAll"
        :disabled="unreadCount === 0"
        @click="handleMarkAll"
      >
        全部已读
      </el-button>
    </header>

    <el-tabs v-model="activeTab" class="notification-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane :label="`未读${unreadCount > 0 ? ` (${unreadCount})` : ''}`" name="unread" />
    </el-tabs>

    <!-- 全部：分页列表 -->
    <template v-if="activeTab === 'all'">
      <div v-if="loadingAll" class="page-loading">加载中…</div>
      <template v-else>
        <div v-if="notifications.length === 0" class="page-empty">暂无消息</div>
        <ul v-else class="notification-list">
          <li
            v-for="item in notifications"
            :key="item.id"
            class="notification-item"
            :class="{ 'is-unread': !item.isRead }"
            @click="handleClickAll(item)"
          >
            <span class="item-dot" :class="{ 'is-unread': !item.isRead }"></span>
            <div class="item-body">
              <div class="item-title-row">
                <span class="item-title">{{ item.title }}</span>
                <span class="item-read" :class="{ 'is-unread': !item.isRead }">
                  {{ item.isRead ? '已读' : '未读' }}
                </span>
              </div>
              <p class="item-content">{{ item.content }}</p>
              <span class="item-time">{{ formatRelative(item.createdAt) }}</span>
            </div>
          </li>
        </ul>
        <Pagination
          v-model:page="page"
          v-model:size="size"
          :total="total"
          @update:page="loadAll"
          @update:size="loadAll"
        />
      </template>
    </template>

    <!-- 未读：最多最新 50 条，接口不分页 -->
    <template v-else>
      <div v-if="loadingUnread" class="page-loading">加载中…</div>
      <template v-else>
        <div v-if="unread.length === 0" class="page-empty">没有未读消息</div>
        <ul v-else class="notification-list">
          <li
            v-for="item in unread"
            :key="item.id"
            class="notification-item is-unread"
            @click="handleClickUnread(item)"
          >
            <span class="item-dot is-unread"></span>
            <div class="item-body">
              <div class="item-title-row">
                <span class="item-title">{{ item.title }}</span>
                <span class="item-read is-unread">未读</span>
              </div>
              <p class="item-content">{{ item.content }}</p>
              <span class="item-time">{{ formatRelative(item.createdAt) }}</span>
            </div>
          </li>
        </ul>
        <p v-if="unreadCount > unread.length" class="unread-more">
          仅展示最新 {{ unread.length }} 条，更早消息请在「全部」中查看
        </p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.notification-page {
  max-width: 760px;
  margin: 0 auto;
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
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.page-loading,
.page-empty {
  padding: var(--spacing-xxl) 0;
  text-align: center;
  color: var(--color-text-secondary);
}

.notification-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.notification-item:hover {
  box-shadow: var(--shadow-md);
}

.notification-item.is-unread {
  background-color: var(--color-primary-bg);
  border-color: var(--color-primary-light);
}

.item-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  margin-top: 8px;
  border-radius: var(--radius-circle);
  background-color: var(--color-border);
}

.item-dot.is-unread {
  background-color: var(--color-primary);
}

.item-body {
  flex: 1;
  min-width: 0;
}

.item-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.item-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.item-read {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.item-read.is-unread {
  color: var(--color-primary);
}

.item-content {
  margin: var(--spacing-xs) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.item-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.unread-more {
  margin: 0;
  text-align: center;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
