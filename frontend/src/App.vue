<script setup lang="ts">
import { useUserStore } from '@/store/user'
import { useNotificationStore } from '@/store/notification'

/* 页面刷新恢复：sessionStorage 会话不走 setSession，登录态下补通知初始化 */
const userStore = useUserStore()
const notificationStore = useNotificationStore()
if (userStore.isLoggedIn) notificationStore.init()
</script>

<template>
  <router-view />
  <div class="mobile-only" role="alert">
    本系统面向桌面端使用，请使用 768px 以上宽度的设备访问。
  </div>
</template>
