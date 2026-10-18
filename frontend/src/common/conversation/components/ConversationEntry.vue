<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/common/auth/store/user'
import type { ConversationSource } from '@/common/conversation/types/conversationInbox'
const props = defineProps<{ id: number; source?: ConversationSource; title?: string; closed?: boolean; compact?: boolean }>()
const route = useRoute(), user = useUserStore()
const target = computed(() => ({ path: `${user.role === 'RESIDENT' ? '/resident' : user.role === 'STAFF' ? '/staff' : '/admin'}/conversations`, query: { id: props.id, source: props.source ?? 'CONVERSATION', returnTo: route.fullPath } }))
</script>
<template>
 <div class="conversation-entry" :class="{ compact }"><div v-if="!compact"><strong>{{ title || '业务沟通' }}</strong><p>{{ closed ? '会话已关闭，可在会话页查看完整记录。' : '文字、图片与业务消息集中在会话页，原沟通记录保持不变。' }}</p></div><router-link :to="target">{{ closed ? '查看沟通记录' : '进入沟通' }} →</router-link></div>
</template>
<style scoped>
.conversation-entry{display:flex;align-items:center;justify-content:space-between;gap:16px;border:1px solid var(--color-border,#e4e9f2);border-radius:10px;padding:16px 20px;background:var(--color-surface,#fff);min-width:0}.conversation-entry strong{font-size:14px;font-weight:600}.conversation-entry p{font-size:12px;line-height:1.7;color:var(--color-text-secondary);margin:6px 0 0}.conversation-entry a{flex-shrink:0;padding:8px 13px;border:1px solid var(--color-border);border-radius:7px;text-decoration:none;font-size:13px;color:var(--color-primary);background:white}.conversation-entry.compact{border:0;padding:0;background:none;display:inline-flex}.conversation-entry a:focus-visible{outline:2px solid var(--color-primary);outline-offset:2px}
</style>
