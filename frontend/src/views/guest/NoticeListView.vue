<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import { listNotices } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { formatDate } from '@/utils/date'

/** 公告列表（公开）：衬线页头 + 置顶横拉卡条 + 日期块期刊列表 */

const notices = ref<INotice[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

/** 置顶判定：兼容旧 priority 枚举与 notice 表 is_pinned 布尔列（VO 暴露前恒 false） */
function isPinned(notice: INotice): boolean {
  return notice.priority === 'HIGH' || notice.priority === 'URGENT'
    || notice.pinned === true || notice.isPinned === true
}

/** 置顶区与期刊列表按优先级拆分（保持后端返回顺序） */
const pinnedNotices = computed<INotice[]>(() => notices.value.filter(isPinned))
const journalNotices = computed<INotice[]>(() => notices.value.filter((notice) => !isPinned(notice)))

/* 置顶横拉卡条：左右悬浮箭头平滑滚动一屏的 80% */
const pinnedScroller = ref<HTMLElement | null>(null)

function scrollPinned(direction: 1 | -1): void {
  const el = pinnedScroller.value
  if (!el) return
  el.scrollBy({ left: direction * el.clientWidth * 0.8, behavior: 'smooth' })
}

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
  <div class="notice-list">
    <header class="list-head">
      <div>
        <h1 class="list-title">社区公告</h1>
        <p class="list-sub">了解社区最新动态与服务安排</p>
      </div>
      <SearchBar v-model="keyword" placeholder="搜索公告标题 / 内容" @search="handleSearch" />
    </header>

    <div v-loading="loading" class="notice-body">
      <!-- 置顶区：横向滚动长条卡 + 左右半透明悬浮箭头（notice 无封面字段，占位插画封面） -->
      <div v-if="pinnedNotices.length > 0" class="pinned-wrap">
        <button type="button" class="pinned-arrow is-left" aria-label="向左滚动" @click="scrollPinned(-1)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <div ref="pinnedScroller" class="pinned-strip">
          <router-link
            v-for="notice in pinnedNotices"
            :key="notice.id"
            :to="`/guest/notices/${notice.id}`"
            class="pinned-card"
          >
            <img class="pinned-cover" src="/images/notice-cover-default.png" :alt="notice.title" />
            <div class="pinned-main">
              <div class="pinned-title-row">
                <span class="pin-mark">置顶</span>
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
        <button type="button" class="pinned-arrow is-right" aria-label="向右滚动" @click="scrollPinned(1)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>
      </div>

      <!-- 公告列表：大白容器内日期块行（大字号日 + 年月 | 标题+摘要 | 阅读量） -->
      <div v-if="journalNotices.length > 0" class="journal-board">
        <router-link
          v-for="notice in journalNotices"
          :key="notice.id"
          :to="`/guest/notices/${notice.id}`"
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

/* 置顶横拉卡条：原生滚动条隐藏，悬浮箭头滚动 */
.pinned-wrap {
  position: relative;
  margin-bottom: var(--spacing-xl);
}

.pinned-strip {
  display: flex;
  gap: var(--spacing-md);
  overflow-x: auto;
  scrollbar-width: none;
  padding: var(--spacing-xs) 0;
}

.pinned-strip::-webkit-scrollbar {
  display: none;
}

.pinned-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-circle);
  background: rgba(255, 255, 255, 0.85);
  color: var(--color-text-secondary);
  box-shadow: var(--shadow-md);
  cursor: pointer;
  transition: color 0.15s ease;
}

.pinned-arrow:hover {
  color: var(--color-primary);
}

.pinned-arrow.is-left {
  left: var(--spacing-xs);
}

.pinned-arrow.is-right {
  right: var(--spacing-xs);
}

.pinned-arrow svg {
  width: 16px;
  height: 16px;
}

/* 置顶长条白卡：左 16:9 偏宽封面 + 右三段文本 */
.pinned-card {
  flex: 0 0 min(620px, 92%);
  display: grid;
  grid-template-columns: 260px 1fr;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.pinned-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.pinned-cover {
  width: 100%;
  height: 100%;
  min-height: 150px;
  object-fit: cover;
}

.pinned-main {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  min-width: 0;
}

.pinned-title-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.pin-mark {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.pinned-title {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pinned-excerpt {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
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
  margin-top: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
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

/* 响应式：窄屏置顶卡降上下结构 */
@media (max-width: 768px) {
  .pinned-card {
    grid-template-columns: 1fr;
  }
}
</style>
