<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import {
  cancelLeaseAgreement,
  confirmLeaseAgreement,
  createLeaseAgreement,
  listAvailableAgreementTemplates,
  listLeaseAgreements
} from '@/api/agreement'
import { getLease } from '@/api/lease'
import type {
  IAgreementTemplate,
  ILeaseAgreement,
  LeaseAgreementStatus
} from '@/types/modules/agreement'
import { leaseAgreementStatusLabels } from '@/types/modules/agreement'
import { formatDateTime } from '@/utils/date'

/**
 * 租赁协议签约面板（R64，管理端与居民端共用）。
 *
 * 自洽性约定：组件不依赖父层任何状态——除 leaseId/role 两个必填 props 外，
 * 协议数据、模板清单、社区归属（用于筛可用模板）全部由组件内部拉取；
 * 发起/确认/撤回成功后仅 emit('changed') 通知父层刷新它自己的租约数据
 *（后端会把协议状态回写租约 agreementStatus）。
 *
 * 社区归属取数：优先用可选的 communityId prop（父层已知时传入，省一次请求）；
 * 缺省则由组件内调 getLease(leaseId) 补全（居民查本人租约、管理员查辖区租约均合法），
 * 取不到时降级为「不指定模板」发起——后端会取该社区默认模板。
 */

const props = defineProps<{
  /** 租约 ID（必填） */
  leaseId: number
  /** 当前身份：ADMIN-管理方（发起/确认/撤回），RESIDENT-居民方（确认） */
  role: 'ADMIN' | 'RESIDENT'
  /** 社区 ID（可选；缺省由组件内部查租约补全，仅影响可用模板清单） */
  communityId?: number
}>()

const emit = defineEmits<{
  /** 协议状态变化（发起/确认/撤回成功）——父层据此刷新租约详情 */
  changed: []
}>()

const agreements = ref<ILeaseAgreement[]>([])
const loading = ref(false)
const submitting = ref(false)
/* 社区归属兜底来源（props.communityId 缺省时才请求） */
const leaseCommunityId = ref<number | null>(null)

/** 当前协议 = 最新一条非已撤回协议；历史协议 = 已撤回流水 */
const current = computed(
  () => agreements.value.find((item) => item.status !== 'CANCELLED') ?? null
)
const cancelledAgreements = computed(() =>
  agreements.value.filter((item) => item.status === 'CANCELLED')
)

const effectiveCommunityId = computed<number | null>(
  () => props.communityId ?? leaseCommunityId.value
)

const statusType: Record<LeaseAgreementStatus, 'pending' | 'processing' | 'completed' | 'canceled'> = {
  PENDING: 'pending',
  PARTIAL: 'processing',
  SIGNED: 'completed',
  CANCELLED: 'canceled'
}

/* ---------- 取数 ---------- */

/* 序号令牌并发防护：props.leaseId 切换时旧请求结果不落地 */
let loadSeq = 0

async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  try {
    const list = await listLeaseAgreements(props.leaseId)
    if (seq !== loadSeq) return
    /* 后端已按倒序返回，这里再排一次以防流水顺序不稳 */
    agreements.value = [...list].sort((a, b) =>
      (b.createdAt ?? '').localeCompare(a.createdAt ?? '')
    )
  } catch (error) {
    if (seq === loadSeq) {
      agreements.value = []
      ElMessage.error(error instanceof Error ? error.message : '协议记录加载失败')
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

/* 社区归属兜底：拿不到只降级模板选择，不阻塞协议流程 */
async function resolveCommunity(): Promise<void> {
  if (props.communityId != null) return
  try {
    const detail = await getLease(props.leaseId)
    leaseCommunityId.value = detail.communityId
  } catch {
    leaseCommunityId.value = null
  }
}

/* ---------- 协议正文查看 ---------- */

const contentVisible = ref(false)
const contentTarget = ref<ILeaseAgreement | null>(null)

function openContent(item: ILeaseAgreement): void {
  contentTarget.value = item
  contentVisible.value = true
}

/** 附件文件名兜底：后端未回填名称时取 URL 末段 */
function attachmentName(url: string): string {
  return url.split('/').pop() ?? url
}

/* ---------- 管理方：发起协议 ---------- */

const createVisible = ref(false)
const templateOptions = ref<IAgreementTemplate[]>([])
const templateLoading = ref(false)
const createForm = reactive<{ templateId: number | null; title: string; remark: string }>({
  templateId: null,
  title: '',
  remark: ''
})

async function openCreate(): Promise<void> {
  Object.assign(createForm, { templateId: null, title: '', remark: '' })
  createVisible.value = true

  const communityId = effectiveCommunityId.value
  if (communityId == null) {
    templateOptions.value = []
    return
  }
  templateLoading.value = true
  try {
    templateOptions.value = await listAvailableAgreementTemplates(communityId)
    /* 默认模板预选，减少一次手工选择 */
    const preferred = templateOptions.value.find((item) => item.isDefault === 1)
    if (preferred) createForm.templateId = preferred.id
  } catch (error) {
    templateOptions.value = []
    ElMessage.error(error instanceof Error ? error.message : '可用协议模板加载失败')
  } finally {
    templateLoading.value = false
  }
}

async function handleCreateSubmit(): Promise<void> {
  submitting.value = true
  try {
    await createLeaseAgreement({
      leaseId: props.leaseId,
      templateId: createForm.templateId ?? undefined,
      title: createForm.title.trim() || undefined,
      remark: createForm.remark.trim() || undefined
    })
    ElMessage.success('协议已发起，等待双方确认')
    createVisible.value = false
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '协议发起失败')
  } finally {
    submitting.value = false
  }
}

/* ---------- 确认 ---------- */

/** 管理方确认：单次二次确认（确认后落管理方确认人与时间，不可自行撤销） */
async function handleAdminConfirm(item: ILeaseAgreement): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认以管理方身份确认协议「${item.title}」？确认后记录您的姓名与时间，如需变更须先撤回再重新发起。`,
      '管理方确认协议',
      { type: 'warning', confirmButtonText: '确认签署', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await doConfirm(item)
}

/** 居民方确认：必须二次确认，弹窗明示「确认后视为已同意协议内容」；可选填确认人姓名（留空由后端取账号姓名） */
async function handleResidentConfirm(item: ILeaseAgreement): Promise<void> {
  let confirmName: string
  try {
    const result = await ElMessageBox.prompt(
      `确认后视为您已同意协议「${item.title}」的全部内容，系统将记录确认人姓名与确认时间，确认后不可自行撤销。`,
      '确认协议内容',
      {
        type: 'warning',
        confirmButtonText: '我已阅读并同意',
        cancelButtonText: '取消',
        inputPlaceholder: '确认人姓名（选填，留空默认使用账号姓名）',
        inputValue: ''
      }
    )
    confirmName = result.value.trim()
  } catch {
    return
  }
  await doConfirm(item, confirmName)
}

async function doConfirm(item: ILeaseAgreement, confirmName?: string): Promise<void> {
  submitting.value = true
  try {
    await confirmLeaseAgreement(item.id, confirmName ? { confirmName } : {})
    ElMessage.success('协议已确认')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '确认失败')
  } finally {
    submitting.value = false
  }
}

/* ---------- 管理方：撤回 ---------- */

async function handleCancel(item: ILeaseAgreement): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      `撤回协议「${item.title}」后该协议作废并转入历史协议，双方确认信息保留留痕；仍需签署请重新发起。`,
      '撤回协议',
      {
        type: 'warning',
        confirmButtonText: '确认撤回',
        cancelButtonText: '取消',
        inputPlaceholder: '撤回原因（必填）',
        inputValidator: (value: string) => (value.trim() ? true : '请填写撤回原因')
      }
    )
    reason = result.value.trim()
  } catch {
    return
  }
  submitting.value = true
  try {
    await cancelLeaseAgreement(item.id, reason)
    ElMessage.success('协议已撤回')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤回失败')
  } finally {
    submitting.value = false
  }
}

/* 身份派生的可见性开关 */
const isAdmin = computed(() => props.role === 'ADMIN')
/** 本人是否已确认（已确认则不再显示本人确认按钮，改为展示确认信息） */
const adminConfirmed = computed(() => !!current.value?.adminConfirmTime)
const tenantConfirmed = computed(() => !!current.value?.tenantConfirmTime)

onMounted(async () => {
  await Promise.all([load(), resolveCommunity()])
})

watch(
  () => props.leaseId,
  async () => {
    leaseCommunityId.value = null
    await Promise.all([load(), resolveCommunity()])
  }
)

defineExpose({ reload: load })
</script>

<template>
  <article v-loading="loading" class="agreement-panel">
    <header class="panel-head">
      <h2 class="panel-title">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6 3.5h8.5L19 8v12.5H6z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
          <path d="m9 13.6 1.8 1.8 3.6-3.9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        协议签约
      </h2>
      <el-button text size="small" @click="load">刷新</el-button>
    </header>

    <!-- 当前协议（最新一条非已撤回） -->
    <template v-if="current">
      <div class="agreement-head">
        <p class="agreement-title">{{ current.title }}</p>
        <StatusTag :label="leaseAgreementStatusLabels[current.status]" :type="statusType[current.status]" />
      </div>
      <dl class="meta-rows">
        <div class="meta-row">
          <dt>来源模板</dt>
          <dd>{{ current.templateName || '手工生成（无模板）' }}</dd>
        </div>
        <div class="meta-row">
          <dt>发起时间</dt>
          <dd>{{ formatDateTime(current.createdAt) }}</dd>
        </div>
      </dl>

      <div class="confirm-block">
        <span class="block-label">双方确认情况</span>
        <div class="confirm-row" :class="{ 'is-done': tenantConfirmed }">
          <span class="confirm-side">居民方</span>
          <span v-if="tenantConfirmed" class="confirm-info">
            {{ current.tenantConfirmName || '居民' }} · {{ formatDateTime(current.tenantConfirmTime) }}
          </span>
          <span v-else class="confirm-pending">待确认</span>
        </div>
        <div class="confirm-row" :class="{ 'is-done': adminConfirmed }">
          <span class="confirm-side">管理方</span>
          <span v-if="adminConfirmed" class="confirm-info">
            {{ current.adminConfirmName || '管理方' }} · {{ formatDateTime(current.adminConfirmTime) }}
          </span>
          <span v-else class="confirm-pending">待确认</span>
        </div>
      </div>

      <div v-if="current.remark" class="remark-block">
        <span class="block-label">协议备注</span>
        <p class="remark-text">{{ current.remark }}</p>
      </div>

      <div class="panel-actions">
        <el-button size="small" type="primary" plain @click="openContent(current)">查看协议正文</el-button>
        <template v-if="isAdmin">
          <el-button
            v-if="!adminConfirmed"
            size="small"
            type="primary"
            :loading="submitting"
            @click="handleAdminConfirm(current)"
          >
            确认协议
          </el-button>
          <el-button size="small" type="danger" plain :loading="submitting" @click="handleCancel(current)">
            撤回协议
          </el-button>
        </template>
        <el-button
          v-else-if="!tenantConfirmed"
          size="small"
          type="primary"
          :loading="submitting"
          @click="handleResidentConfirm(current)"
        >
          确认协议
        </el-button>
      </div>

      <!-- 提示按协议状态而非「本人是否已确认」判定：双方都已确认时不能再提示等待对方 -->
      <p v-if="current.status === 'SIGNED'" class="action-hint">
        双方已确认，协议已生效并同步租约签约状态，可随时查看协议正文。
      </p>
      <p v-else-if="isAdmin && adminConfirmed" class="action-hint">
        您已确认本协议，待居民方确认后协议生效并同步租约签约状态。
      </p>
      <p v-else-if="!isAdmin && tenantConfirmed" class="action-hint">
        您已确认本协议，等待管理方确认。
      </p>
    </template>

    <!-- 空态：无任何未撤回协议 -->
    <template v-else>
      <div class="empty-block">
        <p v-if="isAdmin" class="empty-text">
          该租约尚未发起协议。协议为线下签署的可上传模板附件备案，也可直接用模板正文生成电子协议送居民确认。
        </p>
        <p v-else class="empty-text">管理方尚未发起协议。</p>
      </div>
      <div v-if="isAdmin" class="panel-actions">
        <el-button type="primary" size="small" @click="openCreate">发起协议</el-button>
      </div>
    </template>

    <!-- 历史协议（已撤回流水，正文快照保留） -->
    <el-collapse v-if="cancelledAgreements.length > 0" class="history-collapse">
      <el-collapse-item :title="`历史协议（${cancelledAgreements.length}）`" name="history">
        <ul class="history-list">
          <li v-for="item in cancelledAgreements" :key="item.id" class="history-item">
            <div class="history-main">
              <span class="history-title">{{ item.title }}</span>
              <StatusTag label="已撤回" type="canceled" />
            </div>
            <p class="history-meta">{{ item.templateName || '手工生成' }} · {{ formatDateTime(item.createdAt) }}</p>
            <p v-if="item.cancelReason" class="history-reason">撤回原因：{{ item.cancelReason }}</p>
            <el-button text type="primary" size="small" @click="openContent(item)">查看协议正文</el-button>
          </li>
        </ul>
      </el-collapse-item>
    </el-collapse>

    <!-- 协议正文快照：文档型排版，保留换行 -->
    <el-drawer v-model="contentVisible" title="协议正文" size="620px">
      <template v-if="contentTarget">
        <div class="content-head">
          <h3 class="content-title">{{ contentTarget.title }}</h3>
          <p class="content-meta">
            {{ contentTarget.templateName || '手工生成' }} ·
            {{ leaseAgreementStatusLabels[contentTarget.status] }} ·
            {{ formatDateTime(contentTarget.createdAt) }}
          </p>
          <div v-if="contentTarget.templateFileUrl" class="content-file">
            <span class="block-label">模板附件</span>
            <a class="file-link" :href="contentTarget.templateFileUrl" target="_blank" rel="noopener">
              {{ contentTarget.templateFileName || attachmentName(contentTarget.templateFileUrl) }}
            </a>
            <a
              class="file-download"
              :href="contentTarget.templateFileUrl"
              :download="contentTarget.templateFileName || attachmentName(contentTarget.templateFileUrl)"
            >
              下载
            </a>
          </div>
        </div>
        <pre v-if="contentTarget.content" class="content-body">{{ contentTarget.content }}</pre>
        <el-empty v-else description="本协议无正文文本（以模板附件为准）" :image-size="70" />
      </template>
    </el-drawer>

    <!-- 发起协议（管理方） -->
    <el-dialog v-model="createVisible" title="发起租赁协议" width="520px">
      <el-alert
        v-if="effectiveCommunityId == null"
        type="warning"
        :closable="false"
        show-icon
        class="form-alert"
        title="未能获取租约所属社区"
        description="无法列出可选模板，将按该社区默认模板生成协议正文；若该社区无默认模板，请先在「协议模板」中配置。"
      />
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="协议模板">
          <el-select
            v-model="createForm.templateId"
            :loading="templateLoading"
            :disabled="effectiveCommunityId == null"
            clearable
            filterable
            placeholder="留空使用社区默认模板"
            style="width: 100%"
          >
            <el-option
              v-for="item in templateOptions"
              :key="item.id"
              :label="`${item.name}${item.isDefault === 1 ? '（默认）' : ''}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="协议标题">
          <el-input
            v-model="createForm.title"
            placeholder="留空沿用模板名称"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="补充说明（选填）"
          />
        </el-form-item>
      </el-form>
      <p class="dialog-note">发起后正文以模板占位符渲染成快照存库，模板后续修改不影响本次协议。</p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateSubmit">确认发起</el-button>
      </template>
    </el-dialog>
  </article>
</template>

<style scoped>
/* 面板自带白卡容器：可在管理端详情页与居民端页面直接挂载，无需父层包裹 */
.agreement-panel {
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.panel-title svg {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.agreement-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  padding-bottom: var(--spacing-sm);
  border-bottom: 1px dashed var(--color-border);
}

.agreement-title {
  margin: 0;
  min-width: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.meta-rows {
  margin: var(--spacing-md) 0;
  padding: 0;
}

.meta-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) 0;
  font-size: var(--font-size-sm);
}

.meta-row dt {
  flex-shrink: 0;
  width: 68px;
  color: var(--color-text-secondary);
}

.meta-row dd {
  margin: 0;
  min-width: 0;
  color: var(--color-text-primary);
  word-break: break-all;
}

.confirm-block,
.remark-block {
  margin-bottom: var(--spacing-md);
}

.block-label {
  display: inline-block;
  margin-bottom: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.confirm-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  margin-bottom: var(--spacing-xs);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-sm);
}

.confirm-row.is-done {
  background-color: var(--color-success-soft);
}

.confirm-side {
  flex-shrink: 0;
  width: 48px;
  color: var(--color-text-secondary);
}

.confirm-info {
  color: var(--color-success);
  font-weight: var(--font-weight-medium);
}

.confirm-pending {
  color: var(--color-warning);
}

.remark-text {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
  white-space: pre-wrap;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-md);
}

/* Element Plus 相邻按钮默认外边距会破坏 flex gap，这里统一清零 */
.panel-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.action-hint {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.empty-block {
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.empty-text {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
}

.history-collapse {
  margin-top: var(--spacing-lg);
}

.history-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.history-item {
  padding: var(--spacing-sm) 0;
  border-bottom: 1px dashed var(--color-border);
}

.history-item:last-child {
  border-bottom: none;
}

.history-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.history-title {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.history-meta,
.history-reason {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 正文快照：等宽文档型排版，保留原始换行 */
.content-title {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
}

.content-meta {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.content-file {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  margin-bottom: var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-sm);
}

.file-link {
  color: var(--color-primary);
  word-break: break-all;
}

.file-download {
  flex-shrink: 0;
  color: var(--color-primary);
}

.content-body {
  margin: 0;
  padding: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-bg);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.dialog-note {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.form-alert {
  margin-bottom: var(--spacing-md);
}
</style>
