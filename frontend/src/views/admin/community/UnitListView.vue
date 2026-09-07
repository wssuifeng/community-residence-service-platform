<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createUnit,
  deleteUnit,
  getBuildingList,
  getCommunityList,
  getUnitList,
  updateUnit
} from '@/api/community'
import type { IBuilding, ICommunity, IUnit, IUnitDTO } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 单元管理：社区→楼栋二级联动筛选 + 表格 CRUD */
const units = ref<IUnit[]>([])
const communities = ref<ICommunity[]>([])
const buildings = ref<IBuilding[]>([])
const buildingLoading = ref(false)
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 筛选：社区 → 楼栋联动 */
const communityFilter = ref<number | ''>('')
const buildingFilter = ref<number | ''>('')

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 200 })
    communities.value = result.records
  } catch {
    communities.value = []
  }
}

/** 联动加载楼栋选项（社区变化时重置楼栋筛选） */
async function loadBuildings(): Promise<void> {
  buildings.value = []
  buildingFilter.value = ''
  if (communityFilter.value === '') return
  buildingLoading.value = true
  try {
    const result = await getBuildingList(communityFilter.value, { page: 1, size: 200 })
    buildings.value = result.records
  } catch {
    buildings.value = []
  } finally {
    buildingLoading.value = false
  }
}

async function load(): Promise<void> {
  if (buildingFilter.value === '') {
    units.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await getUnitList(buildingFilter.value, {
      page: page.value,
      size: size.value
    })
    units.value = result.records
    total.value = result.total
  } catch {
    units.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleCommunityChange(): void {
  page.value = 1
  loadBuildings().then(load)
}

function handleBuildingChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  communityFilter.value = ''
  buildingFilter.value = ''
  buildings.value = []
  page.value = 1
  load()
}

/* ---------------------------------- 新建/编辑 ---------------------------------- */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

/** 表单态 DTO：外键未选择时为 undefined（提交前经 rules 校验收敛） */
type UnitForm = Omit<IUnitDTO, 'buildingId'> & { buildingId?: number }

const form = reactive<UnitForm>({
  buildingId: undefined,
  unitNumber: '',
  totalFloors: 1,
  householdsPerFloor: 2
})

/** 对话框内独立维护的社区→楼栋联动（避免与筛选状态互相干扰） */
const formCommunityId = ref<number | ''>('')
const formBuildings = ref<IBuilding[]>([])

const rules: FormRules = {
  buildingId: [{ required: true, message: '请选择所属楼栋', trigger: 'change' }],
  unitNumber: [{ required: true, message: '请输入单元编号', trigger: 'blur' }],
  totalFloors: [{ required: true, message: '请输入单元层数', trigger: 'blur' }],
  householdsPerFloor: [{ required: true, message: '请输入每层户数', trigger: 'blur' }]
}

async function handleFormCommunityChange(): Promise<void> {
  form.buildingId = undefined
  formBuildings.value = []
  if (formCommunityId.value === '') return
  try {
    const result = await getBuildingList(formCommunityId.value, { page: 1, size: 200 })
    formBuildings.value = result.records
  } catch {
    formBuildings.value = []
  }
}

function openCreate(): void {
  editingId.value = null
  form.buildingId = undefined
  form.unitNumber = ''
  form.totalFloors = 1
  form.householdsPerFloor = 2
  // 默认带入当前筛选的社区/楼栋
  if (communityFilter.value !== '' && buildingFilter.value !== '') {
    formCommunityId.value = communityFilter.value
    handleFormCommunityChange().then(() => {
      form.buildingId = buildingFilter.value === '' ? undefined : buildingFilter.value
    })
  } else {
    formCommunityId.value = ''
    formBuildings.value = []
  }
  dialogVisible.value = true
}

function openEdit(row: IUnit): void {
  editingId.value = row.id
  // 编辑回显：楼栋所在社区可能未在筛选中，从楼栋选项定位
  const belonged = buildings.value.find((item) => item.id === row.buildingId)
  if (belonged) {
    formCommunityId.value = belonged.communityId
    formBuildings.value = buildings.value.filter(
      (item) => item.communityId === belonged.communityId
    )
  } else {
    formCommunityId.value = ''
    formBuildings.value = []
  }
  form.buildingId = row.buildingId
  form.unitNumber = row.unitNumber
  form.totalFloors = row.totalFloors
  form.householdsPerFloor = row.householdsPerFloor
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    if (editingId.value === null) {
      await createUnit({ ...form, buildingId: form.buildingId as number })
      ElMessage.success('单元创建成功')
    } else {
      await updateUnit(editingId.value, { ...form, buildingId: form.buildingId as number })
      ElMessage.success('单元已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------------------------- 删除（引用保护由后端报错） ---------------------------------- */

async function handleDelete(row: IUnit): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除单元「${row.unitNumber}」？若该单元下存在房屋，删除将被拒绝。`,
      '删除单元',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteUnit(row.id)
    ElMessage.success('单元已删除')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  loadCommunities()
})
</script>

<template>
  <section class="unit-list">
    <div class="list-toolbar">
      <span class="toolbar-title">单元管理</span>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openCreate">新建单元</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">所属社区</span>
      <el-select
        v-model="communityFilter"
        placeholder="请选择社区"
        style="width: 180px"
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
      <span class="filter-label">所属楼栋</span>
      <el-select
        v-model="buildingFilter"
        placeholder="请选择楼栋"
        style="width: 160px"
        :disabled="communityFilter === ''"
        :loading="buildingLoading"
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
    </FilterPanel>

    <el-alert
      v-if="buildingFilter === ''"
      title="请先选择社区和楼栋查看单元"
      type="info"
      :closable="false"
      class="empty-hint"
    />
    <template v-else>
      <el-table v-loading="loading" :data="units" border>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="buildingName" label="所属楼栋" min-width="140" show-overflow-tooltip />
        <el-table-column prop="unitNumber" label="单元编号" min-width="110" show-overflow-tooltip />
        <el-table-column prop="totalFloors" label="单元层数" width="90" />
        <el-table-column prop="householdsPerFloor" label="每层户数" width="90" />
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建单元' : '编辑单元'"
      width="480px"
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
        <el-form-item label="所属楼栋" prop="buildingId">
          <el-select
            v-model="form.buildingId"
            placeholder="请选择楼栋"
            style="width: 100%"
            :disabled="formCommunityId === ''"
          >
            <el-option
              v-for="item in formBuildings"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="单元编号" prop="unitNumber">
          <el-input v-model="form.unitNumber" placeholder="如：1单元" maxlength="20" />
        </el-form-item>
        <el-form-item label="单元层数" prop="totalFloors">
          <el-input-number v-model="form.totalFloors" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="每层户数" prop="householdsPerFloor">
          <el-input-number v-model="form.householdsPerFloor" :min="1" :max="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
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
</style>
