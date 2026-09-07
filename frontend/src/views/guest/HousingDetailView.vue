<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { getHousingDetail, recordHousingView } from '@/api/housing'
import type { IHousing, HousingStatus } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import { useUserStore } from '@/store/user'
import { formatDate } from '@/utils/date'

/** 房源详情（公开）：大图 + 基本信息 + 描述；预约看房对游客引导注册/登录 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const housing = ref<IHousing | null>(null)
const loading = ref(false)
const loadError = ref('')

/** 图片兜底：无图房源使用官方示例图（与列表页轮换策略一致，固定第 1 张） */
const images = computed<string[]>(() => {
  if (!housing.value) return []
  return housing.value.images.length > 0 ? housing.value.images : ['/images/housing-sample-1.png']
})

/** 房源状态 → StatusTag 语义色 */
const statusTagType: Record<HousingStatus, 'completed' | 'pending' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'canceled',
  OFFLINE: 'canceled'
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
    /* 浏览量记录为尽力而为的埋点，失败不阻塞游客浏览 */
    recordHousingView(id).catch(() => undefined)
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
        <!-- 左：大图区 -->
        <div class="gallery">
          <el-carousel
            v-if="images.length > 1"
            :autoplay="false"
            height="100%"
            indicator-position="none"
            class="gallery-carousel"
          >
            <el-carousel-item v-for="(image, index) in images" :key="index">
              <img :src="image" :alt="`${housing.title} - 图 ${index + 1}`" />
            </el-carousel-item>
          </el-carousel>
          <img v-else :src="images[0]" :alt="housing.title" class="gallery-single" />
          <StatusTag
            class="gallery-status"
            :label="housingStatusLabels[housing.status]"
            :type="statusTagType[housing.status]"
          />
        </div>

        <!-- 右：信息面板 -->
        <div class="info-panel">
          <h1 class="info-title">{{ housing.title }}</h1>
          <p class="info-address">{{ housing.communityName }} · {{ housing.houseAddress }}</p>

          <div v-if="housing.tags.length > 0" class="info-tags">
            <span v-for="tag in housing.tags" :key="tag" class="info-tag">{{ tag }}</span>
          </div>

          <div class="info-rent">
            <span class="info-rent-amount">¥{{ housing.monthlyRent }}</span>
            <span class="info-rent-unit">/月</span>
            <span v-if="housing.depositAmount !== null" class="info-rent-deposit">
              押金 ¥{{ housing.depositAmount }}
            </span>
          </div>

          <dl class="info-grid">
            <div class="info-item">
              <dt>可入住日期</dt>
              <dd>{{ formatDate(housing.availableDate) }}</dd>
            </div>
            <div class="info-item">
              <dt>联系人</dt>
              <dd>{{ housing.contactPerson }}</dd>
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
        </div>
      </div>

      <!-- 房源描述 -->
      <section class="description">
        <h2 class="description-title">房源描述</h2>
        <p
          v-for="(paragraph, index) in housing.description.split('\n').filter((line) => line.trim() !== '')"
          :key="index"
          class="description-paragraph"
        >
          {{ paragraph }}
        </p>
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

/* 左图右信息布局 */
.detail-layout {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--spacing-lg);
  align-items: start;
}

.gallery {
  position: relative;
  border-radius: var(--radius-lg);
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--color-bg-hover);
}

.gallery-single,
.gallery-carousel,
.gallery-carousel :deep(.el-carousel__container) {
  width: 100%;
  height: 100%;
}

.gallery img {
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

.info-rent {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) 0;
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

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
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

/* 描述区 */
.description {
  margin-top: var(--spacing-xl);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.description-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-md);
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

/* 响应式：窄屏图上信息下 */
@media (max-width: 1024px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }
}
</style>
