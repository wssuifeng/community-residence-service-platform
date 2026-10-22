<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createHousesBatch, createUnitsBatch } from '@/common/community/api/community'
import type { IBatchFailure, HouseStatus, IHouseDTO } from '@/common/community/types/community'
import { manualHouseStatusLabels } from '@/common/community/types/community'
import { SKIP_SYNTAX_HINT, planHouseRows } from '@/admin/community/utils/structureBatch'

/**
 * 批量建单元与房屋（小区管理「建房」区第 ② 级：**指定小区 + 楼栋**）。
 *
 * 一次提交做完两件事：
 *  ① 按「名称前缀 + 起始序号 + 数量」批量建单元（`POST /units/batch`，逐行独立）；
 *  ② 用①返回的新单元 ID，为每个新单元生成「楼层区间 × 每层户数」的房屋
 *     （`POST /houses/batch`，单次上限 200 套/单元）。
 *
 * 为什么必须与结构链生成弹窗分开：结构链生成对**已存在同名楼栋整栋跳过**（后端语义），
 * 所以「给已有楼栋补单元与房屋」走不通那条路，只能在既有楼栋下继续建——本弹窗即该入口。
 *
 * 房号规则（楼层 + 补零序号 + 前后缀）与跳过项语法全部来自共用模块 structureBatch.ts，
 * 与结构链生成、单元内建房三处同源，避免同一套规则出现三份实现。
 * 两个提交都是**部分成功语义**：逐行结果（成功 ID / 失败原因）交父层统一的结果弹窗呈现，
 * 不在本弹窗内自造第二套结果样式。
 */

interface BuildingOption {
  floors: number
  id: number
  name: string
  /** 已有单元数（选项上给出规模感，避免选错楼栋） */
  units: number
  /** 已有房屋数 */
  houses: number | null
}

const props = defineProps<{
  /** 当前小区名（只读展示：结构只能在某个小区下建，故不提供小区切换） */
  communityName?: string
  /** 可选楼栋（父层已加载的结构，含单元/房屋规模） */
  buildingOptions: BuildingOption[]
  /** 打开时预选的楼栋（当前筛选范围） */
  initialBuildingId?: number | ''
}>()

const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  /** saved 携带目标楼栋 ID：父层刷新结构/统计并聚焦该楼栋 */
  saved: [buildingId: number]
  /** 执行结果（部分成功语义）：交父层统一的批量结果弹窗呈现 */
  result: [
    payload: {
      successCount: number
      successText: string
      intro: string
      failures: IBatchFailure[]
    }
  ]
}>()

/** 单次单元上限（与后端 names ≤ 100 对齐，此处取更保守的交互上限） */
const MAX_UNITS = 20
/** 单单元房屋上限（后端 POST /houses/batch 单次上限 200 套） */
const MAX_HOUSES_PER_UNIT = 200
/** 失败明细回传上限：逐行铺开无意义，超出以计数说明 */
const MAX_FAILURES = 50

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  buildingId: undefined as number | undefined,
  /* 单元生成规则 */
  unitPrefix: '',
  unitStartNo: 1,
  unitCount: 2,
  /* 房屋生成规则（可整体关闭：只建单元） */
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
  orientation: '',
  houseStatus: 'VACANT' as HouseStatus
})

const selectedBuilding = computed(
  () => props.buildingOptions.find((item) => item.id === form.buildingId) ?? null
)

/** 单元名前缀留空时取楼栋名（占位提示已说明），保证预览与实际创建同一来源 */
const effectiveUnitPrefix = computed(
  () => form.unitPrefix.trim() || selectedBuilding.value?.name || ''
)

/** 将创建的单元名（前缀 + 序号 + 单元），预览与提交共用同一来源 */
const unitNames = computed<string[]>(() => {
  const prefix = effectiveUnitPrefix.value
  if (!prefix) return []
  const names: string[] = []
  for (let i = 0; i < form.unitCount; i += 1) {
    names.push(`${prefix}${form.unitStartNo + i}单元`)
  }
  return names
})

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
const buildsHouses = computed(() => form.withHouses && housesPerUnit.value > 0)
const totalHouses = computed(() => (buildsHouses.value ? unitNames.value.length * housesPerUnit.value : 0))
const skippedTotal = computed(
  () => housePlan.value.skipped.length + housePlan.value.deduped.length
)

const overUnitLimit = computed(() => unitNames.value.length > MAX_UNITS)
const overHousePerUnit = computed(() => housesPerUnit.value > MAX_HOUSES_PER_UNIT)
const blocked = computed(() => overUnitLimit.value || (form.withHouses && overHousePerUnit.value))

const rules: FormRules = {
  buildingId: [{ required: true, message: '请选择所属楼栋', trigger: 'change' }],
  area: [
    {
      validator: (_rule, _value, callback) => {
        if (form.withHouses && !form.area) {
          callback(new Error('生成房屋时必须填写建筑面积'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

watch(visible, (value) => {
  if (!value) return
  form.buildingId =
    props.initialBuildingId !== '' && props.initialBuildingId !== undefined
      ? Number(props.initialBuildingId)
      : props.buildingOptions[0]?.id
  form.unitPrefix = ''
  form.unitStartNo = 1
  form.unitCount = 2
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
  form.orientation = ''
  form.houseStatus = 'VACANT'
  formRef.value?.clearValidate()
})

/** 房屋提交清单（同一房号清单复用于每个新单元） */
function houseRows(unitId: number): IHouseDTO[] {
  return housePlan.value.kept.map((row) => ({
    unitId,
    houseNumber: row.houseNumber,
    floor: row.floor,
    area: form.area,
    layout: form.layout.trim() || undefined,
    orientation: form.orientation.trim() || undefined,
    status: form.houseStatus
  }))
}

/**
 * 提交：先批量建单元，再对**成功创建的单元**逐个批量建房屋。
 * 单元创建失败的行直接落失败清单（其房屋一并不建）；房屋批次整体失败按整批计入失败。
 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  const buildingId = form.buildingId
  if (!valid || buildingId === undefined) return
  if (buildsHouses.value && (!selectedBuilding.value || form.floorEnd > selectedBuilding.value.floors)) { ElMessage.error("建房楼层不能超过所选楼栋的总层数"); return }
  if (unitNames.value.length === 0) {
    ElMessage.warning('请先确定单元名前缀')
    return
  }
  if (blocked.value) {
    ElMessage.error(
      overUnitLimit.value
        ? `单次最多建 ${MAX_UNITS} 个单元，请减少数量`
        : `单个单元最多 ${MAX_HOUSES_PER_UNIT} 套房屋，请缩小楼层或每层户数`
    )
    return
  }

  const buildingName = selectedBuilding.value?.name ?? '该楼栋'
  const names = [...unitNames.value]
  const failures: IBatchFailure[] = []
  submitting.value = true
  try {
    const unitResult = await createUnitsBatch({ buildingId, names })
    const createdUnits: Array<{ id: number; name: string }> = []
    for (const row of unitResult.rows) {
      const name = names[row.rowNo - 1] ?? `第 ${row.rowNo} 项`
      if (row.success && row.id != null) {
        createdUnits.push({ id: row.id, name })
      } else {
        failures.push({ name, reason: row.reason ?? '创建失败', level: '单元' })
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
              name: `${unit.name} · 第 ${row.rowNo} 套`,
              reason: row.reason ?? '创建失败',
              level: '房屋'
            })
          }
        } catch (error) {
          failures.push({
            name: `${unit.name} 的房屋`,
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
    emit('result', {
      successCount: buildsHouses.value ? houseSuccess : createdUnits.length,
      successText: buildsHouses.value
        ? `套房屋已创建（涉及 ${createdUnits.length} 个新单元）`
        : `个单元已创建`,
      intro: buildsHouses.value
        ? `已在「${buildingName}」下建 ${names.length} 个单元，并为新单元按 ${form.floorStart}~${form.floorEnd} 层 × 每层 ${form.roomStart}~${form.roomEnd} 号生成房屋。`
        : `已在「${buildingName}」下按序号区间建 ${names.length} 个单元（未生成房屋）。`,
      failures: detail
    })
    emit('saved', buildingId)
    visible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量建单元失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="建单元与房屋" width="640px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="108px">
      <div class="batch-section">目标楼栋</div>
      <el-form-item label="所属小区">
        <el-input :model-value="communityName || '-'" disabled />
      </el-form-item>
      <el-form-item label="楼栋" prop="buildingId">
        <el-select v-model="form.buildingId" style="width: 100%" placeholder="请选择楼栋">
          <el-option
            v-for="item in buildingOptions"
            :key="item.id"
            :label="`${item.name}（${item.units} 个单元 · ${item.houses === null ? '房屋按需加载' : item.houses + ' 套'}）`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <p v-if="buildingOptions.length === 0" class="batch-warn">
        该小区还没有楼栋：请先用「建楼栋」或「一键生成结构链」建好楼栋，再来补单元与房屋。
      </p>

      <div class="batch-section">单元生成规则</div>
      <el-form-item label="名称前缀">
        <el-input
          v-model="form.unitPrefix"
          :placeholder="`留空取楼栋名（生成 ${effectiveUnitPrefix || '楼栋名'}1单元）`"
          maxlength="20"
        />
      </el-form-item>
      <div class="batch-grid">
        <el-form-item label="起始序号" label-width="88px">
          <el-input-number v-model="form.unitStartNo" :min="1" :max="999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="数量" label-width="64px">
          <el-input-number
            v-model="form.unitCount"
            :min="1"
            :max="MAX_UNITS"
            style="width: 100%"
          />
        </el-form-item>
      </div>

      <div class="batch-section">
        房屋生成规则
        <el-switch
          v-model="form.withHouses"
          class="batch-switch"
          active-text="同时生成"
          inactive-text="只建单元"
        />
      </div>
      <template v-if="form.withHouses">
        <div class="batch-grid">
          <el-form-item label="楼层 起" label-width="76px">
            <el-input-number v-model="form.floorStart" :min="1" :max="selectedBuilding?.floors" style="width: 100%" />
          </el-form-item>
          <el-form-item label="止" label-width="40px">
            <el-input-number v-model="form.floorEnd" :min="1" :max="selectedBuilding?.floors" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="batch-grid">
          <el-form-item label="每层序号 起" label-width="94px">
            <el-input-number v-model="form.roomStart" :min="1" :max="99" style="width: 100%" />
          </el-form-item>
          <el-form-item label="止" label-width="40px">
            <el-input-number v-model="form.roomEnd" :min="1" :max="99" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="batch-grid">
          <el-form-item label="房号前缀" label-width="88px">
            <el-input v-model="form.housePrefix" placeholder="选填，如 A-" maxlength="10" />
          </el-form-item>
          <el-form-item label="后缀" label-width="64px">
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
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="户型" label-width="64px">
            <el-input v-model="form.layout" placeholder="如 3室2厅" maxlength="20" />
          </el-form-item>
        </div>
        <div class="batch-grid">
          <el-form-item label="朝向" label-width="88px">
            <el-input v-model="form.orientation" placeholder="如 SOUTH" maxlength="10" />
          </el-form-item>
          <el-form-item label="初始状态" label-width="64px">
            <el-select v-model="form.houseStatus" style="width: 100%">
              <el-option
                v-for="(label, value) in manualHouseStatusLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
        </div>
      </template>

      <p v-if="overUnitLimit" class="batch-warn">
        单次最多建 {{ MAX_UNITS }} 个单元（当前 {{ unitNames.length }} 个），请减少数量。
      </p>
      <p v-else-if="form.withHouses && overHousePerUnit" class="batch-warn">
        单个单元最多 {{ MAX_HOUSES_PER_UNIT }} 套房屋（当前 {{ housesPerUnit }} 套），请缩小楼层或每层户数。
      </p>
    </el-form>

    <div class="batch-preview">
      <p class="batch-preview-title">
        将创建 <b>{{ unitNames.length }}</b> 个单元<template v-if="buildsHouses">
          ，每个单元 <b>{{ housesPerUnit }}</b> 套，合计 <b>{{ totalHouses }}</b> 套房屋
          <template v-if="skippedTotal > 0">（规则跳过/撞号去重 {{ skippedTotal }} 套）</template>
        </template>
        <span v-if="unitNames.length === 0" class="batch-preview-empty">请先填写名称前缀或选择楼栋</span>
      </p>
      <div v-if="unitNames.length > 0" class="batch-preview-chips">
        <span v-for="name in unitNames" :key="name" class="batch-chip">{{ name }}</span>
      </div>
      <p v-if="buildsHouses && housePlan.kept.length > 0" class="batch-preview-note">
        每个单元的房号：{{ housePlan.kept[0].houseNumber }} ~
        {{ housePlan.kept[housePlan.kept.length - 1].houseNumber }}
        <template v-if="housePlan.invalidTokens.length > 0">
          ｜无法识别的跳过项已忽略：{{ housePlan.invalidTokens.join('、') }}
        </template>
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="blocked || buildingOptions.length === 0"
        @click="handleSubmit"
      >
        {{ submitting ? '创建中…' : buildsHouses ? `建 ${unitNames.length} 单元 + ${totalHouses} 房屋` : `建 ${unitNames.length} 个单元` }}
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

.batch-switch {
  margin-left: auto;
}

/* 成对数值字段并排：楼层/序号/前后缀各占一行，避免长表单纵向失控 */
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

.batch-preview {
  margin-top: var(--spacing-md);
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

.batch-preview-title b {
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

.batch-preview-note {
  margin: var(--spacing-xs) 0 0;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
