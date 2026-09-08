<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { listHousings } from '@/api/housing'
import type { IHousing, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'

/** 房源列表（公开）：卡片网格 + 关键字/状态筛选 + 分页；无图房源按序号轮换示例图 */

const housings = ref<IHousing[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const statusFilter = ref<HousingStatus | ''>('')
const loading = ref(false)

/** 状态筛选选项：'' 表示全部（接口无房型参数，房型信息经 tags 展示、经关键字匹配） */
const statusOptions: Array<{ label: string; value: HousingStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: housingStatusLabels.AVAILABLE, value: 'AVAILABLE' },
  { label: housingStatusLabels.RESERVED, value: 'RESERVED' },
  { label: housingStatusLabels.RENTED, value: 'RENTED' }
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
    const result = await listHousings({
      page: page.value,
      size: size.value,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      keyword: keyword.value === '' ? undefined : keyword.value
    })
    housings.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '房源列表加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  load()
}

function handleStatusChange(): void {
  page.value = 1
  load()
}

function handleSizeChange(): void {
  /* 每页条数变化后回到第一页，避免停留在越界页码 */
  page.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="housing-list">
    <header class="list-head">
      <div>
        <h1 class="list-title">在租房源</h1>
        <p class="list-sub">找到合适的房子，注册居民账号即可预约看房</p>
      </div>
      <div class="list-filters">
        <SearchBar v-model="keyword" placeholder="搜索小区 / 地址 / 标题" @search="handleSearch" />
        <el-select
          v-model="statusFilter"
          style="width: 130px"
          @change="handleStatusChange"
        >
          <el-option
            v-for="option in statusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </div>
    </header>

    <div v-loading="loading" class="grid">
      <router-link
        v-for="(housing, index) in housings"
        :key="housing.id"
        :to="`/guest/housings/${housing.id}`"
        class="housing-card"
      >
        <div class="housing-media">
          <img :src="coverImage(housing, index)" :alt="housing.title" loading="lazy" />
          <StatusTag
            class="housing-status"
            :label="housingStatusLabels[housing.status]"
            :type="statusTagType[housing.status]"
          />
        </div>
        <div class="housing-body">
          <h3 class="housing-title">{{ housing.title }}</h3>
          <p class="housing-meta">{{ housing.communityName }} · {{ housing.houseAddress }}</p>
          <div v-if="(housing.tags?.length ?? 0) > 0" class="housing-tags">
            <span v-for="tag in housing.tags" :key="tag" class="housing-tag">{{ tag }}</span>
          </div>
          <div class="housing-footer">
            <p class="housing-rent">
              <span class="housing-rent-amount">¥{{ housing.monthlyRent }}</span>
              <span class="housing-rent-unit">/月</span>
            </p>
            <span class="housing-views">{{ housing.viewCount }} 次浏览</span>
          </div>
        </div>
      </router-link>

      <!-- 空状态：示例图 + 引导文案，而非一行灰字 -->
      <div v-if="!loading && housings.length === 0" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无房源" />
        <h3>没有找到符合条件的房源</h3>
        <p>换个关键字或状态试试，或者稍后再来看看——新房源会持续上架。</p>
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
.list-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
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

.list-filters {
  display: flex;
  gap: var(--spacing-sm);
  align-items: center;
  flex-wrap: wrap;
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

.housing-media {
  position: relative;
  aspect-ratio: 4 / 3;
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
}

.housing-tag {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

.housing-footer {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-top: auto;
  padding-top: var(--spacing-sm);
}

.housing-rent-amount {
  font-size: var(--font-size-xl);
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
