<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { getResidenceApplicationList, withdrawResidenceApplication } from '@/api/resident'
import { openDirectConversation } from '@/api/conversation'
import type { IResidenceApplication, ResidenceApplicationStatus } from '@/types/modules/resident'
import { relationTypeLabels, residenceApplicationStatusLabels } from '@/types/modules/resident'
import { formatDateTime } from '@/utils/date'

/**
 * 我的入住申请（批次 g，修复「申请提交后两端都看不到」的居民侧）：
 * 居民端此前没有本人入住申请的落地页，居民只能在重复提交被拒时看到
 * 「已有待审核申请」，既看不到审核进度，也用不上批次 f 已实现的撤回能力。
 *
 * 约束：
 * - 列表取 GET /residence-applications，接口按当前登录居民过滤，本页不做二次过滤；
 * - 状态为四态（待审核/已通过/已驳回/已撤回）；驳回理由取 reviewRemark，仅已驳回展示；
 * - 撤回仅「待审核」可操作（PATCH /residence-applications/{id}/withdraw），
 *   成功后重新拉取列表，状态以后端落库的「已撤回」为准，前端不本地改状态。
 *
 * ① 租约/会话入口（V26 强关联 + DEF-076 接入）：
 * - 后端 `ApplicationVO` 的 `leaseId` 直接取 `residence_application.lease_id`（**审批通过时写死**），
 *   **只有 `APPROVED` 且有真实关联时才有值；为空时后端返回空、不回退弱关联猜测** ——
 *   故前端只在 `APPROVED && leaseId` 时给「查看我的租约」深链，`leaseId` 为空一律中性兜底
 *   （提示"暂无关联租约"+ 去租约列表），**不渲染指向具体租约的死链**；
 * - `conversationId` 取**该租约**的会话（不是靠猜）；已通过且已建群 → 「进入租约会话」；
 *   已通过但尚无群 → 「咨询社区管理员」，走居民侧既有 `POST /conversations/direct`；
 * - 非 `APPROVED` 状态不给任何租约/会话入口。
 */

const router = useRouter()

/** 申请状态 → StatusTag 语义色（已撤回为终态灰，不落驳回红） */
const STATUS_TYPES: Record<
  ResidenceApplicationStatus,
  'pending' | 'completed' | 'rejected' | 'canceled'
> = {
  PENDING: 'pending',
  APPROVED: 'completed',
  REJECTED: 'rejected',
  WITHDRAWN: 'canceled'
}

const records = ref<IResidenceApplication[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
/** 撤回中的申请 ID：按钮级 loading，避免重复提交 */
const withdrawingId = ref<number | null>(null)

const isEmpty = computed(() => !loading.value && records.value.length === 0)

function relationLabel(relationType?: string): string {
  return relationType
    ? relationTypeLabels[relationType as keyof typeof relationTypeLabels] ?? relationType
    : '—'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getResidenceApplicationList({ page: page.value, size: size.value })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    records.value = []
    total.value = 0
    ElMessage.error(error instanceof Error ? error.message : '加载入住申请失败')
  } finally {
    loading.value = false
  }
}

/* ---------- ① 申请入口的租约/会话可达性（只认 APPROVED 的强关联，V26 lease_id） ---------- */

const openingChatId = ref<number | null>(null)

/**
 * 该申请是否可作为「进入租约/会话」的跳转依据（V26 强关联口径）：
 * `residence_application.lease_id` 在**审批通过时写死**，VO 直接取该列且**为空即无关联**
 * （后端不再按房屋回退猜测）。故只有 `APPROVED` 且 `leaseId` 有值才算真实关联，
 * 否则一律走中性兜底——**不渲染指向具体租约的按钮，杜绝死链**。
 */
function leaseLinkUsable(row: IResidenceApplication): boolean {
  return row.status === 'APPROVED' && (row.leaseId ?? null) !== null
}

/** 已通过且已建群：直接进会话详情 */
function openApplicationConversation(row: IResidenceApplication): void {
  if (!leaseLinkUsable(row) || !row.conversationId) return
  router.push(`/resident/conversations/${row.conversationId}`)
}

/** 已通过但尚无群：走居民侧直通会话咨询社区管理员（后端 /conversations/direct 居民仅本人） */
async function openApplicationDirectChat(row: IResidenceApplication): Promise<void> {
  if (!leaseLinkUsable(row)) return
  openingChatId.value = row.id
  try {
    const conversation = await openDirectConversation({ communityId: row.communityId })
    router.push(`/resident/conversations/${conversation.id}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '打开会话失败，请稍后重试')
  } finally {
    openingChatId.value = null
  }
}

/** 撤回申请：二次确认 → 调后端撤回 → 重新拉取（状态由后端落「已撤回」） */
async function handleWithdraw(row: IResidenceApplication): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `撤回后将终止「${row.houseLocation}」的入住申请审核，如仍需入住须重新提交申请。`,
      '撤回入住申请',
      { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  withdrawingId.value = row.id
  try {
    await withdrawResidenceApplication(row.id)
    ElMessage.success('已撤回该入住申请')
    /* 当前页仅剩这一条且不在首页：先回退一页再拉取，避免停落在空页（OCR DEF-125） */
    if (records.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤回失败')
  } finally {
    withdrawingId.value = null
  }
}

/* Hub 内跳转：房源源在「房源信息」Tab，租约在「租住契约」Tab */
function goHub(tab: 'housing' | 'lease'): void {
  router.push({ path: '/resident/housing-lease', query: { tab } })
}

/** 深链到「租住契约」Tab 的对应租约（`leaseId` 由租约列表页 focus 定位，既有契约） */
function goLease(row: IResidenceApplication): void {
  if (!leaseLinkUsable(row) || !row.leaseId) return
  router.push({ path: '/resident/housing-lease', query: { tab: 'lease', leaseId: String(row.leaseId) } })
}

onMounted(load)
</script>

<template>
  <section class="my-application">
    <header class="page-head">
      <div>
        <h1>入住申请</h1>
        <p class="page-head-sub">
          本人入住申请的审核进度：待审核可撤回，审核通过后生成待签约租约
        </p>
      </div>
      <el-button @click="goHub('housing')">继续看房源</el-button>
    </header>

    <div v-loading="loading" class="application-board">
      <div v-if="isEmpty" class="empty-state">
        <p>还没有入住申请记录</p>
        <el-button type="primary" @click="goHub('housing')">去挑房源</el-button>
      </div>

      <template v-else>
        <article v-for="row in records" :key="row.id" class="application-card">
          <div class="card-main">
            <h2 class="card-title">{{ row.houseLocation }}</h2>
            <div class="card-meta">
              <span class="meta-item">
                <span class="meta-label">申请时间</span>
                <span class="meta-value mono">{{ formatDateTime(row.createdAt) }}</span>
              </span>
              <span class="meta-item">
                <span class="meta-label">申请身份</span>
                <span class="meta-value">{{ relationLabel(row.relationType) }}</span>
              </span>
              <span v-if="row.remark" class="meta-item">
                <span class="meta-label">申请备注</span>
                <span class="meta-value">{{ row.remark }}</span>
              </span>
            </div>
            <p v-if="row.status === 'REJECTED' && row.reviewRemark" class="review-remark">
              驳回理由：{{ row.reviewRemark }}
            </p>
          </div>

          <div class="card-side">
            <StatusTag
              :label="residenceApplicationStatusLabels[row.status as ResidenceApplicationStatus]"
              :type="STATUS_TYPES[row.status as ResidenceApplicationStatus]"
            />

            <!-- 待审核：批次 f 的撤回能力在此接线（仅 PENDING 可撤） -->
            <el-button
              v-if="row.status === 'PENDING'"
              type="danger"
              plain
              round
              size="small"
              :loading="withdrawingId === row.id"
              @click="handleWithdraw(row)"
            >
              撤回申请
            </el-button>

            <!-- 已通过：审核通过即生成待签约租约（R66）；租约/会话 id 取后端强关联（V26 lease_id）回带 -->
            <template v-else-if="row.status === 'APPROVED'">
              <span class="action-hint">已生成租约</span>
              <!-- ① 会话入口：已建群进群；未建群走居民侧直通会话（后端 /conversations/direct 居民仅本人） -->
              <el-button
                v-if="row.conversationId"
                text
                type="primary"
                size="small"
                @click="openApplicationConversation(row)"
              >
                进入租约会话
              </el-button>
              <el-button
                v-else
                text
                type="primary"
                size="small"
                :loading="openingChatId === row.id"
                @click="openApplicationDirectChat(row)"
              >
                咨询社区管理员
              </el-button>
              <!-- 有真实关联（V26 写死的 lease_id）→ 深链到「租住契约」Tab 的对应租约 -->
              <el-button
                v-if="leaseLinkUsable(row)"
                text
                type="primary"
                size="small"
                @click="goLease(row)"
              >
                查看我的租约
              </el-button>
              <!-- 无关联（lease_id 为空）→ 中性兜底：说明情况并给租约列表入口，不出死链 -->
              <template v-else>
                <span class="action-hint">暂无关联租约</span>
                <el-button text type="primary" size="small" @click="goHub('lease')">
                  去租约列表
                </el-button>
              </template>
            </template>
          </div>
        </article>
      </template>
    </div>

    <Pagination
      v-if="total > size"
      v-model:page="page"
      v-model:size="size"
      :total="total"
      @update:page="load"
      @update:size="load"
    />
  </section>
</template>

<style scoped>
.my-application {
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

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.application-board {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 160px;
}

.application-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-lg);
  padding: var(--spacing-lg);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.card-main {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: 0;
}

.card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs) var(--spacing-lg);
}

.meta-item {
  display: inline-flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  font-size: var(--font-size-sm);
}

.meta-label {
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}

.meta-value {
  color: var(--color-text-secondary);
}

/* 驳回理由是居民最需要看到的结果信息，用警示底色单独成行 */
.review-remark {
  margin: 0;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: rgba(239, 68, 68, 0.08);
  color: var(--status-rejected);
  font-size: var(--font-size-sm);
}

.card-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--spacing-sm);
  flex-shrink: 0;
}

.action-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-xxl) var(--spacing-lg);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.empty-state p {
  margin: 0;
}

.mono {
  font-family: var(--font-family-mono);
}

@media (max-width: 767px) {
  .application-card {
    flex-direction: column;
  }

  .card-side {
    align-items: flex-start;
  }
}
</style>
