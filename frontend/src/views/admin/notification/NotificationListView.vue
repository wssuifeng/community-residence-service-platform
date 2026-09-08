<script setup lang="ts">
import NotificationList from '@/components/business/NotificationList.vue'
import { useUserStore } from '@/store/user'

/** 管理端消息中心（三端复用组件，接口按登录态返回本人通知） */
const userStore = useUserStore()
</script>

<template>
  <NotificationList :role="userStore.role" />
</template>
