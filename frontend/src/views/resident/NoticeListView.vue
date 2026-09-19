<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import { listNotices } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { formatDate } from '@/utils/date'

/** 居民端公告列表：同步游客端 v5 定稿设计（衬线页头 + 置顶轮播 + 日期块期刊列表）；
    数据层维持居民登录态 listNotices 分页/搜索与关键词 URL 回写，详情跳转走居民端路由 */

const route = useRoute()
const router = useRouter()

/** URL ?keyword= 读取（与游客端同口径；非字符串/空值归一为空串） */
function keywordFromQuery(): string {
  const value = route.query.keyword
  return typeof value === 'string' ? value.trim() : ''
}

const notices = ref<INotice[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref(keywordFromQuery())
const loading = ref(false)

/** 置顶轮播背景：背景实体感大图按标题语义映射（活动/安全/指南），无匹配按序号轮换（与游客端同款公共目录图） */
const NOTICE_BG_POOLS = ['/notice_bg_activity.png', '/notice_bg_safety.png', '/notice_bg_guide.png']

function noticeBgOf(notice: INotice, index: number): string {
  const title = notice.title
  if (title.includes('活动')) return '/notice_bg_activity.png'
  if (title.includes('安全') || title.includes('消防')) return '/notice_bg_safety.png'
  if (title.includes('指南') || title.includes('提示') || title.includes('教程')) return '/notice_bg_guide.png'
  return NOTICE_BG_POOLS[index % NOTICE_BG_POOLS.length]
}

/** 置顶判定：isPinned 真实字段（0/1）优先，旧布尔命名留兜底（优先级是展示属性，不参与置顶） */
function isPinned(notice: INotice): boolean {
  return notice.isPinned === 1 || notice.pinned === true
}

/** 置顶区与期刊列表按优先级拆分（保持后端返回顺序）；居民端置顶可能为 0 条，此时不渲染轮播区 */
const pinnedNotices = computed<INotice[]>(() => notices.value.filter(isPinned))
const journalNotices = computed<INotice[]>(() => notices.value.filter((notice) => !isPinned(notice)))

/* 置顶轮播：整宽单卡轮换 + 左右箭头 + 缩略图导航；
   自动轮播开启（悬浮暂停），与房源详情图片区（仅手动切换）按任务口径区分 */
const pinnedIndex = ref(0)
const PINNED_INTERVAL_MS = 5000
let pinnedTimer: ReturnType<typeof setInterval> | null = null

function stopAutoplay(): void {
  if (pinnedTimer !== null) {
    clearInterval(pinnedTimer)
    pinnedTimer = null
  }
}

function startAutoplay(): void {
  stopAutoplay()
  if (pinnedNotices.value.length < 2) return
  pinnedTimer = setInterval(() => {
    pinnedIndex.value = (pinnedIndex.value + 1) % pinnedNotices.value.length
  }, PINNED_INTERVAL_MS)
}

/** 箭头切换（首尾环绕）：手动操作后重新计时，避免紧接的自动跳转打断阅读 */
function goPinned(direction: 1 | -1): void {
  const count = pinnedNotices.value.length
  if (count < 2) return
  pinnedIndex.value = (pinnedIndex.value + direction + count) % count
  startAutoplay()
}

/** 缩略图直达切换 */
function setPinned(index: number): void {
  pinnedIndex.value = index
  startAutoplay()
}

/* 列表刷新后按新置顶条数收敛指针并重启自动轮播（条数 <2 时自然不启动） */
watch(
  () => pinnedNotices.value.length,
  (count) => {
    if (pinnedIndex.value >= count) pinnedIndex.value = 0
    startAutoplay()
  }
)

onUnmounted(stopAutoplay)

/** 日期块：大字号「日」（publishTime 为空时占位） */
function dayOf(notice: INotice): string {
  const date = new Date(notice.publishTime ?? '')
  return Number.isNaN(date.getTime()) ? '--' : String(date.getDate()).padStart(2, '0')
}

/** 日期块：小字「年-月」 */
function yearMonthOf(notice: INotice): string {
  return formatDate(notice.publishTime).slice(0, 7)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listNotices({
      page: page.value,
      size: size.value,
      keyword: keyword.value === '' ? undefined : keyword.value
    })
    notices.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  /* 搜索关键词回写 URL（清空时移除参数），刷新与前进/后退回显保持一致 */
  router.replace({
    query: { ...route.query, keyword: keyword.value.trim() === '' ? undefined : keyword.value.trim() }
  })
  load()
}

function handleSizeChange(): void {
  /* 每页条数变化后回到第一页，避免停留在越界页码 */
  page.value = 1
  load()
}

/* 浏览器前进/后退（或其他入口带参跳转）时组件被复用，query 变化需同步重查；
   页内搜索触发的 replace 与输入框同值，此处不重复请求 */
watch(
  () => route.query.keyword,
  (value) => {
    const kw = typeof value === 'string' ? value.trim() : ''
    if (kw !== keyword.value) {
      keyword.value = kw
      page.value = 1
      load()
    }
  }
)

onMounted(load)
</script>

<template>
  <div class="notice-list">
    <header class="list-head">
      <div>
        <h1 class="list-title">社区公告</h1>
        <p class="list-sub">了解社区最新动态与服务安排</p>
      </div>
      <SearchBar v-model="keyword" placeholder="搜索公告标题 / 内容" @search="handleSearch" />
    </header>

    <div v-loading="loading" class="notice-body">
      <!-- 置顶区：整宽轮播——与下方列表同宽的单卡轮换 + 右侧透明文字区 + 右上角置顶徽章
           + 左右贴边毛玻璃箭头 + 底部贴底半透明缩略图横拉条（含序号角标）；
           自动轮播 5s、悬浮暂停；置顶 0 条时不渲染本区，直接显示期刊列表 -->
      <div
        v-if="pinnedNotices.length > 0"
        class="pinned-carousel"
        @mouseenter="stopAutoplay"
        @mouseleave="startAutoplay"
      >
        <div class="pinned-viewport">
          <div class="pinned-track" :style="{ transform: `translateX(-${pinnedIndex * 100}%)` }">
            <router-link
              v-for="(notice, index) in pinnedNotices"
              :key="notice.id"
              :to="`/resident/notices/${notice.id}`"
              class="pinned-card"
            >
              <img class="pinned-cover" :src="noticeBgOf(notice, index)" :alt="notice.title" />
              <span class="pin-mark">置顶</span>
              <div class="pinned-main">
                <div class="pinned-title-row">
                  <h2 class="pinned-title">{{ notice.title }}</h2>
                </div>
                <p class="pinned-excerpt">{{ notice.content }}</p>
                <p class="pinned-meta">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="9" />
                    <path d="M12 7v5l3 3" />
                  </svg>
                  {{ formatDate(notice.publishTime) }} · 阅读 {{ notice.viewCount }}
                </p>
              </div>
            </router-link>
          </div>
          <button
            v-if="pinnedNotices.length > 1"
            type="button"
            class="carousel-arrow is-left"
            aria-label="上一条置顶公告"
            @click="goPinned(-1)"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="15 18 9 12 15 6" />
            </svg>
          </button>
          <button
            v-if="pinnedNotices.length > 1"
            type="button"
            class="carousel-arrow is-right"
            aria-label="下一条置顶公告"
            @click="goPinned(1)"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </button>
        </div>
        <div v-if="pinnedNotices.length > 1" class="pinned-thumbs">
          <button
            v-for="(notice, index) in pinnedNotices"
            :key="notice.id"
            type="button"
            class="pinned-thumb"
            :class="{ active: pinnedIndex === index }"
            :aria-label="`查看置顶公告 ${index + 1}：${notice.title}`"
            @click="setPinned(index)"
          >
            <img :src="noticeBgOf(notice, index)" alt="" />
            <span class="pinned-thumb-index">{{ index + 1 }}</span>
          </button>
        </div>
      </div>

      <!-- 公告列表：大白容器内日期块行（大字号日 + 年月 | 标题+摘要 | 阅读量） -->
      <div v-if="journalNotices.length > 0" class="journal-board">
        <router-link
          v-for="notice in journalNotices"
          :key="notice.id"
          :to="`/resident/notices/${notice.id}`"
          class="journal-item"
        >
          <div class="journal-date">
            <span class="journal-day">{{ dayOf(notice) }}</span>
            <span class="journal-ym">{{ yearMonthOf(notice) }}</span>
          </div>
          <span class="journal-sep" aria-hidden="true">|</span>
          <div class="journal-main">
            <h3 class="journal-title">{{ notice.title }}</h3>
            <p class="journal-excerpt">{{ notice.content }}</p>
          </div>
          <span class="journal-views">阅读 {{ notice.viewCount }}</span>
        </router-link>
      </div>

      <!-- 空状态：示例图 + 引导，而非一行灰字 -->
      <div v-if="!loading && notices.length === 0" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无公告" />
        <h3>暂无相关公告</h3>
        <p>换个关键字试试，社区有新动态时会第一时间在这里发布。</p>
      </div>
    </div>

    <Pagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      layout="prev, pager, next"
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

/* 衬线感大标题（期刊氛围，仅此页） */
.list-title {
  font-family: Georgia, 'Songti SC', 'SimSun', serif;
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  letter-spacing: 0.04em;
}

.list-sub {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.notice-body {
  min-height: 200px;
  margin-bottom: var(--spacing-lg);
}

/* 置顶轮播：整宽视口一次一张卡；箭头/缩略图平时半透明虚化，
   悬浮轮播区时完全显形 */
.pinned-carousel {
  position: relative;
  margin: 0 0 var(--spacing-xl);
}

.pinned-viewport {
  position: relative;
  overflow: hidden;
  border-radius: var(--radius-lg);
}

.pinned-track {
  display: flex;
  transition: transform 0.45s ease;
}

/* 左右切换箭头：虚化毛玻璃圆钮（与房源详情图片区同款交互语言），贴视口左右边缘 */
.carousel-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: none;
  border-radius: var(--radius-circle);
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: var(--color-text-primary);
  box-shadow: var(--shadow-md);
  cursor: pointer;
  opacity: 0.26;
  transition: opacity 0.2s ease, background 0.15s ease, color 0.15s ease;
}

.pinned-carousel:hover .carousel-arrow,
.carousel-arrow:focus-visible {
  opacity: 0.85;
}

.carousel-arrow:hover {
  background: rgba(255, 255, 255, 0.85);
  color: var(--color-primary);
}

.carousel-arrow.is-left {
  left: var(--spacing-md);
}

.carousel-arrow.is-right {
  right: var(--spacing-md);
}

.carousel-arrow svg {
  width: 18px;
  height: 18px;
}

/* 缩略图导航：与轮播容器同宽的贴底长条，高度加大、缩略图卡放大；
   两态透明度（常态 0.75 / 背景 16%，悬浮 0.88 / 背景 28%）+ 同款虚化 */
.pinned-thumbs {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  display: flex;
  justify-content: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  background: rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  opacity: 0.75;
  transition: opacity 0.2s ease, background 0.15s ease;
}

.pinned-carousel:hover .pinned-thumbs,
.pinned-thumbs:focus-within {
  opacity: 0.88;
  background: rgba(15, 23, 42, 0.28);
}

.pinned-thumb {
  position: relative;
  width: 120px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: var(--radius-md);
  overflow: hidden;
  aspect-ratio: 3 / 2;
  background: var(--color-bg-hover);
  cursor: pointer;
  opacity: 0.55;
  filter: saturate(0.85);
  transition: opacity 0.2s ease, border-color 0.15s ease, filter 0.15s ease;
}

.pinned-carousel:hover .pinned-thumb,
.pinned-thumb:focus-visible {
  opacity: 1;
  filter: none;
}

.pinned-thumb.active {
  border-color: var(--color-primary);
  opacity: 1;
  filter: none;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.35);
}

.pinned-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.pinned-thumb-index {
  position: absolute;
  right: 4px;
  bottom: 4px;
  min-width: 20px;
  padding: 0 4px;
  border-radius: var(--radius-sm);
  background: rgba(15, 23, 42, 0.65);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}

/* 置顶轮播卡：背景实体感大图铺满 + 右侧竖排文字（背景全透明，可读性由卡片级
   右侧压暗渐变承载）+ 置顶徽章右上角；16:9 比例并按上限收高 */
.pinned-card {
  position: relative;
  flex: 0 0 100%;
  display: block;
  aspect-ratio: 16 / 9;
  /* 高度钳制：与列表同宽后 16:9 全宽过高，宽屏时按上限收高，图 object-fit 铺满 */
  max-height: 500px;
  min-height: 260px;
  background: var(--color-bg-hover);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.pinned-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

/* 背景实体层：大图铺满 + 底部压暗渐变保证浮层文字可读 */
.pinned-cover {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.pinned-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to left, rgba(15, 23, 42, 0.58) 0%, rgba(15, 23, 42, 0.22) 42%, rgba(15, 23, 42, 0) 68%);
}

/* 文字信息容器：右侧竖排、背景全透明；右侧留出箭头通道，
   右箭头贴容器右缘后文字不与其重叠 */
.pinned-main {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1;
  width: 42%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--spacing-xs);
  /* 右侧 64px 箭头通道：右缘 16px 箭头（38px 宽）+ 缓冲，文字不与其重叠 */
  padding: var(--spacing-lg) calc(var(--spacing-xl) + 40px) calc(var(--spacing-xl) + 6%) var(--spacing-xl);
  min-width: 0;
}

.pinned-title-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

/* 置顶徽章：卡片右上角角标 */
.pin-mark {
  position: absolute;
  top: var(--spacing-md);
  right: var(--spacing-md);
  z-index: 2;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  box-shadow: var(--shadow-md);
}

.pinned-title {
  min-width: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-shadow: 0 1px 2px rgba(15, 23, 42, 0.35);
}

.pinned-excerpt {
  font-size: var(--font-size-sm);
  color: rgba(255, 255, 255, 0.92);
  line-height: var(--line-height-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.pinned-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: rgba(255, 255, 255, 0.78);
}

.pinned-meta svg {
  width: 13px;
  height: 13px;
}

/* 期刊列表：一个大白容器，行间细分隔线 */
.journal-board {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 0 var(--spacing-lg);
}

.journal-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) 0;
  border-bottom: 1px solid #eef0f3;
}

.journal-item:last-child {
  border-bottom: none;
}

.journal-item:hover .journal-title {
  color: var(--color-primary);
}

/* 日期块：大字号日 + 小灰年月 */
.journal-date {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 56px;
}

.journal-day {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.journal-ym {
  margin-top: 2px;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.journal-sep {
  flex-shrink: 0;
  color: var(--color-border);
  font-weight: var(--font-weight-normal);
}

.journal-main {
  flex: 1;
  min-width: 0;
}

.journal-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.15s ease;
}

.journal-excerpt {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.journal-views {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  white-space: nowrap;
}

/* 空状态 */
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

/* 响应式：窄屏置顶卡降上下结构，缩略图导航维持居中 */
@media (max-width: 768px) {
  .pinned-card {
    aspect-ratio: auto;
    min-height: 240px;
  }

  .pinned-main {
    width: 100%;
    top: auto;
    justify-content: flex-end;
    padding-bottom: var(--spacing-xl);
  }
}
</style>
