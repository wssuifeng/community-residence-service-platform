<script setup lang="ts">
import { inboxKey, inboxTypeLabels, type ConversationInboxItem } from '@/types/modules/conversationInbox'
import { formatRelative } from '@/utils/date'
defineProps<{ items: ConversationInboxItem[]; selected: string; keyword: string; unreadOnly: boolean; loading: boolean; error: string; hasMore: boolean }>()
const emit = defineEmits<{ select: [item: ConversationInboxItem]; 'update:keyword': [value: string]; 'update:unreadOnly': [value: boolean]; more: []; retry: [] }>()
</script>
<template>
 <aside class="inbox-list" aria-label="会话列表">
  <div class="inbox-search"><label class="sr-only" for="conversation-search">搜索会话标题、社区或房屋编号</label><input id="conversation-search" :value="keyword" type="search" maxlength="100" placeholder="搜索会话、社区、房屋编号" @input="emit('update:keyword', ($event.target as HTMLInputElement).value)" /></div>
  <div class="inbox-filters"><button type="button" :class="{ active: !unreadOnly }" :aria-pressed="!unreadOnly" @click="emit('update:unreadOnly', false)">全部</button><button type="button" :class="{ active: unreadOnly }" :aria-pressed="unreadOnly" @click="emit('update:unreadOnly', true)">未读</button><span>最近消息优先</span></div>
  <div class="inbox-rows" tabindex="0">
   <p v-if="error" class="list-state" role="alert">{{ error }}<button type="button" @click="emit('retry')">重试</button></p>
   <p v-if="loading && !items.length" class="list-state" role="status">加载会话中…</p>
   <p v-else-if="!items.length && !error" class="list-state">{{ keyword || unreadOnly ? '没有符合条件的会话' : '暂无会话。业务产生沟通群后会在这里显示。' }}</p>
   <button v-for="item in items" :key="inboxKey(item)" class="inbox-row" type="button" :class="{ active: selected === inboxKey(item) }" :aria-pressed="selected === inboxKey(item)" @click="emit('select', item)">
    <span class="source-avatar" aria-hidden="true">{{ inboxTypeLabels[item.type].slice(0, 1) }}</span>
    <span class="row-body"><span class="row-title">{{ item.title }}</span><span class="row-location">{{ [item.communityName, item.houseCode || item.residentLabel].filter(Boolean).join(' · ') }}</span><span class="row-preview">{{ item.lastMessage || '暂无消息' }}</span></span>
    <span class="row-side"><time>{{ formatRelative(item.activityAt) }}</time><span v-if="item.unreadCount > 0" class="unread" :aria-label="`${item.unreadCount}条未读`">{{ item.unreadCount > 99 ? '99+' : item.unreadCount }}</span><span v-else-if="item.status === 'CLOSED'" class="closed">只读</span></span>
   </button>
   <button v-if="hasMore" type="button" class="load-more" :disabled="loading" @click="emit('more')">{{ loading ? '加载中…' : '加载更多会话' }}</button>
  </div>
 </aside>
</template>
<style scoped>
.inbox-list{display:flex;flex-direction:column;min-height:0;min-width:0;border-right:1px solid #e7ebf1;background:#fff}.inbox-search{padding:20px 16px 12px}.inbox-search input{width:100%;min-width:0;border:1px solid transparent;border-radius:7px;background:#f2f5f9;padding:10px 12px;font:inherit;font-size:12px;outline:none}.inbox-search input:focus{border-color:#356aff}.inbox-filters{display:flex;gap:6px;align-items:center;padding:0 16px 14px;border-bottom:1px solid #eef1f5}.inbox-filters button{font:inherit;font-size:12px;border:0;border-radius:5px;background:none;padding:5px 10px;cursor:pointer;color:#758093}.inbox-filters button.active{background:#eaf0ff;color:#356aff}.inbox-filters>span{margin-left:auto;font-size:10px;color:#929bad}.inbox-rows{min-height:0;overflow:auto;scrollbar-width:thin;flex:1}.inbox-row{display:flex;align-items:center;gap:11px;border:0;border-bottom:1px solid #f1f3f7;background:#fff;width:100%;text-align:left;padding:17px 14px;cursor:pointer;font:inherit}.inbox-row:hover{background:#f7f9fc}.inbox-row.active{background:#edf3ff;box-shadow:inset 3px 0 #356aff}.source-avatar{display:grid;place-items:center;width:40px;height:40px;border-radius:11px;background:#e9effa;color:#5576ac;font-size:16px;flex-shrink:0}.row-body{display:flex;flex-direction:column;gap:5px;min-width:0;flex:1}.row-title,.row-location,.row-preview{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row-title{font-size:13px;font-weight:600;color:#25334b}.row-location{font-size:10px;color:#8a94a4}.row-preview{font-size:12px;color:#758093}.row-side{display:flex;flex-direction:column;align-items:flex-end;align-self:stretch;justify-content:space-between;gap:8px;flex-shrink:0;font-size:10px;color:#929bad}.unread{background:#ef5b62;color:white;min-width:18px;padding:2px 5px;border-radius:12px;text-align:center;font-size:10px}.closed{font-size:10px}.list-state{text-align:center;padding:26px 16px;line-height:1.8;font-size:12px;color:#758093}.list-state button,.load-more{border:0;background:transparent;color:#356aff;cursor:pointer;padding:12px;font:inherit;font-size:12px}.load-more{display:block;margin:auto}.sr-only{position:absolute;width:1px;height:1px;clip-path:inset(50%);overflow:hidden}button:focus-visible{outline:2px solid #356aff;outline-offset:-3px}
</style>
