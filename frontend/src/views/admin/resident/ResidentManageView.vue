<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import ResidentListView from './ResidentListView.vue'
import ResidenceApplicationListView from './ResidenceApplicationListView.vue'
import ResidenceRelationListView from './ResidenceRelationListView.vue'
import { getResidenceApplicationList, getResidentList } from '@/api/resident'

/**
 * 居民管理容器：Tab 深链 ?tab=list|applications|relations（任务 1 契约，取值不得变更）。
 * 对照设计稿 04-居民管理：页头 + 3 统计卡 + 白卡 Tab 条（入住申请带待审核角标）+
 * 三列表 Tab。审批动作后经 reviewed 事件刷新统计卡与角标。
 */

const TABS = ['list', 'applications', 'relations'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const router = useRouter()

const activeTab = computed<TabName>(() => {
  const tab = route.query.tab
  return typeof tab === 'string' && (TABS as readonly string[]).includes(tab)
    ? (tab as TabName)
    : TABS[0]
})

/* Tab 切换走 query 替换：?tab= 深链可直达、可刷新、不产生历史记录；
   切换时顺带刷新统计卡（冻结/审批等动作可能已改变口径） */
function switchTab(tab: TabName): void {
  if (tab === activeTab.value) return
  router.replace({ query: { ...route.query, tab } })
  loadStats()
}

/* ---------- 统计卡（真实口径）：
   居民总数 = /residents 全量 total；待审核申请 = status=PENDING total；
   设计稿第三卡「本月新增」因后端无时间参数接口不造假 → 换真实指标「已冻结账号」 ---------- */

const ICONS = {
  users: [
    'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2',
    'M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
    'M23 21v-2a4 4 0 0 0-3-3.87',
    'M16 3.13a4 4 0 0 1 0 7.75'
  ],
  doc: [
    'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z',
    'M14 2v6h6',
    'M16 13H8',
    'M16 17H8'
  ],
  lock: [
    'M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z',
    'M7 11V7a5 5 0 0 1 10 0v4'
  ]
}

const residentTotal = ref(0)
const pendingTotal = ref(0)
const frozenTotal = ref(0)

const statCards = computed(() => [
  { label: '居民总数', value: residentTotal.value, icon: ICONS.users, accent: 'primary' as const },
  { label: '待审核申请', value: pendingTotal.value, icon: ICONS.doc, accent: 'warning' as const },
  { label: '已冻结账号', value: frozenTotal.value, icon: ICONS.lock, accent: 'danger' as const }
])

async function loadStats(): Promise<void> {
  try {
    const [all, pending, frozen] = await Promise.all([
      getResidentList({ page: 1, size: 1 }),
      getResidenceApplicationList({ page: 1, size: 1, status: 'PENDING' }),
      getResidentList({ page: 1, size: 1, status: 'FROZEN' })
    ])
    residentTotal.value = all.total
    pendingTotal.value = pending.total
    frozenTotal.value = frozen.total
  } catch {
    /* 统计加载失败不阻塞列表，保留当前值 */
  }
}

/* ---------- 跨 Tab 联动：列表「居住关系」→ 关系 Tab 并预选该居民 ---------- */

const presetResidentId = ref<number | undefined>(undefined)

function goRelations(residentId: number): void {
  presetResidentId.value = residentId
  if (activeTab.value !== 'relations') {
    router.replace({ query: { ...route.query, tab: 'relations' } })
  }
}

onMounted(loadStats)
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="居民管理" />

    <!-- 统计卡：真实口径（见 script 决策注释） -->
    <div class="stat-row">
      <AdminStatCard
        v-for="card in statCards"
        :key="card.label"
        :label="card.label"
        :value="card.value"
        :icon="card.icon"
        :accent="card.accent"
      />
    </div>

    <!-- 白卡 Tab 条：观感与运营看板/社区结构一致（激活蓝字 + 底部 2px 下划线） -->
    <nav class="tab-bar" role="tablist" aria-label="居民管理视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'list' }"
        :aria-selected="activeTab === 'list'"
        @click="switchTab('list')"
      >
        居民列表
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'applications' }"
        :aria-selected="activeTab === 'applications'"
        @click="switchTab('applications')"
      >
        入住申请
        <span v-if="pendingTotal > 0" class="tab-badge">{{ pendingTotal }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'relations' }"
        :aria-selected="activeTab === 'relations'"
        @click="switchTab('relations')"
      >
        居住关系
      </button>
    </nav>

    <ResidentListView v-if="activeTab === 'list'" @go-relations="goRelations" />
    <ResidenceApplicationListView v-else-if="activeTab === 'applications'" @reviewed="loadStats" />
    <ResidenceRelationListView v-else :preset-resident-id="presetResidentId" />
  </div>
</template>

<style scoped>
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}

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

/* 待审核数角标：语义 soft 色对，随统计自动消隐 */
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

@media (max-width: 767px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
