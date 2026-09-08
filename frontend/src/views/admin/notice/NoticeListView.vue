<script setup lang="ts">
/** 后端 Jackson 不解析带 Z 的 ISO 时间，统一转本地无时区格式 */
function toLocalIso(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listNotices,
  publishNotice,
  withdrawNotice,
  deleteNotice
} from '@/api/notice'
import type { INotice, NoticePriority } from '@/types/modules/notice'
import {
  noticeStatusLabels,
  noticePriorityLabels,
  noticeTypeLabels
} from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 公告管理列表：筛选 + 发布/撤回/删除操作 + 新建入口 */
const router = useRouter()

const notices = ref<INotice[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const priorityFilter = ref<NoticePriority | ''>('')

/** 状态 → StatusTag 语义色 */
function statusTagType(status: INotice['status']): 'pending' | 'completed' | 'canceled' | 'rejected' {
  if (status === 'PUBLISHED') return 'completed'
  if (status === 'DRAFT') return 'pending'
  if (status === 'EXPIRED') return 'canceled'
  return 'rejected'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listNotices({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      priority: priorityFilter.value || undefined
    })
    notices.value = result.records
    total.value = result.total
  } catch {
    notices.value = []
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
  priorityFilter.value = ''
  page.value = 1
  load()
}

function goCreate(): void {
  router.push('/admin/notices/create')
}

function goDetail(id: number): void {
  router.push(`/admin/notices/${id}`)
}

/** 发布：立即发布（publishTime 取当前时间） */
async function handlePublish(row: INotice): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定发布公告「${row.title}」？发布后居民端立即可见。`,
      '发布公告',
      { type: 'info', confirmButtonText: '发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await publishNotice(row.id, { publishTime: toLocalIso(new Date()) })
    ElMessage.success('公告已发布')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  }
}

/** 撤回（下线）：需填写撤回原因 */
async function handleWithdraw(row: INotice): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt('请输入撤回原因', `撤回「${row.title}」`, {
      type: 'warning',
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      inputValidator: (value: string) =>
        value.trim().length > 0 ? true : '撤回原因不能为空'
    })
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await withdrawNotice(row.id, { reason })
    ElMessage.success('公告已撤回')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤回失败')
  }
}

/** 删除（仅草稿/已过期） */
async function handleDelete(row: INotice): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除公告「${row.title}」？删除后不可恢复。`, '删除公告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteNotice(row.id)
    ElMessage.success('公告已删除')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-notice-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索公告标题/内容" @search="handleSearch" />
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="goCreate">新建公告</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">优先级</span>
      <el-select v-model="priorityFilter" style="width: 140px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in noticePriorityLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="notices" stripe>
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goDetail(row.id)">{{ row.title }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="社区" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.communityName ?? '全系统' }}</template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ noticeTypeLabels[row.type as keyof typeof noticeTypeLabels] }}</template>
      </el-table-column>
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">
          {{ noticePriorityLabels[row.priority as keyof typeof noticePriorityLabels] }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag :label="noticeStatusLabels[row.status as keyof typeof noticeStatusLabels]" :type="statusTagType(row.status)" />
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.publishTime) }}</template>
      </el-table-column>
      <el-table-column label="过期时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.expireTime) }}</template>
      </el-table-column>
      <el-table-column prop="viewCount" label="阅读数" width="90" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            link
            type="success"
            @click="handlePublish(row)"
          >
            发布
          </el-button>
          <el-button
            v-if="row.status === 'PUBLISHED'"
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            link
            type="warning"
            @click="handleWithdraw(row)"
          >
            下线
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'EXPIRED'"
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            link
            type="danger"
            @click="handleDelete(row)"
          >
            删除
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
