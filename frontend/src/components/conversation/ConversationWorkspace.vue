<script setup lang="ts">
import { computed, nextTick, onMounted, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useConversationInbox } from '@/composables/useConversationInbox'
import { inboxKey, type ConversationInboxItem, type ConversationSource } from '@/types/modules/conversationInbox'
import ConversationSourceNav from './ConversationSourceNav.vue'
import ConversationInboxList from './ConversationInboxList.vue'
import ConversationPane from './ConversationPane.vue'
import DirectConversationLauncher from './DirectConversationLauncher.vue'
const route = useRoute(), router = useRouter(), user = useUserStore()
const { type, keyword, unreadOnly, items, loading, error, hasMore, load, refreshWhenVisible } = useConversationInbox()
const management = computed(() => user.role === 'ADMIN' || user.role === 'SUPER_ADMIN')
const base = computed(() => management.value ? '/admin' : user.role === 'STAFF' ? '/staff' : '/resident')
const identity = computed(() => `${user.role}:${user.user?.id}`)
const selectedId = computed(() => { const id = Number(route.params.id || route.query.id); return Number.isInteger(id) && id > 0 ? id : null })
const source = computed<ConversationSource>(() => route.query.source === 'FEEDBACK' ? 'FEEDBACK' : 'CONVERSATION')
const selectedKey = computed(() => selectedId.value ? `${source.value}:${selectedId.value}` : '')
const retainedSelection = shallowRef<ConversationInboxItem | null>(null)
watch([items, selectedKey], () => {
 const current = items.value.find(item => inboxKey(item) === selectedKey.value)
 if (current) retainedSelection.value = current
 else if (retainedSelection.value && inboxKey(retainedSelection.value) !== selectedKey.value) retainedSelection.value = null
})
watch(identity, () => { retainedSelection.value = null })
const displayedItems = computed(() => {
 const current = retainedSelection.value
 return unreadOnly.value && current && !loading.value && !error.value && !items.value.some(item => inboxKey(item) === selectedKey.value)
  ? [{ ...current, unreadCount: 0 }, ...items.value] : items.value
})
const returnTo = computed(() => typeof route.query.returnTo === 'string' && route.query.returnTo.startsWith(base.value + '/') ? route.query.returnTo : '')
function select(item: Pick<ConversationInboxItem, 'source' | 'id'>) {
 void router.replace({ path: `${base.value}/conversations`, query: { ...route.query, id: item.id, source: item.source } })
}
function back() { const query = { ...route.query }; delete query.id; delete query.source; void router.replace({ path: `${base.value}/conversations`, query }) }
async function opened(id: number) { type.value = ''; keyword.value = ''; unreadOnly.value = false; await nextTick(); select({ source: 'CONVERSATION', id }); refreshWhenVisible() }
watch([type, keyword, unreadOnly], back)
onMounted(() => void load())
</script>
<template>
 <section class="conversation-page" :class="{ management }">
  <header class="workspace-page-head"><div><h1>消息会话</h1><p>所有业务沟通，集中在此</p></div><div class="workspace-page-actions"><router-link v-if="returnTo" :to="returnTo">返回业务详情</router-link><router-link :to="`${base}/notifications`">通知</router-link><button type="button" :disabled="loading" @click="load()">刷新</button><DirectConversationLauncher @opened="opened" /></div></header>
  <div class="conversation-workspace" :class="{ 'has-selection': selectedId, 'is-admin': management }">
   <ConversationSourceNav v-model="type" :staff="user.role === 'STAFF'" />
   <ConversationInboxList v-model:keyword="keyword" v-model:unread-only="unreadOnly" :items="displayedItems" :selected="selectedKey" :loading="loading" :error="error" :has-more="hasMore" @select="select" @more="load(true)" @retry="load()" />
   <ConversationPane v-if="selectedId" :key="`${identity}:${selectedKey}`" :source="source" :id="selectedId" @back="back" @changed="refreshWhenVisible" @read="refreshWhenVisible" />
   <div v-else class="workspace-empty"><span aria-hidden="true">☏</span><h2>选择一个会话</h2><p>租约、看房、工单、反馈和直通消息<br />在这里继续沟通</p><small>仅显示当前账号有权访问的会话</small></div>
  </div>
 </section>
</template>
<style scoped>
.conversation-page{display:flex;flex-direction:column;min-width:0;height:calc(100dvh - 130px);min-height:480px}.conversation-page.management{height:calc(100dvh - 146px)}.workspace-page-head{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:70px;padding-bottom:16px;flex-shrink:0}.workspace-page-head h1{font-size:22px;font-weight:650;margin:0 0 7px;color:#25334b}.workspace-page-head p{font-size:12px;color:#8a94a4;margin:0}.workspace-page-actions{display:flex;align-items:center;gap:12px;font-size:12px}.workspace-page-actions>a{color:#74839a;text-decoration:none;white-space:nowrap}.workspace-page-actions>button{background:transparent;border:0;color:#74839a;font:inherit;font-size:12px;cursor:pointer}.conversation-workspace{display:grid;grid-template-columns:76px 290px minmax(0,1fr);flex:1;min-height:0;min-width:0;background:white;border:1px solid #dee5ef;border-radius:10px;overflow:hidden;box-shadow:0 3px 12px #17335c04}.workspace-empty{display:flex;align-items:center;justify-content:center;flex-direction:column;text-align:center;background:#fbfcfe;color:#8592a6;min-width:0}.workspace-empty>span{font-size:42px;opacity:.5}.workspace-empty h2{font-size:16px;font-weight:500;color:#63758f;margin:18px 0 10px}.workspace-empty p{font-size:12px;line-height:1.9;margin:0}.workspace-empty small{margin-top:24px;font-size:10px;color:#a2adbd}
@media(max-width:1280px){.conversation-workspace{grid-template-columns:70px 260px minmax(0,1fr)}}
@media(max-width:1050px){.conversation-workspace{grid-template-columns:70px minmax(0,1fr)}.conversation-workspace :deep(.workspace-empty){display:none}.conversation-workspace.has-selection :deep(.inbox-list){display:none}.conversation-workspace:not(.has-selection) :deep(.conversation-pane){display:none}.workspace-page-actions{gap:8px}.workspace-page-head h1{font-size:20px}}
@media(max-width:700px){.conversation-page{height:calc(100dvh - 110px);min-height:440px}.conversation-workspace{grid-template-columns:minmax(0,1fr);grid-template-rows:56px minmax(0,1fr)}.workspace-page-head p{display:none}.workspace-page-head{min-height:54px;gap:8px;flex-wrap:wrap}.workspace-page-actions{gap:6px}.workspace-page-actions :deep(.direct-launch){padding:7px;font-size:11px}}
</style>
