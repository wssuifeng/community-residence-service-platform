<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { listWorkOrders } from '@/api/workorder'
import type { IWorkOrder, WorkOrderPriority, WorkOrderStatus } from '@/types/modules/workorder'
import { workOrderStatusLabels, workOrderPriorityLabels } from '@/types/modules/workorder'
import { formatDateTime } from '@/utils/date'
import { residentDisplayName, residentInitial, residentRelationLabel } from '@/utils/staffPlaceholder'

/** 我的工单（UI设计.md §4.2 / §3.3）：状态 Tab + 工单卡片列表（对照 design-mockups/staff/02） */

type ListTab = 'todo' | 'doing' | 'confirm' | 'done'

const route = useRoute()
const router = useRouter()

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

/* Tab → 查询状态集：待接单=已派单；处理中=已接单+处理中；待确认=待确认；已完成=已完成。
   与工作台统计口径一致（工作台「处理中」也是 已接单+处理中），待确认单列不并入处理中 */
const tabStatuses: Record<ListTab, WorkOrderStatus[]> = {
  todo: ['ASSIGNED'],
  doing: ['ACCEPTED', 'IN_PROGRESS'],
  confirm: ['TO_CONFIRM'],
  done: ['COMPLETED']
}

const tabLabels: Record<ListTab, string> = {
  todo: '待接单',
  doing: '处理中',
  confirm: '待确认',
  done: '已完成'
}

const TAB_ORDER: ListTab[] = ['todo', 'doing', 'confirm', 'done']

/** 单次取回条数：后端 size 上限 100（WorkOrderService.page 内 Math.min(size,100)） */
const FETCH_SIZE = 100

const activeTab = ref<ListTab>(normalizeTab(route.query.tab))
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IWorkOrder[]>([])
const tabCounts = ref<Record<ListTab, number>>({ todo: 0, doing: 0, confirm: 0, done: 0 })
const loading = ref(false)

/* 搜索词：输入后 400ms 防抖落定，避免每次按键都请求 */
const keywordDraft = ref('')
const keyword = ref('')
let searchTimer: number | undefined

/* 页内分页切片：mockup 要求真实分页条，而多状态合并后无法交给服务端分页 */
const pagedRecords = computed(() => records.value.slice((page.value - 1) * size.value, page.value * size.value))

function normalizeTab(value: unknown): ListTab {
  return value === 'doing' || value === 'confirm' || value === 'done' ? value : 'todo'
}

/* 紧急程度 → 卡片配色档：紧急红 / 一般黄 / 较低灰（文案始终由 workOrderPriorityLabels 提供） */
function toneOf(priority: WorkOrderPriority): 'urgent' | 'normal' | 'low' {
  if (priority === 'URGENT') return 'urgent'
  return priority === 'LOW' ? 'low' : 'normal'
}

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    /* 后端单次查询仅支持一个状态，多状态 Tab 一次取回后在页内合并分页；
       单状态超过 FETCH_SIZE 条时只呈现最新的一批，待后端支持多状态查询再恢复服务端分页 */
    const results = await Promise.all(
      tabStatuses[activeTab.value].map((status) =>
        listWorkOrders({ status, page: 1, size: FETCH_SIZE, keyword: keyword.value || undefined })
      )
    )
    records.value = results
      .flatMap((result) => result.records)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    total.value = records.value.length
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单列表加载失败')
  } finally {
    loading.value = false
  }
}

/* Tab 角标数量：各状态取 size=1 只读 total，辅助信息失败不打断列表 */
async function loadCounts(): Promise<void> {
  try {
    const entries = await Promise.all(
      TAB_ORDER.map(async (tab) => {
        const results = await Promise.all(tabStatuses[tab].map((status) => listWorkOrders({ status, page: 1, size: 1 })))
        return [tab, results.reduce((sum, result) => sum + result.total, 0)] as const
      })
    )
    tabCounts.value = Object.fromEntries(entries) as Record<ListTab, number>
  } catch {
    /* 角标为辅助信息，静默降级 */
  }
}

function refresh(): void {
  fetchList()
  loadCounts()
}

watch(activeTab, () => {
  page.value = 1
  /* Tab 写回地址栏，刷新后停留在同一 Tab（工作台跳转用的 ?tab= 契约保持双向可用） */
  if (route.query.tab !== activeTab.value) {
    router.replace({ query: { ...route.query, tab: activeTab.value } }).catch(() => undefined)
  }
  refresh()
})

watch([page, size], () => {
  fetchList()
})

watch(keywordDraft, (value) => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    const next = value.trim()
    if (next === keyword.value) return
    keyword.value = next
    if (page.value !== 1) page.value = 1
    else fetchList()
  }, 400)
})

function goDetail(order: IWorkOrder): void {
  router.push(`/staff/work-orders/${order.id}`)
}

onMounted(refresh)
onUnmounted(() => window.clearTimeout(searchTimer))
</script>

<template>
  <section class="staff-work-order-list">
    <header class="page-header">
      <h1 class="page-title">工单列表</h1>
      <!-- 搜索词由后端 keyword 过滤，实际匹配 title/content（WorkOrderService:185-187）；
           工单号暂不参与匹配，故占位文案按真实能力写，不写「搜索工单号」 -->
      <label class="search-box">
        <svg class="search-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2" />
          <path d="M16.5 16.5 21 21" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
        </svg>
        <input v-model="keywordDraft" type="search" placeholder="搜索工单标题/内容…" aria-label="搜索工单标题或内容" />
      </label>
    </header>

    <nav class="tab-bar" role="tablist">
      <button
        v-for="tab in TAB_ORDER"
        :key="tab"
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === tab }"
        :aria-selected="activeTab === tab"
        @click="activeTab = tab"
      >
        {{ tabLabels[tab] }}
        <span v-if="tabCounts[tab] > 0" class="tab-badge">{{ tabCounts[tab] }}</span>
      </button>
    </nav>

    <div v-loading="loading" class="order-list">
      <ul v-if="pagedRecords.length > 0" class="order-cards">
        <li
          v-for="order in pagedRecords"
          :key="order.id"
          class="order-card"
          :class="`is-${toneOf(order.priority)}`"
          tabindex="0"
          @click="goDetail(order)"
          @keydown.enter="goDetail(order)"
        >
          <span class="order-bar" aria-hidden="true" />
          <span class="order-priority">{{ workOrderPriorityLabels[order.priority] }}</span>

          <div class="order-person">
            <span class="order-avatar" aria-hidden="true">{{ residentInitial(order.residentName) }}</span>
            <span class="order-person-text">
              <span class="order-name">{{ residentDisplayName(order) }}</span>
              <span class="order-identity">{{ residentRelationLabel(order) }}</span>
            </span>
          </div>

          <div class="order-main">
            <div class="order-title-line">
              <h3 class="order-title">{{ order.title }}</h3>
              <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />
            </div>
            <p class="order-meta">
              <span class="order-no">{{ order.orderNo }}</span>
              <span>{{ order.categoryName }}</span>
              <span v-if="order.address">{{ order.address }}</span>
              <span>{{ formatDateTime(order.createdAt) }} 提交</span>
            </p>
          </div>

          <div class="order-side">
            <button type="button" class="order-action" @click.stop="goDetail(order)">
              {{ order.status === 'ASSIGNED' ? '接单' : '查看' }}
            </button>
          </div>
        </li>
      </ul>

      <el-empty
        v-else-if="!loading"
        :description="activeTab === 'todo' ? '暂无待接单工单' : '暂无工单'"
        :image-size="80"
      />
    </div>

    <Pagination v-model:page="page" v-model:size="size" :total="total" />
  </section>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-md);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
}

.search-box {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex: 0 1 440px;
  min-width: 200px;
  height: 48px;
  padding: 0 var(--spacing-md);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-disabled);
}

.search-box:focus-within {
  border-color: var(--color-primary-light);
}

.search-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.search-box input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background-color: transparent;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.search-box input::placeholder {
  color: var(--color-text-disabled);
}

/* 状态 Tab：整条白卡，激活项蓝字 + 蓝色圆形角标 + 底部 2px 下划线 */
.tab-bar {
  display: flex;
  align-items: stretch;
  gap: var(--spacing-xl);
  padding: 0 var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow-x: auto;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
  height: 62px;
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

.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1;
}

.tab-item.active .tab-badge {
  background-color: var(--color-primary);
  color: #fff;
}

.order-list {
  min-height: 200px;
}

.order-cards {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 工单卡：左侧优先级色条 + 提交人 + 标题信息 + 操作，整卡可点进详情 */
.order-card {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 190px) minmax(0, 1fr) auto;
  align-items: center;
  column-gap: var(--spacing-lg);
  padding: 26px var(--spacing-lg) 26px 46px;
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.order-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.order-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.order-bar {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 10px;
  background-color: var(--status-pending);
}

.order-card.is-urgent .order-bar {
  background-color: var(--status-rejected);
}

.order-card.is-low .order-bar {
  background-color: var(--color-border);
}

/* 优先级文字胶囊：紧贴卡片左上，与竖向色条同色系 */
.order-priority {
  position: absolute;
  top: 16px;
  left: 46px;
  padding: 3px 12px;
  border-radius: var(--radius-pill);
  background-color: var(--status-pending);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: var(--line-height-tight);
}

.order-card.is-urgent .order-priority {
  background-color: var(--status-rejected);
}

.order-card.is-low .order-priority {
  background-color: var(--color-text-disabled);
}

.order-person {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding-top: 30px;
  min-width: 0;
}

.order-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
}

.order-person-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.order-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.order-identity {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-tight);
}

.order-main {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-self: stretch;
  gap: var(--spacing-sm);
  min-width: 0;
  padding-left: var(--spacing-lg);
  border-left: 1px solid var(--color-border);
}

.order-title-line {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.order-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0 var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.order-meta > span + span::before {
  content: '·';
  margin-right: var(--spacing-sm);
}

.order-no {
  font-family: var(--font-family-mono);
}

.order-side {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.order-action {
  padding: 11px 28px;
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

.order-action:hover {
  background-color: var(--color-primary-dark);
}

@media (max-width: 991px) {
  .page-header {
    flex-wrap: wrap;
  }

  .search-box {
    flex: 1 1 100%;
  }

  .order-card {
    grid-template-columns: minmax(0, 1fr) auto;
    row-gap: var(--spacing-md);
  }

  .order-main {
    grid-column: 1 / -1;
    padding-left: 0;
    border-left: none;
  }

  .order-side {
    align-self: end;
  }
}
</style>
