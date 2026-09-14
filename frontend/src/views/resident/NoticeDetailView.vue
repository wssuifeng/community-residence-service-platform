<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getNotice, listNotices, recordNoticeView } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'

/** 居民端公告详情：与游客端同语言（加宽阅读容器 + 封面 + 上一篇/下一篇）+ 查看回执 */
const route = useRoute()
const router = useRouter()

const noticeId = Number(route.params.id)
const notice = ref<INotice | null>(null)
const loading = ref(true)
const notFound = ref(false)
const prevNotice = ref<INotice | null>(null)
const nextNotice = ref<INotice | null>(null)

/* 置顶判定：兼容旧 priority 枚举与 is_pinned 布尔列 */
const isPinned = computed(
  () =>
    notice.value?.priority === 'HIGH' ||
    notice.value?.priority === 'URGENT' ||
    notice.value?.pinned === true ||
    notice.value?.isPinned === true
)

/** 正文按空行分段展示 */
const paragraphs = computed(() =>
  (notice.value?.content ?? '').split(/\n+/).filter((line) => line.trim() !== '')
)

/* 上一篇/下一篇：无相邻公告接口，用列表接口取一页（后端按优先级+时间排序），
   在序列中定位当前公告取前后邻居；当前公告不在首页序列时降级为省略 */
async function loadNeighbors(): Promise<void> {
  try {
    const result = await listNotices({ page: 1, size: 50 })
    const index = result.records.findIndex((item) => item.id === noticeId)
    if (index === -1) return
    prevNotice.value = result.records[index - 1] ?? null
    nextNotice.value = result.records[index + 1] ?? null
  } catch {
    /* 邻居加载失败静默省略 */
  }
}

function goNotice(id: number): void {
  router.push(`/resident/notices/${id}`)
}

onMounted(async () => {
  try {
    notice.value = await getNotice(noticeId)
    /* 查看回执：失败不阻塞阅读（后端同一用户同一公告仅记录一次） */
    recordNoticeView(noticeId).catch(() => undefined)
    void loadNeighbors()
  } catch {
    notFound.value = true
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="notice-detail">
    <nav class="page-nav">
      <div class="breadcrumb">
        <router-link to="/resident/notices">社区公告</router-link>
        <span class="breadcrumb-sep">/</span>
        <span class="breadcrumb-current">公告详情</span>
      </div>
      <router-link to="/resident/notices" class="back-link">返回列表</router-link>
    </nav>

    <div v-if="loading" class="page-loading">加载中…</div>

    <div v-else-if="notFound" class="page-empty">
      公告不存在或已下线
      <router-link to="/resident/notices" class="empty-link">返回列表</router-link>
    </div>

    <!-- 阅读容器：置顶标独立一行 → 衬线大标题 → 元信息 → 分隔线 → 封面 → 正文 → 上一篇/下一篇 -->
    <div v-else-if="notice" class="notice-article">
      <span v-if="isPinned" class="pin-badge">置顶</span>
      <h1 class="article-title">{{ notice.title }}</h1>
      <div class="article-meta">
        <span>{{ formatDateTime(notice.publishTime) }} 发布</span>
        <span class="meta-dot">·</span>
        <span>阅读 {{ notice.viewCount }}</span>
        <span class="meta-dot">·</span>
        <span>{{ notice.communityName ?? '全社区' }}</span>
      </div>

      <div class="article-divider"></div>

      <!-- notice 无封面字段，统一用占位插画封面 -->
      <img class="article-cover" src="/images/notice-cover-default.png" :alt="notice.title" />

      <div class="article-body">
        <p v-for="(paragraph, index) in paragraphs" :key="index" class="article-paragraph">
          {{ paragraph }}
        </p>
      </div>

      <footer v-if="notice.endTime ?? notice.expireTime" class="article-expire">
        本公告有效期至 {{ formatDateTime(notice.endTime ?? notice.expireTime) }}
      </footer>

      <!-- 上一篇/下一篇：收进容器底部，取不到邻居时不渲染（不做死链接） -->
      <nav v-if="prevNotice || nextNotice" class="article-neighbors">
        <button
          v-if="prevNotice"
          type="button"
          class="neighbor-link"
          @click="goNotice(prevNotice.id)"
        >
          ‹ 上一篇：{{ prevNotice.title }}
        </button>
        <button
          v-if="nextNotice"
          type="button"
          class="neighbor-link is-next"
          @click="goNotice(nextNotice.id)"
        >
          下一篇：{{ nextNotice.title }} ›
        </button>
      </nav>
    </div>
  </section>
</template>

<style scoped>
.notice-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}

.back-link {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
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

/* 加宽阅读容器（约内容区 80%） */
.notice-article {
  max-width: 1000px;
  width: 100%;
  margin: 0 auto;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl) var(--spacing-xxl);
  box-shadow: var(--shadow-sm);
}

/* 置顶标：独立一行，不与标题挤 */
.pin-badge {
  display: inline-flex;
  margin-bottom: var(--spacing-md);
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: var(--color-danger);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

/* 衬线感大标题（与列表页头同语言） */
.article-title {
  margin: 0 0 var(--spacing-md);
  font-family: Georgia, 'Songti SC', 'SimSun', serif;
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
}

.article-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.meta-dot {
  color: var(--color-border);
}

.article-divider {
  margin: var(--spacing-md) 0 var(--spacing-lg);
  border-top: 1px solid var(--color-border);
}

/* 封面：通栏圆角 */
.article-cover {
  display: block;
  width: 100%;
  aspect-ratio: 21 / 9;
  object-fit: cover;
  border-radius: var(--radius-lg);
  margin-bottom: var(--spacing-lg);
}

/* 阅读排版：行高宽松，按段落渲染 */
.article-body {
  font-size: var(--font-size-md);
  line-height: 1.9;
  color: var(--color-text-primary);
}

.article-paragraph {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.article-paragraph + .article-paragraph {
  margin-top: var(--spacing-md);
}

.article-expire {
  margin-top: var(--spacing-lg);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 上一篇/下一篇：容器底部，细分隔线隔开 */
.article-neighbors {
  display: flex;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-top: var(--spacing-xl);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border);
}

.neighbor-link {
  max-width: 48%;
  border: none;
  background: none;
  padding: 0;
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}

.neighbor-link.is-next {
  margin-left: auto;
  text-align: right;
}

.neighbor-link:hover {
  text-decoration: underline;
}
</style>
