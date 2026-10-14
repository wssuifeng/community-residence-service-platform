<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { findCoResidentCandidates, getResidenceDependents, createResidenceRelation, adminCreateResident } from '@/api/resident'
import type { IResidenceRelation, IResident, IAdminCreateResidentVO } from '@/types/modules/resident'
import { todayISO } from '@/utils/date'
const props = defineProps<{ anchor: IResidenceRelation | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [] }>()
const members = ref<IResidenceRelation[]>([])
const residents = ref<Array<Pick<IResident, 'id' | 'username' | 'realName'>>>([])
const loading = ref(false), saving = ref(false)
const error = ref('')
const created = ref<IAdminCreateResidentVO | null>(null)
const mode = ref<'existing' | 'create'>('existing')
const form = reactive({ residentId: undefined as number | undefined, realName: '', phone: '', username: '', relationType: 'FAMILY' as 'FAMILY' | 'CO_RESIDENT', moveInDate: todayISO(), remark: '' })
let version = 0
async function search(keyword: string) {
  const current = version
  try { if (!keyword.trim() || !props.anchor) { residents.value=[]; return }; const result = await findCoResidentCandidates(props.anchor.houseId,keyword.trim()); if (visible.value && current === version) { residents.value = result;error.value='' } }
  catch { if (current === version) error.value='账号查询失败，请重试' }
}
watch(visible, async value => {
  const current = ++version
  if (!value || !props.anchor) return
  members.value=[]; residents.value=[];error.value='';created.value=null;mode.value='existing'
  Object.assign(form,{residentId:undefined,realName:'',phone:'',username:'',relationType:'FAMILY',moveInDate:todayISO(),remark:''})
  loading.value=true
  try { const rows=await getResidenceDependents(props.anchor.id);if(current===version&&visible.value)members.value=rows; await search('') }
  catch(e){if(current===version)error.value=e instanceof Error?e.message:'同住人员加载失败'}
  finally{if(current===version)loading.value=false}
})
async function save() {
  const root=props.anchor
  if(!root || saving.value || loading.value || error.value || created.value)return
  if(!form.moveInDate || (mode.value==='existing'&&!form.residentId) || (mode.value==='create'&&(!form.realName.trim()||!/^1[3-9]\d{9}$/.test(form.phone)))){ElMessage.warning('请填写账号及入住日期');return}
  saving.value=true
  try{
    const relation={houseId:root.houseId,anchorRelationId:root.id,relationType:form.relationType,moveInDate:form.moveInDate,remark:form.remark.trim()||undefined}
    if(mode.value==='create')created.value=await adminCreateResident({...relation,communityId:root.communityId,realName:form.realName.trim(),phone:form.phone,username:form.username.trim()||undefined})
    else{await createResidenceRelation({...relation,residentId:form.residentId!});visible.value=false}
    ElMessage.success('同住关系已登记，不新增租约或付款义务');emit('saved')
  }catch(e){ElMessage.error(e instanceof Error?e.message:'登记失败')}
  finally{saving.value=false}
}
</script>
<template>
  <el-dialog v-model="visible" title="登记同住人员" width="min(620px, calc(100vw - 32px))" :close-on-click-modal="!saving" :show-close="!saving">
    <template v-if="anchor">
      <el-alert type="info" :closable="false" :title="`房屋：${anchor.houseLocation}；主住户：${anchor.residentName}。主居住关系结束时，同住关系同步结束；账号和历史保留。`" />
      <p v-if="loading">正在核对同住人员…</p><el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="members.length">当前同住人员：{{ members.map(m=>m.residentName).join('、') }}</p>
      <el-alert v-if="created" type="success" :closable="false" :title="`账号 ${created.username}，初始密码 ${created.initialPassword}（仅本次显示，请转交本人）`" />
      <el-form v-else label-width="100px" :disabled="saving || loading">
        <el-form-item label="账号方式"><el-radio-group v-model="mode"><el-radio value="existing">选择已有账号</el-radio><el-radio value="create">代建独立账号</el-radio></el-radio-group></el-form-item>
        <el-form-item v-if="mode==='existing'" label="居民账号"><el-select v-model="form.residentId" filterable remote :remote-method="search" placeholder="输入完整用户名或手机号" style="width:100%"><el-option v-for="r in residents" :key="r.id" :value="r.id" :label="`${r.realName} · ${r.username}`" /></el-select></el-form-item>
        <template v-else><el-form-item label="姓名"><el-input v-model="form.realName" maxlength="50" /></el-form-item><el-form-item label="手机号"><el-input v-model="form.phone" maxlength="11" /></el-form-item><el-form-item label="用户名"><el-input v-model="form.username" maxlength="50" placeholder="可选，支持中文" /></el-form-item></template>
        <el-form-item label="关系类型"><el-select v-model="form.relationType"><el-option value="FAMILY" label="家属" /><el-option value="CO_RESIDENT" label="共同居住人" /></el-select></el-form-item>
        <el-form-item label="入住日期"><el-date-picker v-model="form.moveInDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" maxlength="200" /></el-form-item>
      </el-form>
    </template>
    <template #footer><el-button :disabled="saving" @click="visible=false">{{ created?'完成':'取消' }}</el-button><el-button v-if="!created" type="primary" :loading="saving" :disabled="loading || !!error" @click="save">登记同住关系</el-button></template>
  </el-dialog>
</template>
