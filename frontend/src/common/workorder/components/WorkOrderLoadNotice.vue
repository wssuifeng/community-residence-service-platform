<script setup lang="ts">
defineProps<{ mainError?: string; timelineError: string; attachmentsError: string; timelineLoading: boolean; attachmentsLoading: boolean }>()
defineEmits<{ retryMain: []; retryTimeline: []; retryAttachments: [] }>()
</script>
<template>
 <div class="detail-load-notices" aria-live="polite">
  <p v-if="mainError" role="alert">{{ mainError }} <button type="button" @click="$emit('retryMain')">重新加载工单</button></p>
  <p v-if="timelineError" role="alert">处理时间线：{{ timelineError }} <button type="button" :disabled="timelineLoading" @click="$emit('retryTimeline')">重试时间线</button></p>
  <p v-if="attachmentsError" role="alert">附件：{{ attachmentsError }} <button type="button" :disabled="attachmentsLoading" @click="$emit('retryAttachments')">重试附件</button></p>
 </div>
</template>
<style scoped>
.detail-load-notices:empty{display:none}.detail-load-notices p{padding:var(--spacing-sm) var(--spacing-md);margin:0 0 var(--spacing-sm);background:var(--color-warning-light,#fff7e6);border:1px solid var(--color-warning,#e6a23c);border-radius:var(--radius-md);color:var(--color-text-primary)}
button{border:0;background:transparent;color:var(--color-primary);text-decoration:underline;cursor:pointer;font:inherit}button:disabled{cursor:wait;opacity:.6}
</style>
