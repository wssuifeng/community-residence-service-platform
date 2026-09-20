<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StructureTreePane from '@/views/admin/community/StructureTreePane.vue'
import HousesWithHousingView from '@/views/admin/community/HousesWithHousingView.vue'
import HouseListView from '@/views/admin/community/HouseListView.vue'
import PublicResourceListView from '@/views/admin/community/PublicResourceListView.vue'

/**
 * 社区结构容器：Tab 深链 ?tab=tree|housing|houses|resources（任务 1 契约，取值不得变更）。
 * 结构总览 Tab 对照设计稿 01-社区结构重排（StructureTreePane）；
 * 房屋与房源 Tab 为 R62 房源挂牌一体化视图（HousesWithHousingView，行内展开树 + 批量挂牌）；
 * 房屋管理 / 公共资源 Tab 原样嵌入既有列表视图（数据绑定/筛选/CRUD 零删减，仅外壳统一）。
 */

const TABS = ['tree', 'housing', 'houses', 'resources'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const router = useRouter()

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
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="社区结构" subtitle="楼栋 · 单元 · 房屋 · 公共资源一体化管理" />

    <!-- 白卡 Tab 条：观感与运营看板一致（激活蓝字 + 底部 2px 下划线） -->
    <nav class="tab-bar" role="tablist" aria-label="社区结构视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'tree' }"
        :aria-selected="activeTab === 'tree'"
        @click="switchTab('tree')"
      >
        结构总览
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
        :class="{ active: activeTab === 'houses' }"
        :aria-selected="activeTab === 'houses'"
        @click="switchTab('houses')"
      >
        房屋管理
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
    <!-- 房屋/公共资源：原视图换壳内嵌（白卡容器在此统一，视图内仅去重复页头） -->
    <div v-else-if="activeTab === 'houses'" class="tab-pane-card">
      <HouseListView />
    </div>
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
