<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listFeedbacks } from '@/api/feedback'
import type { IFeedback, FeedbackStatus, FeedbackCategory } from '@/types/modules/feedback'
import {
  feedbackStatusLabels,
  feedbackCategoryLabels
} from '@/types/modules/feedback'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/**
 * 反馈管理列表：状态/类别筛选 + 关键字搜索；
 * OPEN 行「受理」进入详情发送首条回复（发送消息即流转为会话中，接口设计 9.6.3.1）
 */
const router = useRouter()

const feedbacks = ref<IFeedback[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref<FeedbackStatus | ''>('')
const categoryFilter = ref<FeedbackCategory | ''>('')

/** 反馈状态 → StatusTag 语义色 */
function statusTagType(
  status: FeedbackStatus
): 'pending' | 'processing' | 'completed' {
  if (status === 'OPEN') return 'pending'
  if (status === 'IN_PROGRESS') return 'processing'
  return 'completed'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listFeedbacks({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
      category: categoryFilter.value || undefined
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

function handleSearch(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  keyword.value = ''
  statusFilter.value = ''
  categoryFilter.value = ''
  page.value = 1
  load()
}

function goDetail(id: number): void {
  router.push(`/admin/feedbacks/${id}`)
}

onMounted(load)
</script>

<template>
  <section class="admin-feedback-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索反馈标题/内容" @search="handleSearch" />
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">状态</span>
      <el-select v-model="statusFilter" style="width: 130px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in feedbackStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <span class="filter-label">类别</span>
      <el-select v-model="categoryFilter" style="width: 130px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in feedbackCategoryLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="feedbacks" stripe>
      <el-table-column prop="feedbackNumber" label="反馈编号" width="170" />
      <el-table-column label="居民" width="120">
        <template #default="{ row }">
          {{ row.isAnonymous ? '匿名' : row.residentName }}
        </template>
      </el-table-column>
      <el-table-column prop="communityName" label="社区" width="140" show-overflow-tooltip />
      <el-table-column label="类别" width="80">
        <template #default="{ row }">
          {{ feedbackCategoryLabels[row.category as keyof typeof feedbackCategoryLabels] }}
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goDetail(row.id)">{{ row.title }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag
            :label="feedbackStatusLabels[row.status as keyof typeof feedbackStatusLabels]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近更新" width="170">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
          <el-button
            v-if="row.status === 'OPEN'"
            link
            type="success"
            @click="goDetail(row.id)"
          >
            受理
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <Pagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      @update:page="load"
      @update:size="load"
    />
  </section>
</template>

<style scoped>
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
