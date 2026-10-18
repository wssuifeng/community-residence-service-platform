<script setup lang="ts">
/**
 * 筛选面板：横向排布筛选控件 + 重置按钮，控件通过插槽注入
 */
defineProps<{
  /** 是否显示重置按钮 */
  resettable?: boolean
}>()

const emit = defineEmits<{
  reset: []
}>()
</script>

<template>
  <div class="filter-panel">
    <div class="filter-panel-items">
      <slot />
    </div>
    <el-button v-if="resettable" text @click="emit('reset')">重置</el-button>
  </div>
</template>

<style scoped>
.filter-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  margin-bottom: var(--spacing-md);
  flex-wrap: wrap;
}

.filter-panel-items {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}
</style>
