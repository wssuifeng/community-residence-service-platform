<script setup lang="ts">
import { computed } from 'vue'

/**
 * 通用状态标签：按语义色渲染（状态色 Token 映射六大状态机，
 * 具体状态 → 语义的翻译由调用方业务层完成）
 */
const props = defineProps<{
  /** 展示文本 */
  label: string
  /** 语义色：pending 黄 / processing 蓝 / completed 绿 / rejected 红 / canceled 灰 */
  type?: 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled' | 'info'
}>()

const typeClass = computed(() => props.type ?? 'info')
</script>

<template>
  <span class="status-tag" :class="`is-${typeClass}`">{{ label }}</span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: var(--line-height-tight);
  white-space: nowrap;
}

.is-pending {
  color: var(--status-pending);
  background-color: rgba(245, 158, 11, 0.12);
}

.is-processing {
  color: var(--status-processing);
  background-color: rgba(59, 130, 246, 0.12);
}

.is-completed {
  color: var(--status-completed);
  background-color: rgba(16, 185, 129, 0.12);
}

.is-rejected {
  color: var(--status-rejected);
  background-color: rgba(239, 68, 68, 0.12);
}

.is-canceled {
  color: var(--status-canceled);
  background-color: rgba(107, 114, 128, 0.12);
}

.is-info {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}
</style>
