<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import FollowupDrawer from './FollowupDrawer.vue'
import { getEvaluationStatistics, listEvaluationFollowups, listEvaluations, listUnsatisfiedEvaluations } from '@/api/evaluation'
import type { IEvaluation, IEvaluationStatistics } from '@/types/modules/evaluation'
import { formatDate } from '@/utils/date'

/**
 * 评价管理容器：对照 design-mockups/admin/10-评价管理。
 * 统计头（综合评分 + 评分分布，数据来自 GET /statistics/evaluations）+
 * 白卡 Tab 条（?tab=all|pending|followed 契约）+ 评价卡片列表 + 跟进抽屉。
 *
 * 数据口径（后端实测，接口设计.md 存在漂移）：
 * - EvaluationVO 无跟进状态字段；跟进状态经「拉全量不满意评价 + 逐条核实跟进记录」
 *   客户端判定（待跟进 = 无跟进记录，已跟进 = 有），角标与卡片胶囊同源；
 * - 已跟进评价必为不满意评价（后端仅不满意可跟进），满意评价不渲染跟进胶囊；
 * - 加载请求经 nextTick 合并（单通道），翻页/切 Tab 任一交互只发一次列表请求。
 */

const TABS = ['all', 'pending', 'followed'] as const
type TabName = (typeof TABS)[number]

/* 星形图标（24×24 stroke，随 AdminStatCard 语义色） */
const ICONS = {
  star: ['M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z']
}

const route = useRoute()
const router = useRouter()

const activeTab = computed<TabName>(() => {
  const tab = route.query.tab
  return typeof tab === 'string' && (TABS as readonly string[]).includes(tab)
    ? (tab as TabName)
    : TABS[0]
})

/* 数据派发依据的 Tab（switchTab 同步更新，路由 replace 异步不参与派发） */
const currentTab = ref<TabName>(activeTab.value)

/* ---------- 统计头：GET /statistics/evaluations（数据权限拦截器按角色收敛范围） ---------- */

const stats = ref<IEvaluationStatistics | null>(null)
const statsLoading = ref(false)

const averageRating = computed(() => {
  const total = stats.value?.total ?? 0
  if (total === 0) return null
  return stats.value?.averageRating ?? null
})

/* 平均分固定一位小数（与设计稿 4.6 形态一致，无数据显示「-」） */
const averageRatingLabel = computed(() =>
  averageRating.value === null ? '-' : averageRating.value.toFixed(1)
)

/* 五档分布行（评分降序）；百分比整数化，与设计稿一致 */
const ratingRows = computed(() => {
  const dist = stats.value?.ratingDistribution ?? {}
  const total = stats.value?.total ?? 0
  return [5, 4, 3, 2, 1].map((rating) => {
    const count = dist[String(rating)] ?? 0
    return {
      rating,
      count,
      percent: total > 0 ? Math.round((count * 100) / total) : 0
    }
  })
})

async function loadStats(): Promise<void> {
  statsLoading.value = true
  try {
    stats.value = await getEvaluationStatistics()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评价统计加载失败')
  } finally {
    statsLoading.value = false
  }
}

/* ---------- 全部 Tab：服务端分页列表 ---------- */

const records = ref<IEvaluation[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const listLoading = ref(false)

async function loadList(): Promise<void> {
  listLoading.value = true
  try {
    const result = await listEvaluations({ page: page.value, size: size.value })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评价列表加载失败')
  } finally {
    listLoading.value = false
  }
}

/* ---------- 待跟进 / 已跟进 Tab：跟进工作台（客户端分组 + 客户端分页） ----------
   后端 unsatisfied 接口无已跟进过滤参数，拉全量不满意评价（size=100 翻页循环，
   上限 20 页）后逐条核实跟进记录数完成分组；数据量随不满意评价数线性增长，
   根治需后端补充 hasFollowup 过滤参数（已记 BEAUTIFY_NOTES）。 */

const workbenchPending = ref<IEvaluation[]>([])
const workbenchFollowed = ref<IEvaluation[]>([])
const followupCounts = ref<Map<number, number>>(new Map())
const workbenchLoading = ref(false)

const MAX_WORKBENCH_PAGES = 20

async function loadWorkbench(): Promise<void> {
  workbenchLoading.value = true
  try {
    const unsatisfied: IEvaluation[] = []
    for (let p = 1; p <= MAX_WORKBENCH_PAGES; p++) {
      const result = await listUnsatisfiedEvaluations({ page: p, size: 100 })
      unsatisfied.push(...result.records)
      if (result.records.length < 100 || p >= result.pages) break
    }
    const counts = new Map<number, number>()
    await Promise.all(
      unsatisfied.map(async (item) => {
        try {
          const followups = await listEvaluationFollowups(item.id)
          counts.set(item.id, followups.length)
        } catch {
          counts.set(item.id, 0)
        }
      })
    )
    workbenchPending.value = unsatisfied.filter((item) => (counts.get(item.id) ?? 0) === 0)
    workbenchFollowed.value = unsatisfied.filter((item) => (counts.get(item.id) ?? 0) > 0)
    followupCounts.value = counts
    /* 列表收缩后页码回夹，避免停留在空页 */
    const maxPage = Math.max(1, Math.ceil(workbenchPending.value.length / clientSize.value))
    if (clientPage.value > maxPage) clientPage.value = maxPage
  } finally {
    workbenchLoading.value = false
  }
}

/* ---------- 加载调度：所有触发经 nextTick 合并，单次交互只发一轮请求 ----------
   （顺修 BEAUTIFY_NOTES 已知问题 4：旧页 watch(filter) 与 watch([page,size]) 叠加双发） */

let loadScheduled = false

function scheduleLoad(): void {
  if (loadScheduled) return
  loadScheduled = true
  nextTick(() => {
    loadScheduled = false
    load()
  })
}

function load(): void {
  if (currentTab.value === 'all') {
    loadList()
  } else {
    loadWorkbench()
  }
}

function switchTab(tab: TabName): void {
  if (tab === currentTab.value) return
  currentTab.value = tab
  router.replace({ query: { ...route.query, tab } })
  page.value = 1
  clientPage.value = 1
  scheduleLoad()
}

/* ---------- 分页：全部 Tab 服务端分页，工作台 Tab 客户端分页 ---------- */

const clientPage = ref(1)
const clientSize = ref(10)

const isAllTab = computed(() => currentTab.value === 'all')

const clientList = computed<IEvaluation[]>(() =>
  currentTab.value === 'pending' ? workbenchPending.value : workbenchFollowed.value
)

const displayRecords = computed<IEvaluation[]>(() => {
  if (isAllTab.value) return records.value
  const start = (clientPage.value - 1) * clientSize.value
  return clientList.value.slice(start, start + clientSize.value)
})

const displayTotal = computed(() => (isAllTab.value ? total.value : clientList.value.length))

const displayLoading = computed(() =>
  isAllTab.value ? listLoading.value : workbenchLoading.value
)

function onPageChange(value: number): void {
  if (isAllTab.value) {
    page.value = value
    scheduleLoad()
  } else {
    clientPage.value = value
  }
}

function onSizeChange(value: number): void {
  if (isAllTab.value) {
    size.value = value
    page.value = 1
    scheduleLoad()
  } else {
    clientSize.value = value
    clientPage.value = 1
  }
}

/* ---------- 卡片展示辅助 ---------- */

const AVATAR_ACCENTS = ['is-primary', 'is-success', 'is-warning', 'is-danger'] as const

function avatarClass(id: number): string {
  return AVATAR_ACCENTS[id % AVATAR_ACCENTS.length]
}

function avatarChar(row: IEvaluation): string {
  return row.residentName?.trim()?.charAt(0) || '客'
}

/* 低分（不满意，rating ≤ 3）星红、满意星金；空心星灰 */
const CARD_STAR_COLORS = ['var(--color-danger)', 'var(--color-danger)', 'var(--color-warning)']
const GOLD_STAR_COLORS = ['var(--color-warning)', 'var(--color-warning)', 'var(--color-warning)']

function tagList(row: IEvaluation): string[] {
  return (row.tags ?? '')
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean)
}

/* 跟进胶囊（VO 无跟进状态字段，经工作台核实结果渲染；满意评价不显示） */
function pillLabel(row: IEvaluation): string {
  if (row.isSatisfied !== 0) return ''
  if (!followupCounts.value.has(row.id)) return ''
  return (followupCounts.value.get(row.id) ?? 0) > 0 ? '已跟进' : '待跟进'
}

function pillType(row: IEvaluation): 'completed' | 'rejected' {
  return (followupCounts.value.get(row.id) ?? 0) > 0 ? 'completed' : 'rejected'
}

function isPendingRow(row: IEvaluation): boolean {
  return row.isSatisfied === 0 && (followupCounts.value.get(row.id) ?? 0) === 0
}

function isFollowedRow(row: IEvaluation): boolean {
  return row.isSatisfied === 0 && (followupCounts.value.get(row.id) ?? 0) > 0
}

function goWorkOrder(row: IEvaluation): void {
  router.push(`/admin/work-orders/${row.workOrderId}`)
}

const emptyText = computed(() => {
  if (currentTab.value === 'pending') return '暂无待跟进评价'
  if (currentTab.value === 'followed') return '暂无已跟进评价'
  return '暂无评价'
})

/* ---------- 跟进抽屉 ---------- */

const drawerVisible = ref(false)
const drawerEvaluation = ref<IEvaluation | null>(null)

function openFollowup(row: IEvaluation): void {
  drawerEvaluation.value = row
  drawerVisible.value = true
}

/* 跟进提交成功后：核实结果与角标同源刷新，当前 Tab 列表随之更新 */
function onFollowupChanged(): void {
  loadWorkbench()
}

onMounted(() => {
  loadStats()
  load()
  /* 全部 Tab 的跟进胶囊/按钮依赖工作台核实结果，挂载即加载 */
  if (currentTab.value === 'all') {
    loadWorkbench()
  }
})
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="服务评价" />

    <!-- 统计头：综合评分 + 评分分布 -->
    <div class="stat-row" v-loading="statsLoading">
      <AdminStatCard
        label="综合评分"
        :value="averageRatingLabel"
        :icon="ICONS.star"
        accent="warning"
        class="rating-card"
      >
        <template #extra>
          <div class="rating-extra">
            <el-rate
              v-if="averageRating"
              :model-value="averageRating"
              disabled
              allow-half
              :colors="GOLD_STAR_COLORS"
              void-color="var(--color-border)"
              disabled-void-color="var(--color-border)"
            />
            <span class="rating-count">{{ stats?.total ? `共 ${stats.total} 条评价` : '暂无评价' }}</span>
          </div>
        </template>
      </AdminStatCard>

      <article class="dist-card">
        <h2 class="dist-title">评分分布</h2>
        <div class="dist-rows">
          <div v-for="row in ratingRows" :key="row.rating" class="dist-row">
            <span class="dist-label">{{ row.rating }}星</span>
            <div class="dist-track">
              <div
                class="dist-fill"
                :class="row.rating === 1 ? 'is-low' : 'is-high'"
                :style="{ width: `${stats?.total ? row.percent : 0}%` }"
              />
            </div>
            <span class="dist-percent">{{ stats?.total ? `${row.percent}%` : '-' }}</span>
          </div>
        </div>
      </article>
    </div>

    <!-- 白卡 Tab 条：?tab= 深链契约 -->
    <nav class="tab-bar" role="tablist" aria-label="评价视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'all' }"
        :aria-selected="activeTab === 'all'"
        @click="switchTab('all')"
      >
        全部
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'pending' }"
        :aria-selected="activeTab === 'pending'"
        @click="switchTab('pending')"
      >
        待跟进
        <span v-if="workbenchPending.length > 0" class="tab-badge">{{ workbenchPending.length }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'followed' }"
        :aria-selected="activeTab === 'followed'"
        @click="switchTab('followed')"
      >
        已跟进
      </button>
    </nav>

    <!-- 评价卡片列表 -->
    <div v-loading="displayLoading" class="card-list">
      <article v-for="row in displayRecords" :key="row.id" class="eval-card">
        <span class="eval-avatar" :class="avatarClass(row.id)" aria-hidden="true">
          {{ avatarChar(row) }}
        </span>
        <div class="eval-main">
          <div class="eval-head">
            <span class="eval-name">{{ row.residentName || '匿名居民' }}</span>
            <el-rate
              :model-value="row.rating"
              disabled
              :colors="CARD_STAR_COLORS"
              void-color="var(--color-border)"
              disabled-void-color="var(--color-border)"
            />
          </div>
          <p class="eval-content">{{ row.content || '未填写评价内容' }}</p>
          <div v-if="tagList(row).length > 0" class="eval-tags">
            <span v-for="tag in tagList(row)" :key="tag" class="eval-tag">{{ tag }}</span>
          </div>
          <p class="eval-meta">
            <el-link class="eval-order" :underline="false" @click="goWorkOrder(row)">#{{ row.workOrderNo }}</el-link>
            <span class="eval-dot">·</span>
            <span>{{ formatDate(row.createdAt) }}</span>
          </p>
        </div>
        <div class="eval-side">
          <StatusTag v-if="pillLabel(row)" :label="pillLabel(row)" :type="pillType(row)" />
          <el-button v-if="isPendingRow(row)" type="primary" size="small" @click="openFollowup(row)">
            发起跟进
          </el-button>
          <el-button v-else-if="isFollowedRow(row)" text type="primary" size="small" @click="openFollowup(row)">
            跟进记录
          </el-button>
        </div>
      </article>

      <el-empty v-if="!displayLoading && displayRecords.length === 0" :description="emptyText" :image-size="90" />
    </div>

    <Pagination
      :page="isAllTab ? page : clientPage"
      :size="isAllTab ? size : clientSize"
      :total="displayTotal"
      @update:page="onPageChange"
      @update:size="onSizeChange"
    />

    <FollowupDrawer v-model="drawerVisible" :evaluation="drawerEvaluation" @changed="onFollowupChanged" />
  </div>
</template>

<style scoped>
/* ---------- 统计头 ---------- */

.stat-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}

/* 综合评分主数值放大（对照设计稿大号数字层级） */
.rating-card :deep(.stat-value) {
  font-size: 44px;
  color: var(--color-primary);
}

.rating-extra {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--spacing-xs);
}

.rating-count {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.dist-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.dist-title {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.dist-rows {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.dist-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.dist-label {
  flex-shrink: 0;
  width: 30px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.dist-track {
  flex: 1;
  height: 8px;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-hover);
  overflow: hidden;
}

.dist-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  transition: width 0.4s ease;
}

.dist-fill.is-high {
  background-color: var(--color-success);
}

.dist-fill.is-low {
  background-color: var(--color-danger);
}

.dist-percent {
  flex-shrink: 0;
  width: 40px;
  text-align: right;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
}

/* ---------- 白卡 Tab 条（与居民管理等页同款） ---------- */

.tab-bar {
  display: flex;
  align-items: stretch;
  gap: var(--spacing-xl);
  padding: 0 var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow-x: auto;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
  height: 56px;
  padding: 0 var(--spacing-xs);
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-md);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease;
}

.tab-item:hover {
  color: var(--color-text-primary);
}

.tab-item.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

/* 待跟进数角标：语义 soft 色对，随工作台数据自动消隐 */
.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1;
}

/* ---------- 评价卡片列表 ---------- */

.card-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 200px;
}

.eval-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.eval-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-circle);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
}

.eval-avatar.is-primary {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.eval-avatar.is-success {
  color: var(--color-success);
  background-color: var(--color-success-soft);
}

.eval-avatar.is-warning {
  color: var(--color-warning);
  background-color: var(--color-warning-soft);
}

.eval-avatar.is-danger {
  color: var(--color-danger);
  background-color: var(--color-danger-soft);
}

.eval-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.eval-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.eval-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.eval-content {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.eval-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.eval-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
}

.eval-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.eval-order {
  font-size: var(--font-size-xs);
  vertical-align: baseline;
}

.eval-dot {
  color: var(--color-text-disabled);
}

.eval-side {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .eval-card {
    flex-direction: column;
    align-items: flex-start;
  }

  .eval-side {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
