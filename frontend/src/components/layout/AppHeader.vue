<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage } from 'element-plus'
import { residentLogout, adminLogout } from '@/api/auth'

/**
 * 顶部导航栏（三端通用，UI设计.md §2）：Logo + 导航菜单 + 用户下拉
 */
defineProps<{
  /** 顶部导航菜单项 */
  items?: { label: string; to: string }[]
}>()

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

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

/* 平板端（768~1199px）：导航可横向滚动 */
@media (min-width: 768px) and (max-width: 1199px) {
  .app-header-nav {
    overflow-x: auto;
  }
}
</style>
