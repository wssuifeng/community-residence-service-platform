<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getNotice, recordNoticeView } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { noticePriorityLabels } from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'

/** 居民端公告详情：正文排版 + 进入即上报查看回执（后端幂等，同一用户仅记录一次） */
const route = useRoute()
const router = useRouter()

const noticeId = Number(route.params.id)
const notice = ref<INotice | null>(null)
const loading = ref(true)
const notFound = ref(false)

/** 优先级 → StatusTag 语义色 */
const priorityTagType = computed(() =>
  notice.value?.priority === 'URGENT'
    ? 'rejected'
    : notice.value?.priority === 'HIGH'
      ? 'pending'
      : 'info'
)

/** 正文按空行分段展示 */
const paragraphs = computed(() =>
  (notice.value?.content ?? '').split(/\n+/).filter((line) => line.trim() !== '')
)

onMounted(async () => {
  try {
    notice.value = await getNotice(noticeId)
    /* 查看回执：失败不阻塞阅读（后端同一用户同一公告仅记录一次） */
    recordNoticeView(noticeId).catch(() => undefined)
  } catch {
    notFound.value = true
  } finally {
    loading.value = false
  }
})

function goBack(): void {
  router.back()
}
</script>

<template>
  <section class="notice-detail">
    <button type="button" class="back-link" @click="goBack">← 返回公告列表</button>

    <div v-if="loading" class="page-loading">加载中…</div>

    <div v-else-if="notFound" class="page-empty">
      公告不存在或已下线
      <router-link to="/resident/notices" class="empty-link">返回列表</router-link>
    </div>

    <article v-else-if="notice" class="notice-article">
      <header class="article-head">
        <div class="article-badges">
          <StatusTag
            v-if="notice.priority === 'HIGH' || notice.priority === 'URGENT'"
            :label="`置顶 · ${noticePriorityLabels[notice.priority]}`"
            :type="priorityTagType"
          />
          <span class="article-community">{{ notice.communityName ?? '全社区' }}</span>
        </div>
        <h1>{{ notice.title }}</h1>
        <div class="article-meta">
          <span>{{ notice.publisherName }} 发布</span>
          <span class="meta-dot">·</span>
          <span>{{ formatDateTime(notice.publishTime) }}</span>
          <span class="meta-dot">·</span>
          <span>{{ notice.viewCount }} 次阅读</span>
        </div>
      </header>

      <div class="article-body">
        <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
      </div>

      <footer v-if="notice.expireTime" class="article-footer">
        本公告有效期至 {{ formatDateTime(notice.expireTime) }}
      </footer>
    </article>
  </section>
</template>

<style scoped>
.notice-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.back-link {
  align-self: flex-start;
  border: none;
  background: none;
  padding: 0;
  color: var(--color-primary);
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.back-link:hover {
  opacity: 0.8;
}

.page-loading,
.page-empty {
  padding: var(--spacing-xxl) 0;
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-link {
  display: block;
  margin-top: var(--spacing-md);
  color: var(--color-primary);
}

.notice-article {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl) var(--spacing-xxl);
  box-shadow: var(--shadow-sm);
}

.article-badges {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.article-community {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.article-head h1 {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.article-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.meta-dot {
  color: var(--color-border);
}

.article-body {
  padding: var(--spacing-lg) 0;
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
}

.article-body p {
  margin: 0 0 var(--spacing-md);
  white-space: pre-wrap;
  word-break: break-word;
}

.article-body p:last-child {
  margin-bottom: 0;
}

.article-footer {
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
