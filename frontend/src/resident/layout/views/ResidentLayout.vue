<script setup lang="ts">
import { navItems } from '@/resident/layout/navigation'

import AppHeader from '@/common/web/components/AppHeader.vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const conversationPage = computed(() => route.path.startsWith('/resident/conversations'))

/**
 * 居民端布局：顶部导航 + 单栏内容区（UI设计.md §2.1）
 * 导航 8 项，按居民高频使用排序：浏览资源与管理预约邻位成组（公共资源 → 我的预约），
 * 租住与房源紧随其后（居住事务双 Tab Hub，R66 导航合并：原「我的租约」「房源」两项合一），
 * 工单/反馈为日常服务链，公告为信息浏览，消息收尾。
 *
 * activeMatch：Hub 之外的租房子页（房源详情、预约看房、申请租住、看房预约详情）
 * 路径不在 Hub 前缀下，靠 activeMatch 一并点亮，避免进入子页后导航失去高亮。
 */

</script>

<template>
  <div class="resident-layout">
    <AppHeader :items="navItems" />
    <main class="resident-main" :class="{ 'conversation-host': conversationPage }">
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

.resident-main.conversation-host {
  display: flex;
  flex-direction: column;
  max-width: none;
  height: calc(100dvh - 60px);
  min-height: 0;
  padding: 12px clamp(12px, 2vw, 28px) 16px;
}
</style>
