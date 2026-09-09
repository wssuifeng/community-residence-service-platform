<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useNotificationStore } from '@/store/notification'
import { ElMessage } from 'element-plus'
import { residentLogout, adminLogout } from '@/api/auth'

/**
 * 顶部导航栏（三端通用，UI设计.md §2）：Logo + 导航菜单 + 通知铃铛 + 用户下拉
 */
defineProps<{
  /** 顶部导航菜单项 */
  items?: { label: string; to: string }[]
}>()

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const notificationStore = useNotificationStore()

const roleLabels: Record<string, string> = {
  RESIDENT: '居民',
  STAFF: '服务人员',
  ADMIN: '社区管理员',
  SUPER_ADMIN: '超级管理员'
}

const realName = computed(() => userStore.user?.realName ?? '未登录')
const roleLabel = computed(() => roleLabels[userStore.role] ?? '')

async function handleLogout(): Promise<void> {
  try {
    if (userStore.isLoggedIn) {
      // 居民端与管理端登出接口不同（接口设计.md 9.2.1.3 / 9.10.1.2）
      if (userStore.role === 'RESIDENT') {
        await residentLogout()
      } else {
        await adminLogout()
      }
    }
  } finally {
    // 登出接口失败也清理本地会话，令牌黑名单由后端兜底
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/auth/login')
  }
}

function handleCommand(command: string): void {
  if (command === 'logout') {
    void handleLogout()
  }
}

function isActive(to: string): boolean {
  return route.path.startsWith(to)
}
</script>

<template>
  <header class="app-header">
    <div class="app-header-inner">
      <router-link to="/" class="app-header-logo">社区居住服务</router-link>

      <nav class="app-header-nav" aria-label="主导航">
        <router-link
          v-for="item in items"
          :key="item.to"
          :to="item.to"
          class="app-header-nav-item"
          :class="{ active: isActive(item.to) }"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <div class="app-header-user">
        <el-popover
          v-if="userStore.isLoggedIn"
          placement="bottom-end"
          width="320"
          trigger="click"
        >
          <template #reference>
            <button type="button" class="header-bell" aria-label="通知">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
                <path d="M13.73 21a2 2 0 0 1-3.46 0" />
              </svg>
              <span
                v-if="notificationStore.unreadCount > 0"
                class="bell-badge"
              >
                {{ notificationStore.unreadCount > 99 ? '99+' : notificationStore.unreadCount }}
              </span>
            </button>
          </template>
          <div class="bell-panel">
            <div v-if="notificationStore.latest.length === 0" class="bell-empty">
              暂无通知
            </div>
            <div
              v-for="item in notificationStore.latest.slice(0, 5)"
              :key="item.id"
              class="bell-item"
              :class="{ 'is-unread': !item.isRead }"
            >
              <div class="bell-item-title">{{ item.title }}</div>
              <div class="bell-item-content">{{ item.content }}</div>
            </div>
          </div>
        </el-popover>
        <template v-if="userStore.isLoggedIn">
          <el-dropdown @command="handleCommand">
            <span class="app-header-user-name">
              {{ realName }}（{{ roleLabel }}）
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <router-link to="/auth/login" class="app-header-login">登录</router-link>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: var(--z-index-fixed);
  background: #fff;
  border-bottom: 1px solid var(--color-border);
}

.app-header-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  height: 60px;
  padding: 0 var(--spacing-md);
}

.app-header-logo {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  white-space: nowrap;
}

.app-header-nav {
  display: flex;
  gap: var(--spacing-md);
  flex: 1;
}

.app-header-nav-item {
  color: var(--color-text-secondary);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
}

.app-header-nav-item:hover,
.app-header-nav-item.active {
  color: var(--color-primary);
  background: var(--color-primary-bg);
}

.app-header-user-name {
  cursor: pointer;
  color: var(--color-text-primary);
}

.app-header-login {
  color: var(--color-primary);
}

/* 通知铃铛 */
.header-bell {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: none;
  color: var(--color-text-secondary);
  cursor: pointer;
  border-radius: var(--radius-sm);
}

.header-bell:hover {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.bell-badge {
  position: absolute;
  top: 0;
  right: 0;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background-color: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  line-height: 16px;
  text-align: center;
}

.bell-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  max-height: 320px;
  overflow-y: auto;
}

.bell-empty {
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.bell-item {
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
}

.bell-item.is-unread {
  background-color: var(--color-primary-bg);
  border-left: 3px solid var(--color-primary);
}

.bell-item-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.bell-item-content {
  margin-top: 2px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 平板端（768~1199px）：导航可横向滚动 */
@media (min-width: 768px) and (max-width: 1199px) {
  .app-header-nav {
    overflow-x: auto;
  }
}
</style>
