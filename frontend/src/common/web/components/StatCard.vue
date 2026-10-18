<script setup lang="ts">
/**
 * 统计卡片：数值 + 标题 + 可选趋势说明（运营看板 16 卡片复用）
 */
defineProps<{
  /** 卡片标题 */
  label: string
  /** 主数值 */
  value: number | string
  /** 数值单位 */
  unit?: string
  /** 辅助说明（如"较上周 +3"） */
  hint?: string
  /** 语义色强调 */
  type?: 'default' | 'success' | 'warning' | 'danger' | 'primary'
}>()
</script>

<template>
  <div class="stat-card" :class="type ? `is-${type}` : ''">
    <div class="stat-card-value">
      {{ value }}<span v-if="unit" class="stat-card-unit">{{ unit }}</span>
    </div>
    <div class="stat-card-label">{{ label }}</div>
    <div v-if="hint" class="stat-card-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg) var(--spacing-md);
  box-shadow: var(--shadow-sm);
}

.stat-card-value {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.stat-card-unit {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-normal);
  margin-left: var(--spacing-xs);
}

.stat-card-label {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.stat-card-hint {
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.is-success .stat-card-value {
  color: var(--color-success);
}

.is-warning .stat-card-value {
  color: var(--color-warning);
}

.is-danger .stat-card-value {
  color: var(--color-danger);
}

.is-primary .stat-card-value {
  color: var(--color-primary);
}
</style>
