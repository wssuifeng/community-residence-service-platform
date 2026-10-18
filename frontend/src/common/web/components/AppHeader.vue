<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/common/auth/store/user'
import { useNotificationStore } from '@/common/messaging/store/notification'
import { ElMessage } from 'element-plus'
import { residentLogout, adminLogout } from '@/common/auth/api/auth'
import { markNotificationRead } from '@/common/messaging/api/notification'
import { notificationTarget } from '@/common/messaging/utils/notificationNav'
import AppLogo from '@/common/web/components/AppLogo.vue'

/**
 * 顶部导航栏（三端通用，UI设计.md §2）：Logo + 导航菜单 + 通知铃铛 + 用户下拉
 */
defineProps<{
  /**
   * 顶部导航菜单项。
   * `to` 为跳转目标；`activeMatch` 为可选的点亮前缀集合——缺省时用 `to` 前缀匹配，
   * 提供后按集合逐一前缀匹配（用于一个菜单项覆盖多个不共享前缀的子路径，
   * 如「租住与房源」需覆盖 /resident/housings/:id 等 Hub 之外的子页）。
   */
  items?: { label: string; to: string; activeMatch?: string[] }[]
  /** 未登录态是否追加「注册」按钮（游客端对齐设计稿；默认不展示，其他端不受影响） */
  showRegister?: boolean
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
const portalBase = computed(() => userStore.role === 'RESIDENT' ? '/resident' : userStore.role === 'STAFF' ? '/staff' : '/admin')
const profilePath = computed(() => `${portalBase.value}/profile`)
const homePath = computed(() => userStore.role === 'RESIDENT' ? '/resident/home' : `${portalBase.value}/dashboard`)

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
  if (command === 'profile') {
    void router.push(profilePath.value)
  } else if (command === 'home') {
    void router.push(homePath.value)
  } else if (command === 'logout') {
    void handleLogout()
  }
}

/**
 * 导航项点亮判定：优先用 activeMatch 前缀集合，缺省回落到 `to` 前缀匹配。
 * 集合内任一项命中即点亮（向后兼容：既有调用方不传 activeMatch，行为与原先一致）。
 */
function isItemActive(item: { to: string; activeMatch?: string[] }): boolean {
  const prefixes = item.activeMatch?.length ? item.activeMatch : [item.to]
  return prefixes.some((prefix) => route.path === prefix || route.path.startsWith(`${prefix}/`))
}

/** 铃铛下拉面板实例（点击面板项后收起面板） */
const bellPopoverRef = ref<{ hide: () => void } | null>(null)

/** 铃铛面板项点击：未读先标记已读，再按来源类型跳转并收起面板 */
async function handleBellItemClick(item: {
  id: number
  isRead: boolean
  sourceType?: string | null
  sourceId?: number | null
}): Promise<void> {
  if (!item.isRead) {
    try {
      await markNotificationRead(item.id)
      notificationStore.markLocalRead(item.id)
    } catch {
      /* 已读失败不阻塞跳转 */
    }
  }
  bellPopoverRef.value?.hide()
  const target = notificationTarget(item, userStore.role)
  if (target) router.push(target)
}
</script>

<template>
  <header class="app-header">
    <div class="app-header-inner">
      <slot name="menu-toggle" />
      <router-link to="/" class="app-header-logo">
        <AppLogo :size="22" />
        <span>社区居住服务</span>
      </router-link>

      <nav class="app-header-nav" aria-label="主导航">
        <router-link
          v-for="item in items"
          :key="item.to"
          :to="item.to"
          class="app-header-nav-item"
          :class="{ active: isItemActive(item) }"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <div class="app-header-user">
        <el-popover
          v-if="userStore.isLoggedIn"
          ref="bellPopoverRef"
          placement="bottom-end"
          width="320"
          trigger="click"
          @show="notificationStore.refreshBaseline"
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
          <div class="bell-heading"><strong>未读通知</strong><span>{{ notificationStore.unreadCount }} 条</span></div>
          <div class="bell-panel" role="region" aria-label="未读通知列表" tabindex="0">
            <div v-if="notificationStore.unread.length === 0" class="bell-empty">暂无未读通知</div>
            <button
              v-for="item in notificationStore.unread"
              :key="item.id"
              type="button"
              class="bell-item is-unread"
              @click="handleBellItemClick(item)"
            >
              <span class="bell-item-title">{{ item.title }}</span>
              <span class="bell-item-content">{{ item.content }}</span>
            </button>
          </div>
          <router-link class="bell-all" :to="`/${userStore.role === 'RESIDENT' ? 'resident' : userStore.role === 'STAFF' ? 'staff' : 'admin'}/notifications`" @click="bellPopoverRef?.hide()">进入消息中心 →</router-link>
        </el-popover>
        <template v-if="userStore.isLoggedIn">
          <el-dropdown trigger="click" @command="handleCommand">
            <button type="button" class="app-header-user-trigger" aria-label="个人菜单">
              <!-- 用户无头像字段，统一用首字圆形占位头像（主色底白字） -->
              <span class="app-header-avatar">{{ realName.slice(0, 1) }}</span>
              <span class="app-header-user-name">{{ realName }}（{{ roleLabel }}）</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="home">返回首页</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <router-link to="/auth/login" class="app-header-login">登录</router-link>
          <router-link v-if="showRegister" to="/auth/register" class="app-header-register">
            注册
          </router-link>
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
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  white-space: nowrap;
}

.app-header-nav {
  display: flex;
  gap: var(--spacing-md);
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.app-header-nav::-webkit-scrollbar {
  display: none;
}

.app-header-nav-item {
  color: var(--color-text-secondary);
  flex-shrink: 0;
  white-space: nowrap;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
}

.app-header-nav-item:hover,
.app-header-nav-item.active {
  color: var(--color-primary);
  background: var(--color-primary-bg);
}

.app-header-user {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
  /* 兜底右对齐：nav 缺席或被压缩时用户集群仍贴容器右缘 */
  margin-left: auto;
}

.app-header-user-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--color-text-primary);
  font: inherit;
  cursor: pointer;
}

.app-header-user-trigger:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 4px;
  border-radius: var(--radius-sm);
}

.app-header-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-circle);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
}

.app-header-user-name {
  line-height: 1;
}

.app-header-login {
  color: var(--color-primary);
}

.app-header-register {
  margin-left: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-pill);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
}

.app-header-register:hover {
  background: var(--color-primary-bg);
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

.bell-heading { display: flex; align-items: center; justify-content: space-between; padding: 2px 4px 12px; border-bottom: 1px solid var(--color-border); }
.bell-heading span { color: var(--color-text-secondary); font-size: 12px; }
.bell-all { display: block; text-align: center; padding: 12px 0 0; border-top: 1px solid var(--color-border); color: var(--color-primary); }
.bell-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  max-height: min(360px, 60vh);
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 8px 0;
  overflow-y: auto;
}

.bell-empty {
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.bell-item {
  display: block;
  width: 100%;
  text-align: left;
  border: 0;
  font: inherit;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.bell-item:hover {
  background-color: var(--color-bg-hover);
}

.bell-item.is-unread {
  background-color: var(--color-primary-bg);
  border-left: 3px solid var(--color-primary);
}

.bell-item-title {
  display: block;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.bell-item-content {
  display: block;
  margin-top: 2px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

<style>
/* 管理端宿主（.admin-layout）内容区为全宽，头部同步放开 1200px 限宽，
   使右侧用户集群贴近视口右缘（验收反馈：集群偏左、右侧大片空白）；
   居民端/服务人员端/游客端内容列仍为 1200px 居中，维持与内容对齐。
   说明：scoped 规则无法向祖先方向匹配，故用非 scoped 块并提升特异性压过上面的限宽规则 */
.admin-layout .app-header .app-header-inner {
  max-width: none;
}
</style>
