<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ConversationChat from '@/components/business/ConversationChat.vue'
import { listConversations } from '@/api/conversation'
import type { IConversation, ConversationType } from '@/types/modules/conversation'
import { formatDateTime } from '@/utils/date'

/**
 * 管理端消息会话中心（R63，v1.5）：左会话列表（群聊/直通筛选、未读角标）
 * + 右会话窗（ConversationChat 自处理 WS/轮询/403 只读）。
 * 管理端从看房预约「进入会话」直达本页 ?id= 预选；管理员的看房群聊与
 * 居民直通会话统一在此收口。
 */

const typeFilter = ref<'' | ConversationType>('')
const conversations = ref<IConversation[]>([])
const loading = ref(false)
const activeId = ref<number | null>(null)

const activeConversation = computed(
  () => conversations.value.find((item) => item.id === activeId.value) ?? null
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listConversations({
      page: 1,
      size: 50,
      type: typeFilter.value === '' ? undefined : typeFilter.value
    })
    conversations.value = result.records
    /* 无选中（或选中已不在过滤结果内）时默认选第一条 */
    if (activeId.value === null || !result.records.some((item) => item.id === activeId.value)) {
      activeId.value = result.records[0]?.id ?? null
    }
  } catch (error) {
    conversations.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载会话列表失败')
  } finally {
    loading.value = false
  }
}

/** 选中会话：进入后未读由组件轮询/后端 read 端点收口，这里刷新列表角标 */
function selectConversation(id: number): void {
  activeId.value = id
  const item = conversations.value.find((c) => c.id === id)
  if (item) item.unreadCount = 0
}

const typeLabels: Record<ConversationType, string> = {
  VIEWING_GROUP: '看房群聊',
  DIRECT: '直通'
}

function handleTypeChange(): void {
  activeId.value = null
  load()
}

onMounted(() => {
  const fromQuery = Number(new URLSearchParams(location.search).get('id'))
  if (Number.isInteger(fromQuery) && fromQuery > 0) activeId.value = fromQuery
  load()
})
</script>

<template>
  <section class="conversation-center">
    <header class="page-head">
      <div>
        <h1>消息会话</h1>
        <p class="page-head-sub">看房群聊与居民直通会话统一收口，点击会话即时沟通</p>
      </div>
    </header>

    <div class="center-body">
      <!-- 左：会话列表 -->
      <aside class="conv-list card" v-loading="loading">
        <div class="conv-filter">
          <el-radio-group :model-value="typeFilter" size="small" @update:model-value="handleTypeChange">
            <el-radio-button :value="''">全部</el-radio-button>
            <el-radio-button value="VIEWING_GROUP">看房群聊</el-radio-button>
            <el-radio-button value="DIRECT">直通</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="conversations.length === 0 && !loading" class="conv-empty">暂无会话</div>

        <button
          v-for="item in conversations"
          :key="item.id"
          type="button"
          class="conv-item"
          :class="{ active: item.id === activeId }"
          @click="selectConversation(item.id)"
        >
          <span class="conv-type" :data-type="item.type">{{ typeLabels[item.type] }}</span>
          <span class="conv-title">{{ item.title }}</span>
          <span v-if="item.unreadCount > 0" class="conv-unread">{{ item.unreadCount > 99 ? '99+' : item.unreadCount }}</span>
          <span class="conv-last">{{ item.lastMessageAt ? formatDateTime(item.lastMessageAt) : '' }}</span>
          <span class="conv-preview">{{ item.lastMessage ?? '—' }}</span>
        </button>
      </aside>

      <!-- 右：会话窗 -->
      <div class="conv-panel">
        <div v-if="activeConversation" class="panel-head">
          <h2>{{ activeConversation.title }}</h2>
          <span class="panel-type">{{ typeLabels[activeConversation.type] }}</span>
        </div>
        <ConversationChat v-if="activeId !== null" :key="activeId" :conversation-id="activeId" />
        <div v-else class="panel-empty card">选择左侧会话开始沟通</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.conversation-center {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

/* 左右布局：窄屏堆叠 */
.center-body {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--spacing-md);
  align-items: start;
}

.conv-list {
  display: flex;
  flex-direction: column;
  min-height: 480px;
  max-height: 640px;
  overflow-y: auto;
}

.conv-filter {
  padding: var(--spacing-sm) var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
  position: sticky;
  top: 0;
  background: #fff;
  z-index: 1;
}

.conv-empty {
  margin: auto;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.conv-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  grid-template-areas:
    'type title unread'
    'type preview last';
  column-gap: var(--spacing-sm);
  row-gap: 2px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: none;
  border-bottom: 1px solid var(--color-bg-hover);
  background: none;
  text-align: left;
  cursor: pointer;
}

.conv-item:hover {
  background: var(--color-bg-hover);
}

.conv-item.active {
  background: var(--color-primary-bg);
}

.conv-type {
  grid-area: type;
  align-self: start;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  font-size: 11px;
}

.conv-type[data-type='VIEWING_GROUP'] {
  background: rgba(59, 109, 255, 0.1);
  color: var(--color-primary);
}

.conv-type[data-type='DIRECT'] {
  background: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.conv-title {
  grid-area: title;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-preview {
  grid-area: preview;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-unread {
  grid-area: unread;
  align-self: start;
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: var(--color-danger);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}

.conv-last {
  grid-area: last;
  font-size: 11px;
  color: var(--color-text-disabled);
  justify-self: end;
}

.conv-panel {
  min-width: 0;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.panel-head h2 {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.panel-type {
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: 11px;
}

.panel-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

@media (max-width: 991px) {
  .center-body {
    grid-template-columns: 1fr;
  }
}
</style>
