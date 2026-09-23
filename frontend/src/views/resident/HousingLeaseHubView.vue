<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LeaseListView from './LeaseListView.vue'
import HousingListView from './HousingListView.vue'
import ResidenceApplicationListView from './ResidenceApplicationListView.vue'

/**
 * 租住与房源 Hub（批次 f 导航合并 + 批次 g 补入住申请）：把原「我的租约」与「房源」
 * 两个菜单项收口为一个容器，批次 g 再增「入住申请」Tab，居民端导航项数 9 → 8 不变。
 *
 * 约束：
 * - Tab 状态写 URL query（`?tab=lease|housing|application`）而非组件内部状态，深链可分享、可刷新；
 *   旧路径 /resident/leases、/resident/housings 由路由函数式 redirect 透传 query 落到本页；
 * - 缺省与非法取值回落租住 Tab（租住事务优先级高于房源浏览）；
 * - 页标题由子视图各自承担（「我的租约」/「在租房源」/「入住申请」），本页只提供 Tab 条，
 *   避免同一内容出现两层重复大标题；
 * - 三个子视图 v-show 保持挂载：Tab 往返不重复拉取接口，也保留用户的筛选与展开状态；
 * - 子视图查询参数（leaseId / fromLease）原样透传，Hub 不改写子视图 query 契约。
 */

const route = useRoute()
const router = useRouter()

type HubTab = 'lease' | 'housing' | 'application'

/** 缺省与非法取值一律回落租住 Tab（租住事务优先级高于房源浏览） */
const activeTab = computed<HubTab>(() => {
  const tab = route.query.tab
  return tab === 'housing' || tab === 'application' ? tab : 'lease'
})

const TABS: Array<{ value: HubTab; label: string; desc: string }> = [
  { value: 'lease', label: '租住契约', desc: '我的租约 · 协议确认 · 续租与退租' },
  { value: 'housing', label: '房源信息', desc: '浏览在租房源 · 看房预约 · 申请租住' },
  { value: 'application', label: '入住申请', desc: '我的入住申请进度 · 待审核可撤回' }
]

/**
 * 会话内「预约看房」跳转上下文（?fromLease=租约ID）：只在房源 Tab 顶部提示本次
 * 看房归属哪份租约，三步预约流程仍由房源详情页承载（不重复实现第二套日历/时段选择）。
 */
const fromLeaseId = computed(() => {
  const value = Number(route.query.fromLease)
  return Number.isInteger(value) && value > 0 ? value : null
})

function switchTab(next: HubTab): void {
  if (next === activeTab.value) return
  /* replace 避免来回切换污染历史栈；leaseId 属租住 Tab 语境，切到房源时清除 */
  const query: Record<string, string> = { tab: next }
  if (next === 'lease' && typeof route.query.leaseId === 'string') {
    query.leaseId = route.query.leaseId
  }
  if (typeof route.query.fromLease === 'string') {
    query.fromLease = route.query.fromLease
  }
  router.replace({ query })
}
</script>

<template>
  <section class="housing-lease-hub">
    <!-- Hub 级 Tab 条：替代原独立菜单项，文案带职责说明避免只靠两词猜内容 -->
    <nav class="hub-tabs" role="tablist" aria-label="租住与房源分区">
      <button
        v-for="item in TABS"
        :key="item.value"
        type="button"
        role="tab"
        class="hub-tab"
        :aria-selected="activeTab === item.value"
        :class="{ active: activeTab === item.value }"
        @click="switchTab(item.value)"
      >
        <span class="hub-tab-label">{{ item.label }}</span>
        <span class="hub-tab-desc">{{ item.desc }}</span>
      </button>
    </nav>

    <!-- 子视图保持挂载：Tab 往返不重复拉取，保留筛选与展开状态 -->
    <div v-show="activeTab === 'lease'" class="hub-panel">
      <LeaseListView />
    </div>
    <div v-show="activeTab === 'housing'" class="hub-panel">
      <!-- 会话内「预约看房」落地提示：三步预约由房源详情页承载 -->
      <p v-if="fromLeaseId !== null" class="from-lease-hint">
        正在为租约（ID {{ fromLeaseId }}）预约看房：从下方选择目标房源进入详情后点击「预约看房」。
      </p>
      <HousingListView />
    </div>
    <div v-show="activeTab === 'application'" class="hub-panel">
      <ResidenceApplicationListView />
    </div>
  </section>
</template>

<style scoped>
.housing-lease-hub {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* Hub Tab：卡片式分段，激活态主色描边 + 浅底，与居民端整体语言一致 */
.hub-tabs {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.hub-tab {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 220px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.hub-tab:hover {
  border-color: var(--color-primary-light);
}

.hub-tab.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
}

.hub-tab-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.hub-tab.active .hub-tab-label {
  color: var(--color-primary);
}

.hub-tab-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.hub-panel {
  min-height: 240px;
}

/* 会话内预约看房的上下文提示（?fromLease=） */
.from-lease-hint {
  margin: 0 0 var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: #e6f6f8;
  color: #0e7490;
  font-size: var(--font-size-sm);
}
</style>
