<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConversationChat from '@/components/business/ConversationChat.vue'
import { listConversations } from '@/api/conversation'
import type { IConversation } from '@/types/modules/conversation'

/**
 * 沟通会话详情（R63，v1.5）：看房群聊/居民-社区管理员直通的独立会话页。
 * 消息实时性（WS 推送 + 轮询兜底 + 403 只读降级）全部由 ConversationChat 组件内处理；
 * 标题取会话列表中的会话名（列表拉取失败降级通用标题，不影响会话本身）。
 */

const route = useRoute()
const router = useRouter()

const conversationId = Number(route.params.id)
/** 非法直达（旧书签/坏链）→ 回消息中心沟通会话 tab */
const invalidEntry = !Number.isInteger(conversationId) || conversationId <= 0

const conversation = ref<IConversation | null>(null)

const title = computed(() => conversation.value?.title ?? '沟通会话')

async function loadTitle(): Promise<void> {
  try {
    const result = await listConversations({ page: 1, size: 50 })
    conversation.value = result.records.find((item) => item.id === conversationId) ?? null
  } catch {
    /* 标题拉取失败不影响会话本身，保持通用标题 */
  }
}

onMounted(() => {
  if (invalidEntry) {
    router.replace('/resident/notifications?tab=conversations')
    return
  }
  void loadTitle()
})
</script>

<template>
  <section class="conversation-detail">
    <nav class="breadcrumb">
      <router-link to="/resident/notifications?tab=conversations">消息中心</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">{{ title }}</span>
    </nav>

    <ConversationChat :conversation-id="conversationId" />
  </section>
</template>

<style scoped>
.conversation-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
}

.breadcrumb a {
  color: var(--color-text-secondary);
}

.breadcrumb a:hover {
  color: var(--color-primary);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}
</style>
