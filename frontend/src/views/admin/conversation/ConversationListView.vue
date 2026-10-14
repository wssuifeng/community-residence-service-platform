<script setup lang="ts">
import AgreementSignPanel from '@/components/business/AgreementSignPanel.vue'
import { getLease } from '@/api/lease'
import type { ILeaseRecord } from '@/types/modules/lease'

import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ConversationChat from '@/components/business/ConversationChat.vue'
import { listConversations, openDirectConversation } from '@/api/conversation'
import { getCommunityList } from '@/api/community'
import { getResidentList } from '@/api/resident'
import type { IConversation, ConversationType } from '@/types/modules/conversation'
import { conversationStatusLabels, conversationTypeLabels } from '@/types/modules/conversation'
import type { IResident } from '@/types/modules/resident'
import type { ICommunity } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'

/**
 * 管理端消息会话中心（R63 v1.5 / R68 批次 f）：左会话列表（群聊/租约群聊/直通筛选、
 * 未读角标、已关闭标记）+ 右会话窗（ConversationChat 自处理 WS/轮询/403 只读与关闭态）。
 * 管理端从看房预约「进入会话」直达本页 ?id= 预选；管理员的看房群聊、租约群聊与
 * 居民直通会话统一在此收口。
 *
 * DEF-076（本批接入）：`POST /conversations/direct` 已放开 ADMIN/SUPER_ADMIN 并支持
 * `residentId` —— 管理端可**代居民发起**直通会话（幂等：同（社区，居民）返回同一条），
 * 故本页补「发起直通会话」入口（选社区 + 选居民）。
 */

const typeFilter = ref<'' | ConversationType>('')
const conversations = ref<IConversation[]>([])
const loading = ref(false)
const activeId = ref<number | null>(null)
const agreementVisible=ref(false), leaseContext=ref<ILeaseRecord|null>(null)
const requestedAgreementId=ref<number|undefined>()
async function openAgreement(id?:number){
  const conversation=activeConversation.value
  if(conversation?.type!=='LEASE_GROUP' || !conversation.relatedId) return
  try{
    leaseContext.value=await getLease(conversation.relatedId)
    if(activeConversation.value?.id!==conversation.id)return
    requestedAgreementId.value=id;agreementVisible.value=true
  }catch(e){ElMessage.error(e instanceof Error?e.message:'租约加载失败，未打开发送入口')}
}
watch(activeId,()=>{agreementVisible.value=false;leaseContext.value=null})

const activeConversation = computed(
  () => conversations.value.find((item) => item.id === activeId.value) ?? null
)

/** 已关闭会话（R68 生命周期终止）：列表标记「已关闭」并置灰，会话窗同步禁用输入 */
const activeClosed = computed(() => activeConversation.value?.status === 'CLOSED')

function isClosed(item: IConversation): boolean {
  return item.status === 'CLOSED'
}

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

const typeLabels = conversationTypeLabels

/* 单选组未用 v-model：必须把 emit 值显式写回 typeFilter，否则筛选永远停在旧值
   （DEF-122：原实现忽略 emit 参数，点筛选只重载不生效） */
function handleTypeChange(value: string | number | boolean | undefined): void {
  typeFilter.value =
    value === 'LEASE_GROUP' || value === 'VIEWING_GROUP' || value === 'DIRECT' ? value : ''
  activeId.value = null
  load()
}

/* ---------------- DEF-076 管理端「发起直通会话」 ---------------- */

const directVisible = ref(false)
const directSubmitting = ref(false)
const communities = ref<ICommunity[]>([])
const residents = ref<IResident[]>([])
const directForm = ref<{ communityId: number | null; residentId: number | null }>({
  communityId: null,
  residentId: null
})

/**
 * 居民选项本地扩展（DEF-122）：真实后端 ResidentVO / GET /residents 均无 communityId
 * （接口设计 9.2.1.8 契约只有 status/keyword），此处扩展字段供假后端/联调桩携带；
 * 有值时按已选社区本地过滤，无值时不过滤——居民-社区归属的硬校验在后端
 * openDirect（在住居住关系或生效租约），前端过滤只是缩小选择面的体验优化。
 */
interface ResidentOption extends IResident {
  communityId?: number
}

/** 当前社区下可选的居民（communityId 缺失的字段不过滤，见上） */
const residentOptions = computed<ResidentOption[]>(() => {
  const communityId = directForm.value.communityId
  if (communityId === null) return residents.value
  return residents.value.filter(
    (item) => (item as ResidentOption).communityId === undefined
      || (item as ResidentOption).communityId === communityId
  )
})

/* 已选居民被社区过滤排除时清空选择，避免带着隐藏值提交（DEF-122） */
watch(
  () => directForm.value.communityId,
  () => {
    const residentId = directForm.value.residentId
    if (residentId !== null && !residentOptions.value.some((item) => item.id === residentId)) {
      directForm.value.residentId = null
    }
  }
)

async function openDirectDialog(): Promise<void> {
  directVisible.value = true
  directForm.value = { communityId: null, residentId: null }
  try {
    const [communityPage, residentPage] = await Promise.all([
      getCommunityList({ page: 1, size: 100 }),
      /* 居民列表契约只支持 status/keyword（无 communityId 参数），数据级权限由后端按绑定社区收口 */
      getResidentList({ page: 1, size: 100 })
    ])
    communities.value = communityPage.records
    residents.value = residentPage.records
  } catch (error) {
    /* 选项加载失败不静默（DEF-122）：记录原因并轻提示；下拉为空时提交前会拦下 */
    console.error('[ConversationListView] 发起直通会话选项加载失败', error)
    ElMessage.warning('社区/居民选项加载失败，请稍后重试')
    communities.value = []
    residents.value = []
  }
}

/**
 * 代居民发起直通会话（幂等）：
 * 后端按（DIRECT，社区，居民）命中同一条会话——用「调用前列表里是否已有该会话 id」
 * 判断本次是"命中既有"还是"新建"，据此给出不同提示。
 */
async function submitDirect(): Promise<void> {
  const { communityId, residentId } = directForm.value
  if (communityId === null) {
    ElMessage.warning('请选择社区')
    return
  }
  if (residentId === null) {
    /* 与后端 400 文案对齐（缺 residentId → "请指定直通会话的居民"） */
    ElMessage.warning('请指定直通会话的居民')
    return
  }
  directSubmitting.value = true
  const before = new Set(conversations.value.map((item) => item.id))
  try {
    const conversation = await openDirectConversation({ communityId, residentId })
    directVisible.value = false
    /* 非 DIRECT 筛选下新建的直通会话会被列表过滤出去——右侧空白还提示「已建立」
       即误报（DEF-122）：先复位筛选为「全部」再加载选中 */
    if (typeFilter.value !== '' && typeFilter.value !== 'DIRECT') {
      typeFilter.value = ''
    }
    await load()
    activeId.value = conversation.id
    ElMessage.success(before.has(conversation.id) ? '已存在与该居民的会话，已为你打开' : '直通会话已建立')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发起会话失败')
  } finally {
    directSubmitting.value = false
  }
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
        <p class="page-head-sub">租约群聊、看房群聊与居民直通会话统一收口，点击会话即时沟通</p>
      </div>
      <!-- DEF-076：管理端代居民发起直通会话（幂等） -->
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openDirectDialog">
        发起直通会话
      </el-button>
    </header>

    <!-- 发起直通会话：社区 + 居民（后端要求管理端必须指定 residentId） -->
    <el-dialog v-model="directVisible" title="发起直通会话" width="460px">
      <el-form label-width="72px">
        <el-form-item label="社区">
          <el-select v-model="directForm.communityId" placeholder="选择社区" style="width: 100%">
            <el-option
              v-for="item in communities"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="居民">
          <!-- 选项按已选社区本地过滤（DEF-122）：真实后端居民列表无 communityId 参数/字段，
               归属硬校验在后端 openDirect，此处仅缩小选择面防误选他社区居民 -->
          <el-select
            v-model="directForm.residentId"
            filterable
            placeholder="选择居民（必选）"
            style="width: 100%"
          >
            <el-option
              v-for="item in residentOptions"
              :key="item.id"
              :label="`${item.realName}（${item.phone}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="direct-note">
        直通会话由居民与社区管理员参与；同一（社区，居民）只会有一条会话，重复发起将直接打开既有会话。
      </p>
      <template #footer>
        <el-button @click="directVisible = false">取消</el-button>
        <el-button type="primary" :loading="directSubmitting" @click="submitDirect">发起会话</el-button>
      </template>
    </el-dialog>

    <div class="center-body">
      <!-- 左：会话列表 -->
      <aside class="conv-list card" v-loading="loading">
        <div class="conv-filter">
          <el-radio-group :model-value="typeFilter" size="small" @update:model-value="handleTypeChange">
            <el-radio-button :value="''">全部</el-radio-button>
            <el-radio-button value="LEASE_GROUP">租约群聊</el-radio-button>
            <el-radio-button value="VIEWING_GROUP">看房群聊</el-radio-button>
            <el-radio-button value="DIRECT">直通</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="conversations.length === 0 && !loading" class="conv-empty">
          <p>暂无会话</p>
          <!-- ③ 会话来源可解释：会话由业务动作自动建立，管理端当前没有「主动开启会话」的端点 -->
          <p class="conv-empty-hint">
            会话由业务动作自动建立：入住申请审核通过后建立「租约群聊」，
            工单受理后建立「工单沟通」群，居民可从「消息中心」发起与管理员的直通会话；
            管理端可用右上角「发起直通会话」代居民开启（同一（社区，居民）只有一条会话）。
          </p>
        </div>

        <button
          v-for="item in conversations"
          :key="item.id"
          type="button"
          class="conv-item"
          :class="{ active: item.id === activeId, 'is-closed': isClosed(item) }"
          @click="selectConversation(item.id)"
        >
          <span class="conv-type" :data-type="item.type">{{ typeLabels[item.type] }}</span>
          <span class="conv-title">{{ item.title }}</span>
          <!-- 右上角位：已关闭优先于未读角标（关闭会话不再有新的未读，二者互斥呈现） -->
          <span v-if="isClosed(item)" class="conv-closed">已关闭</span>
          <span v-else-if="item.unreadCount > 0" class="conv-unread">{{ item.unreadCount > 99 ? '99+' : item.unreadCount }}</span>
          <span class="conv-last">{{ item.lastMessageAt ? formatDateTime(item.lastMessageAt) : '' }}</span>
          <span class="conv-preview">{{ item.lastMessage ?? '—' }}</span>
        </button>
      </aside>

      <!-- 右：会话窗 -->
      <div class="conv-panel">
        <div v-if="activeConversation" class="panel-head">
          <h2>{{ activeConversation.title }}</h2>
          <span class="panel-type">{{ typeLabels[activeConversation.type] }}</span>
          <span
            class="panel-status"
            :class="{ 'is-closed': activeClosed }"
          >
            {{ conversationStatusLabels[activeConversation.status ?? 'ACTIVE'] }}
          </span>
        </div>
        <ConversationChat
          v-if="activeId !== null"
          :key="activeId"
          :conversation-id="activeId"
          :closed="activeClosed"
          @open-agreement="openAgreement"
        >
          <template v-if="activeConversation?.type==='LEASE_GROUP'" #business-actions>
            <el-button :disabled="activeClosed" @click="openAgreement()">发出 / 查看租赁协议</el-button>
          </template>
        </ConversationChat>
        <div v-else class="panel-empty card">选择左侧会话开始沟通</div>
      </div>
    </div>
    <el-dialog v-model="agreementVisible" title="本租约群聊 · 租赁协议" width="min(680px, 94vw)" destroy-on-close>
      <AgreementSignPanel v-if="leaseContext" :lease-id="leaseContext.id" :community-id="leaseContext.communityId" :lease-status="leaseContext.status" role="ADMIN" :self-managed="false" :read-only="activeClosed" :requested-agreement-id="requestedAgreementId" @changed="openAgreement(requestedAgreementId)" />
    </el-dialog>
  </section>
</template>

<style scoped>
.conversation-center {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.direct-note {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
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

.conv-empty-hint {
  margin-top: var(--spacing-sm);
  max-width: 320px;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-normal);
  color: var(--color-text-secondary);
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

.conv-type[data-type='LEASE_GROUP'] {
  background: rgba(8, 145, 178, 0.12);
  color: #0891b2;
}

.conv-type[data-type='VIEWING_GROUP'] {
  background: rgba(59, 109, 255, 0.1);
  color: var(--color-primary);
}

.conv-type[data-type='DIRECT'] {
  background: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

/* R74 工单临时群聊（管理端受理后自动建群） */
.conv-type[data-type='WORK_ORDER_GROUP'] {
  background: rgba(245, 158, 11, 0.12);
  color: var(--status-pending);
}

/* 已关闭会话（R68）：整行降透明度，标题旁挂「已关闭」标记 */
.conv-item.is-closed {
  opacity: 0.68;
}

.conv-closed {
  grid-area: unread;
  align-self: start;
  justify-self: end;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: 11px;
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

/* 会话状态标（R68）：进行中绿、已关闭灰 */
.panel-status {
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  background: var(--color-success-soft);
  color: var(--color-success);
  font-size: 11px;
}

.panel-status.is-closed {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
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
