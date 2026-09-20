<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createHouse, updateHouse } from '@/api/community'
import type { IHouse, IHouseDTO, HouseStatus } from '@/types/modules/community'
import { houseStatusLabels } from '@/types/modules/community'

/**
 * 房屋新建/编辑小对话框（第三轮 C4，结构总览网格便捷操作用）：
 * 所属单元由网格入口锁定（楼栋/单元上下文已知，后端 update 亦拒绝变更 unitId），
 * 免去三级联动选择。新建只留快速字段（门牌/楼层/面积/状态，
 * CreateHouseDTO 必填项为 unitId/houseNumber/floor/area，status 可选默认空置）；
 * 编辑字段与原「房屋管理」Tab 编辑对话框同契约（updateHouse，不含 status——
 * 状态变更须走 updateHouseStatus 留痕接口，入口在总览网格悬浮钮/信息卡）。
 */

const props = defineProps<{
  /** 编辑目标：null = 新建（按 unitContext 快速添加单间） */
  house: IHouse | null
  /** 网格入口所在单元上下文（只读展示楼栋/单元） */
  unitContext: { buildingName: string; unitId: number; unitName: string } | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带受影响单元ID，父层据此局部刷新该单元房屋网格 */
const emit = defineEmits<{ saved: [unitId: number] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<{
  houseNumber: string
  floor: number
  area?: number
  status: HouseStatus
  roomCount?: number
  layout: string
  orientation: string
  description: string
}>({
  houseNumber: '',
  floor: 1,
  area: undefined,
  status: 'VACANT',
  roomCount: undefined,
  layout: '',
  orientation: '',
  description: ''
})

const isEdit = computed(() => props.house !== null)

const rules: FormRules = {
  houseNumber: [{ required: true, message: '请输入门牌号', trigger: 'blur' }],
  floor: [{ required: true, message: '请输入所在楼层', trigger: 'blur' }],
  area: [{ required: true, message: '请输入建筑面积', trigger: 'blur' }]
}

/* 每次打开按目标/上下文重置（新建=快速四字段默认值；编辑=整单回显） */
watch(visible, (value) => {
  if (!value) return
  if (props.house) {
    form.houseNumber = props.house.houseNumber
    form.floor = props.house.floor
    form.area = props.house.area ?? undefined
    form.roomCount = props.house.roomCount ?? undefined
    form.layout = props.house.layout ?? ''
    form.orientation = props.house.orientation ?? ''
    form.description = props.house.description ?? ''
  } else {
    form.houseNumber = ''
    form.floor = 1
    form.area = undefined
    form.status = 'VACANT'
    form.roomCount = undefined
    form.layout = ''
    form.orientation = ''
    form.description = ''
  }
  formRef.value?.clearValidate()
})

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !props.unitContext) return
  submitting.value = true
  try {
    if (props.house) {
      /* 编辑不含 status（状态变更须留痕）；文本空串原样提交（清空生效，与总览编辑表单同口径） */
      const payload: IHouseDTO = {
        unitId: props.house.unitId,
        houseNumber: form.houseNumber,
        floor: form.floor,
        area: form.area,
        roomCount: form.roomCount,
        layout: form.layout,
        orientation: form.orientation,
        description: form.description
      }
      await updateHouse(props.house.id, payload)
      ElMessage.success('房屋已更新')
    } else {
      const payload: IHouseDTO = {
        unitId: props.unitContext.unitId,
        houseNumber: form.houseNumber,
        floor: form.floor,
        area: form.area,
        status: form.status
      }
      await createHouse(payload)
      ElMessage.success('房屋创建成功')
    }
    visible.value = false
    emit('saved', props.unitContext.unitId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? `编辑房屋：${house?.houseNumber ?? ''}` : '快速添加房屋'"
    width="480px"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="所属单元">
        <span class="unit-context">
          {{ unitContext ? `${unitContext.buildingName} · ${unitContext.unitName}` : '-' }}
        </span>
      </el-form-item>
      <el-form-item label="门牌号" prop="houseNumber">
        <el-input v-model="form.houseNumber" placeholder="如：101" maxlength="20" />
      </el-form-item>
      <el-form-item label="所在楼层" prop="floor">
        <el-input-number v-model="form.floor" :min="1" :max="99" />
      </el-form-item>
      <el-form-item label="建筑面积(㎡)" prop="area">
        <el-input-number v-model="form.area" :min="1" :max="10000" :precision="2" />
      </el-form-item>
      <el-form-item v-if="!isEdit" label="房屋状态">
        <el-select v-model="form.status" style="width: 100%">
          <el-option
            v-for="(label, value) in houseStatusLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </el-form-item>
      <template v-else>
        <el-form-item label="户型">
          <el-input v-model="form.layout" placeholder="如：2室1厅1卫" maxlength="20" />
        </el-form-item>
        <el-form-item label="房间数">
          <el-input-number v-model="form.roomCount" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="朝向">
          <el-input v-model="form.orientation" placeholder="如：南北" maxlength="10" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入房屋描述"
            maxlength="200"
          />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.unit-context {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
