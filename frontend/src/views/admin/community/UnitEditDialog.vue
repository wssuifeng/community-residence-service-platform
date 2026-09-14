<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createUnit, getBuildingList, updateUnit } from '@/api/community'
import type { IBuilding, ICommunity, IUnit, IUnitDTO } from '@/types/modules/community'

/**
 * 单元新建/编辑对话框（任务 3 自 UnitListView 表单逻辑收编抽取，
 * 结构树楼栋节点「新增单元」/单元节点「编辑」共用）。
 * 保留原对话框内社区→楼栋二级联动；编辑回显改由 IUnit.communityId
 * 直接定位（原视图靠筛选选项兜底推断，对话框场景无筛选态）。
 * 社区选项由父层（结构树已加载）传入，避免重复请求。
 */
const props = defineProps<{
  /** 社区下拉选项（结构树已加载的社区列表） */
  communities: ICommunity[]
  /** 编辑目标：null = 新建 */
  unit?: IUnit | null
  /** 新建时的默认社区/楼栋（树上下文预选） */
  defaultCommunityId?: number | null
  defaultBuildingId?: number | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带保存后的实体（新建返回创建结果），供父层展开对应树节点 */
const emit = defineEmits<{ saved: [entity: IUnit] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单态 DTO：外键未选择时为 undefined（提交前经 rules 校验收敛） */
type UnitForm = Omit<IUnitDTO, 'buildingId'> & { buildingId?: number }

const form = reactive<UnitForm>({
  buildingId: undefined,
  name: '',
  description: ''
})

/** 对话框内独立维护的社区→楼栋联动（与树选中状态互不干扰） */
const formCommunityId = ref<number | ''>('')
const formBuildings = ref<IBuilding[]>([])

const rules: FormRules = {
  buildingId: [{ required: true, message: '请选择所属楼栋', trigger: 'change' }],
  name: [{ required: true, message: '请输入单元名称', trigger: 'blur' }]
}

async function handleFormCommunityChange(): Promise<void> {
  form.buildingId = undefined
  formBuildings.value = []
  if (formCommunityId.value === '') return
  try {
    const result = await getBuildingList(formCommunityId.value, { page: 1, size: 200 })
    formBuildings.value = result.records
  } catch {
    formBuildings.value = []
  }
}

/* 每次打开按编辑目标/树上下文重置表单与联动 */
watch(visible, (value) => {
  if (!value) return
  formBuildings.value = []
  if (props.unit) {
    formCommunityId.value = props.unit.communityId
    form.name = props.unit.name
    form.description = props.unit.description ?? ''
    form.buildingId = props.unit.buildingId
    /* 回显楼栋选项：按单元所属社区拉取（communityId 为 IUnit 既有字段） */
    getBuildingList(props.unit.communityId, { page: 1, size: 200 })
      .then((result) => {
        formBuildings.value = result.records
      })
      .catch(() => {
        formBuildings.value = []
      })
  } else {
    form.name = ''
    form.description = ''
    form.buildingId = props.defaultBuildingId ?? undefined
    formCommunityId.value = props.defaultCommunityId ?? ''
    if (formCommunityId.value !== '') {
      getBuildingList(formCommunityId.value, { page: 1, size: 200 })
        .then((result) => {
          formBuildings.value = result.records
        })
        .catch(() => {
          formBuildings.value = []
        })
    }
  }
  formRef.value?.clearValidate()
})

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    let saved: IUnit
    if (props.unit) {
      saved = await updateUnit(props.unit.id, { ...form, buildingId: form.buildingId as number })
      ElMessage.success('单元已更新')
    } else {
      saved = await createUnit({ ...form, buildingId: form.buildingId as number })
      ElMessage.success('单元创建成功')
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
  <el-dialog v-model="visible" :title="unit ? '编辑单元' : '新建单元'" width="480px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="所属社区">
        <el-select
          v-model="formCommunityId"
          placeholder="请选择社区"
          style="width: 100%"
          @change="handleFormCommunityChange"
        >
          <el-option
            v-for="item in communities"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="所属楼栋" prop="buildingId">
        <el-select
          v-model="form.buildingId"
          placeholder="请选择楼栋"
          style="width: 100%"
          :disabled="formCommunityId === ''"
        >
          <el-option
            v-for="item in formBuildings"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="单元名称" prop="name">
        <el-input v-model="form.name" placeholder="如：1单元" maxlength="20" />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          placeholder="请输入单元描述"
          maxlength="100"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>
