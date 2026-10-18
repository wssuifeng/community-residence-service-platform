<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppHeader from '@/common/web/components/AppHeader.vue'
import AppSidebar from '@/admin/layout/components/AppSidebar.vue'
import { useUserStore } from '@/common/auth/store/user'
import { computed, ref, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import { useResponsive } from '@/common/web/composables/useResponsive'

/** 管理端布局：左侧边栏 + 顶部导航（面包屑）+ 内容区（UI设计.md §2.3） */
const route = useRoute()
const conversationPage = computed(() => route.path.startsWith('/admin/conversations'))
const userStore = useUserStore()

const breadcrumb = computed(() => String(route.meta.title ?? ''))
const realName = computed(() => userStore.user?.realName ?? '')
const { isTablet } = useResponsive()
const sidebarOpen = ref(false)

/* 规范§3.2.2：平板默认收起；切页或跨断点不继承覆盖层的打开状态。 */
watch(() => route.fullPath, () => { sidebarOpen.value = false })
watch(isTablet, () => { sidebarOpen.value = false })
</script>

<template>
  <div class="admin-layout">
    <AppHeader>
      <template #menu-toggle>
        <button
          v-if="isTablet"
          type="button"
          class="admin-menu-toggle"
          aria-label="打开管理菜单"
          aria-controls="admin-tablet-menu"
          :aria-expanded="sidebarOpen"
          @click="sidebarOpen = !sidebarOpen"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
      </template>
    </AppHeader>
    <div class="admin-body">
      <AppSidebar v-if="!isTablet" />
      <ElDrawer
        v-else
        v-model="sidebarOpen"
        class="admin-menu-drawer"
        direction="ltr"
        size="var(--admin-sidebar-width)"
        :with-header="false"
        :destroy-on-close="true"
        :close-on-click-modal="true"
        title="管理菜单"
      >
        <div id="admin-tablet-menu" class="admin-sidebar-panel">
          <AppSidebar />
        </div>
      </ElDrawer>
      <main class="admin-main" :class="{ 'conversation-host': conversationPage }">
        <div class="admin-breadcrumb" aria-label="面包屑">
          管理后台 / {{ breadcrumb }}
          <span class="admin-breadcrumb-user">{{ realName }}</span>
        </div>
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-body {
  display: flex;
  min-height: calc(100vh - 60px);
}

.admin-main {
  flex: 1;
  min-width: 0;
  padding: var(--spacing-lg);
  background: var(--admin-canvas);
}

.admin-breadcrumb {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  margin-bottom: var(--spacing-md);
  display: flex;
  justify-content: space-between;
}

.admin-breadcrumb-user {
  color: var(--color-text-disabled);
}

/* Drawer 复用遮罩、Esc 与焦点管理；只改变侧栏展示方式，不复制菜单/权限逻辑。 */
.admin-menu-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  color: var(--color-text-primary);
  cursor: pointer;
}

.admin-menu-toggle:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

:global(.admin-menu-drawer .el-drawer__body) {
  padding: 0;
}

.admin-sidebar-panel :deep(.app-sidebar) {
  position: static;
  width: 100%;
  height: 100vh;
}

/* 平板端（768~1199px）：内容占满，侧栏仅覆盖展开。 */
@media (min-width: 768px) and (max-width: 1199px) {
  .admin-main {
    padding: var(--spacing-md);
  }
}

.admin-main.conversation-host {
  display: flex;
  flex-direction: column;
  height: calc(100dvh - 60px);
  min-height: 0;
  padding: 12px 16px 16px;
}
.conversation-host .admin-breadcrumb { flex-shrink: 0; margin-bottom: 6px; line-height: 18px; }
</style>
