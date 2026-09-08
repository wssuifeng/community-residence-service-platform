<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import { listHousings } from '@/api/housing'
import { getCommunityList } from '@/api/community'
import type { IHousing, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import type { ICommunity } from '@/types/modules/community'

/** 房源浏览（居民端）：卡片网格（4:3 图片、空图用示例图轮换）+ 筛选 + 分页 */

const housingTagTypeMap: Record<HousingStatus, 'completed' | 'pending' | 'processing' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'processing',
  OFFLINE: 'canceled'
}

const communities = ref<ICommunity[]>([])

const query = reactive({
  page: 1,
  size: 12,
  keyword: '',
  communityId: undefined as number | undefined,
  minRent: undefined as number | undefined,
  maxRent: undefined as number | undefined
})

const total = ref(0)
const records = ref<IHousing[]>([])
const loading = ref(false)

/** 空图房源用内置示例图轮换（/public/images/housing-sample-{1..3}.png） */
function coverImage(housing: IHousing, index: number): string {
  if (housing.images && housing.images.length > 0) return housing.images[0]
  return `/images/housing-sample-${(index % 3) + 1}.png`
}

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 100 })
    communities.value = result.records
  } catch {
    /* 社区筛选加载失败不阻塞房源列表 */
  }
}

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listHousings({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      communityId: query.communityId,
      minRent: query.minRent,
      maxRent: query.maxRent
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.page = 1
  loadList()
}

function handleReset(): void {
  query.keyword = ''
  query.communityId = undefined
  query.minRent = undefined
  query.maxRent = undefined
  query.page = 1
  loadList()
}

onMounted(() => {
  loadCommunities()
  loadList()
})
</script>

<template>
  <section class="housing-list">
    <header class="page-head">
      <h1>房源浏览</h1>
      <p class="page-head-sub">挑选心仪的房源，预约时间实地看房</p>
    </header>

    <FilterPanel resettable @reset="handleReset">
      <SearchBar v-model="query.keyword" placeholder="搜索房源标题/地址" @search="handleSearch" />
      <el-select
        v-model="query.communityId"
        placeholder="全部社区"
        clearable
        style="width: 180px"
        @change="query.page = 1; loadList()"
      >
        <el-option
          v-for="community in communities"
          :key="community.id"
          :value="community.id"
          :label="community.name"
        />
      </el-select>
      <el-input-number
        v-model="query.minRent"
        :min="0"
        :controls="false"
        placeholder="最低租金"
        style="width: 110px"
        @change="query.page = 1; loadList()"
      />
      <span class="rent-separator">—</span>
      <el-input-number
        v-model="query.maxRent"
        :min="0"
        :controls="false"
        placeholder="最高租金"
        style="width: 110px"
        @change="query.page = 1; loadList()"
      />
    </FilterPanel>

    <div v-loading="loading" class="grid-body">
      <div v-if="!loading && records.length === 0" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无房源" />
        <p>暂无符合条件的房源，换个条件试试</p>
      </div>

      <router-link
        v-for="(housing, index) in records"
        v-else
        :key="housing.id"
        :to="`/resident/housings/${housing.id}`"
        class="housing-card"
      >
        <div class="card-cover">
          <img :src="coverImage(housing, index)" :alt="housing.title" loading="lazy" />
          <StatusTag
            class="card-status"
            :label="housingStatusLabels[housing.status]"
            :type="housingTagTypeMap[housing.status]"
          />
        </div>
        <div class="card-body">
          <h2 class="card-title">{{ housing.title }}</h2>
          <p class="card-location">{{ housing.communityName }} · {{ housing.houseAddress }}</p>
          <div class="card-tags">
            <span v-for="tag in (housing.tags ?? []).slice(0, 3)" :key="tag" class="tag-chip">{{ tag }}</span>
          </div>
          <div class="card-footer">
            <span class="card-rent">
              <em>￥{{ housing.monthlyRent }}</em>/月
            </span>
            <span class="card-views">{{ housing.viewCount }} 次浏览</span>
          </div>
        </div>
      </router-link>
    </div>

    <Pagination
      v-model:page="query.page"
      v-model:size="query.size"
      :total="total"
      @update:page="loadList"
      @update:size="loadList"
    />
  </section>
</template>

<style scoped>
.page-head {
  margin-bottom: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.rent-separator {
  color: var(--color-text-disabled);
}

.grid-body {
  min-height: 320px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--spacing-md);
}

.empty-state {
  grid-column: 1 / -1;
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xxl) var(--spacing-lg);
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-state img {
  width: 120px;
  margin: 0 auto var(--spacing-md);
}

.housing-card {
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.housing-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-lg);
}

/* 图片 4:3 卡片封面 */
.card-cover {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background-color: var(--color-bg);
}

.card-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.housing-card:hover .card-cover img {
  transform: scale(1.05);
}

.card-status {
  position: absolute;
  top: var(--spacing-sm);
  right: var(--spacing-sm);
  box-shadow: var(--shadow-sm);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  flex: 1;
}

.card-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-location {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.tag-chip {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

.card-footer {
  margin-top: auto;
  padding-top: var(--spacing-sm);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.card-rent {
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}

.card-rent em {
  font-style: normal;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.card-views {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
