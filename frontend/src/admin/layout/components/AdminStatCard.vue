<script setup lang="ts">
/**
 * 管理端统计卡（frontend-beautify 共用组件：运营看板任务 2 先用，任务 4/5/9/10 复用）。
 * 数据驱动，不写死业务：数值/单位/趋势/图标/语义色均由 props 传入；
 * 迷你趋势线与右侧附加区（迷你环、星级等）经插槽注入，MOCK 占位由调用方负责标注。
 */
defineProps<{
  /** 数据名（左上，与图标同行） */
  label: string
  /** 主数值 */
  value: number | string
  /** 数值单位 */
  unit?: string
  /** 趋势文案（如「较上月 +12.5%」），缺省不渲染趋势行 */
  trend?: string
  /** 趋势方向：上升红 / 下降绿（纯方向语义，staff 工作台口径） */
  trendDir?: 'up' | 'down'
  /** 图标 path 数据（24×24 stroke 图标，随语义色着色） */
  icon?: string[]
  /** 语义强调色：图标底用对应 soft token */
  accent?: 'primary' | 'success' | 'warning' | 'danger'
}>()
</script>

<template>
  <article class="admin-stat-card">
    <header class="stat-head">
      <span v-if="icon" class="stat-icon" :class="accent ? `is-${accent}` : ''" aria-hidden="true">
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path v-for="(d, i) in icon" :key="i" :d="d" />
        </svg>
      </span>
      <span class="stat-label">{{ label }}</span>
    </header>

    <div class="stat-main">
      <p class="stat-value">
        {{ value }}<span v-if="unit" class="stat-unit">{{ unit }}</span>
      </p>
      <div class="stat-extra"><slot name="extra" /></div>
    </div>

    <footer v-if="trend" class="stat-trend" :class="trendDir === 'down' ? 'is-down' : 'is-up'">
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path :d="trendDir === 'down' ? 'M12 5v14M5 12l7 7 7-7' : 'M12 19V5M5 12l7-7 7 7'" />
      </svg>
      {{ trend }}
    </footer>
  </article>
</template>

<style scoped>
.admin-stat-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.stat-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
}

.stat-icon svg {
  width: 17px;
  height: 17px;
}

.stat-icon.is-primary {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.stat-icon.is-success {
  color: var(--color-success);
  background-color: var(--color-success-soft);
}

.stat-icon.is-warning {
  color: var(--color-warning);
  background-color: var(--color-warning-soft);
}

.stat-icon.is-danger {
  color: var(--color-danger);
  background-color: var(--color-danger-soft);
}

.stat-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat-main {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.stat-value {
  margin: 0;
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.stat-unit {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
}

.stat-extra {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex: 1;
  min-width: 0;
}

/* 趋势行：箭头与文字同色，上升红 / 下降绿（纯方向着色，不代表好坏） */
.stat-trend {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.stat-trend svg {
  width: 12px;
  height: 12px;
}

.stat-trend.is-up {
  color: var(--color-danger);
}

.stat-trend.is-down {
  color: var(--color-success);
}
</style>
