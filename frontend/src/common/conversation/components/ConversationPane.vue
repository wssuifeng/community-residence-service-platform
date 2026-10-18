<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useUserStore } from '@/common/auth/store/user'
import { getConversation } from '@/common/conversation/api/conversation'
import { getFeedbackDetail } from '@/common/feedback/api/feedback'
import { useLiveRefresh } from '@/common/web/composables/useLiveRefresh'
import ConversationChat from '@/common/conversation/components/ConversationChat.vue'
import FeedbackChat from '@/common/conversation/components/FeedbackChat.vue'
import ConversationBusinessActions from '@/common/conversation/components/ConversationBusinessActions.vue'
import { inboxTypeLabels, type ConversationSource } from '@/common/conversation/types/conversationInbox'
import { conversationRoleLabels, type IConversation } from '@/common/conversation/types/conversation'
import type { IFeedback } from '@/common/feedback/types/feedback'
const props = defineProps<{ source: ConversationSource; id: number }>()
const emit = defineEmits<{ back: []; changed: []; read: [] }>()
const user = useUserStore(), conversation = ref<IConversation | null>(null), feedback = ref<IFeedback | null>(null)
const loading = ref(false), error = ref(''), menu = ref(false), members = ref(false)
const business = ref<InstanceType<typeof ConversationBusinessActions> | null>(null)
let disposed = false, request = 0
const management = computed(() => user.role === 'ADMIN' || user.role === 'SUPER_ADMIN')
const role = computed<'ADMIN' | 'RESIDENT'>(() => management.value ? 'ADMIN' : 'RESIDENT')
const type = computed(() => props.source === 'FEEDBACK' ? 'FEEDBACK' : conversation.value?.type)
const title = computed(() => feedback.value?.title ?? conversation.value?.title ?? '会话')
const closed = computed(() => !!error.value || (feedback.value?.status ?? conversation.value?.status) === 'CLOSED')
const closedLabel = computed(() => (feedback.value?.status ?? conversation.value?.status) === 'CLOSED' ? '已关闭 · 历史只读' : '进行中')
const businessUrl = computed(() => {
 const id = props.source === 'FEEDBACK' ? props.id : conversation.value?.relatedId
 const base = management.value ? '/admin' : user.role === 'STAFF' ? '/staff' : '/resident'
 if (type.value === 'FEEDBACK') return management.value ? `${base}/feedbacks?id=${id}` : `${base}/feedbacks/${id}`
 if (type.value === 'WORK_ORDER_GROUP') return `${base}/work-orders/${id}`
 if (type.value === 'LEASE_GROUP') return management.value ? `${base}/leases/${id}` : `${base}/housing-lease?tab=lease&leaseId=${id}`
 if (type.value === 'VIEWING_GROUP') return management.value ? `${base}/housing-lease?tab=housings&view=viewings&appointmentId=${id}` : user.role === 'STAFF' ? `${base}/viewings` : `${base}/viewing-appointments/${id}`
 return ''
})
async function load() {
 const version = ++request; loading.value = !conversation.value && !feedback.value
 try {
  if (props.source === 'FEEDBACK') { const result = await getFeedbackDetail(props.id); if (!disposed && version === request) feedback.value = result }
  else { const result = await getConversation(props.id); if (!disposed && version === request) conversation.value = result }
  if (!disposed && version === request) error.value = ''
 } catch (e) { if (!disposed && version === request) error.value = e instanceof Error ? e.message : '会话读取失败' }
 finally { if (!disposed && version === request) loading.value = false }
}
function changed() { emit('changed'); void load() }
useLiveRefresh(load, () => [{domain:props.source === 'FEEDBACK' ? 'feedback' : 'conversation',id:props.id}])
onMounted(load)
onBeforeUnmount(() => { disposed = true; request++ })
</script>
<template>
 <section class="conversation-pane" @keydown.esc="menu = false">
  <p v-if="loading" class="pane-state" role="status">正在打开会话…</p>
  <template v-if="conversation || feedback">
   <header class="chat-heading">
    <button type="button" class="mobile-back" aria-label="返回会话列表" @click="emit('back')">‹</button>
    <div class="heading-main"><h2>{{ title }}</h2><p>{{ type ? inboxTypeLabels[type] : '沟通' }}<span>·</span>{{ closedLabel }}<template v-if="feedback?.isAnonymous"><span>·</span>匿名反馈</template></p></div>
    <div class="heading-menu"><button type="button" aria-label="会话菜单" :aria-expanded="menu" @click="menu = !menu">⋯</button><div v-if="menu" class="menu-popover"><button type="button" @click="members = true; menu = false">查看成员</button><router-link v-if="businessUrl" :to="businessUrl" @click="menu = false">查看关联业务</router-link></div></div>
   </header>
   <p v-if="error" class="pane-error" role="alert">{{ error }}；状态未知时暂停发送。<button type="button" @click="load">重试</button></p>
   <p v-if="type === 'LEASE_GROUP'" class="context-note">协议与看房入口在输入区“＋”中。首期费用成功后租约正式生效。</p>
   <ConversationChat v-if="conversation" paged :conversation-id="id" :closed="closed" @read="emit('read')" @changed="changed" @open-agreement="business?.openAgreement($event)">
    <template v-if="conversation.type === 'LEASE_GROUP' && user.role !== 'STAFF'" #business-actions>
     <button v-if="role === 'RESIDENT'" type="button" class="action-button" :disabled="closed" @click="business?.openViewing()">预约本租约房源看房</button>
     <button type="button" class="action-button" @click="business?.openAgreement()">{{ role === 'RESIDENT' ? '查看 / 同意协议' : '发送 / 查看协议' }}</button>
    </template>
   </ConversationChat>
   <FeedbackChat v-if="feedback" paged :feedback-id="id" :closed="closed" :initial-content="feedback.content" :initial-name="feedback.residentName" :initial-date="feedback.createdAt" @changed="changed" @read="emit('read')" />
   <ConversationBusinessActions v-if="conversation?.type === 'LEASE_GROUP' && user.role !== 'STAFF'" ref="business" :conversation="conversation" :role="role" :controls="false" />
   <el-dialog v-model="members" title="会话成员" width="min(480px, 92vw)">
    <ul v-if="conversation" class="members"><li v-for="member in conversation.participants" :key="`${member.ownerType}:${member.userId}`"><span>{{ member.name }}</span><span>{{ conversationRoleLabels[member.role] ?? member.role }}</span></li></ul>
    <ul v-else-if="feedback" class="members"><li><span>{{ feedback.residentName || '居民' }}</span><span>居民</span></li><li><span>{{ feedback.handlerName || '管辖管理方' }}</span><span>管理员</span></li></ul>
   </el-dialog>
  </template>
  <div v-else-if="error" class="pane-state" role="alert">{{ error }}<button type="button" @click="load">重新打开</button></div>
 </section>
</template>
<style scoped>
.conversation-pane{display:flex;flex-direction:column;min-width:0;min-height:0;height:100%;background:#fff}.chat-heading{display:flex;align-items:center;gap:10px;padding:10px 16px;border-bottom:1px solid #e7ebf1;min-height:56px;flex-shrink:0}.heading-main{min-width:0;flex:1}.heading-main h2{margin:0 0 3px;font-size:15px;font-weight:600;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.heading-main p{margin:0;color:#8791a2;font-size:11px}.heading-main p span{padding:0 8px}.heading-menu{position:relative}.heading-menu>button{border:0;border-radius:6px;background:none;font-size:24px;cursor:pointer;width:34px;height:34px}.heading-menu>button:hover{background:#f2f5f9}.menu-popover{position:absolute;right:0;top:40px;z-index:10;min-width:150px;padding:6px;background:white;border:1px solid #e7ebf1;border-radius:8px;box-shadow:0 6px 20px #1426461a}.menu-popover a,.menu-popover button{display:block;width:100%;font:inherit;font-size:13px;text-align:left;padding:10px;border:0;background:none;text-decoration:none;color:#25334b;cursor:pointer}.menu-popover a:hover,.menu-popover button:hover{background:#f2f5f9}.mobile-back{display:none}.pane-state{text-align:center;padding:40px 20px;color:#758093}.pane-state button,.pane-error button{border:0;background:none;color:#356aff;cursor:pointer}.pane-error{padding:10px 20px;background:#fff6ef;color:#955a28;font-size:12px;margin:0}.context-note{padding:4px 16px;margin:0;font-size:11px;color:#7e8c9e;background:#f8faff;border-bottom:1px solid #edf1f6}.action-button{padding:8px 12px;border:1px solid var(--color-border);background:white;border-radius:7px;font:inherit;font-size:12px;color:var(--color-primary);cursor:pointer}.members{list-style:none;padding:0;margin:0}.members li{display:flex;justify-content:space-between;padding:12px 0;border-bottom:1px solid #edf1f6}.members span{color:#8a94a4;font-size:12px}
.conversation-pane :deep(.conversation-chat),.conversation-pane :deep(.feedback-chat){border:0;border-radius:0;flex:1;min-height:0}.conversation-pane :deep(.chat-message-list){max-height:none;min-height:0}.conversation-pane :deep(.chat-scroll-shell){min-height:0}.conversation-pane :deep(.chat-input-row textarea){height:56px;min-height:56px;max-height:clamp(72px,18dvh,140px);resize:vertical}.conversation-pane :deep(.business-actions){order:2}
.conversation-pane :deep(.history-button){flex-shrink:0;padding:4px 8px;font-size:11px;line-height:16px}.conversation-pane :deep(.business-toggle){width:28px;height:28px;font-size:20px}.conversation-pane :deep(.agreement-message){margin-top:6px;padding:6px 10px}
@media(max-width:1050px){.mobile-back{display:block;border:0;background:none;font-size:28px;color:#758093;cursor:pointer}.chat-heading{padding:10px 12px}}
</style>
