<script setup lang="ts">
/** 管理端页头：大标题 + 副题 + 右侧操作区（任务 1 基建，11 类页共用） */
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <div class="admin-page-header">
    <div class="admin-page-header-text">
      <h1 class="admin-page-title">{{ title }}</h1>
      <p v-if="subtitle" class="admin-page-subtitle">{{ subtitle }}</p>
    </div>
    <div class="admin-page-actions"><slot /></div>
  </div>
</template>

<style scoped>
.admin-page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.admin-page-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.admin-page-subtitle {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.admin-page-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
}
</style>
