<script setup lang="ts">
import BaseUploader from './BaseUploader.vue'

/** 文件上传：pdf/doc/docx，单个 ≤10MB（反馈附件等场景） */
withDefaults(
  defineProps<{
    modelValue: string[]
    limit?: number
  }>(),
  { limit: 5 }
)

defineEmits<{
  'update:modelValue': [urls: string[]]
}>()
</script>

<template>
  <BaseUploader
    :model-value="modelValue"
    accept=".pdf,.doc,.docx"
    :max-size="10"
    :limit="limit"
    preview="file"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
