<script lang="ts">
const readingPositions = new Map<string, number>()
</script>
<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type { ChatMessage } from '@/types/chat'
import { formatDateTime } from '@/utils/date'
const props = defineProps<{ messages: ChatMessage[]; emptyText?: string; positionKey?: string }>()
const emit = defineEmits<{ visible: [lastVisibleId: number] }>()
const container = ref<HTMLElement | null>(null), newCount = ref(0)
let atBottom = true, initialized = false, lastEmitted = -Infinity, observer: IntersectionObserver | undefined
const save = () => { if (props.positionKey && container.value) readingPositions.set(props.positionKey, container.value.scrollTop) }
function visible(force = false) {
  const node = container.value
  if (!node || document.visibilityState !== 'visible') return
  const bounds = node.getBoundingClientRect()
  if (bounds.width <= 0 || bounds.height <= 0 || bounds.bottom <= 0 || bounds.top >= window.innerHeight || getComputedStyle(node).visibility === 'hidden') return
  let last = -Infinity
  node.querySelectorAll<HTMLElement>('[data-message-id]').forEach(row => {
    const r = row.getBoundingClientRect()
    if (r.bottom > Math.max(0, bounds.top) && r.top < Math.min(window.innerHeight, bounds.bottom)) last = Math.max(last, Number(row.dataset.messageId))
  })
  if (Number.isFinite(last) && (force || last !== lastEmitted)) { lastEmitted = last; emit('visible', Math.max(0, last)) }
}
function onScroll(event?: Event) {
  const node = container.value
  if (!node) return
  atBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 48
  if (atBottom) newCount.value = 0
  save(); visible(!!event)
}
function bottom() { const node = container.value; if (node) { node.scrollTop = node.scrollHeight; atBottom = true; newCount.value = 0; visible() } }
watch(() => props.positionKey, (_, previous) => { if (previous && container.value) readingPositions.set(previous, container.value.scrollTop); initialized = false; newCount.value = 0; lastEmitted = -Infinity })
watch(() => props.messages, async (rows, old) => {
  const node = container.value, height = node?.scrollHeight ?? 0, top = node?.scrollTop ?? 0
  const previous = old?.at(-1)?.id ?? 0
  const prepended = old?.length && rows[0]?.id !== old[0]?.id && rows.some(row => row.id === old[0]?.id)
  await nextTick()
  if (!container.value || !rows.length) return
  if (!initialized) {
    const saved = props.positionKey ? readingPositions.get(props.positionKey) : undefined
    if (saved !== undefined) container.value.scrollTop = saved
    else bottom()
    initialized = true
  } else if (prepended) container.value.scrollTop = top + container.value.scrollHeight - height
  else if (atBottom) bottom()
  else newCount.value += rows.filter(row => row.id > previous).length
  onScroll()
}, { flush: 'pre' })
const visibilityChanged = () => visible(true)
const resized = () => visible()
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged)
  window.addEventListener('resize', resized)
  if (typeof IntersectionObserver !== 'undefined' && container.value) {
    observer = new IntersectionObserver(() => visible()); observer.observe(container.value)
  }
  void nextTick(visible)
})
onBeforeUnmount(() => { save(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged); window.removeEventListener('resize', resized) })
</script>
<template>
 <div class="chat-scroll-shell">
  <div ref="container" class="chat-message-list" role="log" aria-label="消息记录" aria-live="polite" tabindex="0" @scroll="onScroll">
   <p v-if="!messages.length" class="chat-empty">{{ emptyText ?? '暂无消息，说点什么开始沟通' }}</p>
   <article v-for="message in messages" :key="message.id" :data-message-id="message.id" class="chat-message" :class="{ mine: message.mine }">
    <div class="chat-bubble">
     <p class="chat-sender">{{ message.senderLabel }}</p>
     <el-image v-if="message.image" class="chat-image" :src="message.image.fileUrl" :alt="message.image.fileName" fit="contain" :preview-src-list="[message.image.fileUrl]" preview-teleported />
     <p v-else class="chat-content">{{ message.content }}</p>
     <slot name="message-actions" :message="message" />
     <time class="chat-time">{{ formatDateTime(message.createdAt) }}</time>
    </div>
   </article>
  </div>
  <button v-if="newCount" type="button" class="new-message-reminder" @click="bottom">{{ newCount }} 条新消息 ↓</button>
 </div>
</template>
<style scoped>
.chat-scroll-shell{position:relative;display:flex;flex:1;min-height:0;flex-direction:column}.chat-message-list{display:flex;flex-direction:column;flex:1;gap:16px;min-height:240px;max-height:520px;padding:20px;overflow:auto;scrollbar-width:thin;background:var(--color-bg,#f7f8fa)}
.chat-message{display:flex;justify-content:flex-start}.chat-message.mine{justify-content:flex-end}.chat-bubble{max-width:min(82%,560px);padding:14px 18px;border:1px solid var(--color-border,#e5e7eb);border-radius:12px;background:var(--color-surface,#fff);overflow-wrap:anywhere}
.mine .chat-bubble{background:var(--color-primary,#356aff);color:#fff;border-color:transparent}.chat-sender,.chat-time{display:block;font-size:12px;opacity:.75;margin:0 0 8px}.chat-content{white-space:pre-wrap;margin:0}.chat-time{margin:10px 0 0;text-align:right}.chat-image{display:block;width:220px;max-width:100%;height:180px;border-radius:8px}.chat-empty{color:var(--color-text-secondary,#667085);text-align:center;padding:40px 0}.new-message-reminder{position:absolute;bottom:12px;right:20px;background:#fff;color:var(--color-primary);border:1px solid var(--color-border);border-radius:20px;padding:8px 14px;cursor:pointer;box-shadow:0 2px 10px #14264612}
@media(max-width:768px){.chat-message-list{padding:12px}.chat-bubble{max-width:90%;padding:12px}}
</style>
