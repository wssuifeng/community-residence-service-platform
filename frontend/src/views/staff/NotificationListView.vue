<script setup lang="ts">
import NotificationList from '@/common/messaging/components/NotificationList.vue'

/** 服务人员端消息中心（三端复用组件） */
</script>

<template>
  <router-link class="conversation-link" to="/staff/conversations">进入会话 →</router-link>
  <NotificationList role="STAFF" />
</template>
