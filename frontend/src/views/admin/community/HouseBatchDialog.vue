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
 * 楼层范围 → 跳过项（整层/指定房号）→ 跨楼层撞号去重 → 预览单条取消；
 * 跳过项为单一输入框，语法见 SKIP_SYNTAX_HINT；预览区同时列出生成项与跳过项
 * （跳过项灰字删除线）并给出总数，收敛结果提交前对用户完全可见。
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
  /** 跳过项：整层 / 指定房号混写，语法见 SKIP_SYNTAX_HINT */
  skip: '',
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

/** 跳过项语法（对话框内原样展示，避免实现与提示漂移）：
 *  4      → 第 4 层整层
 *  4:1    → 第 4 层 1 号（精确到单套）
 *  04     → 所有楼层的 4 号（两位补零即序号；序号 ≥ 10 写作 *:12）
 *  *:4    → 所有楼层的 4 号（通配楼层写法，等价于 04）
 *  104    → 基础门牌号（1 层 4 号，等价于 1:4）
 *  A-101  → 含前后缀的完整门牌号
 *  分隔符：逗号 / 顿号 / 空格；无法识别的片段原样提示忽略，不阻断生成。 */
const SKIP_SYNTAX_HINT =
  '4 整层 ｜ 4:1 第4层1号 ｜ 04 所有层4号 ｜ 104 基础房号 ｜ A-101 完整房号；逗号/顿号/空格分隔'

interface GenerateRow {
  floor: number
  /** 该层房号序号（跳过项按序号跨层匹配时使用） */
  seq: number
  /** 基础门牌号（楼层 + 两位序号，不含前后缀） */
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

/** 跳过项种类：整层 / 跨层序号 / 指定层房号 / 基础门牌号 / 完整门牌号 */
type SkipRuleKind = 'floor' | 'seq' | 'spot' | 'base' | 'number'

interface SkipRule {
  /** 用户原始输入片段（未命中与非法提示回显用） */
  raw: string
  kind: SkipRuleKind
  floor?: number
  seq?: number
  /** base / number 的比对文本（大写） */
  text?: string
}

const SPOT_PATTERN = /^(\*|\d{1,2})\s*[:：]\s*(\d{1,2})$/
/** 两位补零（01~09）即序号；一位数或无前导零的两位数是楼层 */
const PADDED_SEQ_PATTERN = /^0\d$/

/* 跳过项分类（位数即语义）：1~2 位纯数字＝整层楼层；两位补零或 *:n＝跨层序号；
   3~4 位＝基础门牌号（门牌号 = 楼层 + 两位序号，最短 3 位、最长 4 位）；
   含非数字字符＝完整门牌号（前后缀场景） */
function parseSkipRules(raw: string): { rules: SkipRule[]; invalid: string[] } {
  const rules: SkipRule[] = []
  const invalid: string[] = []
  for (const token of parseTokens(raw)) {
    const spot = SPOT_PATTERN.exec(token)
    if (spot) {
      const seq = Number(spot[2])
      if (seq > 0) {
        const floor = spot[1] === '*' ? undefined : Number(spot[1])
        rules.push(
          floor === undefined
            ? { raw: token, kind: 'seq', seq }
            : { raw: token, kind: 'spot', floor, seq }
        )
      } else {
        invalid.push(token)
      }
      continue
    }
    if (/^\d+$/.test(token)) {
      const value = Number(token)
      if (value <= 0 || token.length > 4) {
        invalid.push(token)
      } else if (PADDED_SEQ_PATTERN.test(token)) {
        rules.push({ raw: token, kind: 'seq', seq: value })
      } else if (token.length <= 2) {
        rules.push({ raw: token, kind: 'floor', floor: value })
      } else {
        rules.push({ raw: token, kind: 'base', text: token.toUpperCase() })
      }
      continue
    }
    rules.push({ raw: token, kind: 'number', text: token.toUpperCase() })
  }
  return { rules, invalid }
}

const skipRules = computed(() => parseSkipRules(form.skip))

const invalidSkipTokens = computed(() => skipRules.value.invalid)

function skipRuleMatches(rule: SkipRule, row: GenerateRow): boolean {
  if (rule.kind === 'floor') return row.floor === rule.floor
  if (rule.kind === 'seq') return row.seq === rule.seq
  if (rule.kind === 'spot') return row.floor === rule.floor && row.seq === rule.seq
  if (rule.kind === 'base') return row.base.toUpperCase() === rule.text
  return row.houseNumber.toUpperCase() === rule.text
}

/** 命中该行的首个跳过项（预览标注跳过原因用），未命中返回 null */
function firstSkipRule(row: GenerateRow): SkipRule | null {
  return skipRules.value.rules.find((rule) => skipRuleMatches(rule, row)) ?? null
}

/** 跳过项语义回显（预览悬浮提示与统计文案共用） */
function skipRuleLabel(rule: SkipRule): string {
  if (rule.kind === 'floor') return `${rule.floor} 层整层`
  if (rule.kind === 'seq') return `每层 ${String(rule.seq).padStart(2, '0')} 号`
  if (rule.kind === 'spot') return `${rule.floor} 层 ${rule.seq} 号`
  return `房号 ${rule.text}`
}

/** 全量网格（未做任何排除），仅用于统计被规则排除的套数 */
const allRows = computed<GenerateRow[]>(() => {
  const rows: GenerateRow[] = []
  for (let floor = form.floorStart; floor <= form.floorEnd; floor += 1) {
    for (let seq = form.roomStart; seq <= form.roomEnd; seq += 1) {
      const base = baseHouseNumberOf(floor, seq)
      rows.push({ floor, seq, base, houseNumber: `${form.prefix}${base}${form.suffix}` })
    }
  }
  return rows
})

/** 未命中任何生成行的跳过项：写法或范围有误时提示，避免用户以为已生效 */
const unmatchedSkipTokens = computed(() =>
  skipRules.value.rules
    .filter((rule) => !allRows.value.some((row) => skipRuleMatches(rule, row)))
    .map((rule) => rule.raw)
)

/* 规则收敛：一次遍历同时产出保留行与被去重剔除的行（预览与提交共用同一清单）。
   门牌号由楼层 + 两位序号拼成，跨楼层理论上可能撞号，按首次出现保留，
   避免提交重号被后端同单元唯一约束拒绝 */
const filteredRows = computed(() => {
  const seen = new Set<string>()
  const kept: GenerateRow[] = []
  const deduped: GenerateRow[] = []
  for (const row of allRows.value) {
    if (firstSkipRule(row)) continue
    if (seen.has(row.houseNumber)) {
      deduped.push(row)
      continue
    }
    seen.add(row.houseNumber)
    kept.push(row)
  }
  return { kept, deduped }
})

const ruleFilteredRows = computed(() => filteredRows.value.kept)
const dedupedRows = computed(() => filteredRows.value.deduped)

/** 撞号去重剔除条数（预览统计文案用） */
const dedupedCount = computed(() => dedupedRows.value.length)

/** 预览单条取消（增量排除；生成规则变更后失效，见下方 watch） */
const cancelledNumbers = ref<string[]>([])

/** 规则跳过按原因拆分：整层跳过与指定房号跳过（预览统计文案用） */
const skippedByRulesBreakdown = computed(() => {
  let floor = 0
  let house = 0
  for (const row of allRows.value) {
    const rule = firstSkipRule(row)
    if (!rule) continue
    if (rule.kind === 'floor') {
      floor += 1
    } else {
      house += 1
    }
  }
  return { floor, house }
})

const generatedRows = computed<GenerateRow[]>(() =>
  ruleFilteredRows.value.filter((row) => !cancelledNumbers.value.includes(row.houseNumber))
)

const cancelledCount = computed(
  () => ruleFilteredRows.value.length - generatedRows.value.length
)

const overLimit = computed(() => generatedRows.value.length > MAX_GENERATE)

/** 跳过项预览行：规则跳过 / 撞号去重 / 手动取消合成一张清单，渲染时统一灰字删除线 */
interface SkippedPreviewRow {
  houseNumber: string
  reason: string
}

const skippedPreviewRows = computed<SkippedPreviewRow[]>(() => {
  const rows: SkippedPreviewRow[] = []
  for (const row of allRows.value) {
    const rule = firstSkipRule(row)
    if (rule) {
      rows.push({ houseNumber: row.houseNumber, reason: `跳过（${skipRuleLabel(rule)}）` })
    }
  }
  for (const row of dedupedRows.value) {
    rows.push({ houseNumber: row.houseNumber, reason: '撞号去重（与已保留行门牌号重复）' })
  }
  for (const row of ruleFilteredRows.value) {
    if (cancelledNumbers.value.includes(row.houseNumber)) {
      rows.push({ houseNumber: row.houseNumber, reason: '手动取消' })
    }
  }
  return rows
})

const skippedTotal = computed(() => skippedPreviewRows.value.length)

/* 预览折叠：默认生成项与跳过项各铺开前 PREVIEW_LIMIT 套，展开后全量可见 */
const previewExpanded = ref(false)
const previewRows = computed(() =>
  previewExpanded.value ? generatedRows.value : generatedRows.value.slice(0, PREVIEW_LIMIT)
)
const previewSkippedRows = computed(() =>
  previewExpanded.value
    ? skippedPreviewRows.value
    : skippedPreviewRows.value.slice(0, PREVIEW_LIMIT)
)
const previewHiddenCount = computed(
  () =>
    Math.max(0, generatedRows.value.length - PREVIEW_LIMIT) +
    Math.max(0, skippedPreviewRows.value.length - PREVIEW_LIMIT)
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
  form.skip = ''
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
      <el-form-item label="跳过">
        <el-input
          v-model="form.skip"
          :placeholder="`如：14 或 4:1,4:3 或 04（${SKIP_SYNTAX_HINT}）`"
          maxlength="120"
        />
        <span class="batch-hint">{{ SKIP_SYNTAX_HINT }}</span>
        <span v-if="invalidSkipTokens.length > 0" class="batch-hint is-warning">
          忽略无法识别的跳过项：{{ invalidSkipTokens.join('、') }}
        </span>
        <span v-if="unmatchedSkipTokens.length > 0" class="batch-hint is-warning">
          未命中当前生成范围（未生效）：{{ unmatchedSkipTokens.join('、') }}
        </span>
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
        将生成 <b>{{ generatedRows.length }}</b> 套房屋<template v-if="skippedTotal > 0">
          ，跳过 <b>{{ skippedTotal }}</b> 套（整层 {{ skippedByRulesBreakdown.floor }} ·
          指定房号 {{ skippedByRulesBreakdown.house }} · 撞号去重 {{ dedupedCount }} · 手动取消
          {{ cancelledCount }}）</template
        >
        <span v-if="overLimit" class="batch-warning">（超过上限 {{ MAX_GENERATE }}，请缩小范围）</span>
      </p>

      <div v-if="generatedRows.length > 0" class="preview-group">
        <span class="preview-group-label">生成（{{ generatedRows.length }}）</span>
        <div class="preview-chips">
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
      </div>
      <p v-else class="batch-preview-more">当前生成清单为空，请调整范围或跳过规则</p>

      <!-- 跳过项（规则跳过 / 撞号去重 / 手动取消）：灰字删除线只读展示，点选与提交均不受影响 -->
      <div v-if="skippedPreviewRows.length > 0" class="preview-group">
        <span class="preview-group-label">
          跳过（{{ skippedPreviewRows.length }}）
          <el-button
            v-if="cancelledCount > 0"
            link
            type="primary"
            size="small"
            @click="restoreCancelled"
          >
            恢复已取消
          </el-button>
        </span>
        <div class="preview-chips">
          <span
            v-for="(row, index) in previewSkippedRows"
            :key="`${row.houseNumber}-${index}`"
            class="preview-chip is-skipped"
            :title="row.reason"
          >
            {{ row.houseNumber }}
          </span>
        </div>
      </div>

      <el-button
        v-if="previewHiddenCount > 0 || previewExpanded"
        link
        type="primary"
        size="small"
        class="preview-toggle"
        @click="previewExpanded = !previewExpanded"
      >
        {{
          previewExpanded
            ? '收起预览'
            : `展开全部（生成 ${generatedRows.length} · 跳过 ${skippedTotal}）`
        }}
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

/* 预览分组：生成项（可逐条取消）与跳过项（只读灰字删除线）各占一组 */
.preview-group + .preview-group {
  margin-top: var(--spacing-sm);
}

.preview-group-label {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: 2px;
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

/* 跳过项：灰字删除线，与生成项一眼可分（悬浮 title 给出跳过原因） */
.preview-chip.is-skipped {
  padding: 2px 8px;
  background-color: transparent;
  color: var(--color-text-disabled);
  text-decoration: line-through;
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
