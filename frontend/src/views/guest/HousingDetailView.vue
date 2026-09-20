<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { getHousingDetail, listViewingAvailableSlots, recordHousingView } from '@/api/housing'
import type { IAvailableViewingTimeslot, IHousing, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import { useUserStore } from '@/store/user'
import { formatDate, todayISO } from '@/utils/date'

/** 房源详情（公开）：大图 + 基本信息 + 描述；预约看房对游客引导注册/登录 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const housing = ref<IHousing | null>(null)
const loading = ref(false)
const loadError = ref('')
const slots = ref<IAvailableViewingTimeslot[]>([])
const activeImage = ref(0)

/** 图片兜底：无图房源使用官方示例图（与列表页轮换策略一致，固定第 1 张） */
const images = computed<string[]>(() => {
  if (!housing.value) return []
  return housing.value.images.length > 0 ? housing.value.images : ['/images/housing-sample-1.png']
})

/** 多图导航（DEF-048）：首尾环绕切换；仅手动触发，本页不做自动轮播 */
function stepImage(direction: 1 | -1): void {
  const count = images.value.length
  if (count <= 1) return
  activeImage.value = (activeImage.value + direction + count) % count
}

/** 房源状态 → StatusTag 语义色 */
const statusTagType: Record<HousingStatus, 'completed' | 'pending' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'canceled',
  OFFLINE: 'canceled'
}

/** 租售类型标签（后端 V9 rent_type 白名单 RENT/SALE，与列表页筛选用语一致；未识别值原样展示兜底） */
const rentTypeLabels: Record<string, string> = {
  RENT: '出租',
  SALE: '出售'
}

/* 可约时段：公开接口取未来 7 天，首页只读展示；拉取失败降级为不展示，不阻塞详情 */
async function loadSlots(id: number): Promise<void> {
  try {
    slots.value = await listViewingAvailableSlots(id, {
      startDate: todayISO(),
      endDate: formatDate(new Date(Date.now() + 7 * 86400000).toISOString())
    })
  } catch {
    slots.value = []
  }
}

/** 时段胶囊文案：M/d HH:mm（后端 startTime 可能带秒，截前 5 位） */
function slotLabel(slot: IAvailableViewingTimeslot): string {
  const date = new Date(slot.date)
  const datePart = Number.isNaN(date.getTime())
    ? slot.date
    : `${date.getMonth() + 1}/${date.getDate()}`
  return `${datePart} ${slot.startTime.slice(0, 5)}`
}

async function load(): Promise<void> {
  const id = Number(route.params.id)
  if (!Number.isFinite(id)) {
    loadError.value = '房源不存在'
    return
  }
  loading.value = true
  try {
    housing.value = await getHousingDetail(id)
    /* 浏览量记录为尽力而为的埋点，失败不阻塞游客浏览；返回值实时计数就地刷新 */
    recordHousingView(id)
      .then((count) => {
        if (housing.value) housing.value.viewCount = count
      })
      .catch(() => undefined)
    void loadSlots(id)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '房源加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

/** 预约看房：未登录跳登录（带回跳）；非居民角色引导使用居民账号；居民跳转居民端预约入口 */
function handleBookViewing(): void {
  if (!userStore.isLoggedIn) {
    router.push(`/auth/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }
  if (userStore.role !== 'RESIDENT') {
    ElMessage.warning('看房预约需居民账号，请切换居民账号登录后再试')
    return
  }
  router.push(`/resident/housings/${route.params.id}`)
}

onMounted(load)
</script>

<template>
  <div class="housing-detail">
    <nav class="breadcrumb">
      <router-link to="/guest/housings">房源列表</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">{{ housing?.title ?? '房源详情' }}</span>
    </nav>

    <!-- 加载/错误骨架屏 -->
    <div v-if="loading || loadError" class="detail-skeleton">
      <div class="skeleton-image"></div>
      <div class="skeleton-lines">
        <div class="skeleton-line is-title"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-line is-short"></div>
      </div>
      <p v-if="loadError" class="load-error">
        {{ loadError }}，
        <router-link to="/guest/housings">返回房源列表</router-link>
      </p>
    </div>

    <template v-else-if="housing">
      <div class="detail-layout">
        <!-- 左：16:9 主图 + 横向缩略图条（DEF-048：左右虚化悬浮箭头切换，多图时显示；
             缩略图卡点击换主图，悬浮时虚化玻璃背景；单图不显示箭头与缩略图） -->
        <div class="gallery-wrap">
          <div class="gallery">
            <img :src="images[activeImage]" :alt="housing.title" class="gallery-main" />
            <StatusTag
              class="gallery-status"
              on-image
              :label="housingStatusLabels[housing.status]"
              :type="statusTagType[housing.status]"
            />
            <button
              v-if="images.length > 1"
              type="button"
              class="gallery-arrow is-left"
              aria-label="上一张"
              @click="stepImage(-1)"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="15 18 9 12 15 6" />
              </svg>
            </button>
            <button
              v-if="images.length > 1"
              type="button"
              class="gallery-arrow is-right"
              aria-label="下一张"
              @click="stepImage(1)"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </button>
          </div>
          <div v-if="images.length > 1" class="gallery-thumbs">
            <button
              v-for="(image, index) in images"
              :key="index"
              type="button"
              class="gallery-thumb"
              :class="{ active: activeImage === index }"
              @click="activeImage = index"
            >
              <img :src="image" :alt="`${housing.title} - 缩略图 ${index + 1}`" />
            </button>
          </div>
        </div>

        <!-- 右：信息面板 -->
        <div class="info-panel">
          <h1 class="info-title">{{ housing.title }}</h1>
          <p class="info-address">{{ housing.communityName }} · {{ housing.houseLocation }}</p>

          <div class="info-rent">
            <span class="info-rent-amount">¥{{ housing.monthlyRent }}</span>
            <span class="info-rent-unit">/月</span>
            <span v-if="housing.deposit != null" class="info-rent-deposit">
              押金 ¥{{ housing.deposit }}
            </span>
          </div>

          <div v-if="(housing.tags?.length ?? 0) > 0" class="info-tags">
            <span v-for="tag in housing.tags" :key="tag" class="info-tag">{{ tag }}</span>
          </div>

          <!-- 实体无面积/楼层/朝向字段，信息网格取真实返回字段（接口文档字段为漂移命名） -->
          <dl class="info-grid">
            <div class="info-item">
              <dt>户型</dt>
              <dd>{{ housing.layout ?? '见标题' }}</dd>
            </div>
            <div class="info-item">
              <dt>出租方式</dt>
              <dd>{{ rentTypeLabels[housing.rentType] ?? housing.rentType }}</dd>
            </div>
            <div class="info-item">
              <dt>联系电话</dt>
              <dd>{{ housing.contactPhone ?? '注册后可见' }}</dd>
            </div>
            <div class="info-item">
              <dt>浏览量</dt>
              <dd>{{ housing.viewCount }}</dd>
            </div>
          </dl>

          <div class="info-actions">
            <el-button type="primary" size="large" @click="handleBookViewing">
              预约看房
            </el-button>
            <p class="action-hint">注册居民账号即可选择看房时段，在线提交预约</p>
          </div>

          <!-- 可约时段：只读胶囊，最多展示 4 个，预约动作统一走上方按钮 -->
          <div v-if="slots.length > 0" class="info-slots">
            <h2 class="info-slots-title">可约时段</h2>
            <div class="info-slots-list">
              <span v-for="slot in slots.slice(0, 4)" :key="slot.timeslotId" class="info-slot">
                {{ slotLabel(slot) }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 下方两栏：左房源描述 / 右配套设施 -->
      <section class="description">
        <div class="description-col">
          <h2 class="description-title">房源描述</h2>
          <template v-if="housing.description">
            <p
              v-for="(paragraph, index) in housing.description.split('\n').filter((line) => line.trim() !== '')"
              :key="index"
              class="description-paragraph"
            >
              {{ paragraph }}
            </p>
          </template>
          <p v-else class="description-paragraph description-empty">暂无描述</p>
        </div>

        <!-- 配套设施：静态占位（后端无配套字段，待字段落地后改数据驱动） -->
        <div class="description-col">
          <h2 class="description-title">配套设施</h2>
          <ul class="facility-list">
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="2" y="4" width="20" height="8" rx="2" />
                <path d="M6 8h12" />
                <path d="M8 16c0 1.5-1 2-1 3M12 16c0 1.5-1 2-1 3M16 16c0 1.5-1 2-1 3" />
              </svg>
              <span>空调</span>
            </li>
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="4" y="2" width="16" height="20" rx="2" />
                <circle cx="12" cy="13" r="5" />
                <path d="M8 5h4" />
                <circle cx="16.5" cy="5" r="0.5" />
              </svg>
              <span>洗衣机</span>
            </li>
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="6" y="2" width="12" height="20" rx="2" />
                <path d="M6 10h12" />
                <path d="M9 5v2M9 13v3" />
              </svg>
              <span>冰箱</span>
            </li>
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M2 9a15 15 0 0 1 20 0" />
                <path d="M5.5 12.5a10.5 10.5 0 0 1 13 0" />
                <path d="M9 16a6 6 0 0 1 6 0" />
                <circle cx="12" cy="19.5" r="0.5" />
              </svg>
              <span>WiFi</span>
            </li>
          </ul>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
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

/* 加载骨架 */
.detail-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.skeleton-image {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-lg);
  background: var(--color-bg-hover);
  animation: skeleton-pulse 1.4s ease infinite;
}

.skeleton-lines {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.skeleton-line {
  height: var(--spacing-md);
  border-radius: var(--radius-sm);
  background: var(--color-bg-hover);
  animation: skeleton-pulse 1.4s ease infinite;
}

.skeleton-line.is-title {
  width: 45%;
  height: var(--font-size-xl);
}

.skeleton-line.is-short {
  width: 30%;
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

.load-error {
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-danger);
}

/* 左图右信息布局：stretch 使信息面板与图区同高 */
.detail-layout {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--spacing-lg);
  align-items: stretch;
}

.gallery-wrap {
  display: flex;
  flex-direction: column;
}

/* 16:9 微圆角大展示区 */
.gallery {
  position: relative;
  flex: 1;
  border-radius: var(--radius-lg);
  overflow: hidden;
  aspect-ratio: 16 / 9;
  background: var(--color-bg-hover);
}

.gallery-main {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.gallery-status {
  position: absolute;
  top: var(--spacing-md);
  left: var(--spacing-md);
  z-index: 1;
}

/* 左右切换箭头（DEF-048）：虚化毛玻璃圆钮，平时半透明，悬浮主图区时完全显形 */
.gallery-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: var(--radius-circle);
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: var(--color-text-primary);
  box-shadow: var(--shadow-md);
  cursor: pointer;
  opacity: 0.45;
  transition: opacity 0.2s ease, background 0.15s ease, color 0.15s ease;
}

.gallery:hover .gallery-arrow,
.gallery-arrow:focus-visible {
  opacity: 1;
}

.gallery-arrow:hover {
  background: rgba(255, 255, 255, 0.85);
  color: var(--color-primary);
}

.gallery-arrow.is-left {
  left: var(--spacing-md);
}

.gallery-arrow.is-right {
  right: var(--spacing-md);
}

.gallery-arrow svg {
  width: 20px;
  height: 20px;
}

/* 缩略图卡条（DEF-048）：横向滚动，悬浮时整条虚化玻璃底衬 + 卡片上浮；
   选中态主色描边 */
.gallery-thumbs {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
  padding: var(--spacing-xs);
  border-radius: var(--radius-md);
  overflow-x: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  background: transparent;
  transition: background 0.2s ease;
}

.gallery-thumbs:hover {
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  scrollbar-color: var(--color-text-disabled) transparent;
}

.gallery-thumbs::-webkit-scrollbar {
  height: 4px;
}

.gallery-thumbs::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: var(--radius-pill);
}

.gallery-thumbs:hover::-webkit-scrollbar-thumb {
  background: var(--color-text-disabled);
}

.gallery-thumb {
  flex-shrink: 0;
  width: 120px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: var(--radius-md);
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--color-bg-hover);
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.15s ease, box-shadow 0.15s ease;
}

.gallery-thumb:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}

.gallery-thumb.active {
  border-color: var(--color-primary);
}

.gallery-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.info-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.info-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.info-address {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-tags {
  display: flex;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.info-tag {
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

/* 价格行下方细分隔线，与信息网格分区 */
.info-rent {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  padding-bottom: var(--spacing-sm);
  border-bottom: 1px solid var(--color-border);
}

.info-rent-amount {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.info-rent-unit {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-rent-deposit {
  margin-left: auto;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* 信息网格：4 列单行（对齐设计稿），窄屏 2 列 */
.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-md) var(--spacing-sm);
}

.info-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-xs);
}

.info-item dd {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.info-actions {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.info-actions .el-button {
  width: 100%;
}

.action-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-align: center;
}

/* 可约时段胶囊 */
.info-slots-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-sm);
}

.info-slots-list {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.info-slot {
  padding: var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

/* 描述区：左右两栏（描述 / 配套设施），节标题蓝竖条与全站一致 */
.description {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-xl);
  margin-top: var(--spacing-xl);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.description-col {
  min-width: 0;
}

/* DEF-049：描述与配套设施两栏间的淡色竖直分隔线（对照设计稿 06-房源详情） */
.description-col + .description-col {
  position: relative;
  padding-left: var(--spacing-xl);
}

.description-col + .description-col::before {
  content: '';
  position: absolute;
  left: 0;
  top: var(--spacing-xs);
  bottom: var(--spacing-xs);
  width: 1px;
  background: var(--color-border);
}

.description-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-md);
}

.description-title::before {
  content: '';
  width: 4px;
  height: 18px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
}

.description-paragraph {
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
}

.description-paragraph + .description-paragraph {
  margin-top: var(--spacing-sm);
}

/* 配套设施：线性图标 + 名称 */
.facility-list {
  display: flex;
  gap: var(--spacing-xl);
  flex-wrap: wrap;
  padding-top: var(--spacing-sm);
}

.facility-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.facility-item svg {
  width: 30px;
  height: 30px;
}

/* 响应式：窄屏图上信息下、信息网格 2 列、描述区降单栏（分隔线随两栏布局一并取消） */
@media (max-width: 1024px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .description {
    grid-template-columns: 1fr;
  }

  .description-col + .description-col {
    padding-left: 0;
  }

  .description-col + .description-col::before {
    display: none;
  }
}
</style>
