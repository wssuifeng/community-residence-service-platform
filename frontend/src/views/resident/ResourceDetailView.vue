<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getResource, getTimeslotList } from '@/api/community'
import { getMyResidenceRelations } from '@/api/resident'
import type { IPublicResource, IResourceTimeslot, ResourceType } from '@/types/modules/community'
import { resourceTypeLabels } from '@/types/modules/community'

/**
 * 资源详情（居民端三段预约流第二段，视觉对齐房源详情）：
 * 左资源主视觉（类型图标）+ 右信息面板；下方周循环开放时段表（周一~周日）；
 * 醒目「预约该资源」按钮进入预约子页。非本人入住社区的资源显示不可约空态。
 */

const route = useRoute()

const resourceId = Number(route.params.id)

const resource = ref<IPublicResource | null>(null)
const templates = ref<IResourceTimeslot[]>([])
const loading = ref(true)
/** 资源不属于当前登录居民的入住社区：按三段流的社区分配口径拒绝展示预约入口 */
const notInMyCommunity = ref(false)

/** 资源类型 → 线性 SVG 图标（与列表页一致） */
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

const DAY_LABELS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

interface DayGroup {
  dayOfWeek: number
  label: string
  slots: IResourceTimeslot[]
}

/** 周循环模板按周一~周日分组（仅保留开放时段） */
const dayGroups = computed<DayGroup[]>(() =>
  [1, 2, 3, 4, 5, 6, 7]
    .map((day) => ({
      dayOfWeek: day,
      label: DAY_LABELS[day],
      slots: templates.value
        .filter((slot) => slot.dayOfWeek === day && slot.isAvailable === 1)
        .sort((a, b) => a.startTime.localeCompare(b.startTime))
    }))
    .filter((group) => group.slots.length > 0)
)

const openSlotCount = computed(
  () => dayGroups.value.reduce((sum, group) => sum + group.slots.length, 0)
)

function timeRange(slot: IResourceTimeslot): string {
  return `${slot.startTime.slice(0, 5)} ~ ${slot.endTime.slice(0, 5)}`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [detail, relations] = await Promise.all([
      getResource(resourceId),
      getMyResidenceRelations({ status: 'ACTIVE' }).catch(() => null)
    ])
    const myCommunityIds = new Set((relations?.records ?? []).map((row) => row.communityId))
    if (myCommunityIds.size > 0 && !myCommunityIds.has(detail.communityId)) {
      notInMyCommunity.value = true
      return
    }
    resource.value = detail
    const slotPage = await getTimeslotList(resourceId, { page: 1, size: 100 })
    templates.value = slotPage.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资源详情失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="resource-detail">
    <nav class="breadcrumb">
      <router-link to="/resident/resources">公共资源</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">{{ resource?.name ?? '资源详情' }}</span>
    </nav>

    <!-- 非本人入住社区的资源：按社区分配口径拒绝（不展示预约入口） -->
    <div v-if="notInMyCommunity" class="empty-state">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 21V8l7-5 7 5v13M3 21h18M9 21v-6h6v6" />
      </svg>
      <h3>该资源不属于您的入住社区</h3>
      <p>公共资源按入住社区分配展示，请返回列表查看您所在社区的资源。</p>
      <router-link to="/resident/resources">
        <el-button type="primary" plain>返回公共资源列表</el-button>
      </router-link>
    </div>

    <template v-else-if="resource">
      <div class="detail-layout">
        <!-- 左：资源主视觉（无图片字段，类型图标大块占位） -->
        <div class="gallery">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
            <path :d="typeIcon(resource.type)" />
          </svg>
          <span class="gallery-type">{{ resourceTypeLabels[resource.type] ?? resource.type }}</span>
        </div>

        <!-- 右：信息面板 + 预约入口 -->
        <div class="side-col">
          <div class="info-panel">
            <h1 class="info-title">{{ resource.name }}</h1>
            <p class="info-community">{{ resource.communityName }}</p>

            <dl class="info-grid">
              <div class="info-item">
                <dt>位置</dt>
                <dd>{{ resource.location || '—' }}</dd>
              </div>
              <div class="info-item">
                <dt>容纳人数</dt>
                <dd>{{ resource.capacity ? `${resource.capacity} 人` : '—' }}</dd>
              </div>
              <div class="info-item">
                <dt>开放时段</dt>
                <dd>每周 {{ openSlotCount }} 个</dd>
              </div>
              <div class="info-item">
                <dt>预约单位</dt>
                <dd>{{ resource.bookingUnit === 'HOURLY' ? '按小时' : '—' }}</dd>
              </div>
            </dl>

            <button
              v-if="openSlotCount > 0"
              type="button"
              class="book-btn"
              @click="$router.push(`/resident/reservations/create?resourceId=${resource.id}`)"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="18" rx="2" />
                <path d="M3 9h18M8 4v5M12 14l2 2 4-4" />
              </svg>
              预约该资源
            </button>
          </div>

          <div class="desc-panel">
            <h2 class="panel-title">资源介绍</h2>
            <p class="panel-paragraph">{{ resource.description || '暂无介绍' }}</p>
            <template v-if="resource.rules">
              <h2 class="panel-title">使用须知</h2>
              <p class="panel-paragraph">{{ resource.rules }}</p>
            </template>
          </div>
        </div>
      </div>

      <!-- 周循环开放时段表：周一~周日分组，仅列开放时段 -->
      <section class="slots-section">
        <h2 class="panel-title">每周开放时段</h2>
        <div v-if="dayGroups.length > 0" class="slots-grid">
          <div v-for="group in dayGroups" :key="group.dayOfWeek" class="day-card">
            <h3 class="day-label">{{ group.label }}</h3>
            <div class="day-slots">
              <span v-for="slot in group.slots" :key="slot.id" class="slot-chip">
                {{ timeRange(slot) }}
              </span>
            </div>
          </div>
        </div>
        <p v-else class="slots-empty">该资源暂未配置开放时段，暂不可预约。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.breadcrumb a {
  color: var(--color-text-secondary);
}

.breadcrumb a:hover {
  color: var(--color-primary);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 24em;
}

/* 左主视觉 + 右信息栏（与房源详情同构） */
.detail-layout {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--spacing-lg);
  align-items: start;
}

/* 资源主视觉：主色浅底 + 大号类型图标 */
.gallery {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.gallery svg {
  width: 132px;
  height: 132px;
  opacity: 0.9;
}

.gallery-type {
  position: absolute;
  top: var(--spacing-md);
  left: var(--spacing-md);
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: #fff;
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.info-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
}

.info-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.info-community {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-md) var(--spacing-sm);
  padding-top: var(--spacing-sm);
  border-top: 1px solid var(--color-border);
}

.info-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-xs);
}

.info-item dd {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 醒目预约 CTA：主色实心整宽 */
.book-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  cursor: pointer;
  transition: background 0.15s ease;
}

.book-btn:hover {
  background: var(--color-primary-dark);
}

.book-btn svg {
  width: 17px;
  height: 17px;
}

/* 描述/须知面板 */
.desc-panel {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  margin: 0 0 var(--spacing-sm);
}

.panel-title::before {
  content: '';
  width: 4px;
  height: 16px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
}

.panel-paragraph {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
}

.panel-paragraph:last-child {
  margin-bottom: 0;
}

/* 周循环时段表 */
.slots-section {
  margin-top: var(--spacing-lg);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.slots-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--spacing-md);
}

.day-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm) var(--spacing-md);
}

.day-label {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.day-slots {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.slot-chip {
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-family: var(--font-family-mono);
  text-align: center;
}

.slots-empty {
  margin: 0;
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

/* 非本社区资源空态 */
.empty-state {
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
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

@media (max-width: 1024px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }
}
</style>
