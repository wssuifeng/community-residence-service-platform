<script setup lang="ts">
import { computed } from 'vue'

/**
 * 分页条（Element Plus el-pagination 业务薄封装，可整体替换）
 */
const props = defineProps<{
  page: number
  size: number
  total: number
  /** 总条数上限，超过后只显示跳页输入框 */
  maxButtons?: number
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
}>()

const currentPage = computed({
  get: () => props.page,
  set: (value: number) => emit('update:page', value)
})

const pageSize = computed({
  get: () => props.size,
  set: (value: number) => emit('update:size', value)
})

function handleChange(): void {
  emit('update:page', currentPage.value)
}
</script>

<template>
  <div class="pagination-bar">
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      :pager-count="maxButtons ?? 7"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="handleChange"
    />
  </div>
</template>

<style scoped>
.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: var(--spacing-md) 0;
}
</style>
