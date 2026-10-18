import { onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { subscribe, isWsConnected } from '@/common/messaging/utils/websocket'
import type { InboxMessageWindow } from '@/common/conversation/api/conversationInbox'
import { useUserStore } from '@/common/auth/store/user'

interface Message { id: number; content: string; createdAt: string }
interface Adapter<T extends Message> {
  id: () => number
  closed: () => boolean
  load: (id: number) => Promise<T[]>
  window?: (id: number, beforeId?: number, afterId?: number) => Promise<T[]>
  initialWindow?: (id: number) => Promise<InboxMessageWindow<T>>
  send: (id: number, content: string) => Promise<T>
  image: (id: number, file: File) => Promise<T>
  topic: (id: number) => string
  eventType: string
  changed?: () => void
}
interface CachedChannel { draft: string; messages: Message[]; hasOlder: boolean; fetchedCursor: number }
const channels = new Map<string, CachedChannel>()

/** 共用消息引擎：原来源鉴权，有限窗口/增量补拉，切换防串，草稿及断线兜底。 */
export function useChatChannel<T extends Message>(adapter: Adapter<T>) {
  const user = useUserStore()
  const identity = () => `${user.user?.role}:${user.user?.id}`
  const messages = shallowRef<T[]>([])
  const text = ref(''), sending = ref(false), readOnly = ref(false)
  const initialAnchor = ref(0)
  const loading = ref(false), error = ref(''), loadingOlder = ref(false), hasOlder = ref(false)
  let timer: ReturnType<typeof setTimeout> | undefined, unsubscribe: (() => void) | undefined
  let seq = 0, disposed = false, key = '', loadingVersion = -1, fetchedCursor = 0
  const valid = (version: number, id: number) => !disposed && version === seq && id === adapter.id()
  function save() {
    if (!key) return
    channels.set(key, { draft: text.value, messages: messages.value, hasOlder: hasOlder.value, fetchedCursor })
    if (channels.size > 30) channels.delete(channels.keys().next().value!)
  }
  const stop = () => { if (timer) clearTimeout(timer); timer = undefined; unsubscribe?.(); unsubscribe = undefined }
  const merge = (rows: T[]) => { messages.value = [...new Map([...messages.value, ...rows].map(row => [row.id, row])).values()].sort((a, b) => a.id - b.id) }
  async function load() {
    const id = adapter.id(), version = seq
    if (loadingVersion === version) return
    loadingVersion = version; loading.value = messages.value.length === 0; error.value = ''
    try {
      if (!adapter.window) {
        const rows = await adapter.load(id)
        if (valid(version, id)) messages.value = rows
      } else if (!fetchedCursor && !messages.value.length && adapter.initialWindow) {
        const window = await adapter.initialWindow(id)
        if (!valid(version, id)) return
        initialAnchor.value = window.startAtUnread ? window.firstUnreadMessageId ?? 0 : 0
        hasOlder.value = window.hasOlder
        fetchedCursor = window.records.at(-1)?.id ?? 0
        merge(window.records)
      } else {
        let cursor = fetchedCursor || undefined
        do {
          const rows = await adapter.window(id, undefined, cursor)
          if (!valid(version, id)) return
          merge(rows)
          fetchedCursor = rows.at(-1)?.id ?? fetchedCursor
          if (cursor === undefined) hasOlder.value = rows.length === 40
          if (cursor === undefined || rows.length < 40) break
          const next = rows.at(-1)?.id
          if (next === cursor) break
          cursor = next
        } while (valid(version, id))
      }
    } catch (caught) {
      if (!valid(version, id)) return
      const e = caught as { httpStatus?: number; code?: number; message?: string }
      error.value = e.message || '消息加载失败，请重试'
      if (e.httpStatus === 403 || e.httpStatus === 404 || e.code === 403) { readOnly.value = true; stop() }
    } finally { if (version === seq) { loading.value = false; loadingVersion = -1 } }
  }
  async function loadOlder() {
    if (!adapter.window || loadingOlder.value || !hasOlder.value || !messages.value.length) return
    const id = adapter.id(), version = seq; loadingOlder.value = true
    try {
      const rows = await adapter.window(id, messages.value[0].id)
      if (valid(version, id)) { merge(rows); hasOlder.value = rows.length === 40; error.value = '' }
    } catch (e) { if (valid(version, id)) error.value = e instanceof Error ? e.message : '历史消息加载失败' }
    finally { if (version === seq) loadingOlder.value = false }
  }
  function poll() {
    if (disposed || adapter.closed() || readOnly.value) return
    const version = seq
    timer = setTimeout(() => { void load().finally(() => { if (version === seq) poll() }) }, isWsConnected() ? 15000 : 5000)
  }
  function connect() {
    stop()
    if (disposed || adapter.closed() || readOnly.value) return
    const version = seq, id = adapter.id()
    unsubscribe = subscribe(adapter.topic(id), raw => {
      if (!valid(version, id)) return
      const e = raw as { type?: string; data?: T }
      const item = e.type === adapter.eventType ? e.data : e.type === undefined ? raw as T : undefined
      // 先补拉再合并WS，避免乱序推送提高游标后漏掉中间消息。
      if (item && typeof item.id === 'number') { if (!adapter.window) merge([item]); else void load() }
      adapter.changed?.()
    })
    poll()
  }
  async function start() {
    save(); seq++; stop(); readOnly.value = false; loadingOlder.value = false; loadingVersion = -1; sending.value = false
    key = `${identity()}:${adapter.topic(adapter.id())}`
    initialAnchor.value = 0
    const cached = channels.get(key)
    fetchedCursor = cached?.fetchedCursor ?? 0
    messages.value = (cached?.messages ?? []) as T[]; text.value = cached?.draft ?? ''; hasOlder.value = cached?.hasOlder ?? false
    const version = seq
    await load(); if (version === seq) connect()
  }
  async function send(image?: File) {
    if (sending.value || adapter.closed() || readOnly.value || loading.value || error.value && !messages.value.length) return
    const content = text.value.trim(); if (!image && !content) return
    const id = adapter.id(), version = seq; sending.value = true
    try {
      const item = image ? await adapter.image(id, image) : await adapter.send(id, content)
      if (!valid(version, id)) return
      // 按游标补齐后再合并本次响应，不丢与发送并发的对端消息。
      if (adapter.window) await load()
      if (!valid(version, id)) return
      merge([item]); if (!image) text.value = ''; adapter.changed?.()
    } catch (e) { if (valid(version, id)) ElMessage.error(e instanceof Error ? e.message : '发送失败，请重试') }
    finally { if (version === seq) sending.value = false }
  }
  onMounted(() => { void start() })
  watch(adapter.id, () => { void start() })
  watch(identity, () => { channels.clear(); key = ''; void start() })
  watch(adapter.closed, () => { connect(); void load() })
  onUnmounted(() => { save(); disposed = true; seq++; stop() })
  return { messages, text, sending, readOnly, initialAnchor, loading, error, loadingOlder, hasOlder, loadOlder, sendText: () => send(), sendImage: (file: File) => send(file), reload: load }
}
