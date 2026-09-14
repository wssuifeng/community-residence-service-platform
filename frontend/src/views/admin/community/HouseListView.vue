<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createHouse,
  deleteHouse,
  getBuildingList,
  getCommunityList,
  getHouseList,
  getHouseStatusHistory,
  getUnit,
  getUnitList,
  updateHouse,
  updateHouseStatus
} from '@/api/community'
import { getHouseResidentList, moveOutResidenceRelation } from '@/api/resident'
import type {
  IBuilding,
  ICommunity,
  IHouse,
  IHouseDTO,
  IHouseStatusHistory,
  IHouseQuery,
  IUnit,
  HouseStatus
} from '@/types/modules/community'
import { houseStatusLabels } from '@/types/modules/community'
import { relationTypeLabels } from '@/types/modules/resident'
import type { IHouseResident, RelationType } from '@/types/modules/resident'
import { formatDateTime, formatDate, todayISO } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'
import HouseBatchDialog from '@/views/admin/community/HouseBatchDialog.vue'

/** 房屋管理：社区→楼栋→单元三级联动筛选 + CRUD + 状态变更 + 状态历史时间线 */
const houses = ref<IHouse[]>([])
const communities = ref<ICommunity[]>([])
const buildings = ref<IBuilding[]>([])
const units = ref<IUnit[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 筛选：社区 → 楼栋 → 单元联动 + 状态 */
const communityFilter = ref<number | ''>('')
const buildingFilter = ref<number | ''>('')
const unitFilter = ref<number | ''>('')
const statusFilter = ref<HouseStatus | ''>('')

function statusTagType(status: HouseStatus): 'info' | 'completed' | 'pending' | 'processing' {
  if (status === 'OCCUPIED') return 'completed'
  if (status === 'RESERVED') return 'pending'
  if (status === 'MAINTENANCE') return 'processing'
  return 'info'
}

/** 户型展示：后端 layout 为字符串（如「2室1厅1卫」） */
function layoutText(row: IHouse): string {
  return row.layout ?? '-'
}

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 200 })
    communities.value = result.records
  } catch {
    communities.value = []
  }
}

async function loadBuildings(): Promise<void> {
  buildings.value = []
  buildingFilter.value = ''
  if (communityFilter.value === '') return
  try {
    const result = await getBuildingList(communityFilter.value, { page: 1, size: 200 })
    buildings.value = result.records
  } catch {
    buildings.value = []
  }
}

async function loadUnits(): Promise<void> {
  units.value = []
  unitFilter.value = ''
  if (buildingFilter.value === '') return
  try {
    const result = await getUnitList(buildingFilter.value, { page: 1, size: 200 })
    units.value = result.records
  } catch {
    units.value = []
  }
}

async function load(): Promise<void> {
  if (unitFilter.value === '') {
    houses.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const params: IHouseQuery = {
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined
    }
    const result = await getHouseList(unitFilter.value, params)
    /* 后端 HouseVO 无楼栋/单元名称字段（unitName 可空），以当前筛选上下文补全供表格展示与编辑回显 */
    const contextBuildingName = buildings.value.find((item) => item.id === buildingFilter.value)?.name
    const contextUnitName = units.value.find((item) => item.id === unitFilter.value)?.name
    houses.value = result.records.map((record) => ({
      ...record,
      buildingName: contextBuildingName ?? record.buildingName,
      unitName: contextUnitName ?? record.unitName
    }))
    total.value = result.total
  } catch {
    houses.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleCommunityChange(): void {
  page.value = 1
  loadBuildings().then(() => loadUnits()).then(load)
}

function handleBuildingChange(): void {
  page.value = 1
  loadUnits().then(load)
}

function handleUnitChange(): void {
  page.value = 1
  load()
}

function handleStatusFilterChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  communityFilter.value = ''
  buildingFilter.value = ''
  unitFilter.value = ''
  statusFilter.value = ''
  buildings.value = []
  units.value = []
  page.value = 1
  load()
}

/* ---------------------------------- 新建/编辑 ---------------------------------- */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

/** 表单态 DTO：单元外键/面积未填写时为 undefined（提交前经 rules 校验收敛） */
type HouseForm = Omit<IHouseDTO, 'unitId' | 'area'> & { unitId?: number; area?: number }

const form = reactive<HouseForm>({
  unitId: undefined,
  houseNumber: '',
  floor: 1,
  area: undefined,
  roomCount: undefined,
  layout: '',
  orientation: '',
  description: ''
})

/** 对话框内独立的三级联动（社区→楼栋→单元，与筛选状态隔离） */
const formCommunityId = ref<number | ''>('')
const formBuildingId = ref<number | ''>('')
const formBuildings = ref<IBuilding[]>([])
const formUnits = ref<IUnit[]>([])

const rules: FormRules = {
  unitId: [{ required: true, message: '请选择所属单元', trigger: 'change' }],
  houseNumber: [{ required: true, message: '请输入门牌号', trigger: 'blur' }],
  floor: [{ required: true, message: '请输入所在楼层', trigger: 'blur' }],
  area: [{ required: true, message: '请输入建筑面积', trigger: 'blur' }]
}

async function handleFormCommunityChange(): Promise<void> {
  formBuildingId.value = ''
  form.unitId = undefined
  formBuildings.value = []
  formUnits.value = []
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
  if (formBuildingId.value === '') return
  try {
    const result = await getUnitList(formBuildingId.value, { page: 1, size: 200 })
    formUnits.value = result.records
  } catch {
    formUnits.value = []
  }
}

function openCreate(): void {
  editingId.value = null
  form.unitId = undefined
  form.houseNumber = ''
  form.floor = 1
  form.area = undefined
  form.roomCount = undefined
  form.layout = ''
  form.orientation = ''
  form.description = ''
  // 默认带入当前筛选的三级选择
  formCommunityId.value = communityFilter.value
  formBuildingId.value = buildingFilter.value
  handleFormCommunityChange().then(() => {
    if (formBuildingId.value === '') return Promise.resolve()
    return handleFormBuildingChange()
  }).then(() => {
    form.unitId = unitFilter.value === '' ? undefined : unitFilter.value
  })
  dialogVisible.value = true
}

function openEdit(row: IHouse): void {
  editingId.value = row.id
  form.unitId = row.unitId
  form.houseNumber = row.houseNumber
  form.floor = row.floor
  form.area = row.area ?? undefined
  form.roomCount = row.roomCount ?? undefined
  form.layout = row.layout ?? ''
  form.orientation = row.orientation ?? ''
  form.description = row.description ?? ''
  // 回显联动：定位所在楼栋/社区（IHouse 无 unit 溯源字段，从当前筛选选项兜底）
  const belongedBuilding = buildings.value.find(
    (item) => item.name === row.buildingName
  )
  if (belongedBuilding) {
    formCommunityId.value = belongedBuilding.communityId
    formBuildingId.value = belongedBuilding.id
    formBuildings.value = buildings.value.filter(
      (item) => item.communityId === belongedBuilding.communityId
    )
    handleFormBuildingChange()
  } else {
    formCommunityId.value = ''
    formBuildingId.value = ''
    formBuildings.value = []
    formUnits.value = []
  }
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    const payload: IHouseDTO = {
      ...form,
      unitId: form.unitId as number,
      area: form.area as number
    }
    if (editingId.value === null) {
      await createHouse(payload)
      ElMessage.success('房屋创建成功')
    } else {
      await updateHouse(editingId.value, payload)
      ElMessage.success('房屋已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------------------------- 状态变更 ---------------------------------- */

const statusDialogVisible = ref(false)
const statusFormRef = ref<FormInstance>()
const statusTarget = ref<IHouse | null>(null)
const statusForm = reactive<{ status: HouseStatus; remark: string }>({
  status: 'VACANT',
  remark: ''
})

const statusRules: FormRules = {
  status: [{ required: true, message: '请选择房屋状态', trigger: 'change' }]
}

function openStatusChange(row: IHouse): void {
  statusTarget.value = row
  statusForm.status = row.status
  statusForm.remark = ''
  statusDialogVisible.value = true
}

async function handleStatusSubmit(): Promise<void> {
  const valid = await statusFormRef.value?.validate().catch(() => false)
  if (!valid || !statusTarget.value) return
  try {
    await updateHouseStatus(statusTarget.value.id, {
      status: statusForm.status,
      remark: statusForm.remark || undefined
    })
    ElMessage.success('房屋状态已更新')
    statusDialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态变更失败')
  }
}

/* ---------------------------------- 状态历史（时间线抽屉） ---------------------------------- */

const historyVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<IHouseStatusHistory[]>([])
const historyHouse = ref<IHouse | null>(null)

async function openHistory(row: IHouse): Promise<void> {
  historyHouse.value = row
  historyVisible.value = true
  historyLoading.value = true
  try {
    const result = await getHouseStatusHistory(row.id, { page: 1, size: 50 })
    historyList.value = result.records
  } catch {
    historyList.value = []
  } finally {
    historyLoading.value = false
  }
}

/* ---------------------------------- 删除（引用保护由后端报错） ---------------------------------- */

async function handleDelete(row: IHouse): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除房屋「${row.houseNumber}」？若该房屋存在居住/租住关系，删除将被拒绝。`,
      '删除房屋',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteHouse(row.id)
    ElMessage.success('房屋已删除')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* ---------------------------------- 批量生成（A7 步骤 7.3） ---------------------------------- */

const batchVisible = ref(false)

function openBatchCreate(): void {
  batchVisible.value = true
}

/** 批量生成完成：以单元详情回溯三级上下文定位筛选并刷新列表 */
async function handleBatchSaved(unitId: number): Promise<void> {
  try {
    const unit = await getUnit(unitId)
    communityFilter.value = unit.communityId
    await loadBuildings()
    buildingFilter.value = unit.buildingId
    await loadUnits()
    unitFilter.value = unitId
  } catch {
    /* 上下文回溯失败时仅刷新当前列表 */
  }
  page.value = 1
  load()
}

/* ---------------------------------- 多选删除（A7 步骤 7.3） ---------------------------------- */

const selectedRows = ref<IHouse[]>([])

function handleSelectionChange(rows: IHouse[]): void {
  selectedRows.value = rows
}

/** 多选删除：二次确认一次，逐条调既有 delete，逐个失败不中断并汇报结果 */
async function handleBatchDelete(): Promise<void> {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(
      `已选择 ${rows.length} 套房屋，将逐个删除；若房屋存在居住/租住关系，该房屋删除将被拒绝。确定继续？`,
      '批量删除房屋',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  let success = 0
  let fail = 0
  let firstError = ''
  for (const row of rows) {
    try {
      await deleteHouse(row.id)
      success += 1
    } catch (error) {
      fail += 1
      if (!firstError) {
        firstError = error instanceof Error ? error.message : '删除失败'
      }
    }
  }
  if (fail === 0) {
    ElMessage.success(`成功删除 ${success} 套房屋`)
  } else {
    ElMessage.warning(`成功删除 ${success} 套，失败 ${fail} 套${firstError ? `：${firstError}` : ''}`)
  }
  selectedRows.value = []
  /* 当前页可能被删空：若剩余总数落在前一页则回退页码 */
  if (houses.value.length <= fail && page.value > 1) {
    page.value -= 1
  }
  load()
}

/* ---------------------------------- 行内编辑（A7 步骤 7.3） ---------------------------------- */

/* 行内编辑覆盖文本/数值字段；状态变更走既有「状态」入口（updateHouseStatus 记录变更历史），
   行内保存复用对话框编辑同一契约（updateHouse，不含 status） */
type InlineForm = Pick<IHouseDTO, 'houseNumber' | 'floor' | 'area' | 'roomCount' | 'layout' | 'orientation'>

const inlineEditingId = ref<number | null>(null)
const inlineForm = reactive<InlineForm>({
  houseNumber: '',
  floor: 1,
  area: undefined,
  roomCount: undefined,
  layout: '',
  orientation: ''
})

function openInlineEdit(row: IHouse): void {
  if (inlineEditingId.value !== null) {
    ElMessage.warning('请先保存或取消当前行编辑')
    return
  }
  inlineEditingId.value = row.id
  inlineForm.houseNumber = row.houseNumber
  inlineForm.floor = row.floor
  inlineForm.area = row.area ?? undefined
  inlineForm.roomCount = row.roomCount ?? undefined
  inlineForm.layout = row.layout ?? ''
  inlineForm.orientation = row.orientation ?? ''
}

function cancelInlineEdit(): void {
  inlineEditingId.value = null
}

async function saveInlineEdit(row: IHouse): Promise<void> {
  if (!inlineForm.houseNumber.trim()) {
    ElMessage.error('门牌号不能为空')
    return
  }
  try {
    await updateHouse(row.id, {
      unitId: row.unitId,
      houseNumber: inlineForm.houseNumber.trim(),
      floor: inlineForm.floor,
      area: inlineForm.area,
      roomCount: inlineForm.roomCount,
      layout: inlineForm.layout || undefined,
      orientation: inlineForm.orientation || undefined,
      description: row.description
    })
    ElMessage.success('房屋已更新')
    inlineEditingId.value = null
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------------------------- 住户信息抽屉（A7 步骤 7.4） ---------------------------------- */

const router = useRouter()

const residentsVisible = ref(false)
const residentsLoading = ref(false)
const residentsHouse = ref<IHouse | null>(null)
const residentsList = ref<IHouseResident[]>([])

const activeResidents = computed(() => residentsList.value.filter((item) => item.status === 'ACTIVE'))
const movedOutResidents = computed(() => residentsList.value.filter((item) => item.status !== 'ACTIVE'))

async function openResidents(row: IHouse): Promise<void> {
  residentsHouse.value = row
  residentsVisible.value = true
  residentsLoading.value = true
  try {
    const result = await getHouseResidentList(row.id, { page: 1, size: 50 })
    residentsList.value = result.records
  } catch {
    residentsList.value = []
  } finally {
    residentsLoading.value = false
  }
}

/** 关系类型展示：枚举走标签表，后端扩展值原样回显 */
function relationTypeText(relationType: RelationType | undefined): string {
  if (!relationType) return '-'
  return relationTypeLabels[relationType] ?? relationType
}

/* 住户登记直建端点缺失（POST /residence-relations 不存在，关系建立仅经
   居民提交入住申请 + 管理端审批，缺口已记 BEAUTIFY_NOTES 后端适配清单）：
   引导前往既有审批流页面 */
function goApplicationApproval(): void {
  residentsVisible.value = false
  router.push({ path: '/admin/residents', query: { tab: 'applications' } })
}

/* 办理搬出：小对话框二次确认（迁出日期 + 原因），成功后刷新抽屉与列表 */
const moveOutVisible = ref(false)
const moveOutSubmitting = ref(false)
const moveOutTarget = ref<IHouseResident | null>(null)
const moveOutFormRef = ref<FormInstance>()
const moveOutForm = reactive<{ moveOutDate: string; reason: string }>({
  moveOutDate: todayISO(),
  reason: ''
})

const moveOutRules: FormRules = {
  moveOutDate: [{ required: true, message: '请选择迁出日期', trigger: 'change' }]
}

function openMoveOut(resident: IHouseResident): void {
  moveOutTarget.value = resident
  moveOutForm.moveOutDate = todayISO()
  moveOutForm.reason = ''
  moveOutVisible.value = true
}

async function submitMoveOut(): Promise<void> {
  const valid = await moveOutFormRef.value?.validate().catch(() => false)
  if (!valid || !moveOutTarget.value || !residentsHouse.value) return
  moveOutSubmitting.value = true
  try {
    await moveOutResidenceRelation(moveOutTarget.value.id, {
      moveOutDate: moveOutForm.moveOutDate,
      reason: moveOutForm.reason || undefined
    })
    ElMessage.success(`已为 ${moveOutTarget.value.residentName} 办理迁出`)
    moveOutVisible.value = false
    /* 房屋无在住居民时后端回翻空置：抽屉与列表同步刷新 */
    openResidents(residentsHouse.value)
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '迁出失败')
  } finally {
    moveOutSubmitting.value = false
  }
}

onMounted(loadCommunities)
</script>

<template>
  <section class="house-list">
    <!-- 任务 3 换壳内嵌：页头/标题由容器 Tab 承担，此处仅保留操作按钮 -->
    <div class="list-toolbar">
      <el-button
        v-if="selectedRows.length > 0"
        v-permission="['ADMIN', 'SUPER_ADMIN']"
        type="danger"
        plain
        @click="handleBatchDelete"
      >
        批量删除（{{ selectedRows.length }}）
      </el-button>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" @click="openBatchCreate">批量生成</el-button>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" :disabled="unitFilter === ''" @click="openCreate">新建房屋</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">社区</span>
      <el-select
        v-model="communityFilter"
        placeholder="请选择社区"
        style="width: 160px"
        @change="handleCommunityChange"
      >
        <el-option label="全部社区" value="" />
        <el-option
          v-for="item in communities"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
      <span class="filter-label">楼栋</span>
      <el-select
        v-model="buildingFilter"
        placeholder="请选择楼栋"
        style="width: 140px"
        :disabled="communityFilter === ''"
        @change="handleBuildingChange"
      >
        <el-option label="全部楼栋" value="" />
        <el-option
          v-for="item in buildings"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
      <span class="filter-label">单元</span>
      <el-select
        v-model="unitFilter"
        placeholder="请选择单元"
        style="width: 140px"
        :disabled="buildingFilter === ''"
        @change="handleUnitChange"
      >
        <el-option label="全部单元" value="" />
        <el-option
          v-for="item in units"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
      <span class="filter-label">状态</span>
      <el-select
        v-model="statusFilter"
        placeholder="全部状态"
        style="width: 120px"
        @change="handleStatusFilterChange"
      >
        <el-option label="全部状态" value="" />
        <el-option
          v-for="(label, value) in houseStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-alert
      v-if="unitFilter === ''"
      title="请先选择社区/楼栋/单元查看房屋"
      type="info"
      :closable="false"
      class="empty-hint"
    />
    <template v-else>
      <el-table
        v-loading="loading"
        :data="houses"
        border
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="buildingName" label="楼栋" min-width="100" show-overflow-tooltip />
        <el-table-column prop="unitName" label="单元" width="90" show-overflow-tooltip />
        <!-- 行内编辑列（A7 7.3）：门牌/楼层/面积/户型/朝向可直接输入，逐行保存调既有 update -->
        <el-table-column min-width="110" show-overflow-tooltip>
          <template #header>门牌号</template>
          <template #default="{ row }">
            <el-input
              v-if="inlineEditingId === row.id"
              v-model="inlineForm.houseNumber"
              size="small"
              maxlength="20"
            />
            <span v-else>{{ row.houseNumber }}</span>
          </template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>楼层</template>
          <template #default="{ row }">
            <el-input-number
              v-if="inlineEditingId === row.id"
              v-model="inlineForm.floor"
              size="small"
              :min="1"
              :max="99"
              controls-position="right"
              style="width: 88px"
            />
            <span v-else>{{ row.floor }}</span>
          </template>
        </el-table-column>
        <el-table-column width="130">
          <template #header>面积(㎡)</template>
          <template #default="{ row }">
            <el-input-number
              v-if="inlineEditingId === row.id"
              v-model="inlineForm.area"
              size="small"
              :min="1"
              :max="10000"
              :precision="2"
              controls-position="right"
              style="width: 96px"
            />
            <span v-else>{{ row.area ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column width="130">
          <template #header>户型</template>
          <template #default="{ row }">
            <el-input
              v-if="inlineEditingId === row.id"
              v-model="inlineForm.layout"
              size="small"
              maxlength="20"
            />
            <span v-else>{{ layoutText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column width="110">
          <template #header>朝向</template>
          <template #default="{ row }">
            <el-input
              v-if="inlineEditingId === row.id"
              v-model="inlineForm.orientation"
              size="small"
              maxlength="10"
            />
            <span v-else>{{ row.orientation || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag
              :label="houseStatusLabels[row.status as HouseStatus]"
              :type="statusTagType(row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <template v-if="inlineEditingId === row.id">
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="saveInlineEdit(row)">保存</el-button>
              <el-button link type="info" size="small" @click="cancelInlineEdit">取消</el-button>
            </template>
            <template v-else>
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openInlineEdit(row)">行内编辑</el-button>
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="warning" size="small" @click="openStatusChange(row)">状态</el-button>
              <el-button link type="info" size="small" @click="openResidents(row)">住户</el-button>
              <el-button link type="info" size="small" @click="openHistory(row)">历史</el-button>
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        @update:page="load"
        @update:size="load"
      />
    </template>

    <!-- 新建/编辑房屋 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建房屋' : '编辑房屋'"
      width="560px"
    >
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
        <el-form-item label="门牌号" prop="houseNumber">
          <el-input v-model="form.houseNumber" placeholder="如：101" maxlength="20" />
        </el-form-item>
        <el-form-item label="所在楼层" prop="floor">
          <el-input-number v-model="form.floor" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="建筑面积(㎡)" prop="area">
          <el-input-number v-model="form.area" :min="1" :max="10000" :precision="2" />
        </el-form-item>
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
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 状态变更 -->
    <el-dialog
      v-model="statusDialogVisible"
      :title="statusTarget ? `状态变更：${statusTarget.houseNumber}` : '状态变更'"
      width="440px"
    >
      <el-form ref="statusFormRef" :model="statusForm" :rules="statusRules" label-width="100px">
        <el-form-item label="房屋状态" prop="status">
          <el-select v-model="statusForm.status" style="width: 100%">
            <el-option
              v-for="(label, value) in houseStatusLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="变更备注">
          <el-input
            v-model="statusForm.remark"
            type="textarea"
            :rows="2"
            placeholder="请输入变更原因（选填）"
            maxlength="100"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleStatusSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 状态历史时间线 -->
    <el-drawer
      v-model="historyVisible"
      :title="historyHouse ? `状态历史：${historyHouse.houseNumber}` : '状态历史'"
      size="400px"
    >
      <div v-loading="historyLoading" class="history-body">
        <el-timeline v-if="historyList.length > 0">
          <el-timeline-item
            v-for="item in historyList"
            :key="item.id"
            :timestamp="formatDateTime(item.createdAt)"
            placement="top"
          >
            <div class="history-item">
              <span class="history-status">
                {{ item.oldStatus ? houseStatusLabels[item.oldStatus] : '初始' }}
                →
                {{ houseStatusLabels[item.newStatus] }}
              </span>
              <span class="history-operator">操作人：{{ item.operatorName }}</span>
              <span v-if="item.remark" class="history-remark">备注：{{ item.remark }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无状态变更记录" />
      </div>
    </el-drawer>

    <!-- 批量生成房屋（A7 7.3）：选单元 + 楼层范围 + 每层房号，预览确认后循环创建 -->
    <HouseBatchDialog
      v-model="batchVisible"
      :communities="communities"
      :initial-community-id="communityFilter"
      :initial-building-id="buildingFilter"
      :initial-unit-id="unitFilter"
      @saved="handleBatchSaved"
    />

    <!-- 住户信息抽屉（A7 7.4）：房屋维度居住信息维护，非用户账号信息 -->
    <el-drawer
      v-model="residentsVisible"
      :title="residentsHouse ? `住户信息：${residentsHouse.houseNumber}` : '住户信息'"
      size="480px"
    >
      <div v-loading="residentsLoading" class="residents-body">
        <!-- 住户登记引导：直建关系端点缺失，经居民入住申请 + 审批流建立 -->
        <el-alert type="info" :closable="false" class="residents-guide">
          <template #title>
            住户登记须经居民端提交入住申请，审批通过后自动建立居住关系
          </template>
          <el-button link type="primary" @click="goApplicationApproval">
            前往入住申请审批
          </el-button>
        </el-alert>

        <h4 class="residents-section-title">在住住户（{{ activeResidents.length }}）</h4>
        <template v-if="!residentsLoading">
          <ul v-if="activeResidents.length > 0" class="resident-list">
            <li v-for="item in activeResidents" :key="item.id" class="resident-item">
              <div class="resident-main">
                <span class="resident-name">{{ item.residentName }}</span>
                <span class="resident-relation">{{ relationTypeText(item.relationType) }}</span>
              </div>
              <div class="resident-meta">
                <span>{{ item.residentPhone || '-' }}</span>
                <span>入住：{{ formatDate(item.moveInDate) }}</span>
              </div>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="warning"
                plain
                size="small"
                @click="openMoveOut(item)"
              >
                办理搬出
              </el-button>
            </li>
          </ul>
          <el-empty v-else description="暂无在住住户，可经入住申请流程登记" :image-size="72" />
        </template>

        <template v-if="movedOutResidents.length > 0">
          <h4 class="residents-section-title">迁出记录（{{ movedOutResidents.length }}）</h4>
          <ul class="resident-list is-history">
            <li v-for="item in movedOutResidents" :key="item.id" class="resident-item is-muted">
              <div class="resident-main">
                <span class="resident-name">{{ item.residentName }}</span>
                <span class="resident-relation">{{ relationTypeText(item.relationType) }}</span>
              </div>
              <div class="resident-meta">
                <span>入住：{{ formatDate(item.moveInDate) }}</span>
                <span>迁出：{{ formatDate(item.moveOutDate) }}</span>
              </div>
            </li>
          </ul>
        </template>
      </div>
    </el-drawer>

    <!-- 办理搬出（二次确认）：迁出日期 + 原因，成功后联动刷新抽屉与列表 -->
    <el-dialog
      v-model="moveOutVisible"
      :title="moveOutTarget ? `办理搬出：${moveOutTarget.residentName}` : '办理搬出'"
      width="420px"
      append-to-body
    >
      <el-form ref="moveOutFormRef" :model="moveOutForm" :rules="moveOutRules" label-width="90px">
        <el-form-item label="迁出日期" prop="moveOutDate">
          <el-date-picker
            v-model="moveOutForm.moveOutDate"
            type="date"
            placeholder="请选择迁出日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="迁出原因">
          <el-input
            v-model="moveOutForm.reason"
            type="textarea"
            :rows="2"
            placeholder="选填"
            maxlength="100"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveOutVisible = false">取消</el-button>
        <el-button type="primary" :loading="moveOutSubmitting" @click="submitMoveOut">确认迁出</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.list-toolbar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.empty-hint {
  margin-bottom: var(--spacing-md);
}

.layout-inputs {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.layout-sep {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.history-body {
  padding: var(--spacing-sm) var(--spacing-md);
  min-height: 120px;
}

.history-item {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.history-status {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.history-operator,
.history-remark {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* ---------- 住户信息抽屉（A7 7.4） ---------- */

.residents-body {
  padding: var(--spacing-sm) var(--spacing-md);
  min-height: 200px;
}

.residents-guide {
  margin-bottom: var(--spacing-md);
}

.residents-section-title {
  margin: var(--spacing-md) 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.resident-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.resident-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border-light, var(--color-border));
  border-radius: var(--radius-md);
}

.resident-item.is-muted {
  opacity: 0.72;
}

.resident-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 88px;
}

.resident-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.resident-relation {
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

.resident-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
