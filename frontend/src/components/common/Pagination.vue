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
  /** el-pagination layout（默认完整版；公告等阅读型页面用精简版 'prev, pager, next'） */
  layout?: string
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
</script>

<template>
  <div class="pagination-bar">
    <!-- 页码经 v-model setter 单通道回传；勿再监听 current-change，
         否则读取未更新的 props 会多发一次旧页码把页码抢回 -->
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      :pager-count="maxButtons ?? 7"
      :layout="layout ?? 'total, sizes, prev, pager, next, jumper'"
      background
    />
  </div>
</template>

<style scoped>
.pagination-bar {
  display: flex;
  justify-content: center;
  padding: var(--spacing-md) 0;
}
</style>
