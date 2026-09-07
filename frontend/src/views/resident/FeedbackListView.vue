<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listFeedbacks } from '@/api/feedback'
import type { IFeedback, FeedbackStatus } from '@/types/modules/feedback'
import {
  feedbackStatusLabels,
  feedbackCategoryLabels
} from '@/types/modules/feedback'
import { formatRelative } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'

/** 我的反馈列表：状态筛选 + 卡片流 */
const router = useRouter()

const feedbacks = ref<IFeedback[]>([])
const loading = ref(false)
const statusFilter = ref<FeedbackStatus | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 反馈状态 → StatusTag 语义色（OPEN 待受理黄 / IN_PROGRESS 蓝 / CLOSED 绿） */
function statusTagType(status: FeedbackStatus): 'pending' | 'processing' | 'completed' {
  if (status === 'OPEN') return 'pending'
  if (status === 'IN_PROGRESS') return 'processing'
  return 'completed'
}

const statusTabs: { label: string; value: FeedbackStatus | '' }[] = [
  { label: '全部', value: '' },
  { label: '待受理', value: 'OPEN' },
  { label: '会话中', value: 'IN_PROGRESS' },
  { label: '已办结', value: 'CLOSED' }
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listFeedbacks({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined
    })
    feedbacks.value = result.records
    total.value = result.total
  } catch {
    feedbacks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleTabChange(): void {
  page.value = 1
  load()
}

function goDetail(id: number): void {
  router.push(`/resident/feedbacks/${id}`)
}

function goCreate(): void {
  router.push('/resident/feedbacks/create')
}

onMounted(load)
</script>

<template>
  <section class="feedback-list-page">
    <header class="page-head">
      <div>
        <h1>我的反馈</h1>
        <p>您的建议与诉求，我们都会认真对待</p>
      </div>
      <el-button type="primary" round @click="goCreate">+ 提交反馈</el-button>
    </header>

    <el-tabs v-model="statusFilter" class="status-tabs" @tab-change="handleTabChange">
      <el-tab-pane
        v-for="tab in statusTabs"
        :key="tab.value"
        :label="tab.label"
        :name="tab.value"
      />
    </el-tabs>

    <div v-if="loading" class="page-loading">加载中…</div>

    <template v-else>
      <div v-if="feedbacks.length === 0" class="page-empty">
        还没有反馈记录，有问题随时告诉我们
      </div>

      <ul v-else class="feedback-cards">
        <li
          v-for="feedback in feedbacks"
          :key="feedback.id"
          class="feedback-card"
          @click="goDetail(feedback.id)"
        >
          <div class="card-head">
            <StatusTag
              :label="feedbackStatusLabels[feedback.status]"
              :type="statusTagType(feedback.status)"
            />
            <span class="card-category">{{ feedbackCategoryLabels[feedback.category] }}</span>
            <span class="card-number">{{ feedback.feedbackNumber }}</span>
          </div>
          <h2 class="card-title">{{ feedback.title }}</h2>
          <p class="card-summary">{{ feedback.content }}</p>
          <div class="card-meta">
            <span>提交于 {{ formatRelative(feedback.createdAt) }}</span>
            <span v-if="feedback.closeReason" class="card-closed">
              办结意见：{{ feedback.closeReason }}
            </span>
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
.feedback-list-page {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
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

.feedback-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.feedback-card {
  padding: var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}

.feedback-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.card-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.card-category {
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
}

.card-number {
  margin-left: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  font-family: var(--font-family-mono);
}

.card-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.card-summary {
  margin: 0 0 var(--spacing-md);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.card-closed {
  color: var(--status-completed);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-pagination {
  display: flex;
  justify-content: center;
  padding: var(--spacing-md) 0;
}
</style>
