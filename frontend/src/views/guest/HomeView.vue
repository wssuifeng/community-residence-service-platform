<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { listHousings } from '@/api/housing'
import { listNotices } from '@/api/notice'
import type { IHousing, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import type { INotice } from '@/types/modules/notice'
import { formatRelative } from '@/utils/date'

/** 游客首页：hero 横幅 + 最新房源 + 公告摘要 + 注册引导（产品门面，避免模板化三等分布局） */

const housings = ref<IHousing[]>([])
const notices = ref<INotice[]>([])
const housingLoading = ref(false)
const noticeLoading = ref(false)

/** 房源状态 → StatusTag 语义色（可租=绿 / 已预订=黄 / 已出租、已下架=灰） */
const statusTagType: Record<HousingStatus, 'completed' | 'pending' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'canceled',
  OFFLINE: 'canceled'
}

/** 高优先级公告由后端置顶返回，前端补显「置顶」标记 */
function isPinned(notice: INotice): boolean {
  return notice.priority === 'HIGH' || notice.priority === 'URGENT'
}

/** 房源封面兜底：无图时按序号轮换官方示例图 */
function coverImage(housing: IHousing, index: number): string {
  return housing.images[0] ?? `/images/housing-sample-${(index % 3) + 1}.png`
}

async function loadHousings(): Promise<void> {
  housingLoading.value = true
  try {
    const result = await listHousings({ page: 1, size: 4 })
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
    <!-- hero：宽幅插画横幅，白字压图（负 margin 冲出 1200px 容器做通栏） -->
    <section class="hero">
      <img class="hero-image" src="/images/guest-hero.png" alt="社区生活插画" />
      <div class="hero-overlay">
        <h1 class="hero-title">安心居住，从了解这个社区开始</h1>
        <p class="hero-subtitle">
          浏览在租房源、了解社区公告——注册成为居民，即可预约看房、报修与服务申请一站办理。
        </p>
        <div class="hero-actions">
          <router-link to="/guest/housings" class="hero-btn is-solid">浏览房源</router-link>
          <router-link to="/guest/notices" class="hero-btn is-ghost">查看公告</router-link>
        </div>
      </div>
    </section>

    <!-- 最新房源：一行四卡 -->
    <section class="section">
      <header class="section-head">
        <h2 class="section-title">最新房源</h2>
        <router-link to="/guest/housings" class="more-link">查看全部 →</router-link>
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
              :label="housingStatusLabels[housing.status]"
              :type="statusTagType[housing.status]"
            />
          </div>
          <div class="housing-body">
            <h3 class="housing-title">{{ housing.title }}</h3>
            <p class="housing-meta">{{ housing.communityName }} · {{ housing.houseAddress }}</p>
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
    </section>

    <!-- 公告摘要 + 注册引导：左右不对称分栏 -->
    <section class="split">
      <div class="notice-panel">
        <header class="section-head">
          <h2 class="section-title">社区公告</h2>
          <router-link to="/guest/notices" class="more-link">全部公告 →</router-link>
        </header>
        <ul v-loading="noticeLoading" class="notice-list">
          <li v-for="notice in notices" :key="notice.id">
            <router-link :to="`/guest/notices/${notice.id}`" class="notice-item">
              <span v-if="isPinned(notice)" class="pin-mark">置顶</span>
              <span class="notice-title">{{ notice.title }}</span>
              <span class="notice-time">{{ formatRelative(notice.publishTime) }}</span>
            </router-link>
          </li>
          <li v-if="!noticeLoading && notices.length === 0" class="section-empty">
            暂无公告
          </li>
        </ul>
      </div>

      <aside class="cta-panel">
        <h2 class="cta-title">成为社区居民</h2>
        <p class="cta-desc">
          注册居民账号后，即可在线预约看房、提交服务工单、预约公共资源，体验完整的社区服务。
        </p>
        <div class="cta-actions">
          <router-link to="/auth/register" class="hero-btn is-solid">立即注册</router-link>
          <router-link to="/auth/login" class="hero-btn is-ghost">已有账号，去登录</router-link>
        </div>
      </aside>
    </section>
  </div>
</template>

<style scoped>
/* hero 通栏横幅 */
.hero {
  position: relative;
  margin-inline: calc(50% - 50vw);
  margin-bottom: var(--spacing-xl);
  height: clamp(320px, 42vw, 520px);
  overflow: hidden;
  border-radius: 0;
}

.hero-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 左下压图排版：渐变保证白字可读性 */
.hero-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: var(--spacing-xl) calc(50vw - 50% + var(--spacing-md)) var(--spacing-xl);
  background: linear-gradient(
    to top,
    rgba(17, 24, 39, 0.72),
    rgba(17, 24, 39, 0.32) 55%,
    transparent
  );
}

.hero-title {
  color: #fff;
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.45);
  max-width: 30em;
}

.hero-subtitle {
  margin-top: var(--spacing-sm);
  color: rgba(255, 255, 255, 0.92);
  font-size: var(--font-size-md);
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.45);
  max-width: 40em;
}

.hero-actions {
  display: flex;
  gap: var(--spacing-md);
  margin-top: var(--spacing-lg);
  flex-wrap: wrap;
}

.hero-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-sm) var(--spacing-lg);
  border-radius: var(--radius-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.hero-btn:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.hero-btn.is-solid {
  background: #fff;
  color: var(--color-primary);
}

.hero-btn.is-ghost {
  border: 1px solid rgba(255, 255, 255, 0.7);
  color: #fff;
}

/* 通用节标题 */
.section {
  margin-bottom: var(--spacing-xl);
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.section-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
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

/* 最新房源卡片行 */
.housing-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-md);
  min-height: 120px;
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
}

.housing-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
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
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.housing-rent-unit {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 公告摘要 + 注册引导不对称分栏 */
.split {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: var(--spacing-lg);
  align-items: stretch;
}

.notice-panel {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.notice-panel .section-head {
  margin-bottom: var(--spacing-sm);
}

.notice-list {
  min-height: 160px;
}

.notice-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-xs);
  border-bottom: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.notice-list li:last-child .notice-item {
  border-bottom: none;
}

.notice-item:hover {
  background: var(--color-bg-hover);
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

.notice-title {
  flex: 1;
  min-width: 0;
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notice-time {
  flex-shrink: 0;
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}

/* 注册引导卡：主色渐变 */
.cta-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xl);
  border-radius: var(--radius-lg);
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-dark));
  color: #fff;
}

.cta-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.cta-desc {
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: rgba(255, 255, 255, 0.88);
}

.cta-actions {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
}

.cta-actions .hero-btn.is-solid {
  background: #fff;
  color: var(--color-primary);
}

/* 响应式：中屏 2 列 / 窄屏 1 列，分栏降为单列 */
@media (max-width: 1024px) {
  .housing-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .housing-grid {
    grid-template-columns: 1fr;
  }
}
</style>
