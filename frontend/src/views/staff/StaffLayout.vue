<script setup lang="ts">
import AppHeader from '@/components/layout/AppHeader.vue'

/** 服务人员工作台布局：顶部导航 + 单栏内容区（UI设计.md §2.2） */
const navItems = [
  { label: '工作台', to: '/staff/dashboard' },
  { label: '工单列表', to: '/staff/work-orders' },
  { label: '消息', to: '/staff/notifications' }
]
</script>

<template>
  <div class="staff-layout">
    <AppHeader :items="navItems" />
    <main class="staff-main">
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
</style>
