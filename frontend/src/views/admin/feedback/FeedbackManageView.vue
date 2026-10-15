<script setup lang="ts">
import FeedbackChat from '@/components/business/FeedbackChat.vue'
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  closeFeedback,
  deleteFeedbackAttachment,
  getFeedbackDetail,
  listFeedbackAttachments,
  listFeedbacks,
} from '@/api/feedback'
import type {
  FeedbackCategory,
  FeedbackStatus,
  IFeedback,
  IFeedbackAttachment,
} from '@/types/modules/feedback'
import {
  feedbackCategoryLabels,
  feedbackStatusLabels
} from '@/types/modules/feedback'
import { formatDate, formatDateTime } from '@/utils/date'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'

/**
 * 反馈管理（对照设计稿 design-mockups/admin/07-反馈管理.png，主从聊天双栏）：
 * 左栏反馈卡列表（状态/类别真实服务端参数 + keyword 前端过滤），
 * 右栏会话态（信息条 + 原始反馈气泡 + 会话气泡流 + 回复/受理/办结）+ 空态。
 * 原 FeedbackListView/FeedbackDetailView 收编于此（任务 8），详情子路由已删。
 * R30 实时化原样迁移——WS 订阅 /topic/feedback/{id}（载荷 FEEDBACK_MESSAGE
 * type/data 包裹，按 id 去重），仅订阅当前选中会话、切换即换订（防旧会话串扰）；
 * 轮询兜底自适应降频：WS 在线 30s 对账防静默丢包，WS 不可用回退 5s；
 * 发消息仍走 HTTP POST（校验与错误提示链路不变），卸载时退订并清理定时器
 */

/* ===================== 图标（24×24 stroke path，同公告管理先例） ===================== */

const ICONS = {
  search: ['M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14z', 'M21 21l-4.35-4.35'],
  chevron: ['M9 6l6 6-6 6']
}

/* ===================== 左栏：反馈列表 ===================== */

const feedbacks = ref<IFeedback[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref<FeedbackStatus | ''>('')
const categoryFilter = ref<FeedbackCategory | ''>('')

/* 副题计数（对照稿「待受理 N · 会话中 M」）：由状态过滤查询的 total 派生，真实数据 */
const pendingTotal = ref(0)
const sessionTotal = ref(0)
const subtitle = computed(() => `待受理 ${pendingTotal.value} · 会话中 ${sessionTotal.value}`)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listFeedbacks({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
      category: categoryFilter.value || undefined
    })
    feedbacks.value = result.records
    total.value = result.total
  } catch {
    feedbacks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 副题计数：两条 size=1 的状态过滤查询取 total（受理/办结后同步刷新） */
async function loadCounts(): Promise<void> {
  try {
    const [pending, session] = await Promise.all([
      listFeedbacks({ page: 1, size: 1, status: 'PENDING' }),
      listFeedbacks({ page: 1, size: 1, status: 'IN_SESSION' })
    ])
    pendingTotal.value = pending.total
    sessionTotal.value = session.total
  } catch {
    /* 计数失败静默，副题保持上次值 */
  }
}

/**
 * keyword 搜索：后端 GET /feedbacks 仅支持 page/size/status/category
 * （FeedbackController.page 已核，无 keyword 参数），故对当前加载记录做
 * 前端过滤（标题/内容/提交人）；后端补 keyword 后可切服务端参数
 */
const clientFiltered = computed<IFeedback[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return feedbacks.value
  return feedbacks.value.filter(
    (fb) =>
      fb.title.toLowerCase().includes(kw) ||
      fb.content.toLowerCase().includes(kw) ||
      fb.residentName.toLowerCase().includes(kw)
  )
})

function handleSearch(): void {
  page.value = 1
  load()
}

/** 反馈状态 → StatusTag 语义色（后端实际枚举 PENDING/IN_SESSION/CLOSED） */
function statusTagType(
  status: IFeedback['status']
): 'pending' | 'processing' | 'completed' {
  if (status === 'PENDING') return 'pending'
  if (status === 'IN_SESSION') return 'processing'
  return 'completed'
}

/** 左栏状态色点与状态胶囊同源（状态语义色 token） */
function statusDotClass(status: IFeedback['status']): string {
  return `is-${statusTagType(status)}`
}

/** 匿名防御读取（后端 VO 不返回 isAnonymous，恒显真实姓名） */
function displayName(fb: IFeedback): string {
  return fb.isAnonymous ? '匿名' : fb.residentName
}

/** 列表卡短日期（对照稿「王女士 · 09-08」） */
function shortDate(iso: string): string {
  const full = formatDate(iso)
  return full === '-' ? '-' : full.slice(5)
}

/* ===================== 右栏：会话态（组件内 state，不新增路由） ===================== */

const selectedId = ref<number | null>(null)
const feedback = ref<IFeedback | null>(null)
const attachments = ref<IFeedbackAttachment[]>([])
const conversationLoading = ref(false)
const closing = ref(false)

/** 轮询兜底间隔（ms）：WS 在线降频对账 / WS 不可用维持既有 5s */
/** 会话加载序号：快速切换会话时丢弃过期响应 */
let conversationSeq = 0



/** 仅订阅当前选中会话主题；切换/清空选中时换订（dest 参与者鉴权由后端保证） */

/** 选中左栏项 → 右栏加载该会话（清空上一会话数据防串扰） */
async function select(target: IFeedback): Promise<void> {
  if (selectedId.value === target.id) return
  const seq = ++conversationSeq
  feedback.value = null
  attachments.value = []
  selectedId.value = target.id
  conversationLoading.value = true
  try {
    const [detail, atts] = await Promise.all([
      getFeedbackDetail(target.id),
      listFeedbackAttachments(target.id)
    ])
    if (seq !== conversationSeq) return
    feedback.value = detail
    attachments.value = atts
    await nextTick()
  } catch (error) {
    if (seq !== conversationSeq) return
    ElMessage.error(error instanceof Error ? error.message : '反馈加载失败')
    selectedId.value = null
    feedback.value = null
    } finally {
    if (seq === conversationSeq) conversationLoading.value = false
  }
}


/** 详情刷新（轮询/WS 状态事件用）：静默失败不打断当前会话 */
async function refreshDetail(): Promise<void> {
  const id = selectedId.value
  if (id === null) return
  try {
    const detail = await getFeedbackDetail(id)
    if (selectedId.value !== id) return
    feedback.value = detail
  } catch {
    /* 静默：轮询兜底下一轮重试 */
  }
}

async function loadAttachments(): Promise<void> {
  const id = selectedId.value
  if (id === null) return
  try {
    const list = await listFeedbackAttachments(id)
    if (selectedId.value !== id) return
    attachments.value = list
  } catch {
    attachments.value = []
  }
}



/**
 * WS 推送归一（原样迁移）：§9.6.3.3 为 {type, data} 包裹；后端若直推裸
 * FeedbackMessageVO（无 type 字段）亦兼容，最终以联调实测为准
 */

/** WS 推送处理（原样迁移）：消息按 id 去重实时追加；状态事件刷新详情收口 */

/** 轮询兜底（原样迁移）：每轮按 WS 连接状态自适应选间隔（setTimeout 链，间隔可变） */

/** 办结（沿用现状：需填写办结原因并二次确认）；后端仅 IN_SESSION→CLOSED */
async function handleClose(): Promise<void> {
  const id = selectedId.value
  if (id === null) return
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '办结后居民将无法继续回复，请填写办结原因。',
      '办结反馈',
      {
        type: 'warning',
        confirmButtonText: '确认办结',
        cancelButtonText: '取消',
        inputValidator: (value: string) =>
          value.trim().length > 0 ? true : '办结原因不能为空'
      }
    )
    reason = result.value.trim()
  } catch {
    return
  }
  closing.value = true
  try {
    await closeFeedback(id, { remark: reason })
    ElMessage.success('反馈已办结')
    await Promise.all([refreshDetail(), load(), loadCounts()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '办结失败')
  } finally {
    closing.value = false
  }
}

/** 删除附件（沿用现状二次确认）；已办结禁删（后端同规则），入口按状态隐藏 */
async function handleDeleteAttachment(id: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确认删除该附件？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteFeedbackAttachment(id)
    ElMessage.success('附件已删除')
    await loadAttachments()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* ===================== 初始化：首屏自动选中第一条（对照稿选中态） ===================== */

onMounted(async () => {
  await load()
  void loadCounts()
  if (feedbacks.value.length > 0) await select(feedbacks.value[0])
})

</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="反馈管理" :subtitle="subtitle" />

    <div class="feedback-master">
      <!-- 左栏：反馈卡列表 -->
      <aside class="list-panel">
        <div class="list-search">
          <el-input
            v-model="keyword"
            placeholder="搜索反馈标题/内容/提交人"
            clearable
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix>
              <svg class="search-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path v-for="(d, i) in ICONS.search" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </template>
          </el-input>
        </div>

        <div class="list-filters">
          <el-select v-model="statusFilter" placeholder="全部状态" @change="handleSearch">
            <el-option label="全部状态" value="" />
            <el-option
              v-for="(label, value) in feedbackStatusLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-select v-model="categoryFilter" placeholder="全部类别" @change="handleSearch">
            <el-option label="全部类别" value="" />
            <el-option
              v-for="(label, value) in feedbackCategoryLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </div>

        <div v-loading="loading" class="feedback-cards">
          <el-empty
            v-if="!loading && clientFiltered.length === 0"
            :description="keyword ? '未找到匹配的反馈' : '暂无反馈'"
            :image-size="72"
          />
          <button
            v-for="fb in clientFiltered"
            :key="fb.id"
            type="button"
            class="feedback-card"
            :class="{ selected: selectedId === fb.id }"
            :aria-pressed="selectedId === fb.id"
            @click="select(fb)"
          >
            <span class="status-dot" :class="statusDotClass(fb.status)" aria-hidden="true" />
            <span class="card-main">
              <span class="card-title">{{ fb.title }}</span>
              <span class="card-meta">{{ displayName(fb) }} · {{ shortDate(fb.createdAt) }}</span>
            </span>
            <span class="card-side">
              <StatusTag
                :label="feedbackStatusLabels[fb.status]"
                :type="statusTagType(fb.status)"
              />
              <svg class="card-chevron" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path v-for="(d, i) in ICONS.chevron" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
          </button>
        </div>

        <Pagination
          v-if="total > 0"
          v-model:page="page"
          v-model:size="size"
          :total="total"
          layout="prev, pager, next"
          @update:page="load"
          @update:size="load"
        />
      </aside>

      <!-- 右栏：会话态 / 空态 -->
      <section class="detail-panel">
        <template v-if="selectedId !== null">
          <div v-loading="conversationLoading" class="conversation">
            <template v-if="feedback">
              <!-- 顶部信息条：标题 + 状态 + 元信息 -->
              <header class="conv-head">
                <div class="conv-heading">
                  <h2 class="conv-title">{{ feedback.title }}</h2>
                  <StatusTag
                    :label="feedbackStatusLabels[feedback.status]"
                    :type="statusTagType(feedback.status)"
                  />
                </div>
                <dl class="meta-row">
                  <div class="meta-item">
                    <dt class="meta-label">类别</dt>
                    <dd class="meta-value">{{ feedbackCategoryLabels[feedback.category] }}</dd>
                  </div>
                  <div class="meta-item">
                    <dt class="meta-label">提交人</dt>
                    <dd class="meta-value">{{ displayName(feedback) }}</dd>
                  </div>
                  <div class="meta-item">
                    <dt class="meta-label">提交时间</dt>
                    <dd class="meta-value">{{ formatDateTime(feedback.createdAt) }}</dd>
                  </div>
                  <div class="meta-item">
                    <dt class="meta-label">受理人</dt>
                    <dd class="meta-value">{{ feedback.handlerName ?? '未受理' }}</dd>
                  </div>
                </dl>
              </header>

              <!-- 消息气泡流：居民左（白底）、管理员右（品牌蓝底白字）；
                   首条气泡为原始反馈，历史附件独立呈现；新图片属于各自消息 -->
              <FeedbackChat :key="feedback.id" :feedback-id="feedback.id" :closed="feedback.status==='CLOSED'" :initial-content="feedback.content" :initial-name="feedback.residentName" :initial-date="feedback.createdAt" @changed="refreshDetail();load();loadCounts()" />
              <section v-if="attachments.length" class="legacy-attachments"><h3>提交附件／历史附件</h3><p>旧附件不归属于某条消息；新图片请从输入区发送。</p><a v-for="item in attachments" :key="item.id" :href="item.fileUrl" target="_blank" rel="noopener"><el-image v-if="item.fileType==='IMAGE'" :src="item.fileUrl" :preview-src-list="[item.fileUrl]" preview-teleported style="width:140px;height:100px"/>{{item.fileName}}</a><el-button v-if="feedback.status!=='CLOSED'" v-for="item in attachments" :key="`delete-${item.id}`" text type="danger" @click="handleDeleteAttachment(item.id)">删除历史附件：{{item.fileName}}</el-button></section>

              <!-- 底部操作条：回复输入框 + 发送 + 办结（办结仅会话中，后端仅 IN_SESSION→CLOSED）；
                   已办结态只读（输入区隐藏，沿用现状规则） -->
              <div class="composer-actions"><el-button v-if="feedback.status==='IN_SESSION'" type="primary" :loading="closing" @click="handleClose">办结反馈</el-button><p v-else-if="feedback.status==='PENDING'">首次回复即受理，可发送文字或图片。</p></div>
            </template>
          </div>
        </template>

        <!-- 空态：未选中 -->
        <template v-else>
          <div class="empty-state">
            <el-empty description="在左侧选择反馈开启会话" />
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 主从双栏（左 380px : 右 1fr，两栏各自白卡） ---------- */

.feedback-master {
  display: grid;
  grid-template-columns: 380px minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: stretch;
}

.list-panel,
.detail-panel {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.detail-panel {
  display: flex;
  flex-direction: column;
}

/* ---------- 左栏 ---------- */

.list-search {
  margin-bottom: var(--spacing-md);
}

.search-icon {
  width: 14px;
  height: 14px;
  color: var(--color-text-secondary);
}

.list-filters {
  display: flex;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.list-filters .el-select {
  flex: 1;
  min-width: 0;
}

.feedback-cards {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-height: 120px;
  margin-bottom: var(--spacing-md);
}

.feedback-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  border: none;
  border-radius: var(--radius-md);
  background: none;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.feedback-card:hover {
  background-color: var(--color-bg-hover);
}

.feedback-card.selected {
  background-color: var(--color-primary-bg);
}

/* 状态色点：与状态胶囊同源的状态语义色 token */
.status-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--radius-circle);
  flex-shrink: 0;
}

.status-dot.is-pending {
  background-color: var(--status-pending);
}

.status-dot.is-processing {
  background-color: var(--status-processing);
}

.status-dot.is-completed {
  background-color: var(--status-completed);
}

.card-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.card-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.feedback-card.selected .card-title {
  color: var(--color-primary);
}

.card-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.card-side {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

.card-chevron {
  width: 14px;
  height: 14px;
  color: var(--color-text-disabled);
}

/* ---------- 右栏：会话态 ---------- */

.conversation {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 420px;
}

.conv-head {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.conv-heading {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  min-width: 0;
}

.conv-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
  word-break: break-word;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md) var(--spacing-xl);
  margin: 0;
  padding: var(--spacing-md) var(--spacing-lg);
  background-color: var(--color-bg-subtle);
  border-radius: var(--radius-md);
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 96px;
}

.meta-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.meta-value {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

/* 消息气泡流：高度随左栏拉伸，内部滚动 */

.chat-messages {
  flex: 1;
  min-height: 320px;
  overflow-y: auto;
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  background-color: var(--color-bg);
  border-radius: var(--radius-md);
}

.chat-empty {
  margin: auto;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.chat-row {
  display: flex;
}

.chat-row.is-self {
  justify-content: flex-end;
}

.chat-row.is-other {
  justify-content: flex-start;
}

.chat-bubble {
  max-width: 72%;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
}

.is-self .chat-bubble {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--admin-card-bg);
}

.is-other .chat-bubble {
  background-color: var(--admin-card-bg);
}

.bubble-sender {
  font-size: var(--font-size-xs);
  margin-bottom: var(--spacing-xs);
  color: var(--color-text-secondary);
}

.is-self .bubble-sender {
  color: rgba(255, 255, 255, 0.8);
}

.bubble-content {
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble-time {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: right;
}

.is-self .bubble-time {
  color: rgba(255, 255, 255, 0.7);
}

/* 气泡内附件：图片画廊预览 + 文档链接 */

.bubble-attachments {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
}

.bubble-attachment {
  position: relative;
  display: block;
}

.bubble-image {
  display: block;
  width: 128px;
  height: 96px;
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
}

.attachment-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  border: none;
  border-radius: var(--radius-sm);
  padding: 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--admin-card-bg);
  background-color: rgba(0, 0, 0, 0.55);
  cursor: pointer;
}

.bubble-file {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  background-color: var(--admin-card-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
}

.bubble-file-link {
  color: var(--color-primary);
  text-decoration: none;
  word-break: break-all;
}

.bubble-file-link:hover {
  text-decoration: underline;
}

/* 底部操作条：附件上传 + 回复输入 + 发送/办结 */

.composer {
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.composer-uploads {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-sm);
}

.upload-tip {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--spacing-sm);
}

.composer-hint {
  margin-right: auto;
  font-size: var(--font-size-xs);
  color: var(--color-warning);
}

.chat-closed-tip {
  width: 100%;
  text-align: center;
  padding: var(--spacing-md) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

/* 空态（未选中） */

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

/* ---------- 响应式 ---------- */

@media (max-width: 1199px) {
  .feedback-master {
    grid-template-columns: minmax(0, 1fr);
  }

  .conversation {
    min-height: 360px;
  }
}
</style>
