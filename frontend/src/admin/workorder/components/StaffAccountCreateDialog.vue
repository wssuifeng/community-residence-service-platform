<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import type { ICommunity } from '@/common/community/types/community'
import type { ICreateStaffAccountDTO } from '@/common/workorder/types/staff'
import { createStaffAccount } from '@/common/workorder/api/staff'
import { isValidUsername, USERNAME_HINT } from '@/common/auth/utils/username'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ communities: ICommunity[] }>()
const emit = defineEmits<{ created: [] }>()
const formRef = shallowRef<FormInstance>()
const saving = shallowRef(false)
const available = computed(() => props.communities.filter(item => item.status === 'ACTIVE'))
const form = reactive<ICreateStaffAccountDTO>({ username: '', password: '', realName: '', phone: '', email: '', role: 'STAFF', communityIds: [] })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }, { validator: (_rule, value, done) => isValidUsername(value) ? done() : done(new Error(USERNAME_HINT)), trigger: 'blur' }],
  password: [{ required: true, message: '请输入初始密码', trigger: 'blur' }, { min: 8, max: 20, message: '密码为8～20位', trigger: 'blur' }],
  realName: [{ required: true, whitespace: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }, { pattern: /^1[3-9]\d{9}$/, message: '请输入有效手机号', trigger: 'blur' }],
  email: [{ type: 'email', message: '请输入有效邮箱', trigger: 'blur' }],
  communityIds: [{ type: 'array', required: true, min: 1, message: '至少选择一个社区', trigger: 'change' }]
}
watch(visible, value => {
  if (!value) return
  Object.assign(form, { username: '', password: '', realName: '', phone: '', email: '', role: 'STAFF', communityIds: available.value.length === 1 ? [available.value[0]!.id] : [] })
  formRef.value?.clearValidate()
})
async function submit(): Promise<void> {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    await createStaffAccount({ ...form, communityIds: [...form.communityIds] })
    ElMessage.success('服务人员账号已创建并绑定社区')
    form.password = ''
    visible.value = false
    emit('created')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '代建失败') }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" title="代建服务人员账号" width="min(520px, 94vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @closed="form.password = ''">
    <p class="form-note">账号将以服务人员身份创建，必须绑定至少一个你有权管理的运营中社区。</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px" @submit.prevent="submit">
      <el-form-item label="用户名" prop="username"><el-input v-model="form.username" :placeholder="USERNAME_HINT" maxlength="50" autocomplete="off" /></el-form-item>
      <el-form-item label="初始密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" maxlength="20" /></el-form-item>
      <el-form-item label="姓名" prop="realName"><el-input v-model="form.realName" maxlength="50" /></el-form-item>
      <el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" maxlength="11" /></el-form-item>
      <el-form-item label="邮箱" prop="email"><el-input v-model="form.email" maxlength="100" placeholder="选填" /></el-form-item>
      <el-form-item label="绑定社区" prop="communityIds">
        <el-select v-model="form.communityIds" multiple filterable style="width:100%" placeholder="至少选择一个社区">
          <el-option v-for="item in available" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
      </el-form-item>
      <el-alert v-if="available.length === 0" type="warning" :closable="false" title="当前没有可绑定的运营中社区，请先确认社区授权。" />
    </el-form>
    <template #footer><el-button :disabled="saving" @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="available.length === 0" @click="submit">创建并绑定</el-button></template>
  </el-dialog>
</template>

<style scoped>
.form-note { margin: 0 0 20px; color: var(--color-text-secondary); line-height: 1.7; }
</style>
