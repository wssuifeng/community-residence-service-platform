<script setup lang="ts">
import BaseUploader from '@/common/upload/components/BaseUploader.vue'

/** 图片上传：jpg/jpeg/png/gif，单张 ≤5MB（工单图片等场景） */
withDefaults(
  defineProps<{
    modelValue: string[]
    limit?: number
    disabled?: boolean
  }>(),
  { limit: 6, disabled: false }
)

defineEmits<{
  'update:modelValue': [urls: string[]]
  'busy-change': [busy: boolean]
}>()
</script>

<template>
  <BaseUploader
    :model-value="modelValue"
    type="IMAGE"
    accept="image/jpeg,image/png,image/gif"
    :max-size="5"
    :limit="limit"
    :disabled="disabled"
    preview="image"
    @busy-change="$emit('busy-change', $event)"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
