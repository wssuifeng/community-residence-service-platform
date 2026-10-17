<script setup lang="ts">
import AppHeader from '@/components/layout/AppHeader.vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const conversationPage = computed(() => route.path.startsWith('/staff/conversations'))

/** 服务人员工作台布局：顶部导航 + 单栏内容区（UI设计.md §2.2） */
const navItems = [
  { label: '工作台', to: '/staff/dashboard' },
  { label: '工单列表', to: '/staff/work-orders' },
  { label: '我的带看', to: '/staff/viewings' },
  { label: '会话', to: '/staff/conversations', activeMatch: ['/staff/conversations'] }
]
</script>

<template>
  <div class="staff-layout">
    <AppHeader :items="navItems" />
    <main class="staff-main" :class="{ 'conversation-host': conversationPage }">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.staff-main {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-lg) var(--spacing-md) var(--spacing-xxl);
  width: 100%;
}

.staff-main.conversation-host {
  display: flex;
  flex-direction: column;
  max-width: none;
  height: calc(100dvh - 60px);
  min-height: 0;
  padding: 12px clamp(12px, 2vw, 28px) 16px;
}
</style>
