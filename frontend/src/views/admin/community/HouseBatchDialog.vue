<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createHouse, getBuildingList, getHouseList, getUnitList } from '@/api/community'
import type { IBuilding, ICommunity, IHouse, IUnit } from '@/types/modules/community'

/**
 * 房屋批量生成对话框（第二轮验收任务 A7 步骤 7.3；2026-09-20 精确建房增强）：
 * 选单元 + 楼层范围 + 每层房号序号起止（如 1~6 层 × 序号 1~4 → 101~104/…/601~604），
 * 预览确认后循环调既有 createHouse——逐个失败不中断，结束汇报成功/失败数。
 *
 * 精确建房（跳过/前后缀/单条取消）全部在生成前收敛为一份行清单：
 * 楼层范围 → 整层跳过 → 指定房号跳过 → 跨楼层撞号去重 → 预览单条取消；
 * 提交仍为逐条 createHouse，未改动任何接口契约与部分成功语义。
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

/** 预览折叠阈值：默认只铺开前若干套，其余展示套数由用户主动展开 */
const PREVIEW_LIMIT = 24

const formRef = ref<FormInstance>()
const submitting = ref(false)
const progress = ref(0)

const form = reactive({
  unitId: undefined as number | undefined,
  floorStart: 1,
  floorEnd: 1,
  roomStart: 1,
  roomEnd: 4,
  /** 房号前缀/后缀：拼接在基础门牌号两侧（A- + 101 + A → A-101A） */
  prefix: '',
  suffix: '',
  /** 跳过楼层（整层）：逗号/空格/顿号分隔的数字列表 */
  skipFloors: '',
  /** 跳过房号：可填基础门牌号（101）或含前后缀的完整门牌号（A-101） */
  skipHouses: '',
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

interface GenerateRow {
  floor: number
  /** 基础门牌号（楼层 + 两位序号，不含前后缀）：跳过房号可按此形式填写 */
  base: string
  /** 最终门牌号（拼接前后缀后提交后端） */
  houseNumber: string
}

/** 门牌号构成：楼层 + 两位序号（floor 2 × 序号 3 → 203），预览与创建共用 */
function baseHouseNumberOf(floor: number, seq: number): string {
  return `${floor}${String(seq).padStart(2, '0')}`
}

/* 输入解析：中英文逗号、顿号、空格（含连续）皆作分隔；空片段忽略 */
function parseTokens(raw: string): string[] {
  return raw
    .split(/[,，、\s]+/)
    .map((token) => token.trim())
    .filter(Boolean)
}

/** 整层跳过：仅识别正整数，非法片段在下拉提示中回显（不阻断生成） */
const skippedFloors = computed(() => {
  const floors: number[] = []
  for (const token of parseTokens(form.skipFloors)) {
    const value = Number(token)
    if (Number.isInteger(value) && value > 0) floors.push(value)
  }
  return floors
})

const invalidFloorTokens = computed(() =>
  parseTokens(form.skipFloors).filter((token) => {
    const value = Number(token)
    return !(Number.isInteger(value) && value > 0)
  })
)

/** 跳过房号：大小写不敏感比对，完整门牌号与基础门牌号均命中 */
const skippedHouseNumbers = computed(() =>
  parseTokens(form.skipHouses).map((token) => token.toUpperCase())
)

/** 全量网格（未做任何排除），仅用于统计被规则排除的套数 */
const allRows = computed<GenerateRow[]>(() => {
  const rows: GenerateRow[] = []
  for (let floor = form.floorStart; floor <= form.floorEnd; floor += 1) {
    for (let seq = form.roomStart; seq <= form.roomEnd; seq += 1) {
      const base = baseHouseNumberOf(floor, seq)
      rows.push({ floor, base, houseNumber: `${form.prefix}${base}${form.suffix}` })
    }
  }
  return rows
})

function isRuleExcluded(row: GenerateRow): boolean {
  if (skippedFloors.value.includes(row.floor)) return true
  const number = row.houseNumber.toUpperCase()
  return (
    skippedHouseNumbers.value.includes(number) ||
    skippedHouseNumbers.value.includes(row.base.toUpperCase())
  )
}

/** 预览单条取消（增量排除；生成规则变更后失效，见下方 watch） */
const cancelledNumbers = ref<string[]>([])

/* 规则去重：门牌号 = 楼层 + 两位序号，跨楼层理论上可能撞号，按首次出现保留，
   避免提交重号被后端同单元唯一约束拒绝 */
const ruleFilteredRows = computed<GenerateRow[]>(() => {
  const seen = new Set<string>()
  const rows: GenerateRow[] = []
  for (const row of allRows.value) {
    if (isRuleExcluded(row)) continue
    if (seen.has(row.houseNumber)) continue
    seen.add(row.houseNumber)
    rows.push(row)
  }
  return rows
})

/** 规则跳过的套数（整层跳过 + 指定房号跳过），与去重、手动取消分开计数 */
const skippedByRulesCount = computed(
  () => allRows.value.length - allRows.value.filter((row) => !isRuleExcluded(row)).length
)

const dedupedCount = computed(
  () =>
    allRows.value.filter((row) => !isRuleExcluded(row)).length - ruleFilteredRows.value.length
)

const generatedRows = computed<GenerateRow[]>(() =>
  ruleFilteredRows.value.filter((row) => !cancelledNumbers.value.includes(row.houseNumber))
)

const cancelledCount = computed(
  () => ruleFilteredRows.value.length - generatedRows.value.length
)

const overLimit = computed(() => generatedRows.value.length > MAX_GENERATE)

/* 预览折叠：默认铺开前 PREVIEW_LIMIT 套，展开后全量可见 */
const previewExpanded = ref(false)
const previewRows = computed(() =>
  previewExpanded.value ? generatedRows.value : generatedRows.value.slice(0, PREVIEW_LIMIT)
)
const previewHiddenCount = computed(() =>
  Math.max(0, generatedRows.value.length - PREVIEW_LIMIT)
)

/* 生成规则（范围/前后缀）变更后，原单条取消记录已不对应同一批门牌号，整体失效 */
watch(
  () => [form.floorStart, form.floorEnd, form.roomStart, form.roomEnd, form.prefix, form.suffix].join('|'),
  () => {
    cancelledNumbers.value = []
  }
)

function cancelRow(row: GenerateRow): void {
  if (!cancelledNumbers.value.includes(row.houseNumber)) {
    cancelledNumbers.value = [...cancelledNumbers.value, row.houseNumber]
  }
}

function restoreCancelled(): void {
  cancelledNumbers.value = []
}

/* 与所选单元现有房屋的重名预检（仅提示不阻断，失败由循环汇总汇报） */
const existingHouseNumbers = ref<string[]>([])
const duplicatedNumbers = computed(() => [
  ...new Set(
    generatedRows.value
      .filter((row) => existingHouseNumbers.value.includes(row.houseNumber))
      .map((row) => row.houseNumber)
  )
])

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
  form.prefix = ''
  form.suffix = ''
  form.skipFloors = ''
  form.skipHouses = ''
  form.area = undefined
  form.layout = ''
  form.orientation = ''
  formBuildings.value = []
  formUnits.value = []
  existingHouseNumbers.value = []
  cancelledNumbers.value = []
  previewExpanded.value = false
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
  if (generatedRows.value.length === 0) {
    ElMessage.error('当前生成清单为空，请调整楼层/房号范围或跳过规则')
    return
  }
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
  <el-dialog v-model="visible" title="批量生成房屋" width="680px" :close-on-click-modal="false">
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
      <el-form-item label="房号前后缀">
        <div class="range-inputs">
          <el-input v-model="form.prefix" placeholder="前缀（选填）" maxlength="6" style="width: 140px" />
          <el-input v-model="form.suffix" placeholder="后缀（选填）" maxlength="6" style="width: 140px" />
        </div>
        <span class="batch-hint">拼接在基础门牌号两侧：前缀 A- 与后缀 A → A-101A</span>
      </el-form-item>
      <el-form-item label="整层跳过">
        <el-input
          v-model="form.skipFloors"
          placeholder="如：4, 14（该层全部不生成）"
          maxlength="80"
        />
        <span v-if="invalidFloorTokens.length > 0" class="batch-hint is-warning">
          忽略无法识别的楼层：{{ invalidFloorTokens.join('、') }}
        </span>
      </el-form-item>
      <el-form-item label="跳过房号">
        <el-input
          v-model="form.skipHouses"
          placeholder="如：104, 404（可填基础房号或含前后缀的完整房号）"
          maxlength="120"
        />
        <span class="batch-hint">逗号/空格/顿号分隔；与现有房屋重名的号码可在此排除</span>
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
      <p
        v-if="skippedByRulesCount > 0 || dedupedCount > 0 || cancelledCount > 0"
        class="batch-preview-excluded"
      >
        <span>
          规则跳过 {{ skippedByRulesCount }} 套<template v-if="dedupedCount > 0">
            ，撞号去重 {{ dedupedCount }} 套</template
          ><template v-if="cancelledCount > 0">，已手动取消 {{ cancelledCount }} 套</template>
        </span>
        <el-button
          v-if="cancelledCount > 0"
          link
          type="primary"
          size="small"
          @click="restoreCancelled"
        >
          恢复已取消
        </el-button>
      </p>

      <div v-if="generatedRows.length > 0" class="preview-chips">
        <span
          v-for="row in previewRows"
          :key="row.houseNumber"
          class="preview-chip"
          :title="`${row.floor} 层 · 点 × 取消该套`"
        >
          {{ row.houseNumber }}
          <button
            type="button"
            class="chip-remove"
            :aria-label="`取消生成 ${row.houseNumber}`"
            @click="cancelRow(row)"
          >
            ×
          </button>
        </span>
      </div>
      <p v-else class="batch-preview-more">当前生成清单为空，请调整范围或跳过规则</p>

      <el-button
        v-if="previewHiddenCount > 0 || previewExpanded"
        link
        type="primary"
        size="small"
        class="preview-toggle"
        @click="previewExpanded = !previewExpanded"
      >
        {{ previewExpanded ? '收起预览' : `展开全部 ${generatedRows.length} 套` }}
      </el-button>

      <p v-if="duplicatedNumbers.length > 0" class="batch-warning">
        与现有房屋重名：{{ duplicatedNumbers.join('、') }}，创建时可能被拒绝
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :disabled="overLimit || generatedRows.length === 0"
        :loading="submitting"
        @click="handleSubmit"
      >
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

.batch-hint.is-warning {
  color: var(--color-warning);
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

/* 跳过/取消统计行：与预览 chip 区分层级，弱化展示 */
.batch-preview-excluded {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.preview-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  max-height: 180px;
  overflow: auto;
}

.preview-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 4px 2px 8px;
  border-radius: var(--radius-pill);
  background-color: var(--admin-card-bg);
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
}

.chip-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: var(--radius-circle);
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  line-height: 1;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.chip-remove:hover {
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
}

.preview-toggle {
  margin-top: var(--spacing-xs);
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
