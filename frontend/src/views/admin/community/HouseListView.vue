<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createHouse,
  deleteHouse,
  getBuildingList,
  getCommunityList,
  getHouseList,
  getHouseStatusHistory,
  getUnitList,
  updateHouse,
  updateHouseStatus
} from '@/api/community'
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
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

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
    houses.value = result.records
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

onMounted(loadCommunities)
</script>

<template>
  <section class="house-list">
    <div class="list-toolbar">
      <span class="toolbar-title">房屋管理</span>
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
      <el-table v-loading="loading" :data="houses" border>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="buildingName" label="楼栋" min-width="100" show-overflow-tooltip />
        <el-table-column prop="unitName" label="单元" width="90" show-overflow-tooltip />
        <el-table-column prop="houseNumber" label="门牌号" min-width="100" show-overflow-tooltip />
        <el-table-column prop="floor" label="楼层" width="70" />
        <el-table-column label="面积(㎡)" width="90">
          <template #default="{ row }">{{ row.area ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="户型" width="110">
          <template #default="{ row }">{{ layoutText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag
              :label="houseStatusLabels[row.status as HouseStatus]"
              :type="statusTagType(row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="warning" size="small" @click="openStatusChange(row)">状态</el-button>
            <el-button link type="info" size="small" @click="openHistory(row)">历史</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
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
  </section>
</template>

<style scoped>
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.toolbar-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
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
</style>
