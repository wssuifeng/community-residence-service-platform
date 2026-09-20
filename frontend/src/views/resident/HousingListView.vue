<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { listHousings } from '@/api/housing'
import { getCommunityList } from '@/api/community'
import type { ICommunity } from '@/types/modules/community'
import type { IHousing, HousingRentType, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'

/** 房源浏览（居民端）：与游客端列表同款——筛选条 + 卡片网格 + 分页；空图按序号轮换示例图 */

const housings = ref<IHousing[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const statusFilter = ref<HousingStatus | ''>('')
const rentRange = ref('')
const rentTypeFilter = ref<HousingRentType | ''>('')
const layoutFilter = ref('')
const communityFilter = ref(0)
const communityOptions = ref<ICommunity[]>([])
const loading = ref(false)

/** 状态筛选选项：'' 表示全部 */
const statusOptions: Array<{ label: string; value: HousingStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: housingStatusLabels.AVAILABLE, value: 'AVAILABLE' },
  { label: housingStatusLabels.RESERVED, value: 'RESERVED' },
  { label: housingStatusLabels.RENTED, value: 'RENTED' }
]

/** 租售类型选项：后端 RENT/SALE 白名单（V9，R53） */
const rentTypeOptions: Array<{ label: string; value: HousingRentType | '' }> = [
  { label: '全部类型', value: '' },
  { label: '出租', value: 'RENT' },
  { label: '出售', value: 'SALE' }
]

/** 户型选项：后端无枚举约束（house.layout 自由文本、精确匹配），选项取种子数据实际值；
 *  filterable + allow-create 支持输入列表外户型兜底（无匹配返回空集如实呈现） */
const layoutOptions = ['2室1厅1卫', '3室2厅2卫']

/** 租金区间选项：接口支持 minRent/maxRent，档位映射两个参数（朝向接口无参数，不做对应控件） */
const rentRangeOptions = [
  { label: '全部租金', value: '' },
  { label: '2000 以下', value: '-2000' },
  { label: '2000 - 3000', value: '2000-3000' },
  { label: '3000 - 4000', value: '3000-4000' },
  { label: '4000 以上', value: '4000-' }
]

/** 房源状态 → StatusTag 语义色（可租=绿 / 已预订=黄 / 已出租、已下架=灰） */
const statusTagType: Record<HousingStatus, 'completed' | 'pending' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'canceled',
  OFFLINE: 'canceled'
}

/** 图片兜底：images 为空时按列表序号取模轮换三张示例图 */
function coverImage(housing: IHousing, index: number): string {
  return housing.images[0] ?? `/images/housing-sample-${(index % 3) + 1}.png`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    /* 租金档位拆成 min/max 两个接口参数，空端表示不限 */
    const [rangeMin, rangeMax] = rentRange.value.split('-')
    const result = await listHousings({
      page: page.value,
      size: size.value,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      keyword: keyword.value === '' ? undefined : keyword.value,
      communityId: communityFilter.value > 0 ? communityFilter.value : undefined,
      minRent: rangeMin === '' ? undefined : Number(rangeMin),
      maxRent: rangeMax === '' || rangeMax === undefined ? undefined : Number(rangeMax),
      rentType: rentTypeFilter.value === '' ? undefined : rentTypeFilter.value,
      layout: layoutFilter.value === '' ? undefined : layoutFilter.value
    })
    housings.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '房源列表加载失败')
  } finally {
    loading.value = false
  }
}

/* 社区下拉选项：社区列表接口居民可访问，拉取失败降级为不过滤。
   R62 全社区开放要求筛选器覆盖全量社区：接口分页返回，翻页累加至 total（护栏 5 页防死循环） */
async function loadCommunities(): Promise<void> {
  try {
    const all: ICommunity[] = []
    let page = 1
    while (page <= 5) {
      const result = await getCommunityList({ page, size: 100 })
      all.push(...result.records)
      if (all.length >= result.total) break
      page += 1
    }
    communityOptions.value = all
  } catch {
    communityOptions.value = []
  }
}

/* 社区房源计数（R62：无房源社区在筛选器显示「0 套房源」提示）。
   列表接口无按社区计数端点，取全量房源（翻页累加，护栏 10 页）后前端聚合；
   计数失败降级为不显示计数后缀，不影响筛选行为 */
const communityHousingCounts = ref(new Map<number, number>())

async function loadCommunityHousingCounts(): Promise<void> {
  try {
    const counts = new Map<number, number>()
    let page = 1
    while (page <= 10) {
      const result = await listHousings({ page, size: 200 })
      for (const housing of result.records) {
        counts.set(housing.communityId, (counts.get(housing.communityId) ?? 0) + 1)
      }
      if (result.records.length === 0 || page * 200 >= result.total) break
      page += 1
    }
    communityHousingCounts.value = counts
  } catch {
    communityHousingCounts.value = new Map()
  }
}

/** 筛选器社区选项标签：计数可用时附「N 套房源」（0 套即无房源提示），失败降级纯社区名 */
function communityOptionLabel(community: ICommunity): string {
  const count = communityHousingCounts.value.get(community.id)
  return count === undefined ? community.name : `${community.name}（${count} 套房源）`
}

function handleSearch(): void {
  page.value = 1
  load()
}

function handleFilterChange(): void {
  page.value = 1
  load()
}

function handleSizeChange(): void {
  /* 每页条数变化后回到第一页，避免停留在越界页码 */
  page.value = 1
  load()
}

onMounted(() => {
  load()
  void loadCommunities()
  void loadCommunityHousingCounts()
})
</script>

<template>
  <div class="housing-list">
    <header class="list-head">
      <h1 class="list-title">在租房源</h1>
      <p class="list-sub">共 {{ total }} 套在售 · 挑选心仪的房源，预约时间实地看房</p>
    </header>

    <!-- 筛选工具条：白卡横条铺满整行；社区/状态/租金/租售/户型/关键字有接口参数，朝向无参数不做控件 -->
    <div class="filter-bar">
      <el-select
        v-model="communityFilter"
        class="filter-select"
        style="width: 210px"
        @change="handleFilterChange"
      >
        <template #prefix>
          <span class="filter-select-label">社区</span>
        </template>
        <el-option label="全部社区" :value="0" />
        <el-option
          v-for="community in communityOptions"
          :key="community.id"
          :label="communityOptionLabel(community)"
          :value="community.id"
        />
      </el-select>
      <el-select
        v-model="statusFilter"
        class="filter-select"
        style="width: 170px"
        @change="handleFilterChange"
      >
        <template #prefix>
          <span class="filter-select-label">状态</span>
        </template>
        <el-option
          v-for="option in statusOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-select
        v-model="rentRange"
        class="filter-select"
        style="width: 190px"
        placeholder="租金区间"
        @change="handleFilterChange"
      >
        <template #prefix>
          <span class="filter-select-label">租金</span>
        </template>
        <el-option
          v-for="option in rentRangeOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-select
        v-model="rentTypeFilter"
        class="filter-select"
        style="width: 150px"
        @change="handleFilterChange"
      >
        <template #prefix>
          <span class="filter-select-label">租售</span>
        </template>
        <el-option
          v-for="option in rentTypeOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-select
        v-model="layoutFilter"
        class="filter-select"
        style="width: 180px"
        filterable
        allow-create
        placeholder="户型"
        @change="handleFilterChange"
      >
        <template #prefix>
          <span class="filter-select-label">户型</span>
        </template>
        <el-option label="全部户型" value="" />
        <el-option
          v-for="layout in layoutOptions"
          :key="layout"
          :label="layout"
          :value="layout"
        />
      </el-select>
      <SearchBar v-model="keyword" placeholder="搜索小区 / 地址 / 标题" @search="handleSearch" />
      <button type="button" class="filter-btn" @click="handleSearch">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M22 3H2l8 9.46V19l4 2v-8.54L22 3z" />
        </svg>
        筛选
      </button>
    </div>

    <div v-loading="loading" class="grid">
      <router-link
        v-for="(housing, index) in housings"
        :key="housing.id"
        :to="`/resident/housings/${housing.id}`"
        class="housing-card"
      >
        <div class="housing-media">
          <img :src="coverImage(housing, index)" :alt="housing.title" loading="lazy" />
          <StatusTag
            class="housing-status"
            on-image
            :label="housingStatusLabels[housing.status]"
            :type="statusTagType[housing.status]"
          />
        </div>
        <div class="housing-body">
          <div class="housing-price-row">
            <p class="housing-rent">
              <span class="housing-rent-amount">¥{{ housing.monthlyRent }}</span>
              <span class="housing-rent-unit">/月</span>
            </p>
            <span class="housing-views">{{ housing.viewCount }} 次浏览</span>
          </div>
          <h3 class="housing-title">{{ housing.title }}</h3>
          <p class="housing-meta">{{ housing.communityName }} · {{ housing.houseLocation }}</p>
          <div v-if="(housing.tags?.length ?? 0) > 0" class="housing-tags">
            <span v-for="tag in housing.tags" :key="tag" class="housing-tag">{{ tag }}</span>
          </div>
        </div>
      </router-link>

      <!-- 空状态：示例图 + 引导文案，而非一行灰字 -->
      <div v-if="!loading && housings.length === 0" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无房源" />
        <h3>没有找到符合条件的房源</h3>
        <p>换个关键字或条件试试，或者稍后再来看看——新房源会持续上架。</p>
      </div>
    </div>

    <Pagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      @update:page="load"
      @update:size="handleSizeChange"
    />
  </div>
</template>

<style scoped>
/* 页头：大标题 + 副标题（贴页面背景） */
.list-head {
  margin-bottom: var(--spacing-lg);
}

.list-title {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.list-sub {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* 筛选工具条：白卡横条 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-lg);
  padding: var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.filter-bar :deep(.search-bar) {
  flex: 1;
  min-width: 200px;
}

/* 下拉前缀标签：label + 控件组合感 */
.filter-select-label {
  padding-right: var(--spacing-xs);
  margin-right: var(--spacing-xs);
  border-right: 1px solid var(--color-border);
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

/* 筛选按钮：主色实心 + 漏斗图标（与搜索同触发逻辑） */
.filter-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition: background 0.15s ease;
}

.filter-btn:hover {
  background: var(--color-primary-dark);
}

.filter-btn svg {
  width: 15px;
  height: 15px;
}

/* 卡片网格：宽屏 3 列 → 中屏 2 列 → 窄屏 1 列 */
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-md);
  min-height: 200px;
}

.housing-card {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.housing-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

/* 16:9 摄影封面 */
.housing-media {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
}

.housing-media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.housing-status {
  position: absolute;
  top: var(--spacing-sm);
  left: var(--spacing-sm);
  z-index: 1;
}

.housing-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  flex: 1;
}

.housing-price-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.housing-rent-amount {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.housing-rent-unit {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.housing-views {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.housing-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.housing-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.housing-tags {
  display: flex;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
  margin-top: var(--spacing-xs);
}

.housing-tag {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

/* 空状态横跨整行 */
.empty-state {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xxl) var(--spacing-md);
  background: #fff;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  text-align: center;
}

.empty-state img {
  width: 180px;
}

.empty-state h3 {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.empty-state p {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

@media (max-width: 1024px) {
  .grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
