<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createBuildingsBatch,
  createHousesBatch,
  createUnitsBatch,
  updateBuilding
} from '@/api/community'
import type {
  HouseStatus,
  IBatchFailure,
  IBuilding,
  IBuildingDTO,
  ICommunity,
  IHouseDTO
} from '@/types/modules/community'
import { houseStatusLabels } from '@/types/modules/community'
import { SKIP_SYNTAX_HINT, planHouseRows } from '@/views/admin/community/structureBatch'

/**
 * 楼栋对话框：建房带第 ① 级「建楼栋」＋「结构维护 → 编辑楼栋」两用。
 *
 * 两用仍保留原文件名（不新增 `.vue`）：`views/` 计数口径 98 个 `.vue` / 子组件 21 /
 * 功能视图 69 不因本次改动漂移（与批次 k 就地重写 UnitBatchDialog 同一处理）。
 *
 * 新建模式（building 为空）= 在指定社区**批量建楼栋**，而非过去的单个空楼栋：
 *   ① 楼栋名按「前缀 + 序号区间 + 后缀」生成（也可切「单栋自定义名称」建 A 座这类
 *      不带序号的名称，避免把任意名称硬套成「前缀+序号」）；
 *   ② 可同批建立每栋的**单元**（按序号）与**楼层房屋**（楼层区间 × 每层序号 + 跳过项），
 *      即"一次把新楼栋连单元房屋一起建好"，不必再逐栋走第 ② 级；
 *   ③ 提交链：POST /buildings/batch → 对成功创建的楼栋 POST /units/batch
 *      → 对成功创建的单元 POST /houses/batch，逐行结果交父层统一的批量结果弹窗。
 *   为什么不用结构链生成（/structures/batch-generate）：后者对**已存在同名楼栋整栋跳过**
 *   （连其单元与房屋一起不建），"社区里已有楼栋、再补几栋并带上单元房屋"走不通；
 *   本路径按行独立（部分成功语义），楼栋重名不拦截（后端无唯一约束，预览里点名提示）。
 * 编辑模式（building 非空）= 既有单栋表单（社区 / 名称 / 总层数 / 描述），行为不变。
 */

const props = defineProps<{
  /** 社区下拉选项（结构树已加载的社区列表） */
  communities: ICommunity[]
  /** 编辑目标：null = 新建（批量建楼栋） */
  building?: IBuilding | null
  /** 新建时的默认社区（树上下文预选） */
  defaultCommunityId?: number | null
  /** 当前社区已存在的楼栋名：重名不阻断创建，但要在预览里点名（避免误按重复序号续建） */
  existingNames?: string[]
}>()

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{
  /** saved 携带保存后的实体（编辑模式），供父层展开对应树节点 */
  saved: [entity: IBuilding]
  /** 创建模式的执行结果（部分成功语义）：交父层统一的批量结果弹窗呈现 */
  result: [
    payload: {
      successCount: number
      successText: string
      intro: string
      failures: IBatchFailure[]
    }
  ]
  /** 创建模式成功创建的楼栋 ID（父层刷新结构并按数量决定聚焦还是回到「全部」） */
  created: [buildingIds: number[]]
}>()

const isEdit = computed(() => Boolean(props.building))

/* ---------------- 交互上限（与后端逐层批量端点及结构链生成同口径） ---------------- */

/** 单次楼栋上限（对齐结构链生成与建楼栋带的规模预期；后端 /buildings/batch 上限 100） */
const MAX_BUILDINGS = 60
/** 单栋楼单元数上限（后端 /units/batch 单次 100，交互上与结构链生成一致取 20） */
const MAX_UNITS_PER_BUILDING = 20
/** 单次单元总数上限（与结构链生成口径一致） */
const MAX_TOTAL_UNITS = 600
/** 单单元房屋上限（后端 POST /houses/batch 单次上限 200 套） */
const MAX_HOUSES_PER_UNIT = 200
/** 单次房屋总数上限（与结构链生成口径一致，避免一次点爆库） */
const MAX_TOTAL_HOUSES = 2400
/** 失败明细回传上限：逐行铺开无意义，超出以计数说明 */
const MAX_FAILURES = 50
/** 名称预览铺开上限（楼栋名可能 60 个，全铺开会顶掉表单） */
const PREVIEW_LIMIT = 12

type CreateMode = 'range' | 'single'

const formRef = ref<FormInstance>()
const submitting = ref(false)

/* 编辑模式表单（既有单栋字段，与原实现一致） */
type BuildingForm = Omit<IBuildingDTO, 'communityId'> & { communityId?: number }

const editForm = reactive<BuildingForm>({
  communityId: undefined,
  name: '',
  floors: 1,
  description: ''
})

const editRules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  name: [{ required: true, message: '请输入楼栋名称', trigger: 'blur' }],
  floors: [{ required: true, message: '请输入总层数', trigger: 'blur' }]
}

/* 新建模式表单：楼栋规则 + 可选的单元/房屋规则 */
const form = reactive({
  communityId: undefined as number | undefined,
  mode: 'range' as CreateMode,
  /* 楼栋：按序号区间（range）/ 单栋自定义名称（single） */
  prefix: '',
  startNo: 1,
  endNo: 1,
  suffix: '号楼',
  name: '',
  floors: 1,
  description: '',
  /* 单元（开关关闭即只建楼栋） */
  withUnits: false,
  unitCount: 2,
  unitPrefix: '',
  unitSuffix: '单元',
  /* 房屋（仅在建立单元时生效） */
  withHouses: true,
  floorStart: 1,
  floorEnd: 6,
  roomStart: 1,
  roomEnd: 4,
  housePrefix: '',
  houseSuffix: '',
  skip: '',
  area: undefined as number | undefined,
  layout: '',
  houseStatus: 'VACANT' as HouseStatus
})

const rules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  name: [{ required: true, message: '请输入楼栋名称', trigger: 'blur' }],
  startNo: [
    {
      validator: (_rule, _value, callback) => {
        if (form.mode === 'range' && form.endNo < form.startNo) {
          callback(new Error('结束序号不能小于起始序号'))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ],
  area: [
    {
      validator: (_rule, _value, callback) => {
        if (buildsHouses.value && !form.area) {
          callback(new Error('生成房屋时必须填写建筑面积'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

/** 只读展示的社区名（结构与房屋只能建在某个社区下，故新建时不给社区切换） */
const communityName = computed(
  () => props.communities.find((item) => item.id === form.communityId)?.name ?? ''
)

/* ---------------- 生成清单（预览与提交共用同一来源，避免两处口径写岔） ---------------- */

/** 将创建的楼栋名清单（唯一来源：预览、单元命名、提交载荷都用它） */
const buildingNames = computed<string[]>(() => {
  if (form.mode === 'single') {
    const name = form.name.trim()
    return name ? [name] : []
  }
  const names: string[] = []
  for (let no = form.startNo; no <= form.endNo; no += 1) {
    names.push(`${form.prefix}${no}${form.suffix}`)
  }
  return names
})

/** 单个楼栋名超过 DTO 上限（前缀 20 + 序号 + 后缀 20 一般够用，防御性提示） */
const overNameLength = computed(() => buildingNames.value.some((name) => name.length > 50))

/** 与既有楼栋重名：后端不拦，但点名提示（序号区间续建时最易撞） */
const duplicateNames = computed(() => {
  const existing = new Set((props.existingNames ?? []).map((name) => name.trim()))
  return buildingNames.value.filter((name) => existing.has(name.trim()))
})

const buildsUnits = computed(() => form.withUnits && form.unitCount > 0)
/** 房屋仅在建立单元且规则能生成到房屋时才有落点（无宿主或空清单都不建房屋） */
const buildsHouses = computed(() => buildsUnits.value && form.withHouses && housesPerUnit.value > 0)
/** 开了房屋开关却生成 0 套：楼层/序号区间写反或跳过项吃光了，需点名否则会被当成已建房 */
const emptyHousePlan = computed(() => buildsUnits.value && form.withHouses && housesPerUnit.value === 0)

/** 单元名前缀留空时取楼栋名（预览与实际创建同一来源） */
function unitPrefixOf(buildingName: string): string {
  return form.unitPrefix.trim() || buildingName
}

function unitNamesOf(buildingName: string): string[] {
  const prefix = unitPrefixOf(buildingName)
  const names: string[] = []
  for (let no = 1; no <= form.unitCount; no += 1) {
    names.push(`${prefix}${no}${form.unitSuffix}`)
  }
  return names
}

/** 单元名样例取首栋（各栋仅前缀不同，同一套序号规则） */
const unitSampleNames = computed(() =>
  buildingNames.value.length > 0 ? unitNamesOf(buildingNames.value[0]) : []
)
const unitTotal = computed(() => (buildsUnits.value ? buildingNames.value.length * form.unitCount : 0))
const overUnitNameLength = computed(() => unitSampleNames.value.some((name) => name.length > 50))

/* 房号规划（共用模块）：跳过项 → 撞号去重 → 得到提交清单 */
const housePlan = computed(() =>
  planHouseRows({
    floorStart: form.floorStart,
    floorEnd: form.floorEnd,
    seqStart: form.roomStart,
    seqEnd: form.roomEnd,
    prefix: form.housePrefix,
    suffix: form.houseSuffix,
    skip: form.skip
  })
)

const housesPerUnit = computed(() => housePlan.value.kept.length)
const houseTotal = computed(() =>
  buildsHouses.value ? unitTotal.value * housesPerUnit.value : 0
)
const skippedTotal = computed(() => housePlan.value.skipped.length + housePlan.value.deduped.length)

/** 房号超过后端上限（house.house_number VARCHAR(20)）：逐行提交必被拒，提前拦下 */
const overHouseNumberLength = computed(
  () => housePlan.value.kept.some((row) => row.houseNumber.length > 20)
)

const overBuildingLimit = computed(() => buildingNames.value.length > MAX_BUILDINGS)
const overUnitTotal = computed(() => unitTotal.value > MAX_TOTAL_UNITS)
const overHousePerUnit = computed(() => buildsHouses.value && housesPerUnit.value > MAX_HOUSES_PER_UNIT)
const overHouseTotal = computed(() => houseTotal.value > MAX_TOTAL_HOUSES)
/** 无社区即无落点：楼栋只能建在某个社区下，缺它直接禁用提交（否则会在禁用输入框上弹校验错误） */
const noCommunity = computed(() => form.communityId === undefined)
const blocked = computed(
  () =>
    noCommunity.value ||
    buildingNames.value.length === 0 ||
    overBuildingLimit.value ||
    overUnitTotal.value ||
    overHousePerUnit.value ||
    overHouseTotal.value ||
    overNameLength.value ||
    /* 开了房屋开关却一套都生成不出来：静默只建单元会让「建单元与房屋」名不副实，直接拦住 */
    emptyHousePlan.value ||
    overHouseNumberLength.value
)
const limitWarning = computed(() => {
  if (overHouseNumberLength.value)
    return '房号超过 20 字符上限（如前后缀过长），请缩短「房号前缀 / 后缀」或调整楼层与序号位数'
  if (emptyHousePlan.value)
    return '房屋规则当前生成不出任何房屋（楼层或每层序号区间写反，或跳过项覆盖了全部房号），请修正规则或关闭「生成」开关'
  if (overBuildingLimit.value) return `单次最多建 ${MAX_BUILDINGS} 栋楼栋（当前 ${buildingNames.value.length} 栋），请缩小序号区间`
  if (overUnitTotal.value) return `单次最多建 ${MAX_TOTAL_UNITS} 个单元（当前 ${unitTotal.value} 个），请减少栋数或每栋单元数`
  if (overHousePerUnit.value) return `单个单元最多 ${MAX_HOUSES_PER_UNIT} 套房屋（当前 ${housesPerUnit.value} 套），请缩小楼层或每层户数`
  if (overHouseTotal.value) return `预计 ${houseTotal.value} 套房屋，超出单次上限 ${MAX_TOTAL_HOUSES} 套，请先缩小规模`
  return ''
})

/** 房屋层数与楼栋总层数对不上：不拦提交，但要点出来（否则楼栋层的字段会与房屋数据互相矛盾） */
const floorMismatch = computed(
  () => buildsHouses.value && form.floorEnd > form.floors
)
/* 打开时重置：编辑模式回填目标，新建模式给一套可直接落在当前社区的默认值 */
watch(visible, (value) => {
  if (!value) return
  if (props.building) {
    editForm.communityId = props.building.communityId
    editForm.name = props.building.name
    editForm.floors = props.building.floors
    editForm.description = props.building.description ?? ''
  } else {
    form.communityId = props.defaultCommunityId ?? props.communities[0]?.id
    form.mode = 'range'
    form.prefix = ''
    form.startNo = 1
    form.endNo = 1
    form.suffix = '号楼'
    form.name = ''
    /* 默认值取自结构链生成弹窗（6 层 × 每层 4 户 × 2 单元）：楼栋层数与房屋楼层同口径，
       否则一打开就出现「房屋 6 层 > 楼栋 1 层」的自相矛盾提示 */
    form.floors = 6
    form.description = ''
    form.withUnits = false
    form.unitCount = 2
    form.unitPrefix = ''
    form.unitSuffix = '单元'
    form.withHouses = true
    form.floorStart = 1
    form.floorEnd = 6
    form.roomStart = 1
    form.roomEnd = 4
    form.housePrefix = ''
    form.houseSuffix = ''
    form.skip = ''
    form.area = undefined
    form.layout = ''
    form.houseStatus = 'VACANT'
  }
  formRef.value?.clearValidate()
})

/** 房屋提交清单（同一套房号清单复用于每个新单元） */
function houseRows(unitId: number): IHouseDTO[] {
  return housePlan.value.kept.map((row) => ({
    unitId,
    houseNumber: row.houseNumber,
    floor: row.floor,
    area: form.area,
    layout: form.layout,
    status: form.houseStatus
  }))
}

/** 编辑模式：既有单栋更新（行为与原实现一致） */
async function submitEdit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const saved = await updateBuilding(props.building!.id, {
      ...editForm,
      communityId: editForm.communityId as number
    })
    ElMessage.success('楼栋已更新')
    visible.value = false
    emit('saved', saved)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 新建模式提交：楼栋批次 → 逐栋单元批次 → 逐单元房屋批次。
 * 失败按层级落清单（上层失败时其下层不再生成）；成功行返回的 ID 是下一层的唯一入口，
 * 因此必须逐行取 ID，不能按提交顺序臆测（部分成功时行号与成功行并不连续）。
 */
async function submitCreate(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  const communityId = form.communityId
  if (!valid || communityId === undefined) return
  if (floorMismatch.value) { ElMessage.error('建房楼层不能超过该楼栋总层数，请先修改总层数或生成范围'); return }
  if (blocked.value) {
    ElMessage.error(limitWarning.value || '请先修正表单')
    return
  }

  const names = [...buildingNames.value]
  const failures: IBatchFailure[] = []
  submitting.value = true
  try {
    const buildingRows: IBuildingDTO[] = names.map((name) => ({
      communityId,
      name,
      floors: form.floors,
      description: form.description.trim() || undefined
    }))
    const buildingResult = await createBuildingsBatch({ communityId, buildings: buildingRows })
    const createdBuildings: Array<{ id: number; name: string }> = []
    for (const row of buildingResult.rows) {
      const name = names[row.rowNo - 1] ?? `第 ${row.rowNo} 项`
      if (row.success && row.id != null) {
        createdBuildings.push({ id: row.id, name })
      } else {
        failures.push({ name, reason: row.reason ?? '创建失败', level: '楼栋' })
      }
    }

    const createdUnits: Array<{ id: number; name: string; buildingName: string }> = []
    if (buildsUnits.value) {
      for (const building of createdBuildings) {
        const unitNames = unitNamesOf(building.name)
        try {
          const unitResult = await createUnitsBatch({ buildingId: building.id, names: unitNames })
          for (const row of unitResult.rows) {
            const name = unitNames[row.rowNo - 1] ?? `第 ${row.rowNo} 项`
            if (row.success && row.id != null) {
              createdUnits.push({ id: row.id, name, buildingName: building.name })
            } else {
              failures.push({
                name: `${building.name} · ${name}`,
                reason: row.reason ?? '创建失败',
                level: '单元'
              })
            }
          }
        } catch (error) {
          failures.push({
            name: `${building.name} 的单元`,
            reason: error instanceof Error ? error.message : '批量建单元失败',
            level: '单元'
          })
        }
      }
    }

    let houseSuccess = 0
    if (buildsHouses.value) {
      for (const unit of createdUnits) {
        try {
          const houseResult = await createHousesBatch({
            unitId: unit.id,
            houses: houseRows(unit.id)
          })
          houseSuccess += houseResult.success
          for (const row of houseResult.rows) {
            if (row.success) continue
            failures.push({
              name: `${unit.buildingName} · ${unit.name} · 第 ${row.rowNo} 套`,
              reason: row.reason ?? '创建失败',
              level: '房屋'
            })
          }
        } catch (error) {
          failures.push({
            name: `${unit.buildingName} · ${unit.name} 的房屋`,
            reason: error instanceof Error ? error.message : '批量建房失败',
            level: '房屋'
          })
        }
      }
    }

    const overflow = failures.length - MAX_FAILURES
    const detail = failures.slice(0, MAX_FAILURES)
    if (overflow > 0) {
      detail.push({ name: '其余失败项', reason: `另有 ${overflow} 项未完成（明细已截断）` })
    }

    const scope = `已在「${communityName.value || '该社区'}」下`
    const buildingPart =
      form.mode === 'single'
        ? `创建楼栋「${names[0]}」`
        : `按序号区间建 ${names.length} 栋楼栋`
    emit('result', {
      successCount: buildsHouses.value
        ? houseSuccess
        : buildsUnits.value
          ? createdUnits.length
          : createdBuildings.length,
      successText: buildsHouses.value
        ? `套房屋已创建（涉及 ${createdBuildings.length} 栋楼 · ${createdUnits.length} 个单元）`
        : buildsUnits.value
          ? `个单元已创建（涉及 ${createdBuildings.length} 栋楼）`
          : `栋楼栋已创建`,
      intro: buildsHouses.value
        ? `${scope}${buildingPart}，每栋 ${form.unitCount} 个单元，并为新单元按 ${form.floorStart}~${form.floorEnd} 层 × 每层 ${form.roomStart}~${form.roomEnd} 号生成房屋。`
        : buildsUnits.value
          ? `${scope}${buildingPart}，每栋 ${form.unitCount} 个单元（未生成房屋）。`
          : `${scope}${buildingPart}（未生成单元与房屋）。`,
      failures: detail
    })
    emit(
      'created',
      createdBuildings.map((item) => item.id)
    )
    visible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量建楼栋失败')
  } finally {
    submitting.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (isEdit.value) {
    await submitEdit()
    return
  }
  await submitCreate()
}

const submitText = computed(() => {
  if (isEdit.value) return '确定'
  if (submitting.value) return '创建中…'
  const parts = [`${buildingNames.value.length} 栋`]
  if (buildsUnits.value) parts.push(`${unitTotal.value} 单元`)
  if (buildsHouses.value) parts.push(`${houseTotal.value} 房屋`)
  return `建 ${parts.join(' + ')}`
})

const buildingNameChips = computed(() => buildingNames.value.slice(0, PREVIEW_LIMIT))
const hiddenBuildingCount = computed(() =>
  Math.max(0, buildingNames.value.length - PREVIEW_LIMIT)
)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑楼栋' : '建楼栋'"
    width="680px"
    top="6vh"
    modal-class="building-batch-dialog"
    :close-on-click-modal="false"
  >
    <!-- 编辑模式：既有单栋表单 -->
    <el-form v-if="isEdit" ref="formRef" :model="editForm" :rules="editRules" label-width="100px">
      <el-form-item label="所属社区" prop="communityId">
        <el-select v-model="editForm.communityId" placeholder="请选择社区" style="width: 100%">
          <el-option
            v-for="item in communities"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="楼栋名称" prop="name">
        <el-input v-model="editForm.name" placeholder="如：1号楼" maxlength="50" />
      </el-form-item>
      <el-form-item label="总层数" prop="floors">
        <el-input-number v-model="editForm.floors" :min="1" />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="editForm.description"
          type="textarea"
          :rows="3"
          placeholder="请输入楼栋描述"
          maxlength="500"
        />
      </el-form-item>
    </el-form>

    <!-- 新建模式：批量建楼栋（可按需同批建立单元与楼层房屋） -->
    <el-form v-else ref="formRef" :model="form" :rules="rules" label-width="108px">
      <div class="batch-section">目标社区</div>
      <el-form-item label="所属社区" prop="communityId">
        <el-input :model-value="communityName || '尚未选择社区'" disabled />
      </el-form-item>
      <p v-if="!form.communityId" class="batch-warn">
        请先在左侧选中一个社区：楼栋只能建在某个社区下。
      </p>

      <div class="batch-section">
        楼栋
        <el-radio-group v-model="form.mode" size="small" class="batch-switch">
          <el-radio-button value="range">按序号批量</el-radio-button>
          <el-radio-button value="single">单栋自定义名称</el-radio-button>
        </el-radio-group>
      </div>

      <template v-if="form.mode === 'range'">
        <div class="batch-grid">
          <el-form-item label="名称前缀" label-width="88px">
            <el-input v-model="form.prefix" placeholder="选填，如 A / 1" maxlength="20" />
          </el-form-item>
          <el-form-item label="名称后缀" label-width="88px">
            <el-input v-model="form.suffix" placeholder="如 号楼 / 栋" maxlength="20" />
          </el-form-item>
        </div>
        <div class="batch-grid">
          <el-form-item label="起始序号" label-width="88px">
            <el-input-number v-model="form.startNo" :min="1" :max="9999" style="width: 100%" />
          </el-form-item>
          <el-form-item label="结束序号" label-width="88px">
            <el-input-number
              v-model="form.endNo"
              :min="form.startNo"
              :max="9999"
              style="width: 100%"
            />
          </el-form-item>
        </div>
      </template>
      <el-form-item v-else label="楼栋名称" prop="name">
        <el-input v-model="form.name" placeholder="如：A 座 / 1号楼" maxlength="50" />
      </el-form-item>

      <div class="batch-grid">
        <el-form-item label="总层数" prop="floors" label-width="88px">
          <el-input-number v-model="form.floors" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="描述" label-width="88px">
          <el-input v-model="form.description" placeholder="选填，本批统一" maxlength="200" />
        </el-form-item>
      </div>

      <div class="batch-section">
        同时建立单元与房屋
        <el-switch
          v-model="form.withUnits"
          class="batch-switch"
          active-text="同时建立"
          inactive-text="只建楼栋"
        />
      </div>
      <p v-if="!form.withUnits" class="batch-hint">
        当前只建楼栋（空楼栋）：打开上面的开关即可同批建立每栋的单元与楼层房屋。
      </p>
      <template v-else>
        <div class="batch-grid">
          <el-form-item label="每栋单元数" label-width="94px">
            <el-input-number
              v-model="form.unitCount"
              :min="1"
              :max="MAX_UNITS_PER_BUILDING"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="单元名后缀" label-width="94px">
            <el-input v-model="form.unitSuffix" placeholder="如 单元" maxlength="20" />
          </el-form-item>
        </div>
        <el-form-item label="单元名前缀">
          <el-input
            v-model="form.unitPrefix"
            :placeholder="`留空取楼栋名（生成 ${buildingNames[0] ?? '楼栋名'}1单元）`"
            maxlength="20"
          />
        </el-form-item>

        <div class="batch-section is-sub">
          房屋（楼层 × 每层户数）
          <el-switch
            v-model="form.withHouses"
            class="batch-switch"
            active-text="生成"
            inactive-text="不生成"
          />
        </div>
        <template v-if="form.withHouses">
          <div class="batch-grid">
            <el-form-item label="楼层 起" label-width="76px">
              <el-input-number v-model="form.floorStart" :min="1" :max="form.floors" style="width: 100%" />
            </el-form-item>
            <el-form-item label="楼层 止" label-width="76px">
              <el-input-number v-model="form.floorEnd" :min="1" :max="form.floors" style="width: 100%" />
            </el-form-item>
          </div>
          <div class="batch-grid">
            <el-form-item label="每层序号 起" label-width="94px">
              <el-input-number v-model="form.roomStart" :min="1" :max="99" style="width: 100%" />
            </el-form-item>
            <el-form-item label="每层序号 止" label-width="94px">
              <el-input-number v-model="form.roomEnd" :min="1" :max="99" style="width: 100%" />
            </el-form-item>
          </div>
          <div class="batch-grid">
            <el-form-item label="房号前缀" label-width="88px">
              <el-input v-model="form.housePrefix" placeholder="选填，如 A-" maxlength="10" />
            </el-form-item>
            <el-form-item label="房号后缀" label-width="88px">
              <el-input v-model="form.houseSuffix" placeholder="选填" maxlength="10" />
            </el-form-item>
          </div>
          <el-form-item label="跳过项">
            <el-input v-model="form.skip" :placeholder="SKIP_SYNTAX_HINT" maxlength="200" />
          </el-form-item>
          <div class="batch-grid">
            <el-form-item label="建筑面积" prop="area" label-width="88px">
              <el-input-number
                v-model="form.area"
                :min="1"
                :max="9999"
                :precision="2"
                placeholder="必填"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="户型" label-width="88px"><el-input v-model="form.layout" placeholder="如：三室一厅一卫" maxlength="50" /></el-form-item>
          </div>
          <el-form-item label="初始状态">
            <el-select v-model="form.houseStatus" style="width: 100%">
              <el-option
                v-for="(label, value) in houseStatusLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
        </template>
      </template>

      <p v-if="overNameLength" class="batch-warn">
        楼栋名最多 50 字符（当前最长 {{ Math.max(...buildingNames.map((name) => name.length)) }} 字符），请缩短名称前后缀。
      </p>
    </el-form>

    <!-- 预览：楼栋名 / 单元名 / 房号三段同源，提交的就是这里列出的 -->
    <div v-if="!isEdit" class="batch-preview">
      <p class="batch-preview-line">
        将创建 <b>{{ buildingNames.length }}</b> 栋楼栋
        <span v-if="buildingNames.length === 0" class="batch-preview-empty">
          （请先填写楼栋名称）
        </span>
      </p>
      <p v-if="buildsUnits" class="batch-preview-line">
        每栋 <b>{{ form.unitCount }}</b> 个单元，共 <b>{{ unitTotal }}</b> 个单元
      </p>
      <p v-if="buildsHouses" class="batch-preview-line">
        每个单元 <b>{{ housesPerUnit }}</b> 套，合计 <b>{{ houseTotal }}</b> 套房屋
        <span v-if="skippedTotal > 0">（规则跳过 / 撞号去重 {{ skippedTotal }} 套）</span>
      </p>
      <div v-if="buildingNames.length > 0" class="batch-preview-chips">
        <span v-for="name in buildingNameChips" :key="name" class="batch-chip">{{ name }}</span>
        <span v-if="hiddenBuildingCount > 0" class="batch-chip is-ghost">
          等共 {{ buildingNames.length }} 栋
        </span>
      </div>
      <p v-if="buildsUnits && unitSampleNames.length > 0" class="batch-preview-note">
        单元名（以第一栋为例）：{{ unitSampleNames.join('、') }}
        <template v-if="overUnitNameLength">｜单元名超过 50 字符，请缩短</template>
      </p>
      <p v-if="buildsHouses && housePlan.kept.length > 0" class="batch-preview-note">
        每个单元的房号：{{ housePlan.kept[0].houseNumber }} ~
        {{ housePlan.kept[housePlan.kept.length - 1].houseNumber }}
        <template v-if="housePlan.invalidTokens.length > 0">
          ｜无法识别的跳过项已忽略：{{ housePlan.invalidTokens.join('、') }}
        </template>
        <template v-if="housePlan.unmatchedTokens.length > 0">
          ｜未命中当前范围的跳过项（未生效）：{{ housePlan.unmatchedTokens.join('、') }}
        </template>
      </p>
      <p v-if="floorMismatch" class="batch-preview-note is-warning">
        房屋最高到 {{ form.floorEnd }} 层，而楼栋总层数为 {{ form.floors }}：必须先把总层数改到 {{ form.floorEnd }}，避免楼栋层数据与房屋互相矛盾。
      </p>
      <p v-if="duplicateNames.length > 0" class="batch-preview-note is-warning">
        该社区已存在同名楼栋：{{ duplicateNames.join('、') }}（后端不做楼栋重名校验，继续创建会得到同名楼栋）。
      </p>
    </div>

    <template #footer>
      <!-- 拦截原因放页脚：禁用按钮与原因同屏（表单可能已滚到下方，写在表单里会看不见） -->
      <span v-if="!isEdit && blocked && limitWarning" class="footer-block-reason">
        {{ limitWarning }}
      </span>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!isEdit && blocked"
        @click="handleSubmit"
      >
        {{ submitText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.batch-section {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: var(--spacing-md) 0 var(--spacing-sm);
  padding-left: 2px;
  border-top: 1px solid var(--color-border);
  padding-top: var(--spacing-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.batch-section:first-of-type {
  margin-top: 0;
  border-top: none;
  padding-top: 0;
}

/* 二级分区（房屋规则嵌在单元规则里）：左侧留出缩进，层级一眼可辨 */
.batch-section.is-sub {
  padding-left: var(--spacing-md);
}

.batch-switch {
  margin-left: auto;
}

/* 成对字段并排：楼层/序号/前后缀各占一行，避免长表单纵向失控 */
.batch-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 var(--spacing-md);
}

.batch-warn {
  margin: 0 0 var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-danger-soft);
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.batch-hint {
  margin: 0 0 var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.batch-preview {
  margin-top: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.batch-preview-line {
  margin: 0 0 2px;
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.batch-preview-line b {
  color: var(--color-text-primary);
}

.batch-preview-empty {
  color: var(--color-text-disabled);
}

.batch-preview-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.batch-chip {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

.batch-chip.is-ghost {
  background-color: transparent;
  color: var(--color-text-secondary);
}

.batch-preview-note {
  margin: var(--spacing-xs) 0 0;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.batch-preview-note.is-warning {
  color: var(--color-warning);
}

/* 页脚拦截原因：贴左显示，与右侧禁用按钮形成「为什么不能提交」的因果排布 */
.footer-block-reason {
  float: left;
  max-width: 62%;
  padding-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  text-align: left;
  color: var(--color-warning);
}
</style>

<!-- 弹窗内容由 Element Plus teleport 到 body，scoped 选择器够不到其内部，
     故用 .building-batch-dialog 命名空间写一条全局规则（只作用于本组件打开的弹窗）：
     展开「同时建立单元与房屋」后表单可达 1045px，不封顶会把预览与提交按钮推出视口 -->
<style>
.building-batch-dialog .el-dialog__body {
  max-height: calc(88vh - 150px);
  overflow-y: auto;
}
</style>
