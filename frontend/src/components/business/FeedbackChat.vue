<script setup lang="ts">
import { computed } from 'vue'
import ChatMessageList from './ChatMessageList.vue'
import ChatComposer from './ChatComposer.vue'
import { useChatChannel } from '@/composables/useChatChannel'
import { listFeedbackMessages,sendFeedbackMessage } from '@/api/feedback'
import { sendChatImage } from '@/api/chat'
import { useUserStore } from '@/store/user'
import type { IFeedbackMessage } from '@/types/modules/feedback'
const props=defineProps<{feedbackId:number;closed:boolean;initialContent?:string;initialName?:string;initialDate?:string}>()
const emit=defineEmits<{changed:[]}>(),user=useUserStore()
const {messages,text,sending,readOnly,sendText,sendImage}=useChatChannel<IFeedbackMessage>({id:()=>props.feedbackId,closed:()=>props.closed,load:listFeedbackMessages,send:(id,content)=>sendFeedbackMessage(id,{content}),image:(id,file)=>sendChatImage('feedbacks',id,file),topic:id=>`/topic/feedback/${id}`,eventType:'FEEDBACK_MESSAGE',changed:()=>emit('changed')})
const rendered=computed(()=>[
 ...(props.initialContent?[{id:-props.feedbackId,content:props.initialContent,createdAt:props.initialDate??'',senderLabel:props.initialName??'居民',mine:user.role==='RESIDENT'}]:[]),
 ...messages.value.map(message=>({...message,senderLabel:`${message.senderName}（${message.senderType==='ADMIN'?'管理员':'居民'}）`,mine:message.senderType===(user.role==='RESIDENT'?'RESIDENT':'ADMIN')}))
])
</script>
<template><section class="feedback-chat"><ChatMessageList :messages="rendered"/><ChatComposer v-model="text" :busy="sending" :closed="closed" :read-only="readOnly" closed-hint="反馈已办结，历史消息只读留档" @send="sendText" @image="sendImage"/></section></template>
