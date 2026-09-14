<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createHouse, getBuildingList, getHouseList, getUnitList } from '@/api/community'
import type { IBuilding, ICommunity, IHouse, IUnit } from '@/types/modules/community'

/**
 * 房屋批量生成对话框（第二轮验收任务 A7 步骤 7.3）：
 * 选单元 + 楼层范围 + 每层房号序号起止（如 1~6 层 × 序号 1~4 → 101~104/…/601~604），
 * 生成预览表确认后循环调既有 createHouse——逐个失败不中断，结束汇报成功/失败数。
 * 分区布局（目标单元/生成规则/公共属性 + 预览），遵守大表单治理原则。
 */
const props = defineProps<{
  /** 社区下拉选项（父层已加载） */
  communities: ICommunity[]
  /** 打开时按当前筛选预选三级上下文（可为空） */
  initialCommunityId?: number | ''
  initialBuildingId?: number | ''
  initialUnitId?: number | ''
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 通知父层刷新房屋列表（携带目标单元便于回填筛选） */
const emit = defineEmits<{ saved: [unitId: number] }>()

/** 单次生成上限：循环创建为逐请求提交，超大批量应分批（防误操作 + 请求风暴） */
const MAX_GENERATE = 100

const formRef = ref<FormInstance>()
const submitting = ref(false)
const progress = ref(0)

const form = reactive({
  unitId: undefined as number | undefined,
  floorStart: 1,
  floorEnd: 1,
  roomStart: 1,
  roomEnd: 4,
  area: undefined as number | undefined,
  layout: '',
  orientation: ''
})

/** 对话框内独立三级联动（与列表筛选状态隔离） */
const formCommunityId = ref<number | ''>('')
const formBuildingId = ref<number | ''>('')
const formBuildings = ref<IBuilding[]>([])
const formUnits = ref<IUnit[]>([])

const rules: FormRules = {
  unitId: [{ required: true, message: '请选择所属单元', trigger: 'change' }],
  area: [{ required: true, message: '请输入建筑面积', trigger: 'blur' }],
  layout: [{ max: 20, message: '户型不超过 20 字', trigger: 'blur' }],
  orientation: [{ max: 10, message: '朝向不超过 10 字', trigger: 'blur' }]
}

/** 门牌号构成：楼层 + 两位序号（floor 2 × 序号 3 → 203），预览与创建共用 */
function houseNumberOf(floor: number, seq: number): string {
  return `${floor}${String(seq).padStart(2, '0')}`
}

const generatedRows = computed<{ floor: number; houseNumber: string }[]>(() => {
  const rows: { floor: number; houseNumber: string }[] = []
  for (let floor = form.floorStart; floor <= form.floorEnd; floor += 1) {
    for (let seq = form.roomStart; seq <= form.roomEnd; seq += 1) {
      rows.push({ floor, houseNumber: houseNumberOf(floor, seq) })
    }
  }
  return rows
})

const overLimit = computed(() => generatedRows.value.length > MAX_GENERATE)

/* 与所选单元现有房屋的重名预检（仅提示不阻断，失败由循环汇总汇报） */
const existingHouseNumbers = ref<string[]>([])
const duplicatedRows = computed(() =>
  generatedRows.value.filter((row) => existingHouseNumbers.value.includes(row.houseNumber))
)

async function handleFormCommunityChange(): Promise<void> {
  formBuildingId.value = ''
  form.unitId = undefined
  formBuildings.value = []
  formUnits.value = []
  existingHouseNumbers.value = []
  if (formCommunityId.value === '') return
  try {
    const result = await getBuildingList(formCommunityId.value, { page: 1, size: 200 })
    formBuildings.value = result.records
  } catch {
    formBuildings.value = []
  }
}

async function handleFormBuildingChange(): Promise<void> {
  form.unitId = undefined
  formUnits.value = []
  existingHouseNumbers.value = []
  if (formBuildingId.value === '') return
  try {
    const result = await getUnitList(formBuildingId.value, { page: 1, size: 200 })
    formUnits.value = result.records
  } catch {
    formUnits.value = []
  }
}

async function loadExistingHouses(unitId: number): Promise<void> {
  try {
    const result = await getHouseList(unitId, { page: 1, size: 200 })
    existingHouseNumbers.value = result.records.map((item: IHouse) => item.houseNumber)
  } catch {
    existingHouseNumbers.value = []
  }
}

watch(
  () => form.unitId,
  (value) => {
    existingHouseNumbers.value = []
    if (value !== undefined) {
      loadExistingHouses(value)
    }
  }
)

/* 楼层/房号范围防倒置：起点越过终点时把终点拉平 */
watch(
  () => form.floorStart,
  (value) => {
    if (form.floorEnd < value) form.floorEnd = value
  }
)
watch(
  () => form.roomStart,
  (value) => {
    if (form.roomEnd < value) form.roomEnd = value
  }
)

watch(visible, (value) => {
  if (!value) return
  formCommunityId.value = props.initialCommunityId ?? ''
  formBuildingId.value = props.initialBuildingId ?? ''
  form.unitId = props.initialUnitId === '' ? undefined : props.initialUnitId
  form.floorStart = 1
  form.floorEnd = 1
  form.roomStart = 1
  form.roomEnd = 4
  form.area = undefined
  form.layout = ''
  form.orientation = ''
  formBuildings.value = []
  formUnits.value = []
  existingHouseNumbers.value = []
  /* 回显三级联动选项：按初始上下文逐级拉取（无上下文则留空让用户选择） */
  const init = async (): Promise<void> => {
    if (formCommunityId.value !== '') {
      try {
        formBuildings.value = (
          await getBuildingList(formCommunityId.value as number, { page: 1, size: 200 })
        ).records
      } catch {
        formBuildings.value = []
      }
    }
    if (formBuildingId.value !== '') {
      try {
        formUnits.value = (
          await getUnitList(formBuildingId.value as number, { page: 1, size: 200 })
        ).records
      } catch {
        formUnits.value = []
      }
    }
    if (form.unitId !== undefined) {
      loadExistingHouses(form.unitId)
    }
  }
  init()
  formRef.value?.clearValidate()
})

/** 逐个创建不中断：任一失败记录首个错误信息继续剩余项 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || form.unitId === undefined) return
  if (overLimit.value) {
    ElMessage.error(`单次最多生成 ${MAX_GENERATE} 套，请缩小楼层/房号范围`)
    return
  }
  submitting.value = true
  progress.value = 0
  let success = 0
  let fail = 0
  let firstError = ''
  try {
    for (const row of generatedRows.value) {
      try {
        await createHouse({
          unitId: form.unitId,
          houseNumber: row.houseNumber,
          floor: row.floor,
          area: form.area,
          layout: form.layout || undefined,
          orientation: form.orientation || undefined
        })
        success += 1
      } catch (error) {
        fail += 1
        if (!firstError) {
          firstError = error instanceof Error ? error.message : '创建失败'
        }
      }
      progress.value += 1
    }
    if (fail === 0) {
      ElMessage.success(`成功生成 ${success} 套房屋`)
    } else {
      ElMessage.warning(`成功 ${success} 套，失败 ${fail} 套${firstError ? `：${firstError}` : ''}`)
    }
    visible.value = false
    emit('saved', form.unitId)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量生成房屋" width="600px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <div class="batch-section">目标单元</div>
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
      <el-form-item label="所属楼栋">
        <el-select
          v-model="formBuildingId"
          placeholder="请选择楼栋"
          style="width: 100%"
          :disabled="formCommunityId === ''"
          @change="handleFormBuildingChange"
        >
          <el-option
            v-for="item in formBuildings"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="所属单元" prop="unitId">
        <el-select
          v-model="form.unitId"
          placeholder="请选择单元"
          style="width: 100%"
          :disabled="formBuildingId === ''"
        >
          <el-option
            v-for="item in formUnits"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <div class="batch-section">生成规则</div>
      <el-form-item label="楼层范围">
        <div class="range-inputs">
          <el-input-number v-model="form.floorStart" :min="1" :max="99" />
          <span class="range-sep">至</span>
          <el-input-number v-model="form.floorEnd" :min="form.floorStart" :max="99" />
          <span class="range-sep">层</span>
        </div>
      </el-form-item>
      <el-form-item label="每层房号">
        <div class="range-inputs">
          <el-input-number v-model="form.roomStart" :min="1" :max="99" />
          <span class="range-sep">至</span>
          <el-input-number v-model="form.roomEnd" :min="form.roomStart" :max="99" />
          <span class="range-sep">号</span>
        </div>
        <span class="batch-hint">门牌号 = 楼层 + 两位序号（1 层序号 1~4 → 101~104）</span>
      </el-form-item>

      <div class="batch-section">公共属性（全部房屋统一）</div>
      <el-form-item label="建筑面积" prop="area">
        <el-input-number v-model="form.area" :min="1" :max="10000" :precision="2" />
        <span class="batch-hint">㎡（必填，后端校验）</span>
      </el-form-item>
      <el-form-item label="户型" prop="layout">
        <el-input v-model="form.layout" placeholder="如：2室1厅1卫" maxlength="20" style="width: 220px" />
      </el-form-item>
      <el-form-item label="朝向" prop="orientation">
        <el-input v-model="form.orientation" placeholder="如：南北" maxlength="10" style="width: 220px" />
      </el-form-item>
    </el-form>

    <div class="batch-preview">
      <p class="batch-preview-title">
        将生成 {{ generatedRows.length }} 套房屋
        <span v-if="overLimit" class="batch-warning">（超过上限 {{ MAX_GENERATE }}，请缩小范围）</span>
      </p>
      <el-table v-if="generatedRows.length > 0" :data="generatedRows.slice(0, 8)" size="small" max-height="200">
        <el-table-column prop="floor" label="楼层" width="80" />
        <el-table-column prop="houseNumber" label="门牌号" min-width="100" />
      </el-table>
      <p v-if="generatedRows.length > 8" class="batch-preview-more">
        仅预览前 8 行，共 {{ generatedRows.length }} 套
      </p>
      <p v-if="duplicatedRows.length > 0" class="batch-warning">
        与现有房屋重名：{{ duplicatedRows.map((row) => row.houseNumber).join('、') }}，创建时可能被拒绝
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="overLimit" :loading="submitting" @click="handleSubmit">
        {{ submitting ? `生成中（${progress}/${generatedRows.length}）` : '确定生成' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.batch-section {
  margin: 0 0 var(--spacing-md);
  padding-left: 2px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.batch-hint {
  display: block;
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  color: var(--color-text-secondary);
}

.range-inputs {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.range-sep {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.batch-preview {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.batch-preview-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.batch-preview-more {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.batch-warning {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--color-warning);
}
</style>
