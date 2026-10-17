<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NotificationList from '@/components/business/NotificationList.vue'
const route = useRoute(), router = useRouter()
watch(() => route.query.tab, value => {
 if (value === 'conversations') void router.replace({ path: '/resident/conversations', query: { ...route.query, tab: undefined } })
}, { immediate: true })
</script>
<template>
 <nav class="message-navigation" aria-label="消息类型"><span>业务通知</span><router-link to="/resident/conversations">进入消息会话 →</router-link></nav>
 <NotificationList role="RESIDENT" />
</template>
<style scoped>.message-navigation{display:flex;justify-content:space-between;padding:0 0 16px;font-size:13px}.message-navigation a{color:var(--color-primary);text-decoration:none}</style>
