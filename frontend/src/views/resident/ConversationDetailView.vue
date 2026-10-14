<script setup lang="ts">
import HousingReservationView from '@/views/resident/HousingReservationView.vue'
import { leaseDisplayLabel } from '@/utils/leaseDisplay'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConversationChat from '@/components/business/ConversationChat.vue'
import AgreementSignPanel from '@/components/business/AgreementSignPanel.vue'
import { getConversation, listConversations } from '@/api/conversation'
import { getLease } from '@/api/lease'
import { conversationStatusLabels, conversationTypeLabels } from '@/types/modules/conversation'
import type { IConversation } from '@/types/modules/conversation'
import type { ILeaseRecord } from '@/types/modules/lease'

/**
 * 沟通会话详情（R63 v1.5 / R68 批次 f）：看房群聊 / 租约群聊 / 居民-管理员直通的独立会话页。
 * 消息实时性（WS 推送 + 轮询兜底 + 403 只读降级）全部由 ConversationChat 组件内处理。
 *
 * 本页承担两件与「会话本身」相关的业务：
 * 1. 会话状态：status='CLOSED'（租约归档或申请取消后关闭）时把 closed 传给聊天组件，
 *    输入区换成「会话已关闭」提示并停收停发，历史消息只读；
 * 2. 租约群聊（type='LEASE_GROUP'，relatedId=租约 ID）业务入口条：
 *    预约看房 / 查看协议并确认，三者都受同一份租约详情驱动。
 *
 * 会话来源取 `GET /conversations/{id}`；该端点未就绪时回落到会话列表
 * 查找（列表上限 50 条）。两者都失败时**状态未知**：按已关闭只读降级
 * （closed=true 停收停发，顶部明示"会话状态未知，已临时锁定为只读"，
 * OCR DEF-122），确认关闭与状态未知都以只读保障为先。
 */

const route = useRoute()
const router = useRouter()

const conversationId = Number(route.params.id)
/** 非法直达（旧书签/坏链）→ 回消息中心沟通会话 tab */
const invalidEntry = !Number.isInteger(conversationId) || conversationId <= 0

const conversation = ref<IConversation | null>(null)
/** 租约群聊对应的租约（业务入口条与协议面板的数据源） */
const lease = ref<ILeaseRecord | null>(null)

const title = computed(() => conversation.value?.title ?? '沟通会话')
const isClosed = computed(() => conversation.value?.status === 'CLOSED')
/**
 * 会话状态未知（详情与列表兜底都取不到，OCR DEF-122）时降级按已关闭只读处理：
 * 无法确认状态就不得放开发送（关闭态后端 5604 会拒，但前端先拦可少一次必败请求），
 * 且停收停发/不轮询/不订阅的只读保障都以 closed=true 生效。
 */
const statusUnknown = computed(() => conversation.value === null)
const chatClosed = computed(() => isClosed.value || statusUnknown.value)
const isLeaseGroup = computed(() => conversation.value?.type === 'LEASE_GROUP')

const typeLabel = computed(() =>
  conversation.value ? conversationTypeLabels[conversation.value.type] : '沟通会话'
)

/**
 * 聊天面板关闭态文案（DEF-122）：组件默认文案已改中性，租约专属口径由本调用方补传；
 * 仅**确认关闭**的租约群聊才传「租约流程已结束」——状态未知时不得渲染成"流程已结束"
 * 的不实表述，走中性默认文案即可（顶部已有状态未知说明）。
 */
const chatClosedHint = computed(() =>
  isClosed.value && isLeaseGroup.value
    ? '租约流程已结束，历史消息只读留档，不可再发送'
    : undefined
)
const statusLabel = computed(() =>
  conversation.value?.status
    ? conversationStatusLabels[conversation.value.status]
    : statusUnknown.value
      ? '状态未知'
      : '进行中'
)

async function loadConversation(): Promise<void> {
  try {
    conversation.value = await getConversation(conversationId)
    return
  } catch {
    /* 详情端点未就绪/失败：回落到列表查找，仍失败则保持通用标题 */
  }
  try {
    const result = await listConversations({ page: 1, size: 50 })
    conversation.value = result.records.find((item) => item.id === conversationId) ?? null
  } catch {
    conversation.value = null
  }
}

/* ------------------------------ 租约群聊业务入口（R69/R67/R64） ------------------------------ */

/**
 * 预约看房：在原租约群内复用三步预约组件，通过私有上下文读取本人来源挂牌，
 * 不再跳公开列表；本人的申请占用不放宽对其他居民和游客的公开门禁。
 */
const viewingVisible=ref(false)
function goReserveViewing(): void { viewingVisible.value=true }

/** 查看协议：会话内直接打开签约面板（居民侧可查看正文并在线确认） */
const agreementVisible = ref(false)
const requestedAgreementId=ref<number|undefined>()
function openAgreement(id?:number){requestedAgreementId.value=id;agreementVisible.value=true}

/** 协议状态变化（确认/发起/撤回）后回读租约，刷新入口条的协议徽标 */
async function refreshLease(): Promise<void> {
  const current = lease.value
  if (!current) return
  try {
    lease.value = await getLease(current.id)
  } catch {
    /* 回读失败不影响协议面板自身已刷新的数据 */
  }
}

async function loadLeaseContext(data: IConversation): Promise<void> {
  /* 仅租约群聊需要租约上下文；relatedId 即租约 ID（R68 口径） */
  if (data.type !== 'LEASE_GROUP' || data.relatedId == null) return
  try {
    lease.value = await getLease(data.relatedId)
  } catch {
    /* 租约取不到（越权/已删）时入口条降级为不可用，会话本身照常可读 */
    lease.value = null
  }
}

onMounted(async () => {
  if (invalidEntry) {
    router.replace('/resident/notifications?tab=conversations')
    return
  }
  await loadConversation()
  if (conversation.value) await loadLeaseContext(conversation.value)
})
</script>

<template>
  <section class="conversation-detail">
    <nav class="breadcrumb">
      <router-link to="/resident/notifications?tab=conversations">消息中心</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">{{ title }}</span>
    </nav>

    <!-- 会话元信息条：类型 + 状态（已关闭时明示历史只读） -->
    <header class="conv-head">
      <div class="conv-head-main">
        <h1 class="conv-title">{{ title }}</h1>
        <div class="conv-tags">
          <span class="conv-tag" :data-type="conversation?.type">{{ typeLabel }}</span>
          <span class="conv-status-tag" :class="{ 'is-closed': chatClosed }">{{ statusLabel }}</span>
        </div>
      </div>
      <p v-if="isClosed" class="conv-closed-note">会话已关闭，历史消息只读留档</p>
      <!-- 状态未知时降级只读（DEF-122）：顶部明示锁定原因，避免误以为会话损坏 -->
      <p v-else-if="statusUnknown" class="conv-closed-note">会话状态未知，已临时锁定为只读</p>
    </header>

    <!-- 租约群聊业务入口条（R69/R67/R64）：三项均以租约详情为前置，取不到租约时禁用 -->
    <div v-if="isLeaseGroup" class="biz-bar">
      <div class="biz-info">
        <template v-if="lease">
          <span class="biz-house">{{ lease.houseLocation }}</span>
          <span class="biz-meta">
            租期 {{ lease.startDate }} ~ {{ lease.endDate }} ·
            租约状态 {{ leaseDisplayLabel(lease) }} ·
            {{ lease.agreementStatus === 'SIGNED' ? '协议已确认' : '协议待确认' }}
          </span>
        </template>
        <span v-else class="biz-meta">租约信息暂不可用（可能已归档或无权查看）</span>
      </div>
      <p class="biz-meta">使用聊天输入栏的“＋”打开预约与协议功能。</p>
    </div>

    <!-- 群聊内确认承租后的引导：管理方发起协议前无须居民额外操作 -->
    <p v-if="isLeaseGroup && lease?.status === 'TO_SIGN'" class="biz-hint">
      管理方发出协议后，请在「查看协议」中阅读正文并确认同意；协议确认后继续保留房屋，首期费用支付成功后正式生效，无需另行确认承租。
    </p>

    <!-- 租约群聊的关闭态文案传租约口径（DEF-122：组件默认文案已改中性，业务语义由调用方补） -->
    <ConversationChat :conversation-id="conversationId" :closed="chatClosed" :closed-hint="chatClosedHint" @open-agreement="openAgreement">
      <template v-if="isLeaseGroup && lease" #business-actions>
        <el-button :disabled="chatClosed" @click="goReserveViewing">预约本租约房源看房</el-button>
        <el-button @click="openAgreement()">查看 / 同意协议</el-button>
      </template>
    </ConversationChat>

    <el-dialog v-model="viewingVisible" title="在本租约群聊预约看房" width="min(960px, 94vw)" destroy-on-close>
      <HousingReservationView v-if="viewingVisible" :conversation-id="conversationId" @created="viewingVisible=false" />
    </el-dialog>
    <!-- 协议查看/确认（复用协议签约面板，弹窗内嵌故去掉白卡容器） -->
    <el-dialog
      v-model="agreementVisible"
      :title="`租赁协议 · ${lease?.houseLocation ?? ''}`"
      width="min(680px, 94vw)"
      destroy-on-close
    >
      <AgreementSignPanel
        v-if="lease"
        :lease-id="lease.id"
              :lease-status="lease.status"
        role="RESIDENT"
        :community-id="lease.communityId"
        :self-managed="false"
        :read-only="chatClosed"
        :requested-agreement-id="requestedAgreementId"
        @changed="refreshLease"
      />
      <el-empty v-else description="租约信息暂不可用" :image-size="70" />
    </el-dialog>
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

.conv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.conv-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.conv-tags {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-xs);
}

.conv-tag,
.conv-status-tag {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
}

.conv-tag[data-type='LEASE_GROUP'] {
  background: #e6f6f8;
  color: #0891b2;
}

.conv-tag[data-type='VIEWING_GROUP'] {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.conv-tag[data-type='DIRECT'] {
  background: #f1ebfd;
  color: #8b5cf6;
}

.conv-status-tag {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.conv-status-tag.is-closed {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.conv-closed-note {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* 业务入口条：左信息右动作，窄屏换行 */
.biz-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  padding: var(--spacing-sm) var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-left: 3px solid #0891b2;
  border-radius: var(--radius-lg);
}

.biz-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.biz-house {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.biz-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.biz-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

/* Element Plus 相邻按钮默认外边距会破坏 flex gap */
.biz-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.biz-hint {
  margin: 0;
  padding: var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
