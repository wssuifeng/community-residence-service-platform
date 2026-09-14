<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createBuilding, createHouse, createUnit, getBuildingList } from '@/api/community'
import type { IBuilding, ICommunity } from '@/types/modules/community'

/**
 * 整栋创建对话框（第三轮验收任务 C3，升级自第二轮 A7 批量建楼）：
 * 一次录入「楼栋 + 单元 + 房屋」三级生成规则，前端循环调既有
 * createBuilding / createUnit / createHouse 逐步创建（后端无批量端点，
 * 缺口已记 BEAUTIFY_NOTES 后端适配清单）。
 * - 分区①楼栋：前缀 + 起始序号 + 数量 + 总层数（沿用 A7 规则）；
 * - 分区②单元：前缀可编辑（默认逐栋取完整楼栋名，清空则纯序号）+ 每栋单元数（0 = 仅建楼栋）；
 * - 分区③房屋：每层房号起止 + 面积默认值（CreateHouseDTO.area @NotNull，必填给默认输入）。
 * 逐个 try/catch 不中断，按楼/单元/房三层计数汇报；全失败保留输入不关对话框
 * （A7 遗留 Minor 修复）。个别房屋差异（如某层多一间）属后续精细化操作，
 * 走房屋管理 Tab 的批量生成/行内编辑/新建，本对话框只负责整栋基础数据。
 */
const props = defineProps<{
  /** 社区下拉选项（结构树已加载的社区列表） */
  communities: ICommunity[]
  /** 打开时预选的社区（树选中上下文） */
  defaultCommunityId?: number | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带目标社区 ID，父层刷新树/统计并展开该社区 */
const emit = defineEmits<{ saved: [communityId: number] }>()

/** 防请求风暴上限（HouseBatchDialog 100 套先例的整栋放大版）：房屋总量上限 */
const MAX_TOTAL_HOUSES = 200
/** 树形摘要仅展示前 N 栋（其余结构相同，避免 10×10 长列表撑爆预览） */
const PREVIEW_BUILDING_LIMIT = 4

const formRef = ref<FormInstance>()
const submitting = ref(false)
/** 创建进度（已完成数），驱动按钮 loading 文案「创建中（n/总数）」 */
const progress = ref(0)

const form = reactive({
  communityId: undefined as number | undefined,
  prefix: '',
  startNo: 1,
  count: 1,
  floors: 6,
  unitPrefix: '',
  unitCount: 2,
  roomStart: 1,
  roomEnd: 4,
  area: undefined as number | undefined,
  layout: '',
  orientation: ''
})

/** 单元前缀是否被用户手动编辑过（闩锁）：未编辑时逐栋默认完整楼栋名，编辑后固定用输入值 */
const unitPrefixTouched = ref(false)

/** 将生成的楼栋名（前缀 + 序号 + 栋），预览与循环创建共用同一来源 */
const generatedBuildingNames = computed<string[]>(() => {
  if (!form.prefix.trim()) return []
  const names: string[] = []
  for (let i = 0; i < form.count; i += 1) {
    names.push(`${form.prefix.trim()}${form.startNo + i}栋`)
  }
  return names
})

/**
 * 单元名 = 单元前缀 + 序号 + 「单元」：未闩锁时逐栋取完整楼栋名（2026-09-13 用户裁决：
 * 「1号楼」这类楼栋名自动为批量单元前缀，完整链路如 1号楼1单元101）；
 * 编辑后全部楼栋共用输入值，清空则纯序号（「1单元」）。
 */
function unitNameOf(buildingName: string, seq: number): string {
  const prefix = unitPrefixTouched.value ? form.unitPrefix.trim() : buildingName
  return `${prefix}${seq}单元`
}

/** 门牌号构成：楼层 + 两位序号（与 HouseBatchDialog 同规则，floor 2 × 序号 3 → 203） */
function houseNumberOf(floor: number, seq: number): string {
  return `${floor}${String(seq).padStart(2, '0')}`
}

/** 每层房号数（房号起止为闭区间，防倒置由 watch 保证） */
const roomsPerFloor = computed(() => Math.max(0, form.roomEnd - form.roomStart + 1))
/** 每单元房屋数 = 层数 × 每层房号数 */
const housesPerUnit = computed(() => form.floors * roomsPerFloor.value)
const totalUnits = computed(() => generatedBuildingNames.value.length * form.unitCount)
const totalHouses = computed(() => totalUnits.value * housesPerUnit.value)
/** 循环创建总步数（进度分母）：楼栋 + 单元 + 房屋 */
const totalOps = computed(
  () => generatedBuildingNames.value.length + totalUnits.value + totalHouses.value
)
const overLimit = computed(() => totalHouses.value > MAX_TOTAL_HOUSES)

/** 树形摘要：每栋 → 单元 chip（单元全名 × 房屋数，首尾门牌按「单元全名+房号」完整组合）；仅展示前 4 栋 */
const previewTree = computed(() =>
  generatedBuildingNames.value.slice(0, PREVIEW_BUILDING_LIMIT).map((name) => ({
    name,
    units: Array.from({ length: form.unitCount }, (_, j) => {
      const unitName = unitNameOf(name, 1 + j)
      return {
        name: unitName,
        houseCount: housesPerUnit.value,
        houseRange:
          housesPerUnit.value > 0
            ? `${unitName}${houseNumberOf(1, form.roomStart)}~${unitName}${houseNumberOf(form.floors, form.roomEnd)}`
            : '-'
      }
    })
  }))
)
const hiddenBuildingCount = computed(() =>
  Math.max(0, generatedBuildingNames.value.length - PREVIEW_BUILDING_LIMIT)
)

const rules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  prefix: [{ required: true, message: '请输入楼栋名前缀', trigger: 'blur' }],
  count: [{ required: true, message: '请输入创建数量', trigger: 'blur' }],
  floors: [{ required: true, message: '请输入总层数', trigger: 'blur' }],
  unitCount: [{ required: true, message: '请输入每栋单元数', trigger: 'blur' }],
  /* 面积后端必填（CreateHouseDTO.area @NotNull），但仅在确实生成房屋时要求 */
  area: [
    {
      validator: (_rule, value: number | undefined, callback) => {
        if (totalHouses.value > 0 && (value === undefined || value === null)) {
          callback(new Error('请输入默认面积（生成房屋必填）'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

/* 目标社区现有楼栋名（重名仅提示不阻断：失败由循环汇总汇报） */
const existingNames = ref<string[]>([])
const duplicatedNames = computed(() =>
  generatedBuildingNames.value.filter((name) => existingNames.value.includes(name))
)

async function loadExistingBuildings(communityId: number): Promise<void> {
  try {
    const result = await getBuildingList(communityId, { page: 1, size: 200 })
    existingNames.value = result.records.map((item: IBuilding) => item.name)
  } catch {
    existingNames.value = []
  }
}

/* 首栋完整楼栋名 → 单元前缀输入框联动（仅未闩锁时）：输入框展示首栋全名作代表值，
   实际生成由 unitNameOf 逐栋取各自完整楼栋名 */
watch(
  () => generatedBuildingNames.value[0] ?? '',
  (firstName) => {
    if (!unitPrefixTouched.value) form.unitPrefix = firstName
  }
)

/* 房号范围防倒置：起点越过终点时把终点拉平（与 HouseBatchDialog 同策略） */
watch(
  () => form.roomStart,
  (value) => {
    if (form.roomEnd < value) form.roomEnd = value
  }
)

watch(visible, (value) => {
  if (!value) return
  form.communityId = props.defaultCommunityId ?? undefined
  form.prefix = ''
  form.startNo = 1
  form.count = 1
  form.floors = 6
  form.unitPrefix = ''
  form.unitCount = 2
  form.roomStart = 1
  form.roomEnd = 4
  form.area = undefined
  form.layout = ''
  form.orientation = ''
  unitPrefixTouched.value = false
  existingNames.value = []
  if (form.communityId !== undefined) {
    loadExistingBuildings(form.communityId)
  }
  formRef.value?.clearValidate()
})

watch(
  () => form.communityId,
  (value) => {
    existingNames.value = []
    if (value !== undefined) {
      loadExistingBuildings(value)
    }
  }
)

/** 三层计数汇总段（如「楼栋 成功 1 失败 0」），仅在有失败/未执行时追加对应后缀 */
function levelSummary(label: string, ok: number, fail: number, skipped: number): string {
  let text = `${label} 成功 ${ok}`
  if (fail > 0) text += ` 失败 ${fail}`
  if (skipped > 0) text += ` 未执行 ${skipped}`
  return text
}

/**
 * 逐级创建不中断：楼栋失败则其下单元/房屋计为「未执行」（非失败），进度分母保持一致；
 * 全部层级零成功 → 保留输入不关对话框（A7 遗留 Minor），否则关闭并刷新树。
 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || form.communityId === undefined) return
  if (overLimit.value) {
    ElMessage.error(`房屋总量 ${totalHouses.value} 超过上限 ${MAX_TOTAL_HOUSES}，请缩小范围`)
    return
  }
  submitting.value = true
  progress.value = 0
  let buildingOk = 0
  let buildingFail = 0
  let unitOk = 0
  let unitFail = 0
  let unitSkipped = 0
  let houseOk = 0
  let houseFail = 0
  let houseSkipped = 0
  let firstError = ''
  const recordError = (error: unknown): void => {
    if (!firstError) {
      firstError = error instanceof Error ? error.message : '创建失败'
    }
  }
  try {
    for (const name of generatedBuildingNames.value) {
      let buildingId: number | null = null
      try {
        const created = await createBuilding({
          communityId: form.communityId,
          name,
          floors: form.floors
        })
        buildingId = created.id
        buildingOk += 1
      } catch (error) {
        buildingFail += 1
        recordError(error)
      }
      progress.value += 1
      if (buildingId === null) {
        /* 楼栋失败：跳过其下级创建，进度按计划步数补齐，保持分母一致 */
        unitSkipped += form.unitCount
        houseSkipped += form.unitCount * housesPerUnit.value
        progress.value += form.unitCount * (1 + housesPerUnit.value)
        continue
      }
      for (let j = 0; j < form.unitCount; j += 1) {
        let unitId: number | null = null
        try {
          const createdUnit = await createUnit({ buildingId, name: unitNameOf(name, 1 + j) })
          unitId = createdUnit.id
          unitOk += 1
        } catch (error) {
          unitFail += 1
          recordError(error)
        }
        progress.value += 1
        if (unitId === null) {
          houseSkipped += housesPerUnit.value
          progress.value += housesPerUnit.value
          continue
        }
        for (let floor = 1; floor <= form.floors; floor += 1) {
          for (let seq = form.roomStart; seq <= form.roomEnd; seq += 1) {
            try {
              await createHouse({
                unitId,
                houseNumber: houseNumberOf(floor, seq),
                floor,
                area: form.area,
                layout: form.layout || undefined,
                orientation: form.orientation || undefined
              })
              houseOk += 1
            } catch (error) {
              houseFail += 1
              recordError(error)
            }
            progress.value += 1
          }
        }
      }
    }
    const totalOk = buildingOk + unitOk + houseOk
    const summaryParts = [
      levelSummary('楼栋', buildingOk, buildingFail, 0),
      levelSummary('单元', unitOk, unitFail, unitSkipped),
      levelSummary('房屋', houseOk, houseFail, houseSkipped)
    ]
    if (totalOk === 0) {
      /* 全失败：保留输入供修正重试，不关闭对话框 */
      ElMessage.error(
        `整栋创建全部失败（${summaryParts.join('；')}）${firstError ? `：${firstError}` : ''}`
      )
      return
    }
    if (buildingFail + unitFail + houseFail + unitSkipped + houseSkipped > 0) {
      ElMessage.warning(
        `整栋创建完成：${summaryParts.join('；')}${firstError ? `：${firstError}` : ''}`
      )
    } else {
      ElMessage.success(`成功创建 ${buildingOk} 栋 ${unitOk} 单元 ${houseOk} 套房屋`)
    }
    visible.value = false
    emit('saved', form.communityId)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="整栋创建（楼栋 + 单元 + 房屋）"
    width="640px"
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <div class="batch-section">① 楼栋（前缀 + 序号连续生成）</div>
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
      <el-form-item label="楼栋前缀" prop="prefix">
        <el-input v-model="form.prefix" placeholder="如：1号楼（生成 1号楼1栋、1号楼2栋…）" maxlength="20" />
      </el-form-item>
      <el-form-item label="起始序号" prop="startNo">
        <el-input-number v-model="form.startNo" :min="1" :max="999" />
      </el-form-item>
      <el-form-item label="创建数量" prop="count">
        <el-input-number v-model="form.count" :min="1" :max="10" />
        <span class="batch-hint">1 ~ 10 栋</span>
      </el-form-item>
      <el-form-item label="总层数" prop="floors">
        <el-input-number v-model="form.floors" :min="1" :max="99" />
        <span class="batch-hint">全部楼栋统一，逐层生成房屋</span>
      </el-form-item>

      <div class="batch-section">② 单元（每栋结构相同）</div>
      <el-form-item label="单元前缀">
        <el-input
          v-model="form.unitPrefix"
          placeholder="默认逐栋取完整楼栋名，可修改；清空则纯序号"
          maxlength="20"
          @input="unitPrefixTouched = true"
        />
        <span class="batch-hint">默认逐栋取完整楼栋名（如「验收楼1栋」→ 验收楼1栋1单元）；修改后全楼共用；清空 → 1单元</span>
      </el-form-item>
      <el-form-item label="每栋单元数" prop="unitCount">
        <el-input-number v-model="form.unitCount" :min="0" :max="10" />
        <span class="batch-hint">0 ~ 10 个；填 0 = 仅建楼栋，不生成单元/房屋</span>
      </el-form-item>

      <template v-if="form.unitCount > 0">
        <div class="batch-section">③ 房屋（每单元每层统一，个别差异请在「房屋管理」后续调整）</div>
        <el-form-item label="每层房号">
          <div class="range-inputs">
            <el-input-number v-model="form.roomStart" :min="1" :max="99" />
            <span class="range-sep">至</span>
            <el-input-number v-model="form.roomEnd" :min="form.roomStart" :max="99" />
            <span class="range-sep">号</span>
          </div>
          <span class="batch-hint">门牌号 = 楼层 + 两位序号（1 层序号 1~4 → 101~104）</span>
        </el-form-item>
        <el-form-item label="默认面积" prop="area">
          <el-input-number v-model="form.area" :min="1" :max="10000" :precision="2" />
          <span class="batch-hint">㎡（必填，全部房屋统一，后端校验）</span>
        </el-form-item>
        <el-form-item label="户型">
          <el-input v-model="form.layout" placeholder="选填，如：2室1厅1卫" maxlength="20" style="width: 220px" />
        </el-form-item>
        <el-form-item label="朝向">
          <el-input v-model="form.orientation" placeholder="选填，如：南北" maxlength="10" style="width: 220px" />
        </el-form-item>
      </template>
    </el-form>

    <div class="batch-preview">
      <p class="batch-preview-title">
        将创建 {{ generatedBuildingNames.length }} 栋<template v-if="form.unitCount > 0">
          · {{ totalUnits }} 单元 · {{ totalHouses }} 套房屋</template>
        <span v-if="overLimit" class="batch-over-limit">
          房屋总量 {{ totalHouses }} 超过上限 {{ MAX_TOTAL_HOUSES }}，请缩小楼栋/单元/层数/房号范围
        </span>
        <span v-else-if="generatedBuildingNames.length === 0" class="batch-preview-empty">
          请先填写楼栋前缀
        </span>
      </p>
      <ul v-if="previewTree.length > 0" class="preview-tree">
        <li v-for="building in previewTree" :key="building.name" class="preview-building">
          <span class="preview-building-name">{{ building.name }}</span>
          <span v-if="building.units.length === 0" class="preview-unit-plain">仅楼栋，不生成单元</span>
          <span v-for="unit in building.units" :key="unit.name" class="batch-chip">
            {{ unit.name }} · {{ unit.houseCount }} 套（{{ unit.houseRange }}）
          </span>
        </li>
        <li v-if="hiddenBuildingCount > 0" class="preview-more">
          仅预览前 {{ PREVIEW_BUILDING_LIMIT }} 栋，其余 {{ hiddenBuildingCount }} 栋结构相同
        </li>
      </ul>
      <p v-if="duplicatedNames.length > 0" class="batch-warning">
        与现有楼栋重名：{{ duplicatedNames.join('、') }}，创建时可能被拒绝
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="overLimit" :loading="submitting" @click="handleSubmit">
        {{ submitting ? `创建中（${progress}/${totalOps}）` : '确定创建' }}
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
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.batch-preview-empty {
  color: var(--color-text-disabled);
}

.batch-over-limit {
  color: var(--color-warning);
}

.preview-tree {
  margin: 0;
  padding: 0;
  list-style: none;
}

.preview-building {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) 0;
}

.preview-building + .preview-building {
  border-top: 1px dashed var(--color-border);
}

.preview-building-name {
  flex-shrink: 0;
  min-width: 88px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.preview-unit-plain,
.preview-more {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.batch-chip {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

.batch-warning {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--color-warning);
}
</style>
