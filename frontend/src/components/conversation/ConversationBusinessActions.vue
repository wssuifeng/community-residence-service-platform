<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AgreementSignPanel from '@/components/business/AgreementSignPanel.vue'
import HousingReservationForm from '@/components/housing/HousingReservationForm.vue'
import { getLease } from '@/api/lease'
import type { ILeaseRecord } from '@/types/modules/lease'
import type { IConversation } from '@/types/modules/conversation'
const props = defineProps<{ conversation: IConversation; role: 'RESIDENT' | 'ADMIN'; controls?: boolean }>()
const lease = ref<ILeaseRecord | null>(null), agreementVisible = ref(false), viewingVisible = ref(false), loading = ref(false)
const agreementId = ref<number | undefined>()
let disposed = false
async function openAgreement(id?: number) {
 if (!props.conversation.relatedId || loading.value) return
 const cid = props.conversation.id; loading.value = true
 try {
  const result = await getLease(props.conversation.relatedId)
  if (disposed || cid !== props.conversation.id) return
  lease.value = result; agreementId.value = id; agreementVisible.value = true
 } catch (e) { if (!disposed) ElMessage.error(e instanceof Error ? e.message : '租约读取失败，请重试') }
 finally { if (!disposed) loading.value = false }
}
function openViewing() { viewingVisible.value = true }
defineExpose({ openAgreement, openViewing })
onBeforeUnmount(() => { disposed = true })
</script>
<template>
 <button v-if="controls !== false && role === 'RESIDENT'" type="button" class="business-action" :disabled="conversation.status === 'CLOSED'" @click="openViewing">预约本租约房源看房</button>
 <button v-if="controls !== false" type="button" class="business-action" :disabled="loading" @click="openAgreement()">{{ role === 'RESIDENT' ? '查看 / 同意协议' : '发送 / 查看协议' }}</button>
 <el-dialog v-model="viewingVisible" title="在本租约群聊预约看房" width="min(960px, 94vw)" destroy-on-close><HousingReservationForm v-if="viewingVisible" :key="conversation.id" :conversation-id="conversation.id" @created="viewingVisible = false" /></el-dialog>
 <el-dialog v-model="agreementVisible" title="租赁协议" width="min(720px, 94vw)" destroy-on-close>
  <AgreementSignPanel v-if="lease && agreementVisible" :lease-id="lease.id" :lease-status="lease.status" :community-id="lease.communityId" :role="role" :requested-agreement-id="agreementId" :self-managed="false" :read-only="conversation.status === 'CLOSED'" />
 </el-dialog>
</template>
<style scoped>.business-action{border:1px solid var(--color-border);border-radius:7px;padding:8px 12px;font:inherit;font-size:12px;background:white;color:var(--color-primary);cursor:pointer}.business-action:disabled{opacity:.5;cursor:not-allowed}</style>
