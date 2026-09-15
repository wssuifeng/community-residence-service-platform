<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getNotice, listNotices } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'

/** 公告详情（公开）：加宽阅读容器（封面 + 正文 + 上一篇/下一篇）；不可见时引导登录 */

const route = useRoute()
const router = useRouter()

const noticeId = Number(route.params.id)
const notice = ref<INotice | null>(null)
const loading = ref(false)
const loadError = ref('')
const prevNotice = ref<INotice | null>(null)
const nextNotice = ref<INotice | null>(null)

/* 置顶判定：isPinned 真实字段（0/1）优先，旧布尔命名留兜底（优先级是展示属性，不参与置顶） */
const isPinned = computed(
  () => notice.value?.isPinned === 1 || notice.value?.pinned === true
)

/** 正文按空行分段，保持后端纯文本排版的阅读节奏 */
const paragraphs = computed<string[]>(() => {
  if (!notice.value) return []
  return notice.value.content
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
})

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
  router.push(`/guest/notices/${id}`)
}

async function load(): Promise<void> {
  if (!Number.isFinite(noticeId)) {
    loadError.value = '公告不存在'
    return
  }
  loading.value = true
  try {
    /* 游客无回执主体（notice_view_record 以 resident_id 记录，接口权限 RESIDENT），
       仅阅读公开详情，不调用查看回执接口 */
    notice.value = await getNotice(noticeId)
    void loadNeighbors()
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '公告加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <article class="notice-detail">
    <nav class="breadcrumb">
      <router-link to="/guest/notices">公告列表</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">公告详情</span>
    </nav>

    <!-- 加载骨架 -->
    <div v-if="loading" class="detail-skeleton">
      <div class="skeleton-line is-title"></div>
      <div class="skeleton-line is-meta"></div>
      <div class="skeleton-line"></div>
      <div class="skeleton-line"></div>
      <div class="skeleton-line is-short"></div>
    </div>

    <!-- 不可见/加载失败：引导登录后查看 -->
    <div v-else-if="loadError" class="error-panel">
      <h2>暂时无法查看这条公告</h2>
      <p>{{ loadError }}</p>
      <p class="error-hint">部分公告仅对登录用户可见，登录后再来看看。</p>
      <div class="error-actions">
        <router-link
          :to="`/auth/login?redirect=${encodeURIComponent(route.fullPath)}`"
          class="error-btn is-primary"
        >
          登录后查看
        </router-link>
        <router-link to="/guest/notices" class="error-btn">返回公告列表</router-link>
      </div>
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
  </article>
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
}

/* 加载骨架 */
.detail-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  max-width: 1000px;
  margin: 0 auto;
  width: 100%;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl);
}

.skeleton-line {
  height: var(--spacing-md);
  border-radius: var(--radius-sm);
  background: var(--color-bg-hover);
  animation: skeleton-pulse 1.4s ease infinite;
}

.skeleton-line.is-title {
  width: 55%;
  height: var(--font-size-xl);
}

.skeleton-line.is-meta {
  width: 40%;
}

.skeleton-line.is-short {
  width: 25%;
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

/* 不可见/失败面板 */
.error-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  max-width: 1000px;
  margin: 0 auto;
  width: 100%;
  padding: var(--spacing-xxl) var(--spacing-lg);
  background: #fff;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  text-align: center;
}

.error-panel h2 {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.error-panel p {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.error-hint {
  color: var(--color-text-disabled);
}

.error-actions {
  display: flex;
  gap: var(--spacing-md);
  margin-top: var(--spacing-sm);
}

.error-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xs) var(--spacing-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.error-btn.is-primary {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
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
