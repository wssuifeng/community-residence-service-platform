<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import { listNotices } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { noticeTypeLabels } from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'

/** 公告列表（公开）：仅已发布可见（后端过滤），高优先级置顶在前 */

const notices = ref<INotice[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

/** 高优先级公告由后端置顶返回，前端补显「置顶」标记 */
function isPinned(notice: INotice): boolean {
  return notice.priority === 'HIGH' || notice.priority === 'URGENT'
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

    <ul v-loading="loading" class="notice-list-body">
      <li v-for="notice in notices" :key="notice.id">
        <router-link :to="`/guest/notices/${notice.id}`" class="notice-item">
          <div class="notice-main">
            <div class="notice-badges">
              <span v-if="isPinned(notice)" class="pin-mark">置顶</span>
              <span class="type-mark">{{ noticeTypeLabels[notice.type] }}</span>
            </div>
            <h3 class="notice-title">{{ notice.title }}</h3>
            <p class="notice-excerpt">
              {{ notice.content.length > 80 ? `${notice.content.slice(0, 80)}……` : notice.content }}
            </p>
          </div>
          <div class="notice-side">
            <span class="notice-time">{{ formatDateTime(notice.publishTime) }}</span>
            <span class="notice-views">{{ notice.viewCount }} 次阅读</span>
          </div>
        </router-link>
      </li>

      <!-- 空状态：示例图 + 引导，而非一行灰字 -->
      <li v-if="!loading && notices.length === 0" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无公告" />
        <h3>暂无相关公告</h3>
        <p>换个关键字试试，社区有新动态时会第一时间在这里发布。</p>
      </li>
    </ul>

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

.notice-list-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 200px;
}

.notice-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-lg);
  padding: var(--spacing-md) var(--spacing-lg);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.notice-item:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.notice-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.notice-badges {
  display: flex;
  gap: var(--spacing-xs);
}

.pin-mark {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.type-mark {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.notice-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.notice-excerpt {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notice-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

.notice-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.notice-views {
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

/* 响应式：窄屏侧栏信息收到正文下方 */
@media (max-width: 768px) {
  .notice-item {
    flex-direction: column;
    gap: var(--spacing-sm);
  }

  .notice-side {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    width: 100%;
  }
}
</style>
