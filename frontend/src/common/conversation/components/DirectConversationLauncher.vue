<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/common/auth/store/user'
import { getCommunityList } from '@/common/community/api/community'
import { getResidentList } from '@/common/resident/api/resident'
import { getLeaseList } from '@/common/lease/api/lease'
import { openDirectConversation } from '@/common/conversation/api/conversation'
const emit = defineEmits<{ opened: [id: number] }>(), user = useUserStore()
const visible = ref(false), busy = ref(false), communityId = ref<number>(), residentId = ref<number>()
const communities = ref<Array<{ id: number; name: string }>>([]), residents = ref<Array<{ id: number; realName: string; username: string }>>([])
const admin = computed(() => user.role === 'ADMIN' || user.role === 'SUPER_ADMIN')
let disposed = false, sequence = 0
async function searchResidents(keyword = '') {
 const version = ++sequence
 try { const result = await getResidentList({ page: 1, size: 100, keyword }); if (!disposed && version === sequence) residents.value = result.records }
 catch (e) { if (!disposed) ElMessage.error(e instanceof Error ? e.message : '居民选项读取失败') }
}
async function open() {
 visible.value = true; communityId.value = undefined; residentId.value = undefined; communities.value = []
 try {
  if (admin.value) { const page = await getCommunityList({ page: 1, size: 100 }); if (!disposed) communities.value = page.records; void searchResidents() }
  else { const page = await getLeaseList({ page: 1, size: 100 }); if (!disposed) communities.value = [...new Map(page.records.map(item => [item.communityId, { id: item.communityId, name: item.communityName || `社区 #${item.communityId}` }])).values()] }
  if (!disposed && communities.value.length === 1) communityId.value = communities.value[0].id
 } catch (e) { if (!disposed) ElMessage.error(e instanceof Error ? e.message : '社区选项读取失败') }
}
async function submit() {
 if (!communityId.value || admin.value && !residentId.value || busy.value) return
 busy.value = true
 try { const result = await openDirectConversation({ communityId: communityId.value, ...(admin.value ? { residentId: residentId.value } : {}) }); if (!disposed) { visible.value = false; emit('opened', result.id) } }
 catch (e) { if (!disposed) ElMessage.error(e instanceof Error ? e.message : '发起会话失败') }
 finally { if (!disposed) busy.value = false }
}
onBeforeUnmount(() => { disposed = true; sequence++ })
</script>
<template>
 <button v-if="user.role !== 'STAFF'" type="button" class="direct-launch" @click="open">＋ {{ admin ? '发起直通会话' : '联系社区管理员' }}</button>
 <el-dialog v-model="visible" title="发起直通会话" width="min(460px, 92vw)">
  <el-form label-width="72px"><el-form-item label="社区"><el-select v-model="communityId" placeholder="选择社区" filterable><el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
   <el-form-item v-if="admin" label="居民"><el-select v-model="residentId" placeholder="输入姓名或用户名搜索居民" filterable remote :remote-method="searchResidents"><el-option v-for="item in residents" :key="item.id" :label="`${item.realName || item.username}（${item.username}）`" :value="item.id" /></el-select></el-form-item>
  </el-form>
  <p v-if="!communities.length" class="direct-hint">暂无可用社区，请确认社区归属。发起时后端会再次核验归属。</p>
  <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!communityId || (admin && !residentId)" @click="submit">发起会话</el-button></template>
 </el-dialog>
</template>
<style scoped>.direct-launch{border:1px solid #dce3ed;border-radius:7px;padding:9px 13px;background:#fff;color:#557096;font:inherit;font-size:12px;cursor:pointer}.direct-hint{font-size:12px;color:#8a94a4}</style>
