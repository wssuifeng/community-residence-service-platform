<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { batchGenerateStructure } from '@/api/community'
import type { ICommunity, IStructureBatchGenerateResult } from '@/types/modules/community'
import { houseStatusLabels } from '@/types/modules/community'
import type { HouseStatus } from '@/types/modules/community'
import { SKIP_SYNTAX_HINT, planHouseRows } from '@/views/admin/community/structureBatch'

/**
 * 结构批量生成（社区管理「批量建房」主入口，替代原「整栋创建」的前端循环创建）：
 * 一次请求建成 楼栋 → 单元 → 房屋 整条链，取代此前「建楼栋 → 选楼栋建单元 →
 * 选单元建房屋」三次来回与逐条 createXxx 循环（中断即半成品）。
 *
 * 两段式交互（约束：不允许未预览直接落库）：
 *  ① 预览：dryRun=true 只读演算，展示后端将生成的楼栋/单元/房号（各上限 20/20/50 条）
 *     与三类计数、跳过数、撞号去重数；表单任何改动都会使预览失效，须重新预览。
 *  ② 生成：dryRun=false 落库，结果面板给出三类计数与逐条失败明细，可直达该社区结构。
 *
 * 跳过项语法与结构生成预览口径来自共用模块 structureBatch.ts（与单元批量建房同源）。
 * 房屋总量在提交前按前端估算设上限，避免误操作把库写爆。
 */
const props = defineProps<{
  /** 目标社区（入口已锁定，本弹窗不提供社区选择：结构生成只能在某个社区下进行） */
  community: ICommunity | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带目标社区：父层刷新该社区结构、统计与列表概览 */
const emit = defineEmits<{
  saved: [communityId: number]
  /** 生成成功后跳转该社区结构（父层负责收起弹窗并聚焦社区） */
  viewStructure: [communityId: number]
}>()

/** 单次楼栋上限（后端约束：起止序号合计 60 栋） */
const MAX_BUILDINGS = 60
/** 前端房屋总量上限：dryRun 前先拦住明显的数量级误填 */
const MAX_ESTIMATED_HOUSES = 2400
/** 预览清单折叠阈值（后端各层级已限量返回，此处仅控制铺开观感） */
const PREVIEW_LIMIT = 30

type Phase = 'form' | 'done'

const formRef = ref<FormInstance>()
const phase = ref<Phase>('form')

const form = reactive({
  buildingNamePrefix: '',
  buildingStartNo: 1,
  buildingEndNo: 1,
  buildingNameSuffix: '号楼',
  unitCountPerBuilding: 2,
  unitNamePrefix: '',
  unitNameSuffix: '单元',
  floorsPerUnit: 6,
  housesPerFloor: 4,
  houseNumberPrefix: '',
  houseNumberWidth: 2,
  skipItems: '',
  area: undefined as number | undefined,
  roomCount: undefined as number | undefined,
  description: '',
  houseStatus: 'VACANT' as HouseStatus
})

/* ---------------- 客户端估算（即时反馈；权威预览以后端 dryRun 为准） ---------------- */

const buildingCount = computed(() =>
  Math.max(0, form.buildingEndNo - form.buildingStartNo + 1)
)
const buildingSampleNames = computed(() => {
  const names: string[] = []
  for (let no = form.buildingStartNo; no <= form.buildingEndNo; no += 1) {
    names.push(`${form.buildingNamePrefix}${no}${form.buildingNameSuffix}`)
  }
  return names
})

const unitSampleNames = computed(() => {
  const names: string[] = []
  for (let seq = 1; seq <= form.unitCountPerBuilding; seq += 1) {
    names.push(`${form.unitNamePrefix}${seq}${form.unitNameSuffix}`)
  }
  return names
})

/** 每个单元的房屋计划（与后端同口径：层 1..N × 每层 1..M，按跳过项收敛） */
const housePlan = computed(() =>
  planHouseRows({
    floorStart: 1,
    floorEnd: form.floorsPerUnit,
    seqStart: 1,
    seqEnd: form.housesPerFloor,
    prefix: form.houseNumberPrefix,
    width: form.houseNumberWidth,
    skip: form.skipItems
  })
)

const buildsHouses = computed(() => form.floorsPerUnit > 0 && form.housesPerFloor > 0)
const unitTotal = computed(() => buildingCount.value * form.unitCountPerBuilding)
const houseTotal = computed(() =>
  buildsHouses.value ? unitTotal.value * housePlan.value.kept.length : 0
)
const overBuildingLimit = computed(() => buildingCount.value > MAX_BUILDINGS)
const overHouseLimit = computed(() => houseTotal.value > MAX_ESTIMATED_HOUSES)
const houseNumberSample = computed(() => housePlan.value.kept.slice(0, PREVIEW_LIMIT))
const houseSkippedTotal = computed(
  () => housePlan.value.skipped.length + housePlan.value.deduped.length
)

const rules: FormRules = {
  buildingNamePrefix: [{ required: true, message: '请输入楼栋名前缀', trigger: 'blur' }],
  buildingEndNo: [
    {
      validator: (_rule, _value, callback) => {
        if (form.buildingEndNo < form.buildingStartNo) {
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

/* ---------------- 预览（dryRun）与生成 ---------------- */

const previewState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const previewResult = ref<IStructureBatchGenerateResult | null>(null)
const previewError = ref('')
const generating = ref(false)
const generated = ref<IStructureBatchGenerateResult | null>(null)
const previewExpanded = ref(false)

/** 请求体：dryRun 由调用方显式给出，避免把「预览」与「落库」两条路径写岔 */
function buildPayload(dryRun: boolean) {
  const trimmed = (value: string): string | undefined => value.trim() || undefined
  return {
    communityId: props.community?.id ?? 0,
    buildingNamePrefix: trimmed(form.buildingNamePrefix),
    buildingStartNo: form.buildingStartNo,
    buildingEndNo: form.buildingEndNo,
    buildingNameSuffix: trimmed(form.buildingNameSuffix),
    unitCountPerBuilding: form.unitCountPerBuilding,
    unitNamePrefix: trimmed(form.unitNamePrefix),
    unitNameSuffix: trimmed(form.unitNameSuffix),
    floorsPerUnit: form.floorsPerUnit,
    housesPerFloor: form.housesPerFloor,
    houseNumberPrefix: trimmed(form.houseNumberPrefix),
    houseNumberWidth: form.houseNumberWidth,
    skipItems: trimmed(form.skipItems),
    houseStatus: buildsHouses.value ? form.houseStatus : undefined,
    area: buildsHouses.value ? form.area : undefined,
    roomCount: buildsHouses.value ? form.roomCount : undefined,
    description: trimmed(form.description),
    dryRun
  }
}

/* 预览有效性：表单任一字段变化即失效（预览只是当次演算，不代表已改后的结果） */
const payloadKey = computed(() => JSON.stringify(buildPayload(true)))
const previewKey = ref('')
const previewFresh = computed(() => previewState.value === 'ready' && previewKey.value === payloadKey.value)

watch(payloadKey, () => {
  if (previewState.value === 'ready' && previewKey.value !== payloadKey.value) {
    previewState.value = 'idle'
  }
})

const previewBlocked = computed(() => overBuildingLimit.value || overHouseLimit.value)

async function handlePreview(): Promise<void> {
  if (!props.community) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (previewBlocked.value) {
    ElMessage.error(
      overBuildingLimit.value
        ? `单次最多 ${MAX_BUILDINGS} 栋楼，请缩小起止序号区间`
        : `预计生成 ${houseTotal.value} 套房屋，超出单次上限 ${MAX_ESTIMATED_HOUSES} 套，请缩小规模`
    )
    return
  }
  previewState.value = 'loading'
  previewError.value = ''
  previewResult.value = null
  const key = payloadKey.value
  try {
    const result = await batchGenerateStructure(buildPayload(true))
    previewResult.value = result
    previewKey.value = key
    previewState.value = 'ready'
  } catch (error) {
    previewError.value = error instanceof Error ? error.message : '预览失败'
    previewState.value = 'error'
  }
}

async function handleGenerate(): Promise<void> {
  if (!props.community || !previewFresh.value) return
  generating.value = true
  try {
    const result = await batchGenerateStructure(buildPayload(false))
    generated.value = result
    phase.value = 'done'
    emit('saved', props.community.id)
    if (result.failures.length === 0) {
      ElMessage.success(
        `已生成 ${result.buildingsCreated} 栋楼、${result.unitsCreated} 个单元、${result.housesCreated} 套房屋`
      )
    } else {
      ElMessage.warning('部分结构生成失败，明细见结果面板')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '结构生成失败')
  } finally {
    generating.value = false
  }
}

/** 继续生成：保留本次规则继续在同一社区追加（清空上次预览结论） */
function backToForm(): void {
  phase.value = 'form'
  generated.value = null
  previewState.value = 'idle'
  previewResult.value = null
  previewKey.value = ''
}

function viewStructure(): void {
  const communityId = props.community?.id
  if (communityId == null) return
  visible.value = false
  emit('viewStructure', communityId)
}

/* 每次打开回到表单态并按入口社区重置：默认楼栋前缀取社区名，减少输入 */
watch(visible, (value) => {
  if (!value) return
  phase.value = 'form'
  generated.value = null
  previewState.value = 'idle'
  previewResult.value = null
  previewError.value = ''
  previewKey.value = ''
  previewExpanded.value = false
  form.buildingNamePrefix = props.community?.name ?? ''
  form.buildingStartNo = 1
  form.buildingEndNo = 1
  form.buildingNameSuffix = '号楼'
  form.unitCountPerBuilding = 2
  form.unitNamePrefix = ''
  form.unitNameSuffix = '单元'
  form.floorsPerUnit = 6
  form.housesPerFloor = 4
  form.houseNumberPrefix = ''
  form.houseNumberWidth = 2
  form.skipItems = ''
  form.area = undefined
  form.roomCount = undefined
  form.description = ''
  form.houseStatus = 'VACANT'
  formRef.value?.clearValidate()
})

/* 起止序号防倒置：起点越过终点时把终点拉平 */
watch(
  () => form.buildingStartNo,
  (value) => {
    if (form.buildingEndNo < value) form.buildingEndNo = value
  }
)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="phase === 'done' ? '结构生成结果' : '批量建房（一次生成 楼栋 → 单元 → 房屋）'"
    width="760px"
    :close-on-click-modal="false"
  >
    <!-- ---------------- 结果面板 ---------------- -->
    <template v-if="phase === 'done' && generated">
      <p class="gen-intro">
        已在社区「{{ community?.name }}」生成下列结构。计数为本次实际写入量。
      </p>
      <div class="count-grid">
        <div class="count-cell">
          <b>{{ generated.buildingsCreated }}</b>
          <span>栋楼</span>
        </div>
        <div class="count-cell">
          <b>{{ generated.unitsCreated }}</b>
          <span>个单元</span>
        </div>
        <div class="count-cell">
          <b>{{ generated.housesCreated }}</b>
          <span>套房屋</span>
        </div>
        <div class="count-cell is-muted">
          <b>{{ generated.skippedCount }}</b>
          <span>套按跳过项排除</span>
        </div>
        <div class="count-cell is-muted">
          <b>{{ generated.dedupedCount }}</b>
          <span>套因房号已存在去重</span>
        </div>
      </div>

      <section v-if="generated.failures.length > 0" class="failure-block">
        <h4 class="block-title">未写入明细（{{ generated.failures.length }}）</h4>
        <ul class="failure-list">
          <li v-for="(item, index) in generated.failures" :key="`${item.name ?? index}`">
            <span class="failure-name">{{ item.level ? `${item.level} · ` : '' }}{{ item.name ?? '未知' }}</span>
            <span class="failure-reason">{{ item.reason }}</span>
          </li>
        </ul>
      </section>
    </template>

    <!-- ---------------- 表单 ---------------- -->
    <template v-else>
      <p class="gen-intro">
        目标社区：<b>{{ community?.name ?? '-' }}</b>
        ｜ 一次请求建成整条结构链，不再逐层创建。
      </p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <!-- 分区①：楼栋 -->
        <section class="gen-section">
          <header class="gen-section-head">
            <h4 class="block-title">楼栋</h4>
            <span class="gen-section-note">
              将生成 <b>{{ buildingCount }}</b> 栋
              <template v-if="buildingSampleNames.length > 0">
                （{{ buildingSampleNames.slice(0, 2).join('、')
                }}{{ buildingSampleNames.length > 2 ? ` 等` : '' }}）
              </template>
            </span>
          </header>
          <div class="field-grid">
            <el-form-item label="名称前缀" prop="buildingNamePrefix">
              <el-input v-model="form.buildingNamePrefix" placeholder="如 C10 / 1" maxlength="20" />
            </el-form-item>
            <el-form-item label="起始序号">
              <el-input-number v-model="form.buildingStartNo" :min="1" :max="99" style="width: 100%" />
            </el-form-item>
            <el-form-item label="结束序号" prop="buildingEndNo">
              <el-input-number
                v-model="form.buildingEndNo"
                :min="form.buildingStartNo"
                :max="99"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="名称后缀">
              <el-input v-model="form.buildingNameSuffix" placeholder="如 号楼 / 栋" maxlength="10" />
            </el-form-item>
          </div>
          <p v-if="overBuildingLimit" class="gen-warn">
            单次最多 {{ MAX_BUILDINGS }} 栋（当前 {{ buildingCount }} 栋），请缩小起止区间。
          </p>
        </section>

        <!-- 分区②：单元 -->
        <section class="gen-section">
          <header class="gen-section-head">
            <h4 class="block-title">单元</h4>
            <span class="gen-section-note">
              <template v-if="form.unitCountPerBuilding > 0">
                每栋 {{ form.unitCountPerBuilding }} 个，共 <b>{{ unitTotal }}</b> 个
                <template v-if="unitSampleNames.length > 0">
                  （{{ unitSampleNames.slice(0, 3).join('、')
                  }}{{ unitSampleNames.length > 3 ? ' 等' : '' }}）
                </template>
              </template>
              <template v-else>填 0 则只建楼栋，不建单元</template>
            </span>
          </header>
          <div class="field-grid">
            <el-form-item label="每栋单元数">
              <el-input-number v-model="form.unitCountPerBuilding" :min="0" :max="20" style="width: 100%" />
            </el-form-item>
            <el-form-item label="单元名前缀">
              <el-input v-model="form.unitNamePrefix" placeholder="选填，如 C10-1号楼" maxlength="20" />
            </el-form-item>
            <el-form-item label="单元名后缀">
              <el-input v-model="form.unitNameSuffix" placeholder="如 单元" maxlength="10" />
            </el-form-item>
          </div>
        </section>

        <!-- 分区③：房屋 -->
        <section class="gen-section">
          <header class="gen-section-head">
            <h4 class="block-title">房屋</h4>
            <span class="gen-section-note">
              <template v-if="buildsHouses">
                每单元 {{ housePlan.kept.length }} 套，共 <b>{{ houseTotal }}</b> 套
                <template v-if="housePlan.kept.length > 0">
                  （{{ housePlan.kept[0].houseNumber }} ~
                  {{ housePlan.kept[housePlan.kept.length - 1].houseNumber }}）
                </template>
                ；楼层数同时写入楼栋总层数
              </template>
              <template v-else>楼层数或每层户数为 0 时不生成房屋</template>
            </span>
          </header>
          <div class="field-grid">
            <el-form-item label="每单元楼层数">
              <el-input-number v-model="form.floorsPerUnit" :min="0" :max="99" style="width: 100%" />
            </el-form-item>
            <el-form-item label="每层户数">
              <el-input-number v-model="form.housesPerFloor" :min="0" :max="99" style="width: 100%" />
            </el-form-item>
            <el-form-item label="房号前缀">
              <el-input v-model="form.houseNumberPrefix" placeholder="选填，如 A-" maxlength="10" />
            </el-form-item>
            <el-form-item label="序号补零宽度">
              <el-input-number v-model="form.houseNumberWidth" :min="1" :max="4" style="width: 100%" />
            </el-form-item>
          </div>

          <el-form-item label="跳过房号">
            <el-input
              v-model="form.skipItems"
              :placeholder="`如：4 或 4:1，104 或 A-101（${SKIP_SYNTAX_HINT}）`"
              maxlength="200"
            />
            <div class="field-hints">
              <span>{{ SKIP_SYNTAX_HINT }}</span>
              <span v-if="housePlan.invalidTokens.length > 0" class="is-warning">
                忽略无法识别的片段：{{ housePlan.invalidTokens.join('、') }}
              </span>
              <span v-if="housePlan.unmatchedTokens.length > 0" class="is-warning">
                未命中当前范围（未生效）：{{ housePlan.unmatchedTokens.join('、') }}
              </span>
              <span v-if="houseSkippedTotal > 0">
                按跳过项与撞号去重，每单元实际生成 {{ housePlan.kept.length }} 套
              </span>
            </div>
          </el-form-item>

          <div v-if="houseNumberSample.length > 0" class="chip-block">
            <span class="chip-block-label">
              房号估算（{{ housePlan.kept.length }} 套，节选）
            </span>
            <div class="chip-flow">
              <span
                v-for="row in (previewExpanded ? housePlan.kept : houseNumberSample)"
                :key="row.houseNumber"
                class="chip"
              >
                {{ row.houseNumber }}
              </span>
            </div>
            <button
              v-if="housePlan.kept.length > PREVIEW_LIMIT"
              type="button"
              class="link-btn"
              @click="previewExpanded = !previewExpanded"
            >
              {{ previewExpanded ? '收起' : `展开全部 ${housePlan.kept.length} 套` }}
            </button>
          </div>
        </section>

        <!-- 分区④：房屋属性 -->
        <section class="gen-section is-last">
          <header class="gen-section-head">
            <h4 class="block-title">房屋属性（本批统一）</h4>
            <span class="gen-section-note">不生成房屋时无需填写</span>
          </header>
          <div class="field-grid">
            <el-form-item label="建筑面积（㎡）" prop="area">
              <el-input-number
                v-model="form.area"
                :min="1"
                :max="10000"
                :precision="2"
                :disabled="!buildsHouses"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="房间数">
              <el-input-number
                v-model="form.roomCount"
                :min="1"
                :max="20"
                :disabled="!buildsHouses"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="初始状态">
              <el-select v-model="form.houseStatus" :disabled="!buildsHouses" style="width: 100%">
                <el-option
                  v-for="(label, value) in houseStatusLabels"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="描述">
              <el-input
                v-model="form.description"
                placeholder="选填，本批房屋统一"
                maxlength="100"
                :disabled="!buildsHouses"
              />
            </el-form-item>
          </div>
          <p v-if="overHouseLimit" class="gen-warn">
            预计 {{ houseTotal }} 套，超出单次上限 {{ MAX_ESTIMATED_HOUSES }} 套，请先缩小规模再生成。
          </p>
        </section>
      </el-form>

      <!-- 预览结果（后端 dryRun 演算）：未预览或表单已改动时不可提交 -->
      <section class="preview-block">
        <header class="preview-head">
          <span class="preview-title">
            <template v-if="previewState === 'ready'">预览结果（未写入）</template>
            <template v-else-if="previewState === 'loading'">正在演算…</template>
            <template v-else-if="previewState === 'error'">预览失败</template>
            <template v-else>尚未预览</template>
          </span>
          <el-button size="small" type="primary" plain :loading="previewState === 'loading'" @click="handlePreview">
            预览
          </el-button>
        </header>

        <p v-if="previewState === 'idle'" class="preview-hint">
          点「预览」由后端演算将要生成的楼栋、单元与房号；确认无误后才能生成。
        </p>
        <p v-else-if="previewState === 'error'" class="preview-error">
          预览失败：{{ previewError }}
        </p>
        <template v-else-if="previewState === 'ready' && previewResult">
          <p class="preview-line">
            将生成 <b>{{ previewResult.buildingsCreated }}</b> 栋楼 ·
            <b>{{ previewResult.unitsCreated }}</b> 个单元 ·
            <b>{{ previewResult.housesCreated }}</b> 套房屋
            <template v-if="previewResult.skippedCount > 0">
              ，跳过 {{ previewResult.skippedCount }} 套
            </template>
            <template v-if="previewResult.dedupedCount > 0">
              ，房号已存在去重 {{ previewResult.dedupedCount }} 套
            </template>
          </p>
          <div v-if="previewResult.previewBuildings.length > 0" class="preview-group">
            <span class="preview-group-label">楼栋（{{ previewResult.previewBuildings.length }}）</span>
            <p class="preview-group-text">{{ previewResult.previewBuildings.join('、') }}</p>
          </div>
          <div v-if="previewResult.previewUnits.length > 0" class="preview-group">
            <span class="preview-group-label">单元（{{ previewResult.previewUnits.length }}）</span>
            <p class="preview-group-text">{{ previewResult.previewUnits.join('、') }}</p>
          </div>
          <div v-if="previewResult.previewHouses.length > 0" class="preview-group">
            <span class="preview-group-label">房号（节选 {{ previewResult.previewHouses.length }}）</span>
            <div class="chip-flow">
              <span v-for="number in previewResult.previewHouses" :key="number" class="chip is-preview">
                {{ number }}
              </span>
            </div>
          </div>
          <p v-if="previewResult.failures.length > 0" class="preview-warn">
            演算中已发现 {{ previewResult.failures.length }} 项将被拒绝：
            {{ previewResult.failures.map((item) => `${item.name ?? '-'}（${item.reason}）`).join('、') }}
          </p>
        </template>
      </section>
    </template>

    <!-- 页脚随两段式交互切换：结果态给「继续生成 / 查看结构」，表单态给「取消 / 确认生成」 -->
    <template #footer>
      <template v-if="phase === 'done' && generated">
        <el-button @click="backToForm">继续生成</el-button>
        <el-button type="primary" @click="viewStructure">查看该社区结构</el-button>
      </template>
      <template v-else>
        <el-button @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!previewFresh || previewBlocked"
          :loading="generating"
          @click="handleGenerate"
        >
          {{ previewFresh ? '确认生成' : '请先预览' }}
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.gen-intro {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.gen-intro b {
  color: var(--color-text-primary);
}

/* 分区以留白与字重分层，不套卡片：段间仅一条极浅分隔线 */
.gen-section {
  padding-bottom: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
}

.gen-section.is-last {
  padding-bottom: 0;
  margin-bottom: var(--spacing-sm);
  border-bottom: none;
}

.gen-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-sm);
}

.block-title {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.gen-section-note {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-align: right;
}

.gen-section-note b {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 var(--spacing-md);
}

/* 表单区块内的 el-form-item 自带底部间距已足够，去掉默认外边距叠加 */
.field-grid :deep(.el-form-item) {
  margin-bottom: var(--spacing-sm);
}

.gen-warn {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-warning);
}

.field-hints {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-normal);
  color: var(--color-text-secondary);
}

.field-hints .is-warning {
  color: var(--color-warning);
}

.chip-block {
  margin-top: var(--spacing-xs);
}

.chip-block-label {
  display: block;
  margin-bottom: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.chip-flow {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  max-height: 108px;
  overflow: auto;
}

.chip {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-subtle);
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
}

.chip.is-preview {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.link-btn {
  margin-top: var(--spacing-xs);
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  cursor: pointer;
}

/* 预览块：唯一的有底色容器，与表单分区在观感上分开 */
.preview-block {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.preview-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.preview-hint {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-secondary);
}

.preview-error {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.preview-line {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.preview-group {
  margin-top: var(--spacing-sm);
}

.preview-group-label {
  display: block;
  margin-bottom: 2px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.preview-group-text {
  margin: 0;
  max-height: 60px;
  overflow: auto;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
}

.preview-warn {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-warning);
}

/* ---------- 结果面板 ---------- */

.count-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--spacing-md);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
}

.count-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.count-cell b {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  color: var(--color-primary);
}

.count-cell span {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.count-cell.is-muted b {
  color: var(--color-text-secondary);
}

.failure-block {
  margin-top: var(--spacing-md);
}

.failure-list {
  display: flex;
  flex-direction: column;
  max-height: 200px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.failure-list li {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) 0;
}

.failure-list li + li {
  border-top: 1px solid var(--color-border);
}

.failure-name {
  flex-shrink: 0;
  max-width: 45%;
  overflow: hidden;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.failure-reason {
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

/* ---------- 响应式：窄屏字段改两列/单列 ---------- */

@media (max-width: 1099px) {
  .field-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .count-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 767px) {
  .field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .count-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
