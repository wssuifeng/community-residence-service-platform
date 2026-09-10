<script setup lang="ts">
import BaseUploader from './BaseUploader.vue'

/** 图片上传：jpg/jpeg/png/gif，单张 ≤5MB（工单图片等场景） */
withDefaults(
  defineProps<{
    modelValue: string[]
    limit?: number
  }>(),
  { limit: 6 }
)

defineEmits<{
  'update:modelValue': [urls: string[]]
}>()
</script>

<template>
  <BaseUploader
    :model-value="modelValue"
    type="IMAGE"
    accept="image/jpeg,image/png,image/gif"
    :max-size="5"
    :limit="limit"
    preview="image"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
