<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getCommunityList, getResourceList, getTimeslotList } from '@/api/community'
import { getMyResidenceRelations } from '@/api/resident'
import type { ICommunity, IPublicResource, ResourceType } from '@/types/modules/community'
import { resourceTypeLabels } from '@/types/modules/community'

/**
 * 公共资源列表（居民端三段预约流第一段）：
 * 只展示当前登录居民已生效居住关系社区的开放资源，点击卡片进入资源详情。
 * 无生效居住关系时显示引导空态（不展示任何社区的资源）。
 */

const resources = ref<IPublicResource[]>([])
/** 每个资源的每周开放时段数（资源 ID → 数量） */
const timeslotCounts = ref<Map<number, number>>(new Map())
const communityName = ref('')
const loading = ref(true)
/** 未查到生效居住关系：显引导空态而非资源网格 */
const noRelation = ref(false)

const hasResources = computed(() => resources.value.length > 0)

/** 资源类型 → 线性 SVG 图标（与全站 stroke 图标语言一致） */
function typeIcon(type: ResourceType): string {
  switch (type) {
    case 'GYM':
      return 'M6.5 6.5v11M17.5 6.5v11M3 9v6M21 9v6M6.5 12h11'
    case 'PARKING':
      return 'M5 4h9a5 5 0 0 1 0 10H9V4M9 14v6'
    case 'MEETING_ROOM':
    default:
      return 'M3 5h18v10H3zM8 19h8M12 15v4M7 9h2M12 9h2M17 9h.01'
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    /* 入住社区来源：已生效居住关系（后端 RESIDENT 限本人）；多个社区时取首个，见任务报告 */
    const relations = await getMyResidenceRelations({ status: 'ACTIVE' })
    const communityId = relations.records[0]?.communityId
    if (!communityId) {
      noRelation.value = true
      return
    }
    const [resourcePage, communityPage] = await Promise.all([
      getResourceList(communityId, { page: 1, size: 100, status: 'AVAILABLE' }),
      getCommunityList({ page: 1, size: 100 }).catch(() => null)
    ])
    resources.value = resourcePage.records
    communityName.value =
      communityPage?.records.find((community: ICommunity) => community.id === communityId)?.name ??
      ''
    /* 开放时段数：周循环模板逐资源统计（资源量级小，可接受逐个查询） */
    const counts = new Map<number, number>()
    await Promise.all(
      resources.value.map(async (resource) => {
        try {
          const slots = await getTimeslotList(resource.id, { page: 1, size: 100 })
          counts.set(
            resource.id,
            slots.records.filter((slot) => slot.isAvailable === 1).length
          )
        } catch {
          counts.set(resource.id, 0)
        }
      })
    )
    timeslotCounts.value = counts
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载公共资源失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="resource-list">
    <header class="list-head">
      <h1 class="list-title">公共资源</h1>
      <p class="list-sub">
        {{ communityName ? `${communityName} · ` : '' }}选择资源查看开放时段并发起预约
      </p>
    </header>

    <div v-loading="loading" class="grid">
      <router-link
        v-for="resource in resources"
        :key="resource.id"
        :to="`/resident/resources/${resource.id}`"
        class="resource-card"
      >
        <div class="resource-media">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
            <path :d="typeIcon(resource.type)" />
          </svg>
        </div>
        <div class="resource-body">
          <div class="resource-title-row">
            <h3 class="resource-name">{{ resource.name }}</h3>
            <span class="resource-type">{{ resourceTypeLabels[resource.type] ?? resource.type }}</span>
          </div>
          <p v-if="resource.location" class="resource-location">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            {{ resource.location }}
          </p>
          <p v-if="resource.description" class="resource-desc">{{ resource.description }}</p>
          <div class="resource-meta">
            <span class="meta-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M12 7v5l3 3" />
              </svg>
              每周 {{ timeslotCounts.get(resource.id) ?? 0 }} 个开放时段
            </span>
            <span v-if="resource.capacity" class="meta-item">容纳 {{ resource.capacity }} 人</span>
          </div>
        </div>
        <span class="resource-go">
          查看详情
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12h14M13 6l6 6-6 6" />
          </svg>
        </span>
      </router-link>

      <!-- 引导空态：未查到生效居住关系（按全局约定不做假数据） -->
      <div v-if="!loading && noRelation" class="empty-state">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 21V8l7-5 7 5v13M3 21h18M9 21v-6h6v6M9 11h.01M15 11h.01" />
        </svg>
        <h3>未查到您的入住社区，请联系物业</h3>
        <p>公共资源按入住社区分配展示，待您的入住申请审核通过后即可预约社区资源。</p>
      </div>

      <!-- 资源空态：有入住社区但暂无开放资源 -->
      <div v-else-if="!loading && !hasResources" class="empty-state">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 3" />
        </svg>
        <h3>社区暂无开放预约的资源</h3>
        <p>管理员配置公共资源后会在这里展示，请稍后再来看看。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页头：大标题 + 副标题（贴页面背景，与房源浏览同语言） */
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

/* 卡片网格：宽屏 3 列 → 中屏 2 列 → 窄屏 1 列 */
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-md);
  min-height: 200px;
}

.resource-card {
  position: relative;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.resource-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

/* 类型图标主视觉区：主色浅底 + 大号线性图标（资源无图片字段的占位语言） */
.resource-media {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.resource-media svg {
  width: 64px;
  height: 64px;
}

.resource-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  flex: 1;
}

.resource-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.resource-name {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.resource-type {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.resource-location {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.resource-location svg {
  flex-shrink: 0;
  width: 13px;
  height: 13px;
}

.resource-desc {
  margin: 0;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.resource-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-top: auto;
  padding-top: var(--spacing-sm);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.meta-item svg {
  width: 13px;
  height: 13px;
}

/* 悬停浮现的「查看详情」引导 */
.resource-go {
  position: absolute;
  right: var(--spacing-md);
  bottom: var(--spacing-md);
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.15s ease, transform 0.15s ease;
  pointer-events: none;
}

.resource-go svg {
  width: 12px;
  height: 12px;
}

.resource-card:hover .resource-go {
  opacity: 1;
  transform: translateY(0);
}

/* 空状态横跨整行（虚线卡 + 引导文案，与房源浏览同语言） */
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

.empty-state svg {
  width: 56px;
  height: 56px;
  color: var(--color-text-disabled);
}

.empty-state h3 {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.empty-state p {
  margin: 0;
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
