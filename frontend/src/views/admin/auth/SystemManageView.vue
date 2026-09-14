<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import SysUserListView from './SysUserListView.vue'
import GlobalConfigView from './GlobalConfigView.vue'
import OperationLogListView from './OperationLogListView.vue'
import { useUserStore } from '@/store/user'

/**
 * 系统管理容器（对照 design-mockups/admin/11-系统管理，任务 12）：
 * Tab 深链 ?tab=users|configs|logs（任务 1 契约，取值不得变更）。
 * 路由不加 roles：操作日志对 ADMIN 开放（后端 hasAnyRole('ADMIN','SUPER_ADMIN')）；
 * 系统用户/全局配置两个 Tab 对 ADMIN 渲染空态（后端 SUPER_ADMIN 专属），Tab 条不隐藏可切换。
 */
const TABS = ['users', 'configs', 'logs'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isSuperAdmin = computed(() => userStore.role === 'SUPER_ADMIN')

const activeTab = computed<TabName>(() => {
  const tab = route.query.tab
  return typeof tab === 'string' && (TABS as readonly string[]).includes(tab)
    ? (tab as TabName)
    : TABS[0]
})

/* Tab 切换走 query 替换（居民管理容器同款）：?tab= 深链可直达、可刷新、不产生历史记录 */
function switchTab(tab: TabName): void {
  if (tab === activeTab.value) return
  router.replace({ query: { ...route.query, tab } })
}
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="系统管理" subtitle="仅超级管理员可访问用户与配置" />

    <!-- 白卡 Tab 条：观感与居民管理/运营看板一致（激活蓝字 + 底部 2px 下划线） -->
    <nav class="tab-bar" role="tablist" aria-label="系统管理视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'users' }"
        :aria-selected="activeTab === 'users'"
        @click="switchTab('users')"
      >
        系统用户
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'configs' }"
        :aria-selected="activeTab === 'configs'"
        @click="switchTab('configs')"
      >
        全局配置
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'logs' }"
        :aria-selected="activeTab === 'logs'"
        @click="switchTab('logs')"
      >
        操作日志
      </button>
    </nav>

    <!-- 系统用户 / 全局配置仅超管；ADMIN 视角渲染空态（不落 403），操作日志对 ADMIN 开放 -->
    <template v-if="activeTab === 'users'">
      <SysUserListView v-if="isSuperAdmin" />
      <div v-else class="tab-empty-card">
        <el-empty description="系统用户仅超级管理员可见" :image-size="90" />
      </div>
    </template>
    <template v-else-if="activeTab === 'configs'">
      <GlobalConfigView v-if="isSuperAdmin" />
      <div v-else class="tab-empty-card">
        <el-empty description="全局配置仅超级管理员可见" :image-size="90" />
      </div>
    </template>
    <OperationLogListView v-else />
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

/* ADMIN 视角空态白卡（与内容 Tab 同层级容器观感） */
.tab-empty-card {
  background: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-xxl) var(--spacing-lg);
}
</style>
