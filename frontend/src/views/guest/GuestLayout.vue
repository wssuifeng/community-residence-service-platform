<script setup lang="ts">
import AppHeader from '@/components/layout/AppHeader.vue'

/** 游客端布局：顶部导航 + 单栏内容区（UI设计.md §2，未登录态） */
const navItems = [
  { label: '首页', to: '/guest/home' },
  { label: '房源', to: '/guest/housings' },
  { label: '公告', to: '/guest/notices' }
]
</script>

<template>
  <div class="guest-layout">
    <AppHeader :items="navItems" />
    <main class="guest-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.guest-main {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-lg) var(--spacing-md) var(--spacing-xxl);
  width: 100%;
}
</style>
