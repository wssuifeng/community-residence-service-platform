<script setup lang="ts">
import type { InboxType } from '@/types/modules/conversationInbox'
import { inboxTypeLabels } from '@/types/modules/conversationInbox'
defineProps<{ modelValue: InboxType | ''; staff: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: InboxType | ''] }>()
const types: InboxType[] = ['LEASE_GROUP', 'VIEWING_GROUP', 'WORK_ORDER_GROUP', 'FEEDBACK', 'DIRECT']
const symbols: Record<InboxType, string> = { LEASE_GROUP: '▤', VIEWING_GROUP: '⌂', WORK_ORDER_GROUP: '⚒', FEEDBACK: '☷', DIRECT: '↔' }
</script>
<template>
 <nav class="source-nav" aria-label="会话业务分类">
  <button type="button" :class="{ active: !modelValue }" :aria-pressed="!modelValue" @click="emit('update:modelValue', '')"><span aria-hidden="true">▦</span>全部</button>
  <template v-for="kind in types" :key="kind">
   <button v-if="!staff || ['VIEWING_GROUP','WORK_ORDER_GROUP'].includes(kind)" type="button" :class="{ active: modelValue === kind }" :aria-pressed="modelValue === kind" @click="emit('update:modelValue', kind)"><span aria-hidden="true">{{ symbols[kind] }}</span>{{ inboxTypeLabels[kind] }}</button>
  </template>
 </nav>
</template>
<style scoped>
.source-nav{display:flex;flex-direction:column;align-items:center;gap:4px;padding:10px 6px;background:#f0f4fa;border-right:1px solid #e2e8f0;overflow:auto;scrollbar-width:thin}.source-nav button{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;min-height:52px;width:100%;border:0;border-radius:8px;background:none;color:#758093;font:inherit;font-size:12px;cursor:pointer}.source-nav button span{font-size:20px;line-height:22px}.source-nav button:hover{background:#e7edf7}.source-nav button.active{background:#e2eaff;color:#356aff;font-weight:600}.source-nav button:focus-visible{outline:2px solid #356aff;outline-offset:1px}
@media(max-width:700px){.source-nav{grid-column:1/-1;flex-direction:row;padding:6px;overflow-x:auto;border-right:0;border-bottom:1px solid #e2e8f0}.source-nav button{min-height:45px;min-width:64px;gap:2px}.source-nav button span{display:none}}
</style>
