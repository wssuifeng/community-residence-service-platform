<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import HousesWithHousingView from '@/views/admin/community/HousesWithHousingView.vue'
import HousingListView from './HousingListView.vue'
import ViewingAppointmentListView from './ViewingAppointmentListView.vue'
import { listHousings, listViewingAppointments } from '@/api/housing'

/**
 * 房源管理（「租住与房源」Hub 的房源管理子视图，批次 g）：页内三分区
 * ?view=houses|viewings|list（缺省 houses）。
 * 主体为 R62 的「房屋与房源」一体化视图（挂牌/编辑/上下架 + 批量挂牌）；
 * 看房预约处置与跨社区「全局房源」总览为同模块的另两个分区，避免同模块再拆页面。
 * 对照设计稿 09-房源管理：页头 + 4 统计卡 + 白卡 Tab 条（看房预约 Tab 带待确认角标）。
 * 稿例「在租/待租/已租/本月看房」为 AI 即兴，统计卡按真实状态枚举
 * （可租/已预订/已出租）取计数，第四卡按简报取看房预约累计（接口无时间参数不造假）；
 * 已下架不占卡位，可经列表状态筛选查看。子视图动作后经 changed 事件刷新统计与角标。
 */

const TABS = ['houses', 'viewings', 'list'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const router = useRouter()

/* ?view= 深链契约（houses|viewings|list）：缺省与非法取值回退 houses（一体化挂牌为房源管理主体）。
   参数名用 view 而非 tab，是为让出 Hub（/admin/housing-lease）的 `tab` 命名空间——
   子视图切换时展开 route.query，Hub 的 tab 不被冲掉 */
const activeTab = computed<TabName>(() => {
  const view = route.query.view
  return typeof view === 'string' && (TABS as readonly string[]).includes(view)
    ? (view as TabName)
    : TABS[0]
})

/* Tab 切换走 query 替换：?view= 深链可直达、可刷新、不产生历史记录；
   切换时顺带刷新统计卡（动作可能已改变口径） */
function switchTab(view: TabName): void {
  if (view === activeTab.value) return
  router.replace({ query: { ...route.query, view } })
  loadStats()
}

/** R62：一体化挂牌管理入口（社区结构 → 房屋与房源 Tab） */
function goCommunityHousing(): void {
  void router.push({ name: 'AdminCommunity', query: { tab: 'housing' } })
}

/* ---------- 统计卡（真实口径）：状态计数走房源列表 size=1 查询 total；
   看房预约为累计 total；待确认数用于 Tab 角标 ---------- */

const ICONS = {
  house: ['M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', 'M9 22V12h6v10'],
  clock: ['M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z', 'M12 6v6l4 2'],
  key: [
    'M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4'
  ],
  eye: ['M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z']
}

const availableTotal = ref(0)
const reservedTotal = ref(0)
const rentedTotal = ref(0)
const viewingTotal = ref(0)
const toConfirmTotal = ref(0)

const statCards = computed(() => [
  { label: '可租房源', value: availableTotal.value, icon: ICONS.house, accent: 'success' as const },
  { label: '已预订', value: reservedTotal.value, icon: ICONS.clock, accent: 'warning' as const },
  { label: '已出租', value: rentedTotal.value, icon: ICONS.key, accent: 'primary' as const },
  { label: '看房预约', value: viewingTotal.value, icon: ICONS.eye, accent: 'primary' as const }
])

async function loadStats(): Promise<void> {
  try {
    const [available, reserved, rented, viewings, toConfirm] = await Promise.all([
      listHousings({ page: 1, size: 1, status: 'AVAILABLE' }),
      listHousings({ page: 1, size: 1, status: 'RESERVED' }),
      listHousings({ page: 1, size: 1, status: 'RENTED' }),
      listViewingAppointments({ page: 1, size: 1 }),
      listViewingAppointments({ page: 1, size: 1, status: 'TO_CONFIRM' })
    ])
    availableTotal.value = available.total
    reservedTotal.value = reserved.total
    rentedTotal.value = rented.total
    viewingTotal.value = viewings.total
    toConfirmTotal.value = toConfirm.total
  } catch {
    /* 统计加载失败不阻塞列表，保留当前值 */
  }
}

onMounted(loadStats)
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader
      title="房源管理"
      subtitle="房屋与房源一体化挂牌 · 看房预约处置 · 全局房源总览"
    />

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

    <!-- 并入说明（R62）：仅「全局房源」分区展示，指向社区结构内同一体化挂牌管理 -->
    <div v-if="activeTab === 'list'" class="merged-notice">
      <span class="merged-notice-text">
        房源挂牌管理由「房屋与房源」分区与「社区结构 → 房屋与房源」共同承载：按 社区→楼栋→单元→房屋 树形完成挂牌/编辑/上下架与批量挂牌。本分区为跨社区「全局房源」总览视图（支持社区筛选），仍可进行详情、时段与删除操作。
      </span>
      <el-button type="primary" link class="merged-notice-link" @click="goCommunityHousing">
        前往社区结构 →
      </el-button>
    </div>

    <!-- 白卡 Tab 条：观感与居民管理/运营看板一致（激活蓝字 + 底部 2px 下划线） -->
    <nav class="tab-bar" role="tablist" aria-label="房源管理视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'houses' }"
        :aria-selected="activeTab === 'houses'"
        @click="switchTab('houses')"
      >
        房屋与房源
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'viewings' }"
        :aria-selected="activeTab === 'viewings'"
        @click="switchTab('viewings')"
      >
        看房预约
        <span v-if="toConfirmTotal > 0" class="tab-badge">{{ toConfirmTotal }}</span>
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'list' }"
        :aria-selected="activeTab === 'list'"
        @click="switchTab('list')"
      >
        全局房源
      </button>
    </nav>

    <HousesWithHousingView v-if="activeTab === 'houses'" />
    <ViewingAppointmentListView v-else-if="activeTab === 'viewings'" @changed="loadStats" />
    <HousingListView v-else @changed="loadStats" />
  </div>
</template>

<style scoped>
/* 并入说明条（R62）：浅品牌蓝底 + 右侧入口链接 */
.merged-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-lg);
  padding: var(--spacing-sm) var(--spacing-lg);
  background-color: var(--color-primary-bg);
  border-radius: var(--radius-lg);
}

.merged-notice-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.merged-notice-link {
  flex-shrink: 0;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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

/* 待确认数角标：语义 soft 色对，随统计自动消隐 */
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

@media (max-width: 1023px) {
  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 767px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
