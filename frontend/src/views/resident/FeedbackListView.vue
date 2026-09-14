<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listFeedbacks } from '@/api/feedback'
import type { IFeedback, FeedbackStatus, FeedbackCategory } from '@/types/modules/feedback'
import { feedbackStatusLabels, feedbackCategoryLabels } from '@/types/modules/feedback'
import { formatDate } from '@/utils/date'

/** 我的反馈列表：状态 Tab + 分类图标卡片流（状态枚举以后端实际 PENDING/IN_SESSION/CLOSED 为准） */
const router = useRouter()

const feedbacks = ref<IFeedback[]>([])
const loading = ref(false)
const statusFilter = ref<FeedbackStatus | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const inSessionTotal = ref<number | null>(null)

const statusTabs: { label: string; value: FeedbackStatus | '' }[] = [
  { label: '全部', value: '' },
  { label: '待受理', value: 'PENDING' },
  { label: '会话中', value: 'IN_SESSION' },
  { label: '已办结', value: 'CLOSED' }
]

/** 分类 → 圆形图标配色（建议蓝 / 投诉橙 / 咨询绿） */
const categoryColor: Record<FeedbackCategory, string> = {
  SUGGESTION: 'blue',
  COMPLAINT: 'orange',
  INQUIRY: 'green'
}

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

/* 副标题「进行中」计数（会话中）：只取 total，失败则不显示该段 */
async function loadInSessionTotal(): Promise<void> {
  try {
    const result = await listFeedbacks({ page: 1, size: 1, status: 'IN_SESSION' })
    inSessionTotal.value = result.total
  } catch {
    inSessionTotal.value = null
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

onMounted(() => {
  load()
  void loadInSessionTotal()
})
</script>

<template>
  <section class="feedback-list-page">
    <header class="page-head">
      <div>
        <h1>我的反馈</h1>
        <p>
          共 {{ total }} 条<template v-if="inSessionTotal !== null">，{{ inSessionTotal }} 条进行中</template>
        </p>
      </div>
      <el-button type="primary" @click="goCreate">+ 提交反馈</el-button>
    </header>

    <!-- 状态 Tab：当前项蓝色下划线（与我的工单同语言） -->
    <div class="status-tabs" role="tablist">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        type="button"
        role="tab"
        class="status-tab"
        :class="{ active: statusFilter === tab.value }"
        @click="statusFilter = tab.value; handleTabChange()"
      >
        {{ tab.label }}
      </button>
    </div>

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
          <span class="category-icon" :data-color="categoryColor[feedback.category]">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <template v-if="feedback.category === 'SUGGESTION'">
                <path d="M9 18h6M10 21h4" />
                <path d="M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z" />
              </template>
              <template v-else-if="feedback.category === 'COMPLAINT'">
                <path d="M12 9v4M12 17h.01" />
                <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
              </template>
              <template v-else>
                <circle cx="12" cy="12" r="9" />
                <path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2.6-3 4" />
                <path d="M12 17h.01" />
              </template>
            </svg>
          </span>

          <div class="card-main">
            <div class="card-head">
              <h2 class="card-title">{{ feedback.title }}</h2>
              <span class="status-pill" :data-status="feedback.status">
                {{ feedbackStatusLabels[feedback.status] }}
              </span>
            </div>
            <!-- 无最后消息字段，摘要取反馈内容 -->
            <p class="card-summary">{{ feedback.content }}</p>
            <!-- 后端无反馈编号与对话数字段，编号用 #id 近似 -->
            <p class="card-foot">反馈 #{{ feedback.id }} · {{ feedbackCategoryLabels[feedback.category] }}</p>
          </div>

          <span class="card-date">{{ formatDate(feedback.createdAt).slice(5) }}</span>
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

/* 状态 Tab 条（与我的工单同款） */
.status-tabs {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  padding: 0 var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.status-tab {
  position: relative;
  padding: var(--spacing-md) var(--spacing-xs);
  border: none;
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.status-tab.active {
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.status-tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
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

/* 反馈卡：左分类圆形图标 / 中内容 / 右日期 */
.feedback-card {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
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

.category-icon {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-circle);
  display: flex;
  align-items: center;
  justify-content: center;
}

.category-icon svg {
  width: 20px;
  height: 20px;
}

.category-icon[data-color='blue'] { background: var(--color-primary-bg); color: var(--color-primary); }
.category-icon[data-color='orange'] { background: #fef3e2; color: var(--color-warning); }
.category-icon[data-color='green'] { background: #e7f8f1; color: var(--color-success); }

.card-main {
  flex: 1;
  min-width: 0;
}

.card-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 状态胶囊：待受理红 / 会话中绿 / 已办结灰 */
.status-pill {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.status-pill[data-status='PENDING'] {
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

.status-pill[data-status='IN_SESSION'] {
  background: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.status-pill[data-status='CLOSED'] {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.card-summary {
  margin: var(--spacing-xs) 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-foot {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.card-date {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  padding-top: 2px;
}

.page-pagination {
  display: flex;
  justify-content: center;
  padding: var(--spacing-md) 0;
}
</style>
