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
  container-type: inline-size;
  min-width: 0;
  width: 100%;
  display: flex;
  justify-content: center;
  padding: var(--spacing-md) 0;
}

/* 平板内容区保留全部分页控件，只换行而非撑宽页面或隐藏跳页能力。 */
:deep(.el-pagination) {
  min-width: 0;
  max-width: 100%;
  flex-wrap: wrap;
  justify-content: center;
  row-gap: var(--spacing-sm);
}

/* 仅内容区较窄时分组换行，上一页/页码/下一页保持同一行。 */
@container (max-width: 650px) {
  :deep(.el-pagination) {
    display: grid;
    grid-template-columns: auto auto auto;
    column-gap: var(--spacing-xs);
  }
  :deep(.el-pagination__total) {
    grid-row: 1;
    grid-column: 1;
    justify-self: end;
    margin: 0;
  }
  :deep(.el-pagination__sizes) {
    grid-row: 1;
    grid-column: 2 / 4;
    margin: 0;
  }
  :deep(.btn-prev) {
    grid-row: 2;
    grid-column: 1;
    justify-self: end;
    margin: 0;
  }
  :deep(.el-pager) {
    grid-row: 2;
    grid-column: 2;
    margin: 0;
  }
  :deep(.btn-next) {
    grid-row: 2;
    grid-column: 3;
    justify-self: start;
    margin: 0;
  }
  :deep(.el-pagination__jump) {
    grid-row: 3;
    grid-column: 1 / 4;
    justify-self: center;
    margin: 0;
  }
}
</style>
