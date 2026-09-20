<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createCommunity, updateCommunity } from '@/api/community'
import type { ICommunity, ICreateCommunityDTO } from '@/types/modules/community'

/**
 * 社区新建/编辑对话框（任务 3 自 CommunityListView 表单逻辑收编抽取：
 * 结构树「新增社区/编辑」与社区详情「编辑社区」双入口共用）。
 * 字段、校验规则、成功/失败文案与原视图保持一致。
 */
const props = defineProps<{
  /** 编辑目标：null = 新建 */
  community?: ICommunity | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带保存后的实体（新建返回创建结果），供父层展开对应树节点 */
const emit = defineEmits<{ saved: [entity: ICommunity] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive<ICreateCommunityDTO>({
  name: '',
  address: '',
  contactPhone: '',
  contactPerson: '',
  description: '',
  autoApproveResidence: 0,
  defaultLeaseMonths: 12
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入社区名称', trigger: 'blur' }],
  address: [{ required: true, message: '请输入社区地址', trigger: 'blur' }]
}

/* 每次打开按编辑目标重置表单（新建为全空，自动化变量回落默认值） */
watch(visible, (value) => {
  if (!value) return
  form.name = props.community?.name ?? ''
  form.address = props.community?.address ?? ''
  form.contactPhone = props.community?.contactPhone ?? ''
  form.contactPerson = props.community?.contactPerson ?? ''
  form.description = props.community?.description ?? ''
  form.autoApproveResidence = props.community?.autoApproveResidence ?? 0
  form.defaultLeaseMonths = props.community?.defaultLeaseMonths ?? 12
  formRef.value?.clearValidate()
})

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    /* 可选文本留空时传 undefined（后端 contactPhone @Pattern 不接受空字符串） */
    const payload: ICreateCommunityDTO = {
      name: form.name.trim(),
      address: form.address.trim(),
      contactPhone: (form.contactPhone ?? '').trim() || undefined,
      contactPerson: (form.contactPerson ?? '').trim() || undefined,
      description: (form.description ?? '').trim() || undefined,
      autoApproveResidence: form.autoApproveResidence ? 1 : 0,
      defaultLeaseMonths: form.defaultLeaseMonths ?? 12
    }
    let saved: ICommunity
    if (props.community) {
      saved = await updateCommunity(props.community.id, payload)
      ElMessage.success('社区已更新')
    } else {
      saved = await createCommunity(payload)
      ElMessage.success('社区创建成功')
    }
    visible.value = false
    emit('saved', saved)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="community ? '编辑社区' : '新建社区'" width="520px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="社区名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入社区名称" maxlength="50" />
      </el-form-item>
      <el-form-item label="社区地址" prop="address">
        <el-input v-model="form.address" placeholder="请输入社区地址" maxlength="100" />
      </el-form-item>
      <el-form-item label="联系人" prop="contactPerson">
        <el-input v-model="form.contactPerson" placeholder="请输入联系人" maxlength="20" />
      </el-form-item>
      <el-form-item label="联系电话" prop="contactPhone">
        <el-input v-model="form.contactPhone" placeholder="请输入联系电话" maxlength="20" />
      </el-form-item>
      <el-form-item label="社区简介" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="请输入社区简介"
          maxlength="200"
        />
      </el-form-item>

      <el-divider content-position="left">入住申请自动化</el-divider>
      <el-form-item label="自动通过">
        <el-switch
          v-model="form.autoApproveResidence"
          :active-value="1"
          :inactive-value="0"
          active-text="提交即通过"
          inactive-text="人工审核"
        />
      </el-form-item>
      <el-form-item v-if="form.autoApproveResidence" label="默认租期">
        <el-input-number v-model="form.defaultLeaseMonths" :min="1" :max="120" :step="1" />
        <span class="form-hint">个月（自动通过时按此推导租期止期；租金与押金取房源挂牌值）</span>
      </el-form-item>
      <p v-else class="form-hint form-hint-block">
        关闭时居民提交租住申请进入「待审核」，由管理方在入住申请中人工审批；
        开启后提交即时通过，并自动生成居住关系与租约。
      </p>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-hint {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.form-hint-block {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg);
  line-height: var(--line-height-relaxed);
  margin-left: 0;
}
</style>
