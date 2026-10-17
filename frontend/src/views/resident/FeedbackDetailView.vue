<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getFeedbackDetail,listFeedbackAttachments,deleteFeedbackAttachment } from '@/api/feedback'
import type { IFeedback,IFeedbackAttachment } from '@/types/modules/feedback'
import { feedbackCategoryLabels,feedbackStatusLabels } from '@/types/modules/feedback'
import { formatDateTime } from '@/utils/date'
import ConversationEntry from '@/components/conversation/ConversationEntry.vue'
const route=useRoute(),router=useRouter(),id=Number(route.params.id)
const feedback=ref<IFeedback|null>(null),attachments=ref<IFeedbackAttachment[]>([]),loading=ref(true)
async function refresh(){try{feedback.value=await getFeedbackDetail(id)}catch(error){ElMessage.error(error instanceof Error?error.message:'反馈加载失败')}}
async function removeAttachment(attachmentId:number){
 try{await ElMessageBox.confirm('确认删除该历史附件？','删除附件',{type:'warning'});}catch{return}
 try{await deleteFeedbackAttachment(attachmentId);attachments.value=attachments.value.filter(a=>a.id!==attachmentId)}catch(error){ElMessage.error(error instanceof Error?error.message:'删除失败')}
}
onMounted(async()=>{await refresh();try{attachments.value=await listFeedbackAttachments(id)}catch{ElMessage.warning('历史附件加载失败，可在会话页继续沟通')}loading.value=false})
</script>
<template>
 <section class="feedback-detail"><button class="back-link" type="button" @click="router.push('/resident/feedbacks')">← 返回我的反馈</button><p v-if="loading">加载中…</p>
  <template v-else-if="feedback"><header><h1>{{feedback.title}}</h1><p>{{feedbackCategoryLabels[feedback.category]}} · {{feedbackStatusLabels[feedback.status]}} · {{formatDateTime(feedback.createdAt)}}</p><p>提交人：{{feedback.residentName}}<span v-if="feedback.isAnonymous">（匿名）</span> · 受理人：{{feedback.handlerName??'待受理'}}</p></header>
   <section class="feedback-original"><h2>反馈内容</h2><p>{{ feedback.content }}</p></section>
   <ConversationEntry :id="id" source="FEEDBACK" title="反馈沟通" :closed="feedback.status === 'CLOSED'" />
   <section v-if="attachments.length" class="legacy-attachments"><h2>提交附件／历史附件</h2><p>这些是旧附件记录，不表示某一条聊天消息。后续图片请使用消息输入区发送。</p><a v-for="item in attachments" :key="item.id" :href="item.fileUrl" target="_blank" rel="noopener"><el-image v-if="item.fileType==='IMAGE'" :src="item.fileUrl" :preview-src-list="[item.fileUrl]" preview-teleported/>{{item.fileName}}</a><el-button v-if="feedback.status!=='CLOSED'" v-for="item in attachments" :key="`delete-${item.id}`" text type="danger" @click="removeAttachment(item.id)">删除历史附件：{{item.fileName}}</el-button></section>
  </template>
 </section>
</template>
<style scoped>.feedback-detail{max-width:1100px;margin:auto;padding:24px}.back-link{border:0;background:transparent;color:var(--color-primary);cursor:pointer;margin-bottom:16px}header{margin-bottom:24px}.feedback-original{padding:20px;margin:0 0 20px;background:var(--color-surface,#fff);border-radius:10px}.feedback-original h2{font-size:15px}.feedback-original p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}header p{color:var(--color-text-secondary)}.legacy-attachments{padding:20px;margin-top:20px;background:var(--color-surface,#fff);border-radius:12px}.legacy-attachments a{display:inline-flex;flex-direction:column;width:180px;margin:12px}.legacy-attachments .el-image{height:140px}</style>
