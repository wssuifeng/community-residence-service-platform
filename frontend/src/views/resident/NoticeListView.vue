<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listNotices } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { formatRelative } from '@/utils/date'

/** 居民端公告列表：卡片流，置顶（高优先级）在前，带发布相对时间 */
const router = useRouter()

const notices = ref<INotice[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listNotices({ page: page.value, size: size.value })
    notices.value = result.records
    total.value = result.total
  } catch {
    notices.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

function goDetail(id: number): void {
  router.push(`/resident/notices/${id}`)
}
</script>

<template>
  <section class="notice-list-page">
    <header class="page-head">
      <h1>社区公告</h1>
      <p>了解社区最新动态与重要通知</p>
    </header>

    <div v-if="loading" class="page-loading">加载中…</div>

    <template v-else>
      <div v-if="notices.length === 0" class="page-empty">
        暂无公告，社区有新消息会第一时间通知您
      </div>

      <ul v-else class="notice-cards">
        <li
          v-for="notice in notices"
          :key="notice.id"
          class="notice-card"
          @click="goDetail(notice.id)"
        >
          <div class="notice-card-head">
            <span
              v-if="notice.priority === 'HIGH' || notice.priority === 'URGENT'"
              class="notice-pin"
            >
              置顶
            </span>
            <h2 class="notice-title">{{ notice.title }}</h2>
          </div>
          <p class="notice-summary">{{ notice.content }}</p>
          <div class="notice-meta">
            <span class="notice-community">{{ notice.communityName ?? '全社区' }}</span>
            <span class="notice-dot">·</span>
            <span>{{ formatRelative(notice.publishTime) }}</span>
            <span class="notice-views">{{ notice.viewCount }} 人已读</span>
          </div>
        </li>
      </ul>

      <div class="page-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.notice-list-page {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head h1 {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.page-loading,
.page-empty {
  padding: var(--spacing-xxl) 0;
  text-align: center;
  color: var(--color-text-secondary);
}

.notice-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.notice-card {
  padding: var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}

.notice-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.notice-card-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.notice-pin {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--status-rejected);
  background-color: rgba(239, 68, 68, 0.1);
}

.notice-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice-summary {
  margin: 0 0 var(--spacing-md);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notice-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.notice-community {
  color: var(--color-primary);
}

.notice-views {
  margin-left: auto;
}

.page-pagination {
  display: flex;
  justify-content: center;
  padding: var(--spacing-md) 0;
}
</style>
