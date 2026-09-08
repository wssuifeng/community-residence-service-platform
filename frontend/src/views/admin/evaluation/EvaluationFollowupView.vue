<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { addEvaluationFollowup, listEvaluationFollowups } from '@/api/evaluation'
import type { IEvaluationFollowup } from '@/types/modules/evaluation'
import { formatDateTime } from '@/utils/date'

/** 跟进记录（UI设计.md §3.4.8，路由 /admin/evaluations/:id/followup）：
 * 评价摘要 + 跟进时间线 + 添加跟进表单 */

const route = useRoute()
const router = useRouter()

/* 路由参数为评价 ID（router/admin.ts AdminEvaluationFollowup） */
const evaluationId = Number(route.params.id)

const records = ref<IEvaluationFollowup[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)

const followupForm = ref({
  action: '',
  result: '',
  remark: ''
})
const submitting = ref(false)

/* 跟进动作快捷选项（常用处置动作，可自由输入） */
const actionPresets = ['电话回访', '上门复核', '重新处理', '服务人员约谈', '补偿安抚', '其他']

async function fetchFollowups(): Promise<void> {
  loading.value = true
  try {
    const result = await listEvaluationFollowups(evaluationId, { page: page.value, size: size.value })
    /* 接口按评价时间倒序返回，页面正序展示时间线 */
    records.value = [...result.records].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '跟进记录加载失败')
  } finally {
    loading.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (!followupForm.value.action.trim()) {
    ElMessage.warning('请填写跟进动作')
    return
  }
  submitting.value = true
  try {
    await addEvaluationFollowup(evaluationId, {
      action: followupForm.value.action.trim(),
      result: followupForm.value.result.trim() || undefined,
      remark: followupForm.value.remark.trim() || undefined
    })
    ElMessage.success('跟进记录已添加')
    followupForm.value = { action: '', result: '', remark: '' }
    fetchFollowups()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加跟进失败')
  } finally {
    submitting.value = false
  }
}

onMounted(fetchFollowups)
</script>

<template>
  <section class="followup-page">
    <header class="page-header">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/admin/evaluations' }">评价列表</el-breadcrumb-item>
        <el-breadcrumb-item>跟进记录</el-breadcrumb-item>
      </el-breadcrumb>
      <el-button text @click="router.push('/admin/evaluations')">返回列表</el-button>
    </header>

    <article class="summary-card">
      <div class="summary-head">
        <h1 class="page-title">跟进记录</h1>
        <StatusTag label="不满意评价" type="rejected" />
      </div>
      <p class="summary-hint">
        评价 ID：{{ evaluationId }} · 评分低于 4 星的工单评价自动进入跟进，请记录每次处置动作与结果直至居民满意
      </p>
    </article>

    <article class="form-card">
      <h2 class="block-title">添加跟进</h2>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="跟进动作" required>
          <el-select
            v-model="followupForm.action"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入跟进动作"
            class="action-select"
          >
            <el-option v-for="item in actionPresets" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理结果">
          <el-input
            v-model="followupForm.result"
            type="textarea"
            :rows="2"
            maxlength="300"
            show-word-limit
            placeholder="该动作达成的结果，如：已重新上门维修完成"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="followupForm.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="其他说明（可选）"
          />
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="submitting" @click="handleSubmit">添加跟进记录</el-button>
        </div>
      </el-form>
    </article>

    <article class="timeline-card">
      <h2 class="block-title">跟进历史（{{ total }}）</h2>
      <div v-loading="loading">
        <el-timeline v-if="records.length > 0" class="timeline">
          <el-timeline-item
            v-for="item in records"
            :key="item.id"
            :timestamp="formatDateTime(item.createdAt)"
            type="primary"
          >
            <div class="timeline-head">
              <span class="timeline-action">{{ item.action }}</span>
              <span class="timeline-operator">{{ item.followerName }}</span>
            </div>
            <p v-if="item.result" class="timeline-text">
              <span class="timeline-label">结果：</span>{{ item.result }}
            </p>
            <p v-if="item.remark" class="timeline-text">
              <span class="timeline-label">备注：</span>{{ item.remark }}
            </p>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无跟进记录，请先添加第一条跟进" :image-size="80" />
      </div>
      <Pagination v-model:page="page" v-model:size="size" :total="total" />
    </article>
  </section>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.summary-card,
.form-card,
.timeline-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-md);
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.summary-hint {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.block-title {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.action-select {
  width: 280px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
}

.timeline {
  padding-left: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
}

.timeline-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.timeline-action {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.timeline-operator {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.timeline-text {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.timeline-label {
  color: var(--color-text-disabled);
}
</style>
