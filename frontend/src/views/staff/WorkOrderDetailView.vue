<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import ImageUploader from '@/components/common/ImageUploader.vue'
import {
  acceptWorkOrder,
  completeWorkOrder,
  getWorkOrder,
  getWorkOrderTimeline,
  listWorkOrderAttachments,
  processWorkOrder,
  uploadWorkOrderAttachment
} from '@/api/workorder'
import type {
  IWorkOrder,
  IWorkOrderAttachment,
  IWorkOrderProcess,
  WorkOrderPriority,
  WorkOrderStatus
} from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import { formatDateTime } from '@/utils/date'
import { residentDisplayName, residentInitial, residentRelationLabel } from '@/utils/staffPlaceholder'
import { useUserStore } from '@/store/user'

/** 工单详情（UI设计.md §4.2.2）：面包屑 + 状态条 + 工单信息 / 处理时间线 / 操作面板（对照 design-mockups/staff/03） */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const orderId = Number(route.params.id)

const statusSemantic: Record<WorkOrderStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  TO_ASSIGN: 'pending',
  TO_CONFIRM: 'pending',
  ASSIGNED: 'processing',
  ACCEPTED: 'processing',
  IN_PROGRESS: 'processing',
  COMPLETED: 'completed',
  CLOSED: 'completed',
  REJECTED: 'rejected',
  CANCELLED: 'canceled'
}

/* 后端 WorkOrderActionDTO.remark @Size(max=500)（完成接口以 remark 承载处理结果），
   故「处理结果说明 + 备注」合计不得超过 500 字，超限在前端拦截 */
const REMARK_MAX = 500
const NOTE_MAX = 200

const order = ref<IWorkOrder | null>(null)
const timeline = ref<IWorkOrderProcess[]>([])
const attachments = ref<IWorkOrderAttachment[]>([])
const loading = ref(false)
const actionLoading = ref(false)

/* 操作面板输入：solution 为处理结果说明（处理中状态必填），remark 为备注，images 为处理后照片 */
const resultForm = ref({
  solution: '',
  remark: '',
  images: [] as string[]
})
const submittingResult = ref(false)

const imageAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'IMAGE'))
const documentAttachments = computed(() => attachments.value.filter((item) => item.fileType === 'DOCUMENT'))
const sortedTimeline = computed(() => [...timeline.value].sort((a, b) => a.createdAt.localeCompare(b.createdAt)))

const canAccept = computed(() => order.value?.status === 'ASSIGNED')
const canProcess = computed(() => order.value?.status === 'ACCEPTED')
const canComplete = computed(() => order.value?.status === 'IN_PROGRESS')
/* 无任何可执行动作（待确认/已完成/已关闭等）时面板给出状态说明，而不是留空 */
const hasAction = computed(() => canAccept.value || canProcess.value || canComplete.value)

/* 紧急程度 → 状态条配色档，与工单列表卡片同一口径 */
function toneOf(priority: WorkOrderPriority): 'urgent' | 'normal' | 'low' {
  if (priority === 'URGENT') return 'urgent'
  return priority === 'LOW' ? 'low' : 'normal'
}

async function fetchDetail(): Promise<void> {
  loading.value = true
  try {
    const [detail, processes, files] = await Promise.all([
      getWorkOrder(orderId),
      getWorkOrderTimeline(orderId),
      listWorkOrderAttachments(orderId)
    ])
    order.value = detail
    timeline.value = processes
    attachments.value = files
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单详情加载失败')
  } finally {
    loading.value = false
  }
}

/* 接单：已派单状态下确认后流转至已接单（状态机 9.4.2.6；
   后端 WorkOrderActionDTO.remark @NotBlank，须携带处置说明；面板备注为空时回落到默认说明） */
async function handleAccept(): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认接下工单 ${order.value?.orderNo ?? ''} 吗？`, '接单', {
      confirmButtonText: '确认接单',
      cancelButtonText: '取消'
    })
    actionLoading.value = true
    const remark = resultForm.value.remark.trim()
    await acceptWorkOrder(orderId, { remark: remark || `服务人员 ${userStore.user?.realName ?? ''} 已接单` })
    ElMessage.success('接单成功，请尽快开始处理')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '接单失败')
  } finally {
    actionLoading.value = false
  }
}

/* 开始处理：已接单状态下流转至处理中（状态机 9.4.2.7，remark 同上必填） */
async function handleProcess(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认开始处理该工单吗？', '开始处理', {
      confirmButtonText: '开始处理',
      cancelButtonText: '取消'
    })
    actionLoading.value = true
    const remark = resultForm.value.remark.trim()
    await processWorkOrder(orderId, { remark: remark || '已开始处理' })
    ElMessage.success('已开始处理')
    fetchDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    actionLoading.value = false
  }
}

/* ImageUploader 产出通用上传 URL；工单附件须回捞转存到工单附件接口 */
async function attachImages(urls: string[]): Promise<number> {
  let failed = 0
  for (const url of urls) {
    try {
      const blob = await (await fetch(url)).blob()
      const fileName = url.split('/').pop() ?? 'result.png'
      await uploadWorkOrderAttachment(orderId, new File([blob], fileName, { type: blob.type || 'image/png' }))
    } catch {
      failed += 1
    }
  }
  return failed
}

/* 组装完成请求：后端 complete 读取的是 WorkOrderActionDTO.remark（@NotBlank + max 500），
   接口文档里的 solution 字段并未被后端读取——只传 solution 会被丢弃、
   只传 solution 且不带 remark 还会触发 @NotBlank 校验失败。
   故把处理结果说明写成 remark 提交，备注追加其后，两个字段同时携带保证不丢信息。 */
function buildCompletePayload(): { solution: string; remark: string } | null {
  const solution = resultForm.value.solution.trim()
  const note = resultForm.value.remark.trim()
  if (!solution) {
    ElMessage.warning('请填写处理结果说明')
    return null
  }
  const remark = note ? `${solution}（备注：${note}）` : solution
  if (remark.length > REMARK_MAX) {
    ElMessage.warning(`处理结果说明与备注合计不能超过 ${REMARK_MAX} 字`)
    return null
  }
  return { solution, remark }
}

/* 提交处理结果：填写解决方案后流转至待确认（状态机 9.4.2.8） */
async function handleSubmitResult(): Promise<void> {
  const payload = buildCompletePayload()
  if (!payload) return
  submittingResult.value = true
  try {
    await completeWorkOrder(orderId, payload)
    const failed = await attachImages(resultForm.value.images)
    if (failed > 0) ElMessage.warning(`${failed} 张结果图片关联失败`)
    ElMessage.success('处理结果已提交，等待居民确认')
    resultForm.value = { solution: '', remark: '', images: [] }
    fetchDetail()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交失败')
  } finally {
    submittingResult.value = false
  }
}

/* 【MOCK 临时占位】申请改派：后端 PATCH /work-orders/{id}/assign 仅 ADMIN/SUPER_ADMIN 可用
   （WorkOrderController.java:91），STAFF 侧无改派接口（待补接口 ⑤）。
   按用户指示不静默省略：按钮照设计稿呈现，点击提示待补接口，接口就绪后替换为真实提交。 */
function notifyReassignPending(): void {
  ElMessage.info('申请改派需要后端提供 STAFF 可用的改派接口（待补接口 ⑤），当前为占位入口')
}

function goList(): void {
  router.push('/staff/work-orders')
}

onMounted(fetchDetail)
</script>

<template>
  <section v-loading="loading" class="staff-order-detail">
    <nav class="crumb-bar">
      <button type="button" class="crumb-home" aria-label="返回工单列表" @click="goList">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 10.5 12 4l8 6.5V20h-5.5v-5h-5v5H4v-9.5Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
        </svg>
      </button>
      <router-link class="crumb-link" to="/staff/work-orders">工单列表</router-link>
      <span class="crumb-sep">/</span>
      <span class="crumb-current">{{ order?.orderNo ?? '工单详情' }}</span>
    </nav>

    <template v-if="order">
      <article class="status-strip" :class="`is-${toneOf(order.priority)}`">
        <span class="status-priority">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
            <path d="M12 7.5v6M12 16.4v.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          {{ workOrderPriorityLabels[order.priority] }}
        </span>
        <h1 class="status-title">{{ order.title }}</h1>
        <StatusTag class="status-flag" :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
      </article>

      <div class="detail-grid">
        <article class="panel info-panel">
          <h2 class="panel-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="8.5" r="3.5" stroke="currentColor" stroke-width="1.8" />
              <path d="M5 20c1.2-3.4 3.9-5 7-5s5.8 1.6 7 5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            工单信息
          </h2>

          <div class="person">
            <span class="person-avatar" aria-hidden="true">{{ residentInitial(order.residentName) }}</span>
            <div class="person-text">
              <span class="person-name">
                {{ residentDisplayName(order) }}
                <span class="person-role">{{ residentRelationLabel(order) }}</span>
              </span>
              <span class="person-caption">提交人</span>
            </div>
          </div>

          <dl class="info-rows">
            <div class="info-row">
              <dt>
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M12 21s6.5-5.7 6.5-10.5A6.5 6.5 0 0 0 5.5 10.5C5.5 15.3 12 21 12 21Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                  <circle cx="12" cy="10.4" r="2.3" stroke="currentColor" stroke-width="1.8" />
                </svg>
                地址
              </dt>
              <dd>{{ order.address || '未填写' }}</dd>
            </div>
            <div class="info-row">
              <dt>
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M4 6.5h16v11H4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                  <path d="M4 8.5h16" stroke="currentColor" stroke-width="1.8" />
                </svg>
                分类
              </dt>
              <dd>{{ order.categoryName }}</dd>
            </div>
            <div class="info-row">
              <dt>
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M6 3.8h3l1.6 4-2 1.3a11 11 0 0 0 5.3 5.3l1.3-2 4 1.6v3a2 2 0 0 1-2.2 2A15.5 15.5 0 0 1 4 5.9 2 2 0 0 1 6 3.8Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                </svg>
                联系电话
              </dt>
              <dd>{{ order.contactPhone || '未填写' }}</dd>
            </div>
            <div class="info-row">
              <dt>
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.8" />
                  <path d="M12 7.5V12l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                </svg>
                提交时间
              </dt>
              <dd>{{ formatDateTime(order.createdAt) }}</dd>
            </div>
          </dl>

          <div class="desc-block">
            <span class="block-label">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M6 3.5h8.5L19 8v12.5H6z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M9 12h6M9 15.5h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              描述
            </span>
            <p class="desc-text">{{ order.content }}</p>
          </div>

          <div v-if="imageAttachments.length > 0" class="photo-block">
            <span class="block-label">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M4 7.5h3.2L9 5.5h6l1.8 2H20v11.5H4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <circle cx="12" cy="13" r="3.2" stroke="currentColor" stroke-width="1.8" />
              </svg>
              现场照片
            </span>
            <div class="image-gallery">
              <el-image
                v-for="file in imageAttachments"
                :key="file.id"
                class="gallery-item"
                :src="file.fileUrl"
                :alt="file.fileName"
                fit="cover"
                :preview-src-list="imageAttachments.map((item) => item.fileUrl)"
                :initial-index="imageAttachments.indexOf(file)"
                preview-teleported
              />
            </div>
          </div>

          <div v-if="documentAttachments.length > 0" class="photo-block">
            <span class="block-label">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M13.5 3.5H7v17h11V8z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M13.5 3.5V8H18" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
              </svg>
              附件
            </span>
            <ul class="file-list">
              <li v-for="file in documentAttachments" :key="file.id">
                <a :href="file.fileUrl" target="_blank" rel="noopener">{{ file.fileName }}</a>
              </li>
            </ul>
          </div>
        </article>

        <article class="panel timeline-panel">
          <h2 class="panel-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.8" />
              <path d="M12 7.5V12l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            处理时间线
          </h2>
          <ol v-if="sortedTimeline.length > 0" class="timeline">
            <li
              v-for="(node, index) in sortedTimeline"
              :key="index"
              class="tl-item"
              :class="index === sortedTimeline.length - 1 ? 'is-current' : 'is-done'"
            >
              <span class="tl-marker" aria-hidden="true">
                <svg v-if="index === sortedTimeline.length - 1" viewBox="0 0 24 24" fill="none">
                  <path d="m6 12.5 4 4 8-9" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <div class="tl-body">
                <p class="tl-time">
                  {{ formatDateTime(node.createdAt) }}
                  <span class="tl-operator">{{ node.operatorName }}</span>
                </p>
                <p class="tl-status">{{ workOrderStatusLabels[node.newStatus] }}</p>
                <p v-if="node.content" class="tl-note">{{ node.content }}</p>
              </div>
            </li>
          </ol>
          <el-empty v-else description="暂无处理记录" :image-size="60" />
        </article>

        <article class="panel action-panel">
          <h2 class="panel-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M4 6.5h16v11H4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
              <path d="M7.5 10.5h9M7.5 13.8h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            操作面板
          </h2>

          <button v-if="canAccept" type="button" class="btn-primary" :disabled="actionLoading" @click="handleAccept">
            接单
          </button>
          <button v-if="canProcess" type="button" class="btn-primary" :disabled="actionLoading" @click="handleProcess">
            开始处理
          </button>

          <a v-if="order.contactPhone" class="btn-outline" :href="`tel:${order.contactPhone}`">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M6 3.8h3l1.6 4-2 1.3a11 11 0 0 0 5.3 5.3l1.3-2 4 1.6v3a2 2 0 0 1-2.2 2A15.5 15.5 0 0 1 4 5.9 2 2 0 0 1 6 3.8Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
            </svg>
            联系业主
          </a>

          <div v-if="canComplete" class="field">
            <label class="field-label" for="result-solution">
              处理结果说明
              <em class="field-required">*</em>
            </label>
            <textarea
              id="result-solution"
              v-model="resultForm.solution"
              class="field-textarea"
              rows="4"
              :maxlength="REMARK_MAX"
              placeholder="请填写问题原因与解决方式，居民确认后工单完结"
            />
            <span class="field-counter">{{ resultForm.solution.length }}/{{ REMARK_MAX }}</span>
          </div>

          <div class="field">
            <label class="field-label" for="result-note">处理备注</label>
            <textarea
              id="result-note"
              v-model="resultForm.remark"
              class="field-textarea"
              rows="3"
              :maxlength="NOTE_MAX"
              placeholder="请输入处理情况、遇到的问题或其他备注…"
            />
            <span class="field-counter">{{ resultForm.remark.length }}/{{ NOTE_MAX }}</span>
          </div>

          <template v-if="canComplete">
            <div class="field">
              <span class="field-label">处理后照片（可选，最多 6 张）</span>
              <ImageUploader v-model="resultForm.images" :limit="6" />
            </div>
            <button type="button" class="btn-primary" :disabled="submittingResult" @click="handleSubmitResult">
              提交处理结果
            </button>
          </template>

          <p v-if="!hasAction" class="action-hint">
            当前状态「{{ workOrderStatusLabels[order.status] }}」无需您操作，如需协助请联系管理员。
          </p>

          <button type="button" class="btn-ghost" @click="notifyReassignPending">申请改派</button>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.crumb-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.crumb-home {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.crumb-home svg {
  width: 20px;
  height: 20px;
}

.crumb-link {
  color: var(--color-text-secondary);
  text-decoration: none;
}

.crumb-link:hover {
  color: var(--color-primary);
}

.crumb-sep {
  color: var(--color-text-disabled);
}

.crumb-current {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 顶部状态条：优先级胶囊 + 工单标题 + 状态标签 */
.status-strip {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-lg) var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.status-priority {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 6px var(--spacing-md);
  border-radius: var(--radius-pill);
  background-color: var(--status-pending);
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.status-priority svg {
  width: 16px;
  height: 16px;
}

.status-strip.is-urgent .status-priority {
  background-color: var(--status-rejected);
}

.status-strip.is-low .status-priority {
  background-color: var(--color-text-disabled);
}

.status-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  min-width: 0;
}

.status-flag {
  margin-left: auto;
  flex-shrink: 0;
}

/* 主区三栏：工单信息 / 处理时间线 / 操作面板，等高对齐（对照稿三张卡底边齐平） */
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr) minmax(0, 1fr);
  align-items: stretch;
  gap: var(--spacing-lg);
}

.panel {
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-lg);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-lg);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.panel-title svg {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  color: var(--color-text-primary);
}

/* 提交人：头像 + 展示名 + 身份胶囊 */
.person {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.person-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 52px;
  height: 52px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
}

.person-text {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 0;
}

.person-name {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.person-role {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.person-caption {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-tight);
}

/* 信息行：小图标 + 标签 + 值 */
.info-rows {
  margin: 0 0 var(--spacing-lg);
  padding: 0;
}

.info-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) 0;
}

.info-row dt {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
  width: 84px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-row dt svg {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.info-row dd {
  margin: 0;
  min-width: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
  word-break: break-all;
}

.block-label {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.block-label svg {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.desc-text {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
}

.photo-block {
  margin-top: var(--spacing-lg);
}

.image-gallery {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

.gallery-item {
  width: 104px;
  height: 104px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  cursor: pointer;
}

.file-list {
  margin: 0;
  padding-left: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.file-list a {
  color: var(--color-primary);
}

/* 时间线：自绘节点（灰=已完成，蓝勾=当前节点），避免 el-timeline 默认形态 */
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.tl-item {
  position: relative;
  display: flex;
  gap: var(--spacing-md);
  padding-bottom: var(--spacing-lg);
}

.tl-item:last-child {
  padding-bottom: 0;
}

.tl-item::before {
  content: '';
  position: absolute;
  top: 18px;
  bottom: 0;
  left: 8px;
  width: 1px;
  background-color: var(--color-border);
}

.tl-item:last-child::before {
  display: none;
}

.tl-marker {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  margin-top: 3px;
  border-radius: var(--radius-circle);
  background-color: var(--color-border);
}

.tl-marker svg {
  width: 13px;
  height: 13px;
  color: #fff;
}

.tl-item.is-current .tl-marker {
  background-color: var(--color-primary);
}

.tl-body {
  min-width: 0;
}

.tl-time {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.tl-operator {
  color: var(--color-text-secondary);
}

.tl-status {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.tl-item.is-current .tl-status {
  color: var(--color-primary);
}

.tl-note {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

/* 操作面板：主按钮 / 描边按钮 / 输入区 / 占位按钮 */
.action-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 13px var(--spacing-lg);
  border: none;
  border-radius: var(--radius-md);
  background-color: var(--color-primary);
  color: #fff;
  font-family: inherit;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.btn-primary:hover:not(:disabled) {
  background-color: var(--color-primary-dark);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-outline {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  width: 100%;
  padding: 12px var(--spacing-lg);
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  background-color: transparent;
  color: var(--color-primary);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  text-decoration: none;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.btn-outline:hover {
  background-color: var(--color-primary-bg);
}

.btn-outline svg {
  width: 18px;
  height: 18px;
}

.btn-ghost {
  width: 100%;
  padding: 13px var(--spacing-lg);
  border: none;
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
  color: var(--color-text-secondary);
  font-family: inherit;
  font-size: var(--font-size-md);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.btn-ghost:hover {
  background-color: var(--color-bg-hover);
}

.field {
  position: relative;
  display: flex;
  flex-direction: column;
}

.field-label {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.field-required {
  color: var(--color-danger);
  font-style: normal;
}

.field-textarea {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: transparent;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
  resize: vertical;
  outline: none;
}

.field-textarea:focus {
  border-color: var(--color-primary-light);
}

.field-counter {
  align-self: flex-end;
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.action-hint {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

@media (max-width: 1199px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .action-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 767px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .action-panel {
    grid-column: auto;
  }

  .status-strip {
    flex-wrap: wrap;
  }

  .status-flag {
    margin-left: 0;
  }
}
</style>
