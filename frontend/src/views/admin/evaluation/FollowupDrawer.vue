<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { addEvaluationFollowup, listEvaluationFollowups } from '@/api/evaluation'
import type { IEvaluation, IEvaluationFollowup } from '@/types/modules/evaluation'
import { formatDate, formatDateTime } from '@/utils/date'

/**
 * 跟进抽屉（原 /admin/evaluations/:id/followup 独立页收编，对照任务 11）：
 * 评价摘要 + 跟进表单 + 历史跟进时间线。提交成功后 emit('changed')，
 * 由父级刷新工作台分组与待跟进角标。
 *
 * 契约对齐（后端实测）：跟进单字段 content（@NotBlank ≤1000），
 * 旧页 action/result/remark 三字段为文档漂移、后端不识别；原跟进动作预设
 * 保留为快捷模板（点击写入【动作】前缀）。
 */
const props = defineProps<{
  modelValue: boolean
  evaluation: IEvaluation | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  changed: []
}>()

const visible = ref(props.modelValue)

watch(
  () => props.modelValue,
  (value) => {
    visible.value = value
    if (value && props.evaluation) {
      resetForm()
      loadFollowups()
    }
  }
)

watch(visible, (value) => {
  if (value !== props.modelValue) emit('update:modelValue', value)
})

/* ---------- 历史跟进时间线（后端按 id 正序返回纯数组） ---------- */

const records = ref<IEvaluationFollowup[]>([])
const loading = ref(false)

async function loadFollowups(): Promise<void> {
  if (!props.evaluation) return
  loading.value = true
  try {
    const result = await listEvaluationFollowups(props.evaluation.id)
    records.value = [...result].sort((a, b) => a.id - b.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '跟进记录加载失败')
  } finally {
    loading.value = false
  }
}

/* ---------- 跟进表单（content 必填，≤1000 字符） ---------- */

const content = ref('')
const submitting = ref(false)

/* 跟进动作快捷模板（沿用原页预设；点击写入【动作】前缀，可继续补充说明） */
const actionPresets = ['电话回访', '上门复核', '重新处理', '服务人员约谈', '补偿安抚', '其他']

function applyPreset(preset: string): void {
  const body = content.value.replace(/^【[^】]*】\s*/, '')
  content.value = `【${preset}】${body}`
}

function resetForm(): void {
  content.value = ''
  records.value = []
}

async function handleSubmit(): Promise<void> {
  if (!props.evaluation) return
  if (!content.value.trim()) {
    ElMessage.warning('请填写跟进内容')
    return
  }
  submitting.value = true
  try {
    await addEvaluationFollowup(props.evaluation.id, { content: content.value.trim() })
    ElMessage.success('跟进记录已添加')
    content.value = ''
    loadFollowups()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加跟进失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="跟进处理" size="520px" class="followup-drawer">
    <div v-if="evaluation" class="drawer-body">
      <!-- 评价摘要 -->
      <section class="summary-card">
        <div class="summary-head">
          <span class="summary-avatar" aria-hidden="true">
            {{ evaluation.residentName?.trim()?.charAt(0) || '客' }}
          </span>
          <div class="summary-info">
            <span class="summary-name">{{ evaluation.residentName || '匿名居民' }}</span>
            <el-rate
              :model-value="evaluation.rating"
              disabled
              :colors="['var(--color-danger)', 'var(--color-danger)', 'var(--color-warning)']"
              void-color="var(--color-border)"
              disabled-void-color="var(--color-border)"
            />
          </div>
        </div>
        <p class="summary-content">{{ evaluation.content || '未填写评价内容' }}</p>
        <p class="summary-meta">
          <span>#{{ evaluation.workOrderNo }}</span>
          <span class="summary-dot">·</span>
          <span>{{ formatDate(evaluation.createdAt) }}</span>
        </p>
      </section>

      <!-- 跟进表单 -->
      <section class="form-card">
        <h3 class="block-title">添加跟进</h3>
        <p class="form-hint">仅不满意评价需要跟进；每次跟进全量留痕，跟进人自动记录为当前管理员</p>
        <div class="preset-row" role="group" aria-label="跟进动作快捷模板">
          <button
            v-for="preset in actionPresets"
            :key="preset"
            type="button"
            class="preset-chip"
            @click="applyPreset(preset)"
          >
            {{ preset }}
          </button>
        </div>
        <el-input
          v-model="content"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="记录本次跟进动作与结果，如：【电话回访】已向居民说明维修进度，居民表示认可"
        />
        <div class="form-actions">
          <el-button type="primary" :loading="submitting" @click="handleSubmit">添加跟进记录</el-button>
        </div>
      </section>

      <!-- 历史跟进时间线 -->
      <section class="timeline-card">
        <h3 class="block-title">跟进历史（{{ records.length }}）</h3>
        <div v-loading="loading">
          <el-timeline v-if="records.length > 0" class="timeline">
            <el-timeline-item
              v-for="item in records"
              :key="item.id"
              :timestamp="formatDateTime(item.followupTime)"
              type="primary"
            >
              <div class="timeline-head">
                <span class="timeline-content">{{ item.followupContent || '-' }}</span>
              </div>
              <p class="timeline-operator">跟进人：{{ item.handlerName ?? '管理员' }}</p>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else-if="!loading" description="暂无跟进记录，请先添加第一条跟进" :image-size="72" />
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* ---------- 评价摘要 ---------- */

.summary-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg);
}

.summary-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.summary-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
}

.summary-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.summary-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.summary-content {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.summary-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.summary-dot {
  color: var(--color-text-disabled);
}

/* ---------- 跟进表单 ---------- */

.form-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.block-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.form-hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.preset-chip {
  padding: 3px var(--spacing-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: none;
  font-family: inherit;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease, background-color 0.2s ease;
}

.preset-chip:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
}

/* ---------- 跟进历史时间线 ---------- */

.timeline-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.timeline {
  padding-left: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
}

.timeline-head {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
}

.timeline-content {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
  white-space: pre-wrap;
  word-break: break-word;
}

.timeline-operator {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
