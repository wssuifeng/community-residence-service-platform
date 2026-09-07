<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getNotice, recordNoticeView } from '@/api/notice'
import type { INotice } from '@/types/modules/notice'
import { noticeTypeLabels } from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'

/** 公告详情（公开）：纯阅读排版；不可见（未发布/需登录）时引导登录 */

const route = useRoute()

const notice = ref<INotice | null>(null)
const loading = ref(false)
const loadError = ref('')

/** 正文按空行分段，保持后端纯文本排版的阅读节奏 */
const paragraphs = computed<string[]>(() => {
  if (!notice.value) return []
  return notice.value.content
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
})

async function load(): Promise<void> {
  const id = Number(route.params.id)
  if (!Number.isFinite(id)) {
    loadError.value = '公告不存在'
    return
  }
  loading.value = true
  try {
    notice.value = await getNotice(id)
    /* 查看记录为尽力而为的埋点（同一用户同一公告仅记录一次），失败不阻塞阅读 */
    recordNoticeView(id).catch(() => undefined)
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

    <!-- 阅读排版 -->
    <template v-else-if="notice">
      <header class="detail-head">
        <div class="detail-badges">
          <span class="type-mark">{{ noticeTypeLabels[notice.type] }}</span>
          <span v-if="notice.communityName" class="community-mark">
            {{ notice.communityName }}
          </span>
        </div>
        <h1 class="detail-title">{{ notice.title }}</h1>
        <div class="detail-meta">
          <span>发布人：{{ notice.publisherName }}</span>
          <span>发布时间：{{ formatDateTime(notice.publishTime) }}</span>
          <span>{{ notice.viewCount }} 次阅读</span>
        </div>
      </header>

      <div class="detail-body">
        <p v-for="(paragraph, index) in paragraphs" :key="index" class="detail-paragraph">
          {{ paragraph }}
        </p>
      </div>

      <footer class="detail-foot">
        <router-link to="/guest/notices">← 返回公告列表</router-link>
      </footer>
    </template>
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

/* 阅读排版：白底限宽长文 */
.detail-head {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl);
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}

.detail-badges {
  display: flex;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
}

.type-mark {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.community-mark {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.detail-title {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.detail-meta {
  display: flex;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.detail-body {
  background: #fff;
  border: 1px solid var(--color-border);
  border-top: none;
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
  padding: var(--spacing-xl);
}

.detail-paragraph {
  max-width: 46em;
  font-size: var(--font-size-md);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
}

.detail-paragraph + .detail-paragraph {
  margin-top: var(--spacing-md);
}

.detail-foot {
  margin-top: var(--spacing-lg);
  font-size: var(--font-size-sm);
}
</style>
