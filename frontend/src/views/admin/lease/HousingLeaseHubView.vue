<script setup lang="ts">
import { computed } from 'vue'
import LivePanel from '@/common/web/components/LivePanel.vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/common/auth/store/user'
import { useAdminTodoStore } from '@/store/adminTodo'
import ResidenceApplicationListView from '@/views/admin/resident/ResidenceApplicationListView.vue'
import LeaseListView from './LeaseListView.vue'
import HousingManageView from '@/views/admin/housing/HousingManageView.vue'
import MoveOutApplicationView from './MoveOutApplicationView.vue'
import AgreementTemplateView from './AgreementTemplateView.vue'

/**
 * 管理端「租住与房源」Hub（批次 g 导航合并）：把原「租住」「房源」「退租申请」
 * 「协议模板」四个一级菜单项与散落在「居民」页内的入住申请审核收口为一个五 Tab 容器，
 * 侧栏只留本项，消除同一模块被拆到多个页面的碎片化。
 *
 * 约束（与居民端 HousingLeaseHubView 同构）：
 * - Tab 状态写 URL query（`?tab=applications|leases|housings|moveout|agreements`），
 *   缺省与非法取值一律回落 `applications`（审核待办优先于台账浏览）；
 * - 旧路径 /admin/leases、/admin/housings、/admin/move-out-applications、
 *   /admin/agreement-templates、/admin/residence-applications 由路由函数式 redirect
 *   透传 query 落到本页对应 Tab；`/admin/leases/:id` 是租约详情页，不经本页，不得被重定向吞掉；
 * - 子视图 v-show 保持挂载：保留筛选与展开状态；激活时重新读取列表
 *   （协议模板面板例外：叠加角色可见性 v-if——不可见角色不挂载，避免 403 报错 toast）；
 * - 页标题由子视图各自承担（「入住申请」「租住管理」「房源管理」「退租申请」「协议模板」），
 *   本页只提供 Tab 条，不出现两层重复大标题；
 * - 协议模板 Tab 仅 SUPER_ADMIN/ADMIN 可见（与侧栏 roles 过滤、后端 @PreAuthorize 同口径）：
 *   角色不可见时该取值按非法处理回落，手工拼 query 也无法进入；
 * - 子视图 query 不占用 Hub 的 `tab` 命名空间（租约分组用 `stage`、房源分区用 `view`）：
 *   子视图切换时展开 `...route.query` 保住 Hub 的 tab，Hub 切 Tab 时同样展开 `...route.query`
 *   保住子视图分组（原实现整体覆盖 query 会把 `stage`/`view` 冲掉，由组件测试发现后修复）。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const todos=useAdminTodoStore()

type HubTab = 'applications' | 'leases' | 'housings' | 'moveout' | 'agreements'

/** Tab 顺序即业务流顺序：申请审核 → 租约 → 房屋房源 → 退租 → 协议模板 */
const TAB_ITEMS: Array<{ value: HubTab; label: string; desc: string; roles?: string[] }> = [
  { value: 'applications', label: '入住申请', desc: '入住申请审核 · 通过后落待签约租约并建群聊' },
  { value: 'leases', label: '租约管理', desc: '租约台账 · 协议确认 · 首期生效 · 续退租' },
  { value: 'housings', label: '房源管理', desc: '房屋与房源一体化 · 挂牌上下架 · 看房预约' },
  { value: 'moveout', label: '退租申请', desc: '居民自助退租申请审核（R70）' },
  { value: 'agreements', label: '协议模板', desc: '租赁协议模板与附件备案（R64）', roles: ['SUPER_ADMIN', 'ADMIN'] }
]

/** 角色过滤后的可见 Tab（超管独占项对其它角色不可见，UI设计.md §10.1 同口径） */
const visibleTabs = computed(() =>
  TAB_ITEMS.filter((item) => !item.roles || item.roles.includes(userStore.role))
)

const activeTab = computed<HubTab>(() => {
  const tab = route.query.tab
  const hit = visibleTabs.value.find((item) => item.value === tab)
  return hit ? hit.value : 'applications'
})

function switchTab(next: HubTab): void {
  if (next === activeTab.value) return
  /* replace 避免来回切换污染历史栈；**展开既有 query**：子视图的分组是 query 驱动的
     （租约管理读 `stage`、房源管理读 `view`），Hub 切 Tab 时冲掉它们会让子视图回落默认分组，
     故只覆盖 `tab` 一个键，其余子视图参数原样保留 */
  router.replace({ query: { ...route.query, tab: next } })
}
</script>

<template>
  <div class="admin-page">
    <!-- Hub 级 Tab 条：替代原四个碎片化一级菜单项，观感沿用管理端白卡下划线容器 -->
    <nav class="tab-bar" role="tablist" aria-label="租住与房源分区">
      <button
        v-for="item in visibleTabs"
        :key="item.value"
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === item.value }"
        :aria-selected="activeTab === item.value"
        :title="item.desc"
        @click="switchTab(item.value)"
      >
        {{ item.label }}<span v-if="(todos.counts[item.value]??0)>0" class="todo-dot" :title="`${todos.counts[item.value]}项待处理`" :aria-label="`${todos.counts[item.value]}项待处理`" />
      </button>
    </nav>

    <!-- 子视图保持挂载：保留筛选与展开状态；激活时重新读取列表 -->
    <LivePanel :active="activeTab === 'applications'" class="hub-panel"><ResidenceApplicationListView /></LivePanel>
    <LivePanel :active="activeTab === 'leases'" class="hub-panel"><LeaseListView /></LivePanel>
    <LivePanel :active="activeTab === 'housings'" class="hub-panel"><HousingManageView /></LivePanel>
    <LivePanel :active="activeTab === 'moveout'" class="hub-panel"><MoveOutApplicationView /></LivePanel>
    <!-- 协议模板面板加角色门控 v-if（OCR DEF-123）：角色不可见即不挂载——
         原纯 v-show 常挂载会随每次 Hub 访问请求协议模板接口，对无权角色触发 403 报错 toast -->
    <LivePanel
      v-if="visibleTabs.some((item) => item.value === 'agreements')"
      :active="activeTab === 'agreements'"
      class="hub-panel"
    >
      <AgreementTemplateView />
    </LivePanel>
  </div>
</template>

<style scoped>
.todo-dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#dc3545;margin-left:7px;vertical-align:middle}
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

.hub-panel {
  min-height: 240px;
}
</style>
