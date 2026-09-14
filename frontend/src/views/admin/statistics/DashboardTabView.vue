<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import DashboardOverview from '@/views/admin/statistics/DashboardOverview.vue'
import StatisticsView from '@/views/admin/statistics/StatisticsView.vue'
import { getCommunityOptions } from '@/api/statistics'
import type { ICommunityOption } from '@/types/modules/statistics'
import { useUserStore } from '@/store/user'

/**
 * 运营看板：Tab 深链 ?tab=overview|details（任务 1 契约，取值不得变更）。
 * 概览 Tab 对照设计稿 02-运营看板重排（DashboardOverview）；
 * 详细统计 Tab 原样嵌入 StatisticsView（数据绑定/筛选/图表零删减）。
 */

const TABS = ['overview', 'details'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeTab = computed<TabName>(() => {
  const tab = route.query.tab
  return typeof tab === 'string' && (TABS as readonly string[]).includes(tab)
    ? (tab as TabName)
    : TABS[0]
})

/* Tab 切换走 query 替换：?tab= 深链可直达、可刷新、不产生历史记录 */
function switchTab(tab: TabName): void {
  if (tab === activeTab.value) return
  router.replace({ query: { ...route.query, tab } })
}

/* ---------- 社区筛选（P2 既有能力沿用，不下拉造假）：
   SUPER_ADMIN 跨社区切换；ADMIN 仅绑定社区（后端数据级权限保证），
   默认选中首个绑定社区使卡片与图表带社区过滤口径。
   筛选仅作用于概览 Tab——详细统计的三个专项接口未接社区参数（StatisticsView 零改动），
   故 details Tab 下隐藏筛选，避免「控件不生效」的假交互 ---------- */

const communityOptions = ref<ICommunityOption[]>([])
const selectedCommunityId = ref<number | null>(null)
/* 等下拉口径就绪再挂概览面板，避免 ADMIN 首屏按全量口径多拉一次 */
const filterReady = ref(false)
const isSuperAdmin = computed(() => userStore.role === 'SUPER_ADMIN')

onMounted(async () => {
  try {
    communityOptions.value = await getCommunityOptions()
    if (!isSuperAdmin.value && communityOptions.value.length > 0) {
      selectedCommunityId.value = communityOptions.value[0].id
    }
  } catch {
    /* 下拉加载失败不阻塞看板，仍按全量口径展示 */
  } finally {
    filterReady.value = true
  }
})
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="运营看板" subtitle="全社区运营数据总览">
      <el-select
        v-if="activeTab === 'overview'"
        v-model="selectedCommunityId"
        clearable
        placeholder="全部社区"
        class="community-filter"
        aria-label="社区筛选"
      >
        <el-option
          v-for="community in communityOptions"
          :key="community.id"
          :label="community.name"
          :value="community.id"
        />
      </el-select>
    </AdminPageHeader>

    <!-- 白卡 Tab 条：观感与 staff 工单列表一致（激活蓝字 + 底部 2px 下划线） -->
    <nav class="tab-bar" role="tablist" aria-label="运营看板视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'overview' }"
        :aria-selected="activeTab === 'overview'"
        @click="switchTab('overview')"
      >
        概览
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'details' }"
        :aria-selected="activeTab === 'details'"
        @click="switchTab('details')"
      >
        详细统计
      </button>
    </nav>

    <!-- :key 绑定社区筛选：切换/清空即重挂载概览面板整体刷新 -->
    <DashboardOverview
      v-if="filterReady && activeTab === 'overview'"
      :key="String(selectedCommunityId)"
      :community-id="selectedCommunityId"
    />
    <StatisticsView v-else-if="activeTab === 'details'" />
  </div>
</template>

<style scoped>
.community-filter {
  width: 200px;
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
</style>
