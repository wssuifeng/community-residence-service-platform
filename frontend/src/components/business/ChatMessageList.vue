<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { ChatMessage } from '@/types/chat'
import { formatDateTime } from '@/utils/date'
const props=defineProps<{messages:ChatMessage[];emptyText?:string}>()
const container=ref<HTMLElement|null>(null)
watch(()=>props.messages.length,()=>{void nextTick(()=>{if(container.value)container.value.scrollTop=container.value.scrollHeight})})
</script>
<template>
 <div ref="container" class="chat-message-list" aria-live="polite">
  <p v-if="!messages.length" class="chat-empty">{{emptyText??'暂无消息，说点什么开始沟通'}}</p>
  <article v-for="message in messages" :key="message.id" class="chat-message" :class="{mine:message.mine}">
   <div class="chat-bubble">
    <p class="chat-sender">{{message.senderLabel}}</p>
    <el-image v-if="message.image" class="chat-image" :src="message.image.fileUrl" :alt="message.image.fileName" fit="contain" :preview-src-list="[message.image.fileUrl]" preview-teleported />
    <p v-else class="chat-content">{{message.content}}</p>
    <slot name="message-actions" :message="message" />
    <time class="chat-time">{{formatDateTime(message.createdAt)}}</time>
   </div>
  </article>
 </div>
</template>
<style scoped>
.chat-message-list{display:flex;flex-direction:column;gap:16px;min-height:240px;max-height:520px;padding:20px;overflow:auto;background:var(--color-bg,#f7f8fa)}
.chat-message{display:flex;justify-content:flex-start}.chat-message.mine{justify-content:flex-end}
.chat-bubble{max-width:min(82%,560px);padding:14px 18px;border:1px solid var(--color-border,#e5e7eb);border-radius:16px;background:var(--color-surface,#fff);overflow-wrap:anywhere}
.mine .chat-bubble{background:var(--color-primary,#356aff);color:#fff;border-color:transparent}.chat-sender,.chat-time{display:block;font-size:12px;opacity:.75;margin:0 0 8px}.chat-content{white-space:pre-wrap;margin:0}.chat-time{margin:10px 0 0;text-align:right}
.chat-image{display:block;width:220px;max-width:100%;height:180px;border-radius:8px}.chat-empty{color:var(--color-text-secondary,#667085);text-align:center;padding:40px 0}
@media(max-width:768px){.chat-message-list{padding:12px}.chat-bubble{max-width:90%;padding:12px}}
</style>
