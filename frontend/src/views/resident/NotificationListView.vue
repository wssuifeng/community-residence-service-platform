<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NotificationList from '@/components/business/NotificationList.vue'
import { listConversations, openDirectConversation } from '@/api/conversation'
import { getLeaseList } from '@/api/lease'
import type { IConversation } from '@/types/modules/conversation'
import { formatRelative } from '@/utils/date'

/**
 * 居民端消息中心（R63，v1.5 改造）：通知 tab（三端复用组件，原有能力不动）
 * + 沟通会话 tab（多方会话列表 + 联系社区管理员直通入口）。
 * 深链口径：消息中心路径带 ?tab=conversations 直达沟通会话 tab。
 */

const route = useRoute()
const router = useRouter()

type MessageTab = 'notifications' | 'conversations'

const tab = ref<MessageTab>(route.query.tab === 'conversations' ? 'conversations' : 'notifications')

/* ------------------------------ 沟通会话 tab ------------------------------ */

const conversations = ref<IConversation[]>([])
const conversationsLoading = ref(false)

const conversationTypeLabels: Record<IConversation['type'], string> = {
  VIEWING_GROUP: '群聊',
  DIRECT: '直通'
}

async function loadConversations(): Promise<void> {
  conversationsLoading.value = true
  try {
    const result = await listConversations({ page: 1, size: 50 })
    conversations.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '沟通会话加载失败')
  } finally {
    conversationsLoading.value = false
  }
}

function openConversation(id: number): void {
  router.push(`/resident/conversations/${id}`)
}

/* 直通入口：居民所属社区取自本人租约（接口按登录态数据级收敛），多租约去重后选择 */
const residentCommunities = ref<Array<{ id: number; name: string }>>([])
const communityDialogVisible = ref(false)
const selectedCommunityId = ref(0)
const opening = ref(false)

async function handleContactAdmin(): Promise<void> {
  try {
    const result = await getLeaseList({ page: 1, size: 100 })
    /* 居民可能同时隶属多个社区（多套在租房屋）：communityId 去重，保留首个社区名 */
    const merged = new Map<number, string>()
    for (const lease of result.records) {
      if (lease.communityId > 0 && !merged.has(lease.communityId)) {
        merged.set(lease.communityId, lease.communityName ?? `社区 #${lease.communityId}`)
      }
    }
    const communities = [...merged].map(([id, name]) => ({ id, name }))
    if (communities.length === 0) {
      ElMessage.warning('未查询到您的租约，无法确定所属社区，请联系社区管理员')
      return
    }
    residentCommunities.value = communities
    if (communities.length === 1) {
      await openDirect(communities[0].id)
      return
    }
    selectedCommunityId.value = communities[0].id
    communityDialogVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '所属社区查询失败，请稍后重试')
  }
}

/** 直通会话幂等：后端已有会话直接返回，重复点击不会建出多个会话 */
async function openDirect(communityId: number): Promise<void> {
  opening.value = true
  try {
    const conversation = await openDirectConversation({ communityId })
    communityDialogVisible.value = false
    router.push(`/resident/conversations/${conversation.id}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发起沟通会话失败，请稍后重试')
  } finally {
    opening.value = false
  }
}

function switchTab(next: MessageTab): void {
  tab.value = next
}

watch(tab, (next) => {
  if (next === 'conversations') void loadConversations()
})

onMounted(() => {
  if (tab.value === 'conversations') void loadConversations()
})
</script>

<template>
  <div class="message-center">
    <header class="page-head">
      <h1>消息中心</h1>
      <div class="mc-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="tab === 'notifications'"
          :class="{ active: tab === 'notifications' }"
          @click="switchTab('notifications')"
        >
          通知消息
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="tab === 'conversations'"
          :class="{ active: tab === 'conversations' }"
          @click="switchTab('conversations')"
        >
          沟通会话
        </button>
      </div>
    </header>

    <!-- 通知 tab：复用三端共享组件；其自带页标题由本页承担（deep 隐藏），
         全部/未读筛选与全部已读操作保持原样 -->
    <div v-show="tab === 'notifications'" class="notice-slot">
      <NotificationList role="RESIDENT" />
    </div>

    <!-- 沟通会话 tab：多方会话列表 + 联系社区管理员直通入口 -->
    <section v-show="tab === 'conversations'" class="conversation-panel">
      <div class="panel-head">
        <p class="panel-sub">与社区管理员、带看人的沟通会话，消息实时互通</p>
        <el-button type="primary" round :loading="opening" @click="handleContactAdmin">
          联系社区管理员
        </el-button>
      </div>

      <ul v-loading="conversationsLoading" class="conversation-items">
        <li
          v-for="item in conversations"
          :key="item.id"
          class="conversation-item"
          @click="openConversation(item.id)"
        >
          <span class="conv-type" :data-type="item.type">{{ conversationTypeLabels[item.type] }}</span>
          <div class="conv-main">
            <p class="conv-title">
              {{ item.title }}
              <span v-if="item.unreadCount > 0" class="conv-unread">
                {{ item.unreadCount > 99 ? '99+' : item.unreadCount }}
              </span>
            </p>
            <p class="conv-last">{{ item.lastMessage ?? '暂无消息，说点什么开始沟通' }}</p>
          </div>
          <time class="conv-time">{{ formatRelative(item.lastMessageAt) }}</time>
        </li>
        <li v-if="!conversationsLoading && conversations.length === 0" class="conv-empty">
          <img src="/images/empty-state.png" alt="" />
          <p>暂无沟通会话</p>
          <p class="conv-empty-sub">点击右上角「联系社区管理员」发起直通会话；看房预约将自动建群</p>
        </li>
      </ul>
    </section>

    <!-- 多社区选择：居民可能同时隶属多个社区，直通前需明确目标社区 -->
    <el-dialog v-model="communityDialogVisible" title="选择要联系的社区" width="420px">
      <el-radio-group v-model="selectedCommunityId">
        <el-radio v-for="community in residentCommunities" :key="community.id" :value="community.id">
          {{ community.name }}
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button round @click="communityDialogVisible = false">取消</el-button>
        <el-button type="primary" round :loading="opening" @click="openDirect(selectedCommunityId)">
          进入会话
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.message-center {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

/* 顶部 tab：通知消息 / 沟通会话 */
.mc-tabs {
  display: inline-flex;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.mc-tabs button {
  padding: var(--spacing-xs) var(--spacing-lg);
  border: none;
  background-color: #fff;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: var(--font-size-sm);
  transition: background-color 0.2s ease, color 0.2s ease;
}

.mc-tabs button.active {
  background-color: var(--color-primary);
  color: #fff;
}

/* 通知 tab 内共享组件自带页标题与本页重复，隐藏其标题保留其操作区 */
.notice-slot :deep(.notification-list .page-head h1) {
  display: none;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.panel-sub {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.conversation-items {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  min-height: 200px;
}

.conversation-items li {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid #eef0f3;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.conversation-items li:last-child {
  border-bottom: none;
}

.conversation-items li:hover {
  background-color: var(--color-bg-hover);
}

/* 会话类型徽标：看房群聊 / 居民-社区管理员直通 */
.conv-type {
  flex-shrink: 0;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
}

.conv-type[data-type='VIEWING_GROUP'] {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.conv-type[data-type='DIRECT'] {
  background: #f1ebfd;
  color: #8b5cf6;
}

.conv-main {
  flex: 1;
  min-width: 0;
}

.conv-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-unread {
  flex-shrink: 0;
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background-color: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  line-height: 18px;
  text-align: center;
}

.conv-last {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.conv-empty {
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-xxl) 0;
  cursor: default;
  text-align: center;
}

.conv-empty:hover {
  background: none;
}

.conv-empty img {
  width: 96px;
  height: 96px;
  object-fit: contain;
}

.conv-empty p {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.conv-empty-sub {
  font-size: var(--font-size-xs);
}
</style>
