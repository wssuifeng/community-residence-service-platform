<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getNotice,
  publishNotice,
  withdrawNotice,
  deleteNotice,
  listNoticeViewers
} from '@/api/notice'
import type { INotice, INoticeViewer } from '@/types/modules/notice'
import {
  noticeStatusLabels,
  noticePriorityLabels,
  noticeTypeLabels,
  targetAudienceLabels
} from '@/types/modules/notice'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 公告详情（管理端）：正文 + 操作（发布/撤回/编辑/删除）+ 查看回执统计与查看者列表 */
const route = useRoute()
const router = useRouter()

const noticeId = Number(route.params.id)
const notice = ref<INotice | null>(null)
const loading = ref(true)

const viewers = ref<INoticeViewer[]>([])
const viewerTotal = ref(0)
const viewerPage = ref(1)
const viewerSize = ref(10)
const viewersLoading = ref(false)

function statusTagType(
  status: INotice['status']
): 'pending' | 'completed' | 'canceled' | 'rejected' {
  if (status === 'PUBLISHED') return 'completed'
  if (status === 'DRAFT') return 'pending'
  if (status === 'EXPIRED') return 'canceled'
  return 'rejected'
}

async function loadNotice(): Promise<void> {
  loading.value = true
  try {
    notice.value = await getNotice(noticeId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告加载失败')
    router.replace('/admin/notices')
  } finally {
    loading.value = false
  }
}

async function loadViewers(): Promise<void> {
  viewersLoading.value = true
  try {
    const result = await listNoticeViewers(noticeId, {
      page: viewerPage.value,
      size: viewerSize.value
    })
    viewers.value = result.records
    viewerTotal.value = result.total
  } catch {
    viewers.value = []
    viewerTotal.value = 0
  } finally {
    viewersLoading.value = false
  }
}

async function handlePublish(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定发布该公告？发布后居民端立即可见。', '发布公告', {
      type: 'info',
      confirmButtonText: '发布',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await publishNotice(noticeId, { publishTime: new Date().toISOString() })
    ElMessage.success('公告已发布')
    loadNotice()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  }
}

async function handleWithdraw(): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt('请输入撤回原因', '撤回公告', {
      type: 'warning',
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      inputValidator: (value: string) => (value.trim().length > 0 ? true : '撤回原因不能为空')
    })
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await withdrawNotice(noticeId, { reason })
    ElMessage.success('公告已撤回')
    loadNotice()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤回失败')
  }
}

function goEdit(): void {
  router.push(`/admin/notices/create?id=${noticeId}`)
}

async function handleDelete(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定删除该公告？删除后不可恢复。', '删除公告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteNotice(noticeId)
    ElMessage.success('公告已删除')
    router.replace('/admin/notices')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  loadNotice()
  loadViewers()
})
</script>

<template>
  <section class="notice-detail-admin">
    <header class="page-head">
      <h1>公告详情</h1>
      <div class="head-actions">
        <el-button v-if="notice?.status === 'DRAFT'" type="primary" @click="handlePublish">
          发布
        </el-button>
        <el-button v-if="notice?.status === 'DRAFT'" @click="goEdit">编辑</el-button>
        <el-button v-if="notice?.status === 'PUBLISHED'" type="warning" @click="handleWithdraw">
          撤回
        </el-button>
        <el-button
          v-if="notice?.status === 'DRAFT' || notice?.status === 'EXPIRED'"
          type="danger"
          @click="handleDelete"
        >
          删除
        </el-button>
        <el-button text @click="router.push('/admin/notices')">← 返回列表</el-button>
      </div>
    </header>

    <div v-loading="loading" class="detail-body">
      <template v-if="notice">
        <!-- 基本信息息 -->
        <el-descriptions :column="2" border class="info-descriptions">
          <el-descriptions-item label="公告ID">{{ notice.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag
              :label="noticeStatusLabels[notice.status]"
              :type="statusTagType(notice.status)"
            />
          </el-descriptions-item>
          <el-descriptions-item label="标题" :span="2">{{ notice.title }}</el-descriptions-item>
          <el-descriptions-item label="所属社区">
            {{ notice.communityName ?? '全系统广播' }}
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ noticeTypeLabels[notice.type] }}
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            {{ noticePriorityLabels[notice.priority] }}
          </el-descriptions-item>
          <el-descriptions-item label="定向范围">
            {{ targetAudienceLabels[notice.targetAudience] }}
          </el-descriptions-item>
          <el-descriptions-item label="发布人">{{ notice.publisherName }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">
            {{ formatDateTime(notice.publishTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="有效期至">
            {{ formatDateTime(notice.expireTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="阅读数">{{ notice.viewCount }}</el-descriptions-item>
        </el-descriptions>

        <!-- 正文 -->
        <div class="content-block">
          <h2 class="block-title">公告正文</h2>
          <div class="content-text">
            <p v-for="(paragraph, index) in notice.content.split(/\n+/).filter(Boolean)" :key="index">
              {{ paragraph }}
            </p>
          </div>
        </div>

        <!-- 查看回执统计 -->
        <div class="content-block">
          <h2 class="block-title">查看回执</h2>
          <div class="viewer-stats">
            <div class="stat-item">
              <span class="stat-value">{{ notice.viewCount }}</span>
              <span class="stat-label">已读次数</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ viewerTotal }}</span>
              <span class="stat-label">已读人数</span>
            </div>
          </div>

          <el-table v-loading="viewersLoading" :data="viewers" stripe>
            <el-table-column prop="residentId" label="居民ID" width="100" />
            <el-table-column prop="residentName" label="居民姓名" min-width="160" />
            <el-table-column label="查看时间" width="200">
              <template #default="{ row }">{{ formatDateTime(row.viewedAt) }}</template>
            </el-table-column>
          </el-table>
          <el-empty
            v-if="!viewersLoading && viewers.length === 0"
            description="暂无居民查看记录"
            :image-size="60"
          />

          <Pagination
            v-model:page="viewerPage"
            v-model:size="viewerSize"
            :total="viewerTotal"
            @update:page="loadViewers"
            @update:size="loadViewers"
          />
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.notice-detail-admin {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.head-actions {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.detail-body {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-height: 200px;
}

.info-descriptions {
  width: 100%;
}

.content-block {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.block-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  padding-left: var(--spacing-sm);
  border-left: 3px solid var(--color-primary);
}

.content-text {
  padding: var(--spacing-md);
  background-color: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
}

.content-text p {
  margin: 0 0 var(--spacing-md);
  white-space: pre-wrap;
  word-break: break-word;
}

.content-text p:last-child {
  margin-bottom: 0;
}

.viewer-stats {
  display: flex;
  gap: var(--spacing-lg);
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  min-width: 120px;
  padding: var(--spacing-md);
  background-color: var(--color-primary-bg);
  border-radius: var(--radius-md);
}

.stat-value {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
