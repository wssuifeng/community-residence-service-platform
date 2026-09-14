<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createBuilding, updateBuilding } from '@/api/community'
import type { IBuilding, IBuildingDTO, ICommunity } from '@/types/modules/community'

/**
 * 楼栋新建/编辑对话框（任务 3 自 BuildingListView 表单逻辑收编抽取，
 * 结构树社区节点「新增楼栋」/楼栋节点「编辑」共用）。
 * 字段、校验规则、成功/失败文案与原视图保持一致；
 * 社区选项由父层（结构树已加载）传入，避免重复请求。
 */
const props = defineProps<{
  /** 社区下拉选项（结构树已加载的社区列表） */
  communities: ICommunity[]
  /** 编辑目标：null = 新建 */
  building?: IBuilding | null
  /** 新建时的默认社区（树上下文预选） */
  defaultCommunityId?: number | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带保存后的实体（新建返回创建结果），供父层展开对应树节点 */
const emit = defineEmits<{ saved: [entity: IBuilding] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单态 DTO：外键未选择时为 undefined（提交前经 rules 校验收敛） */
type BuildingForm = Omit<IBuildingDTO, 'communityId'> & { communityId?: number }

const form = reactive<BuildingForm>({
  communityId: undefined,
  name: '',
  floors: 1,
  description: ''
})

const rules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  name: [{ required: true, message: '请输入楼栋名称', trigger: 'blur' }],
  floors: [{ required: true, message: '请输入总层数', trigger: 'blur' }]
}

/* 每次打开按编辑目标/默认社区重置表单 */
watch(visible, (value) => {
  if (!value) return
  if (props.building) {
    form.communityId = props.building.communityId
    form.name = props.building.name
    form.floors = props.building.floors
    form.description = props.building.description ?? ''
  } else {
    form.communityId = props.defaultCommunityId ?? undefined
    form.name = ''
    form.floors = 1
    form.description = ''
  }
  formRef.value?.clearValidate()
})

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    let saved: IBuilding
    if (props.building) {
      saved = await updateBuilding(props.building.id, { ...form, communityId: form.communityId as number })
      ElMessage.success('楼栋已更新')
    } else {
      saved = await createBuilding({ ...form, communityId: form.communityId as number })
      ElMessage.success('楼栋创建成功')
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
  <el-dialog v-model="visible" :title="building ? '编辑楼栋' : '新建楼栋'" width="480px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="所属社区" prop="communityId">
        <el-select v-model="form.communityId" placeholder="请选择社区" style="width: 100%">
          <el-option
            v-for="item in communities"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="楼栋名称" prop="name">
        <el-input v-model="form.name" placeholder="如：1号楼" maxlength="30" />
      </el-form-item>
      <el-form-item label="总层数" prop="floors">
        <el-input-number v-model="form.floors" :min="1" :max="99" />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="请输入楼栋描述"
          maxlength="200"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>
