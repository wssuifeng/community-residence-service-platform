<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { listHousings } from '@/api/housing'
import { listNotices } from '@/api/notice'
import type { IHousing, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import type { INotice } from '@/types/modules/notice'
import { formatDate } from '@/utils/date'

/** 游客首页：沉浸式摄影 Hero + 服务入口卡 + 精选房源 + 社区公告 + 注册引导（产品门面） */

const router = useRouter()

const housings = ref<IHousing[]>([])
const notices = ref<INotice[]>([])
const housingLoading = ref(false)
const noticeLoading = ref(false)
const searchKeyword = ref('')

/** 房源状态 → StatusTag 语义色（可租=绿 / 已预订=黄 / 已出租、已下架=灰） */
const statusTagType: Record<HousingStatus, 'completed' | 'pending' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'canceled',
  OFFLINE: 'canceled'
}

/** 置顶判定：兼容旧 priority 枚举与 is_pinned 布尔列 */
function isPinned(notice: INotice): boolean {
  return notice.priority === 'HIGH' || notice.priority === 'URGENT'
    || notice.pinned === true || notice.isPinned === true
}

/** 房源封面兜底：无图时按序号轮换官方示例图 */
function coverImage(housing: IHousing, index: number): string {
  return housing.images[0] ?? `/images/housing-sample-${(index % 3) + 1}.png`
}

/* Hero 搜索：房源列表页暂无 URL query 关键字支持，统一落地到房源列表页继续筛选 */
function handleSearch(): void {
  router.push('/guest/housings')
}

async function loadHousings(): Promise<void> {
  housingLoading.value = true
  try {
    const result = await listHousings({ page: 1, size: 6 })
    housings.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '最新房源加载失败')
  } finally {
    housingLoading.value = false
  }
}

async function loadNotices(): Promise<void> {
  noticeLoading.value = true
  try {
    const result = await listNotices({ page: 1, size: 6 })
    notices.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告加载失败')
  } finally {
    noticeLoading.value = false
  }
}

onMounted(() => {
  loadHousings()
  loadNotices()
})
</script>

<template>
  <div class="home">
    <!-- 沉浸式摄影 Hero：通栏（负 margin 冲出 1200px 容器并抵消布局上 padding） -->
    <section class="hero">
      <div class="hero-inner">
        <h1 class="hero-title">回家，是件值得期待的事</h1>
        <p class="hero-subtitle">报修 · 反馈 · 预约 · 看房，社区服务一站式办理</p>
        <form class="hero-search" role="search" @submit.prevent="handleSearch">
          <svg class="hero-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <line x1="21" y1="21" x2="16.5" y2="16.5" />
          </svg>
          <input
            v-model="searchKeyword"
            type="search"
            class="hero-search-input"
            placeholder="搜索房源、公告…"
            aria-label="搜索房源、公告"
          />
          <button type="submit" class="hero-search-btn">搜索</button>
        </form>
      </div>
    </section>

    <!-- 三张服务入口卡：负边距叠在 Hero 底边上；游客点击统一引导登录 -->
    <section class="service-cards" aria-label="服务入口">
      <router-link to="/auth/login" class="service-card">
        <span class="service-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
          </svg>
        </span>
        <span class="service-text">
          <span class="service-title">在线报修</span>
          <span class="service-desc">快速报修，专业处理</span>
        </span>
        <svg class="service-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="5" y1="12" x2="19" y2="12" />
          <polyline points="12 5 19 12 12 19" />
        </svg>
      </router-link>

      <router-link to="/auth/login" class="service-card">
        <span class="service-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
          </svg>
        </span>
        <span class="service-text">
          <span class="service-title">意见反馈</span>
          <span class="service-desc">您的建议，我们在乎</span>
        </span>
        <svg class="service-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="5" y1="12" x2="19" y2="12" />
          <polyline points="12 5 19 12 12 19" />
        </svg>
      </router-link>

      <router-link to="/auth/login" class="service-card">
        <span class="service-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="4" width="18" height="18" rx="2" />
            <line x1="16" y1="2" x2="16" y2="6" />
            <line x1="8" y1="2" x2="8" y2="6" />
            <line x1="3" y1="10" x2="21" y2="10" />
          </svg>
        </span>
        <span class="service-text">
          <span class="service-title">资源预约</span>
          <span class="service-desc">场地 / 设施 / 服务，轻松预约</span>
        </span>
        <svg class="service-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="5" y1="12" x2="19" y2="12" />
          <polyline points="12 5 19 12 12 19" />
        </svg>
      </router-link>
    </section>

    <!-- 精选房源 + 社区公告：不对称分栏 -->
    <section class="split">
      <div class="housing-panel">
        <header class="section-head">
          <h2 class="section-title">精选房源</h2>
          <router-link to="/guest/housings" class="more-link">查看更多 →</router-link>
        </header>
        <div v-loading="housingLoading" class="housing-grid">
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
                on-image
                :label="housingStatusLabels[housing.status]"
                :type="statusTagType[housing.status]"
              />
            </div>
            <div class="housing-body">
              <h3 class="housing-title">{{ housing.title }}</h3>
              <p class="housing-meta">{{ housing.communityName }} · {{ housing.houseLocation }}</p>
              <p class="housing-rent">
                <span class="housing-rent-amount">¥{{ housing.monthlyRent }}</span>
                <span class="housing-rent-unit">/月</span>
              </p>
            </div>
          </router-link>
          <div v-if="!housingLoading && housings.length === 0" class="section-empty">
            暂无在租房源，先去公告里了解社区动态吧
          </div>
        </div>
      </div>

      <aside class="notice-panel">
        <header class="section-head">
          <h2 class="section-title">社区公告</h2>
        </header>
        <div v-loading="noticeLoading" class="notice-board">
          <!-- 最多 5 条平分容器高度（与左侧房源区等高），全量走底部「查看全部」 -->
          <router-link
            v-for="notice in notices.slice(0, 5)"
            :key="notice.id"
            :to="`/guest/notices/${notice.id}`"
            class="notice-item"
          >
            <span class="notice-dot" aria-hidden="true"></span>
            <h3 class="notice-item-title">{{ notice.title }}</h3>
            <span v-if="isPinned(notice)" class="pin-mark">置顶</span>
            <span class="notice-item-time">{{ formatDate(notice.publishTime) }}</span>
          </router-link>
          <div v-if="!noticeLoading && notices.length === 0" class="section-empty">暂无公告</div>
          <router-link to="/guest/notices" class="notice-more">查看全部 →</router-link>
        </div>
      </aside>
    </section>
  </div>
</template>

<style scoped>
/* Hero：全屏摄影 + 深色渐变蒙版（压暗天空保证白字可读），内容居中 */
.hero {
  margin-top: calc(-1 * var(--spacing-lg));
  margin-inline: calc(50% - 50vw);
  height: clamp(440px, 58vh, 620px);
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    linear-gradient(
      to bottom,
      rgba(15, 23, 42, 0.55),
      rgba(15, 23, 42, 0.2) 45%,
      rgba(15, 23, 42, 0.55)
    ),
    url('/images/guest-hero-dusk.png') center 68% / cover no-repeat;
}

.hero-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 var(--spacing-md) var(--spacing-xxl);
  text-align: center;
}

.hero-title {
  color: #fff;
  font-size: var(--font-size-hero);
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.06em;
  line-height: var(--line-height-tight);
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.45);
}

.hero-subtitle {
  margin-top: var(--spacing-md);
  color: rgba(255, 255, 255, 0.85);
  font-size: var(--font-size-md);
  letter-spacing: 0.12em;
  text-shadow: 0 1px 6px rgba(0, 0, 0, 0.45);
}

/* 毛玻璃搜索框：半透明白底 + 背景模糊 */
.hero-search {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: min(560px, 100%);
  margin-top: var(--spacing-xl);
  padding: var(--spacing-xs) var(--spacing-xs) var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: var(--shadow-lg);
}

.hero-search-icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  color: var(--color-text-disabled);
}

.hero-search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.hero-search-input::placeholder {
  color: var(--color-text-disabled);
}

.hero-search-btn {
  flex-shrink: 0;
  padding: var(--spacing-sm) var(--spacing-xl);
  border: none;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition: background 0.15s ease;
}

.hero-search-btn:hover {
  background: var(--color-primary-dark);
}

/* 服务入口卡：负边距叠在 Hero 底边，需相对定位压住 Hero */
.service-cards {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-md);
  margin-top: calc(-1 * var(--spacing-xxl) - var(--spacing-md));
  margin-bottom: var(--spacing-xxl);
}

.service-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.service-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-lg);
}

.service-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-circle);
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.service-icon svg {
  width: 22px;
  height: 22px;
}

.service-text {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 0;
}

.service-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.service-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.service-arrow {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-left: auto;
  color: var(--color-text-disabled);
  transition: color 0.15s ease, transform 0.15s ease;
}

.service-card:hover .service-arrow {
  color: var(--color-primary);
  transform: translateX(2px);
}

/* 通用节标题：左侧蓝色竖条强调 */
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.section-title::before {
  content: '';
  width: 4px;
  height: 18px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
}

.more-link {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
}

.section-empty {
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

/* 精选房源 + 社区公告不对称分栏：stretch 使公告板与房源区等高；
   minmax(0, …) 防止横向卡条的内容最小宽度撑破轨道（grid item 默认 min-width:auto） */
.split {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: stretch;
  margin-bottom: var(--spacing-xxl);
}

/* 横向滚动卡条：固定卡宽，超出横滑（hover 显示滚动条） */
.housing-grid {
  display: flex;
  gap: var(--spacing-md);
  min-height: 120px;
  overflow-x: auto;
  padding-bottom: var(--spacing-xs);
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.housing-grid:hover {
  scrollbar-color: var(--color-text-disabled) transparent;
}

.housing-grid::-webkit-scrollbar {
  height: 4px;
}

.housing-grid::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: var(--radius-pill);
}

.housing-grid:hover::-webkit-scrollbar-thumb {
  background: var(--color-text-disabled);
}

.housing-grid .section-empty {
  flex: 1;
}

.housing-card {
  flex: 0 0 350px;
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
}

.housing-title {
  font-size: var(--font-size-sm);
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

.housing-rent {
  margin-top: var(--spacing-xs);
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

/* 公告区：标题落在页面背景上；公告板与左侧房源区同高，条目 flex 均分 */
.notice-panel {
  display: flex;
  flex-direction: column;
}

.notice-panel .section-head {
  margin-bottom: var(--spacing-md);
}

.notice-board {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 160px;
  padding: var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-lg);
  background: var(--color-bg-subtle);
}

.notice-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
  background: var(--color-primary);
}

/* 单线条目：蓝点 + 标题（单行省略）+ 日期，flex:1 参与五条均分 */
.notice-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs);
  border-bottom: 1px solid rgba(31, 41, 55, 0.06);
  border-radius: var(--radius-sm);
}

.notice-item:last-of-type {
  border-bottom: none;
}

.notice-item:hover {
  background: rgba(255, 255, 255, 0.6);
}

.notice-item-title {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notice-item-time {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 低存在感「查看全部」：沉底右对齐 */
.notice-more {
  align-self: flex-end;
  margin-top: auto;
  padding: var(--spacing-xs) var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.notice-more:hover {
  color: var(--color-primary);
}

.pin-mark {
  flex-shrink: 0;
  padding: 1px var(--spacing-xs);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

/* 响应式：中屏分栏降单列；窄屏服务卡单列（房源列宽由 auto-fit 自适应） */
@media (max-width: 1024px) {
  .split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .service-cards {
    grid-template-columns: 1fr;
  }

  .hero-title {
    font-size: var(--font-size-xxl);
  }
}
</style>
