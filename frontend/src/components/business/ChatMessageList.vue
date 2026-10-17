<script lang="ts">
const readingPositions = new Map<string, number>()
</script>
<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type { ChatMessage } from '@/types/chat'
import { formatDateTime } from '@/utils/date'
function senderInitial(label: string): string { return Array.from(label.trim())[0] || '会' }
function messageTime(iso: string): string {
  const full = formatDateTime(iso)
  if (!/^\d{4}-\d{2}-\d{2} /.test(full)) return full
  const today = formatDateTime(new Date().toISOString())
  if (full.slice(0, 10) === today.slice(0, 10)) return full.slice(11)
  return full.slice(0, 4) === today.slice(0, 4) ? full.slice(5) : full
}
const props = defineProps<{ messages: ChatMessage[]; emptyText?: string; positionKey?: string; initialAnchor?: number }>()
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
    else if (props.initialAnchor) {
      const anchor = container.value.querySelector<HTMLElement>(`[data-message-id="${props.initialAnchor}"]`)
      if (anchor) container.value.scrollTop += anchor.getBoundingClientRect().top - container.value.getBoundingClientRect().top
      else container.value.scrollTop = 0
    } else bottom()
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
    <span class="chat-avatar" aria-hidden="true">{{ senderInitial(message.senderLabel) }}</span>
    <div class="chat-message-body">
     <header class="chat-meta">
      <span class="chat-sender" :title="message.senderLabel">{{ message.senderLabel }}</span>
      <time class="chat-time" :datetime="message.createdAt" :title="formatDateTime(message.createdAt)">{{ messageTime(message.createdAt) }}</time>
     </header>
     <div class="chat-bubble" :class="{ 'has-image': !!message.image }">
      <el-image v-if="message.image" class="chat-image" :src="message.image.fileUrl" :alt="message.image.fileName" fit="contain" :preview-src-list="[message.image.fileUrl]" preview-teleported />
      <p v-else class="chat-content">{{ message.content }}</p>
      <slot name="message-actions" :message="message" />
     </div>
    </div>
   </article>
  </div>
  <button v-if="newCount" type="button" class="new-message-reminder" @click="bottom">{{ newCount }} 条新消息 ↓</button>
 </div>
</template>
<style scoped>
.chat-scroll-shell { position: relative; display: flex; flex: 1; min-height: 0; flex-direction: column; }
.chat-message-list { display: flex; flex-direction: column; flex: 1; gap: 10px; min-height: 200px; max-height: 520px; padding: 14px 18px; overflow: auto; overscroll-behavior: contain; scrollbar-width: thin; background: var(--color-bg, #f7f8fa); }
.chat-message { display: flex; align-items: flex-start; gap: var(--spacing-sm, 8px); flex-shrink: 0; }
.chat-message.mine { flex-direction: row-reverse; }
.chat-avatar { display: grid; place-items: center; flex: 0 0 30px; height: 30px; border-radius: var(--radius-circle, 50%); color: #5c759a; background: #e4ebf5; font-size: 12px; margin-top: 1px; }
.mine .chat-avatar { background: #e6edff; color: var(--color-primary, #356aff); }
.chat-message-body { display: flex; flex-direction: column; align-items: flex-start; gap: var(--spacing-xs, 4px); width: max-content; min-width: 0; max-width: min(78%, 640px); }
.mine .chat-message-body { align-items: flex-end; }
.chat-meta { display: flex; align-items: center; gap: 8px; width: fit-content; max-width: 100%; min-width: 0; line-height: 18px; color: var(--color-text-secondary, #788598); }
.chat-sender { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.chat-time { flex-shrink: 0; color: var(--color-text-disabled, #98a1af); font-size: 11px; white-space: nowrap; }
.chat-bubble { width: fit-content; max-width: 100%; padding: 7px 11px; border: 1px solid #e8ecf2; border-radius: 4px 12px 12px; background: var(--color-surface, #fff); overflow-wrap: anywhere; }
.mine .chat-bubble { background: var(--color-primary, #356aff); color: #fff; border-color: transparent; border-radius: 12px 4px 12px 12px; }
.chat-content { white-space: pre-wrap; margin: 0; font-size: 14px; line-height: 21px; }
.chat-bubble.has-image { padding: 0; border: 0; background: transparent; }
.chat-image { display: block; width: 200px; max-width: 100%; height: 140px; border-radius: var(--radius-md, 8px); background: #e9edf4; }
.chat-empty { color: var(--color-text-secondary, #667085); text-align: center; margin: auto; padding: 24px 0; }
.new-message-reminder { position: absolute; bottom: 10px; right: 16px; background: #fff; color: var(--color-primary); border: 1px solid var(--color-border); border-radius: 20px; padding: 6px 12px; cursor: pointer; box-shadow: 0 2px 10px #14264612; font-size: 12px; }
.new-message-reminder:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
@media (max-width: 900px) { .chat-message-list { padding: 12px; } .chat-message-body { max-width: calc(100% - 38px); } }
</style>
