<script setup lang="ts">
import { ref, watch } from 'vue'

/**
 * 关键字搜索栏：回车/点击触发搜索，输入变化不即时请求
 */
const props = defineProps<{
  /** 占位提示 */
  placeholder?: string
  /** 双向绑定的关键字（v-model） */
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [keyword: string]
  search: []
}>()

const keyword = ref(props.modelValue)

watch(
  () => props.modelValue,
  (value) => {
    keyword.value = value
  }
)

function handleSearch(): void {
  emit('update:modelValue', keyword.value.trim())
  emit('search')
}
</script>

<template>
  <div class="search-bar" @keydown.enter="handleSearch">
    <el-input
      v-model="keyword"
      :placeholder="placeholder ?? '输入关键字搜索'"
      clearable
      :prefix-icon="undefined"
      style="width: 240px"
      @clear="handleSearch"
    />
    <el-button type="primary" @click="handleSearch">搜索</el-button>
  </div>
</template>

<style scoped>
.search-bar {
  display: flex;
  gap: var(--spacing-sm);
  align-items: center;
}
</style>
