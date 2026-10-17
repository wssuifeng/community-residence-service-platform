import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { listConversationInbox } from '@/api/conversationInbox'
import { inboxKey, type ConversationInboxItem, type InboxType } from '@/types/modules/conversationInbox'
import { useLiveRefresh } from './useLiveRefresh'
import { useUserStore } from '@/store/user'
/** 后端过滤后分页；版本隔离旧搜索/旧账号响应，刷新保持已加载范围。 */
export function useConversationInbox() {
 const type = ref<InboxType | ''>(''), keyword = ref(''), unreadOnly = ref(false)
 const items = shallowRef<ConversationInboxItem[]>([]), total = ref(0), page = ref(1)
 const loading = ref(false), error = ref('')
 let version = 0, disposed = false, debounce: ReturnType<typeof setTimeout> | undefined
 const user = useUserStore()
 const hasMore = computed(() => items.value.length < total.value)
 async function load(more = false) {
  const request = ++version, target = more ? page.value + 1 : page.value
  loading.value = true; error.value = ''
  const query = { size: 30, type: type.value || undefined, keyword: keyword.value.trim() || undefined, unreadOnly: unreadOnly.value }
  try {
   const collected = more ? [...items.value] : []
   let count = 0
   for (let next = more ? target : 1; next <= target; next++) {
    const result = await listConversationInbox({ ...query, page: next })
    if (disposed || request !== version) return
    collected.push(...result.records); count = result.total
    if (next >= result.pages) break
   }
   items.value = [...new Map(collected.map(row => [inboxKey(row), row])).values()]
   page.value = target; total.value = count
  } catch (e) { if (request === version && !disposed) error.value = e instanceof Error ? e.message : '会话列表加载失败' }
  finally { if (request === version && !disposed) loading.value = false }
 }
 watch([type, keyword, unreadOnly], () => {
  version++; items.value = []; page.value = 1; total.value = 0; loading.value = true
  clearTimeout(debounce); debounce = setTimeout(() => void load(), 250)
 })
 watch(() => `${user.user?.role}:${user.user?.id}`, () => { version++; items.value = []; page.value = 1; void load() })
 const { refreshWhenVisible } = useLiveRefresh(() => load())
 onBeforeUnmount(() => { disposed = true; version++; clearTimeout(debounce) })
 return { type, keyword, unreadOnly, items, loading, error, hasMore, total, load, refreshWhenVisible }
}
