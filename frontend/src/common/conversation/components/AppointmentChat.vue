<script setup lang="ts">
import { computed } from 'vue'
import { listViewingMessages,sendViewingMessage } from '@/common/housing/api/housing'
import { sendChatImage } from '@/common/conversation/api/chat'
import { useChatChannel } from '@/common/conversation/composables/useChatChannel'
import { useUserStore } from '@/common/auth/store/user'
import type { IViewingMessage } from '@/common/housing/types/housing'
import ChatMessageList from '@/common/conversation/components/ChatMessageList.vue'
import ChatComposer from '@/common/conversation/components/ChatComposer.vue'
const props=defineProps<{appointmentId:number;closed?:boolean}>(),user=useUserStore()
const {messages,text,sending,readOnly,sendText,sendImage}=useChatChannel<IViewingMessage>({id:()=>props.appointmentId,closed:()=>props.closed??false,load:listViewingMessages,send:(id,content)=>sendViewingMessage(id,{content}),image:(id,file)=>sendChatImage('viewing-appointments',id,file),topic:id=>`/topic/appointment/${id}`,eventType:'APPOINTMENT_MESSAGE'})
const rendered=computed(()=>messages.value.map(message=>({...message,senderLabel:message.senderName,mine:message.senderId===user.user?.id})))
</script>
<template><section class="appointment-chat"><ChatMessageList :messages="rendered"/><ChatComposer v-model="text" :busy="sending" :closed="closed" :read-only="readOnly" @send="sendText" @image="sendImage"/></section></template>
