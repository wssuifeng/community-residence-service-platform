<script setup lang="ts">
import AppHeader from '@/components/layout/AppHeader.vue'

/** 居民端布局：顶部导航 + 单栏内容区（UI设计.md §2.1） */
const navItems = [
  { label: '首页', to: '/resident/home' },
  { label: '我的工单', to: '/resident/work-orders' },
  { label: '我的反馈', to: '/resident/feedbacks' },
  { label: '我的预约', to: '/resident/reservations' },
  { label: '公告', to: '/resident/notices' },
  { label: '房源', to: '/resident/housings' },
  { label: '消息', to: '/resident/notifications' }
]
</script>

<template>
  <div class="resident-layout">
    <AppHeader :items="navItems" />
    <main class="resident-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.resident-main {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-lg) var(--spacing-md) var(--spacing-xxl);
  width: 100%;
}
</style>
