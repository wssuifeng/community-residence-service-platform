<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import { useUserStore } from '@/store/user'
import { computed } from 'vue'

/** 管理端布局：左侧边栏 + 顶部导航（面包屑）+ 内容区（UI设计.md §2.3） */
const route = useRoute()
const userStore = useUserStore()

const breadcrumb = computed(() => String(route.meta.title ?? ''))
const realName = computed(() => userStore.user?.realName ?? '')
</script>

<template>
  <div class="admin-layout">
    <AppHeader />
    <div class="admin-body">
      <AppSidebar />
      <main class="admin-main">
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

/* 平板端（768~1199px）：侧边栏收窄 */
@media (min-width: 768px) and (max-width: 1199px) {
  .admin-main {
    padding: var(--spacing-md);
  }
}
</style>
