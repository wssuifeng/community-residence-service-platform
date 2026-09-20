<script setup lang="ts">
import type { IBatchFailure } from '@/types/modules/community'

/**
 * 批量操作结果对话框（社区批量启用/停用/删除与结构批量生成共用）。
 * 契约：后端批量端点均为「部分成功」语义——不为全有或全无，
 * 因此结果必须同时呈现成功量与被拒明细（逐条原因），不允许只报成功/失败。
 * 纯展示组件：不持有业务逻辑，文案与计数由调用方传入。
 */
defineProps<{
  /** 成功数量（大字呈现，是本对话框的视觉重心） */
  successCount: number
  /** 成功量词与动作（如「个社区已停用」「栋楼栋、12 个单元、48 套房屋」） */
  successText: string
  /** 逐条失败明细（name 为空时以 ID 兜底展示） */
  failures: IBatchFailure[]
  /** 顶部前置说明（执行了什么操作） */
  intro?: string
  /** 底部补充说明（如级联删除的不可恢复提醒） */
  warning?: string
}>()

const visible = defineModel<boolean>({ required: true })

/** 失败项展示名：后端 name 可空（结构生成按层级上报时为楼栋/单元位置名，社区批量时必填） */
function failureName(item: IBatchFailure): string {
  if (item.name) return item.name
  if (item.id != null) return `ID ${item.id}`
  return item.level ? `${item.level}级结构` : '未知对象'
}
</script>

<template>
  <el-dialog v-model="visible" title="批量操作结果" width="560px">
    <p v-if="intro" class="result-intro">{{ intro }}</p>

    <div class="result-head">
      <p class="result-count">
        <b>{{ successCount }}</b>
        <span>{{ successText }}</span>
      </p>
      <span class="result-state" :class="failures.length > 0 ? 'is-partial' : 'is-clean'">
        {{ failures.length > 0 ? `部分完成 · 未完成 ${failures.length} 项` : '全部完成' }}
      </span>
    </div>

    <slot />

    <section v-if="failures.length > 0" class="failure-block">
      <h4 class="failure-title">未完成明细</h4>
      <ul class="failure-list">
        <li v-for="(item, index) in failures" :key="`${item.id ?? item.name ?? index}`">
          <span class="failure-name">{{ failureName(item) }}</span>
          <span class="failure-reason">{{ item.reason }}</span>
        </li>
      </ul>
    </section>

    <p v-if="warning" class="result-warning">{{ warning }}</p>

    <template #footer>
      <slot name="footer" />
      <el-button type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.result-intro {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* 结果头部：成功量以大字承担视觉重心，状态词退居右侧 */
.result-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
}

.result-count {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin: 0;
}

.result-count b {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
}

.result-count span {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.result-state {
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.result-state.is-clean {
  color: var(--color-success);
}

.result-state.is-partial {
  color: var(--color-warning);
}

.failure-block {
  margin-top: var(--spacing-md);
}

.failure-title {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.failure-list {
  display: flex;
  flex-direction: column;
  max-height: 220px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.failure-list li {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) 0;
}

.failure-list li + li {
  border-top: 1px solid var(--color-border);
}

.failure-name {
  flex-shrink: 0;
  max-width: 40%;
  overflow: hidden;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.failure-reason {
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.result-warning {
  margin: var(--spacing-md) 0 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-danger-soft);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-danger);
}
</style>
