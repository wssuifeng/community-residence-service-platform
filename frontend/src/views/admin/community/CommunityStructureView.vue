<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StructureTreePane from '@/views/admin/community/StructureTreePane.vue'
import HousesWithHousingView from '@/views/admin/community/HousesWithHousingView.vue'
import PublicResourceListView from '@/views/admin/community/PublicResourceListView.vue'

/**
 * 社区管理容器：Tab 深链 ?tab=tree|housing|resources。
 * 社区结构 Tab 为社区管理主体（StructureTreePane：社区筛选列表 + 批量管理 +
 * 选中社区的楼栋/单元/房屋三级浏览与批量建房）；房屋与房源 Tab 为
 * R62 房源挂牌一体化视图（HousesWithHousingView，行内展开树 + 批量挂牌）；
 * 公共资源 Tab 原样嵌入既有列表视图（数据绑定/筛选/CRUD 零删减，仅外壳统一）。
 *
 * 「房屋管理」（houses）原 Tab 于 2026-09-20 并入社区结构 Tab（房屋增删改、状态变更与
 * 变更历史、批量建房均在结构内闭环），不再作为独立入口暴露；取值 houses 作为已归档
 * Tab 的别名继续被接受（书签/外链/`/admin/houses` 旧路径 redirect 不断链），落点即
 * 社区结构 Tab，并在落地后把 URL 归一为 ?tab=tree（replace，不产生历史记录）。
 */

const TABS = ['tree', 'housing', 'resources'] as const
type TabName = (typeof TABS)[number]

/** 已归档 Tab 取值 → 现役面板：接受但不渲染独立面板 */
const LEGACY_TAB_ALIAS: Record<string, TabName> = { houses: 'tree' }

const route = useRoute()
const router = useRouter()

/* 原始 query 取值：仅用于识别旧深链并按需归一 URL，不参与渲染决策 */
const rawTab = computed(() => (typeof route.query.tab === 'string' ? route.query.tab : ''))

const activeTab = computed<TabName>(() => {
  const tab = rawTab.value
  if ((TABS as readonly string[]).includes(tab)) return tab as TabName
  return LEGACY_TAB_ALIAS[tab] ?? TABS[0]
})

/* 旧深链落地后归一地址：用户看到与复制的 URL 不再带已归档取值；
   其余 query（如 ?communityId=）原样保留，供结构树预选社区 */
watch(
  rawTab,
  (tab) => {
    const alias = LEGACY_TAB_ALIAS[tab]
    if (alias) {
      router.replace({ query: { ...route.query, tab: alias } })
    }
  },
  { immediate: true }
)

/* Tab 切换走 query 替换：?tab= 深链可直达、可刷新、不产生历史记录 */
function switchTab(tab: TabName): void {
  if (tab === activeTab.value) return
  router.replace({ query: { ...route.query, tab } })
}
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader
      title="社区管理"
      subtitle="社区、楼栋、单元、房屋一体化管理 · 支持批量建房与批量启用/停用/删除"
    />

    <!-- 白卡 Tab 条：观感与运营看板一致（激活蓝字 + 底部 2px 下划线） -->
    <nav class="tab-bar" role="tablist" aria-label="社区管理视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'tree' }"
        :aria-selected="activeTab === 'tree'"
        @click="switchTab('tree')"
      >
        社区结构
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'housing' }"
        :aria-selected="activeTab === 'housing'"
        @click="switchTab('housing')"
      >
        房屋与房源
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'resources' }"
        :aria-selected="activeTab === 'resources'"
        @click="switchTab('resources')"
      >
        公共资源
      </button>
    </nav>

    <StructureTreePane v-if="activeTab === 'tree'" />
    <!-- 房屋与房源（R62）：自带白卡面板的视图，直接挂载（不复用 tab-pane-card 外壳） -->
    <HousesWithHousingView v-else-if="activeTab === 'housing'" />
    <!-- 公共资源：原视图换壳内嵌（白卡容器在此统一，视图内仅去重复页头） -->
    <div v-else-if="activeTab === 'resources'" class="tab-pane-card">
      <PublicResourceListView />
    </div>
  </div>
</template>

<style scoped>
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

.tab-pane-card {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}
</style>
