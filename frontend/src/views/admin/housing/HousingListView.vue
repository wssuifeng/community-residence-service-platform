<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import {
  listHousings,
  createHousing,
  updateHousing,
  updateHousingStatus,
  deleteHousing
} from '@/api/housing'
import {
  getCommunityList,
  getBuildingList,
  getUnitList,
  getHouseList
} from '@/api/community'
import type { IHousing, HousingStatus, HousingSaveDTO } from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import type { ICommunity, IBuilding, IUnit, IHouse } from '@/types/modules/community'
import { formatDate } from '@/utils/date'

/**
 * 房源管理（管理端）：CRUD + 上架/下架切换
 * 新建/编辑对话框字段对齐 HousingSaveDTO；房屋通过 社区→楼栋→单元→房屋 级联选择
 */

const tagTypeMap: Record<HousingStatus, 'completed' | 'pending' | 'processing' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'processing',
  OFFLINE: 'canceled'
}

const statusOptions = (Object.keys(housingStatusLabels) as HousingStatus[]).map((value) => ({
  value,
  label: housingStatusLabels[value]
}))

const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  communityId: undefined as number | undefined,
  status: undefined as HousingStatus | undefined
})

const total = ref(0)
const records = ref<IHousing[]>([])
const loading = ref(false)

const communities = ref<ICommunity[]>([])

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 100 })
    communities.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载社区列表失败')
  }
}

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listHousings({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      communityId: query.communityId,
      status: query.status
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.page = 1
  loadList()
}

function handleReset(): void {
  query.keyword = ''
  query.communityId = undefined
  query.status = undefined
  query.page = 1
  loadList()
}

/* ------------------------------ 新建/编辑对话框 ------------------------------ */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)

const emptyForm = (): HousingSaveDTO => ({
  communityId: undefined as unknown as number,
  houseId: undefined as unknown as number,
  title: '',
  description: '',
  monthlyRent: undefined as unknown as number,
  depositAmount: undefined,
  availableDate: '',
  contactPerson: '',
  contactPhone: '',
  images: [],
  tags: []
})

const form = reactive<HousingSaveDTO>(emptyForm())
const imagesText = ref('')
const tagsInput = ref('')

/* 级联选择：社区 → 楼栋 → 单元 → 房屋 */
const cascade = reactive({
  buildingId: undefined as number | undefined,
  unitId: undefined as number | undefined
})
const buildings = ref<IBuilding[]>([])
const units = ref<IUnit[]>([])
const houses = ref<IHouse[]>([])

function resetCascade(): void {
  cascade.buildingId = undefined
  cascade.unitId = undefined
  buildings.value = []
  units.value = []
  houses.value = []
}

async function loadBuildings(communityId: number): Promise<void> {
  resetCascade()
  try {
    const result = await getBuildingList(communityId, { page: 1, size: 100 })
    buildings.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载楼栋列表失败')
  }
}

async function loadUnits(buildingId: number): Promise<void> {
  cascade.unitId = undefined
  units.value = []
  houses.value = []
  try {
    const result = await getUnitList(buildingId, { page: 1, size: 100 })
    units.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载单元列表失败')
  }
}

async function loadHouses(unitId: number): Promise<void> {
  houses.value = []
  try {
    const result = await getHouseList(unitId, { page: 1, size: 100 })
    houses.value = result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房屋列表失败')
  }
}

function handleFormCommunityChange(communityId: number | undefined): void {
  form.houseId = undefined as unknown as number
  if (communityId) loadBuildings(communityId)
  else resetCascade()
}

function handleBuildingChange(buildingId: number | undefined): void {
  form.houseId = undefined as unknown as number
  if (buildingId) loadUnits(buildingId)
  else {
    cascade.unitId = undefined
    units.value = []
    houses.value = []
  }
}

function handleUnitChange(unitId: number | undefined): void {
  form.houseId = undefined as unknown as number
  if (unitId) loadHouses(unitId)
  else houses.value = []
}

function handleHouseChange(houseId: number | undefined): void {
  form.houseId = (houseId ?? undefined) as number
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  imagesText.value = ''
  tagsInput.value = ''
  resetCascade()
  dialogVisible.value = true
}

function openEdit(row: IHousing): void {
  editingId.value = row.id
  Object.assign(form, {
    communityId: row.communityId,
    houseId: row.houseId,
    title: row.title,
    description: row.description,
    monthlyRent: row.monthlyRent,
    depositAmount: row.depositAmount ?? undefined,
    availableDate: row.availableDate,
    contactPerson: row.contactPerson,
    contactPhone: row.contactPhone ?? '',
    images: [...row.images],
    tags: [...(row.tags ?? [])]
  })
  imagesText.value = row.images.join('\n')
  tagsInput.value = (row.tags ?? []).join('、')
  resetCascade()
  dialogVisible.value = true
}

async function handleSave(): Promise<void> {
  if (!form.communityId) {
    ElMessage.warning('请选择所属社区')
    return
  }
  if (!form.houseId) {
    ElMessage.warning('请通过级联选择关联房屋')
    return
  }
  if (!form.title.trim()) {
    ElMessage.warning('请填写房源标题')
    return
  }
  saving.value = true
  try {
    const payload: HousingSaveDTO = {
      communityId: form.communityId,
      houseId: form.houseId,
      title: form.title.trim(),
      description: form.description,
      monthlyRent: form.monthlyRent,
      depositAmount: form.depositAmount,
      availableDate: form.availableDate,
      contactPerson: form.contactPerson.trim(),
      contactPhone: form.contactPhone?.trim() || undefined,
      images: imagesText.value
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0),
      tags: tagsInput.value
        .split(/[、,，\s]+/)
        .map((tag) => tag.trim())
        .filter((tag) => tag.length > 0)
    }
    if (editingId.value === null) {
      await createHousing(payload)
      ElMessage.success('房源已创建')
    } else {
      await updateHousing(editingId.value, payload)
      ElMessage.success('房源已更新')
    }
    dialogVisible.value = false
    loadList()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存房源失败')
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 状态切换与删除 ------------------------------ */

/** 上架/下架：下架 → OFFLINE；重新上架 → AVAILABLE */
async function handleToggleStatus(row: IHousing): Promise<void> {
  const goingOffline = row.status !== 'OFFLINE'
  try {
    if (goingOffline) {
      await ElMessageBox.confirm(`确认下架「${row.title}」？下架后居民端不再展示`, '下架房源', {
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await updateHousingStatus(row.id, { status: 'OFFLINE' })
      ElMessage.success('房源已下架')
    } else {
      await updateHousingStatus(row.id, { status: 'AVAILABLE' })
      ElMessage.success('房源已上架')
    }
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '状态切换失败')
  }
}

/** 删除（有未完成看房预约时后端会拒绝） */
async function handleDelete(row: IHousing): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除房源「${row.title}」？存在未完成的看房预约时将无法删除`,
      '删除房源',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'error' }
    )
    await deleteHousing(row.id)
    ElMessage.success('房源已删除')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '删除房源失败')
  }
}

onMounted(() => {
  loadCommunities()
  loadList()
})
</script>

<template>
  <section class="housing-admin">
    <header class="page-head">
      <div>
        <h1>房源列表</h1>
        <p class="page-head-sub">维护对外展示的房源信息，管理上下架状态</p>
      </div>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openCreate">＋ 新建房源</el-button>
    </header>

    <FilterPanel resettable @reset="handleReset">
      <SearchBar v-model="query.keyword" placeholder="搜索房源标题/地址" @search="handleSearch" />
      <el-select
        v-model="query.communityId"
        placeholder="全部社区"
        clearable
        style="width: 180px"
        @change="query.page = 1; loadList()"
      >
        <el-option
          v-for="community in communities"
          :key="community.id"
          :value="community.id"
          :label="community.name"
        />
      </el-select>
      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 140px"
        @change="query.page = 1; loadList()"
      >
        <el-option v-for="option in statusOptions" :key="option.value" :value="option.value" :label="option.label" />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="records" stripe>
      <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
      <el-table-column prop="communityName" label="社区" min-width="110" />
      <el-table-column prop="houseAddress" label="房屋地址" min-width="140" show-overflow-tooltip />
      <el-table-column label="月租金" width="110" align="right">
        <template #default="{ row }">￥{{ row.monthlyRent }}</template>
      </el-table-column>
      <el-table-column prop="contactPerson" label="联系人" min-width="90" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <StatusTag :label="housingStatusLabels[row.status as HousingStatus]" :type="tagTypeMap[row.status as HousingStatus]" />
        </template>
      </el-table-column>
      <el-table-column prop="viewCount" label="浏览量" width="80" align="center" />
      <el-table-column label="发布时间" min-width="110">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link :type="row.status === 'OFFLINE' ? 'success' : 'warning'" size="small" @click="handleToggleStatus(row)">
            {{ row.status === 'OFFLINE' ? '上架' : '下架' }}
          </el-button>
          <router-link :to="`/admin/housings/${row.id}`" class="table-link">
            <el-button link size="small">详情</el-button>
          </router-link>
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <Pagination
      v-model:page="query.page"
      v-model:size="query.size"
      :total="total"
      @update:page="loadList"
      @update:size="loadList"
    />

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建房源' : `编辑房源 #${editingId}`"
      width="680px"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="关联房屋" required>
          <div class="cascade">
            <el-select
              :model-value="form.communityId"
              placeholder="社区"
              style="width: 140px"
              @change="handleFormCommunityChange"
            >
              <el-option v-for="community in communities" :key="community.id" :value="community.id" :label="community.name" />
            </el-select>
            <el-select
              v-model="cascade.buildingId"
              placeholder="楼栋"
              :disabled="buildings.length === 0"
              style="width: 120px"
              @change="handleBuildingChange"
            >
              <el-option v-for="building in buildings" :key="building.id" :value="building.id" :label="building.name" />
            </el-select>
            <el-select
              v-model="cascade.unitId"
              placeholder="单元"
              :disabled="units.length === 0"
              style="width: 120px"
              @change="handleUnitChange"
            >
              <el-option v-for="unit in units" :key="unit.id" :value="unit.id" :label="unit.name" />
            </el-select>
            <el-select
              :model-value="form.houseId"
              placeholder="房屋"
              :disabled="houses.length === 0"
              style="width: 140px"
              @change="handleHouseChange"
            >
              <el-option
                v-for="house in houses"
                :key="house.id"
                :value="house.id"
                :label="`${house.houseNumber}（${house.floor} 层）`"
              />
            </el-select>
          </div>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="如：精装两居 · 拎包入住" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item label="月租金" required>
          <el-input-number v-model="form.monthlyRent" :min="0" :step="100" />
          <span class="form-unit">元/月</span>
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number v-model="form.depositAmount" :min="0" :step="100" />
          <span class="form-unit">元（不填则面议）</span>
        </el-form-item>
        <el-form-item label="可入住" required>
          <el-date-picker
            v-model="form.availableDate"
            type="date"
            placeholder="选择可入住日期"
            value-format="YYYY-MM-DD"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactPerson" maxlength="30" style="width: 200px" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" maxlength="20" style="width: 200px" />
        </el-form-item>
        <el-form-item label="房源图片">
          <el-input
            v-model="imagesText"
            type="textarea"
            :rows="3"
            placeholder="图片地址，每行一个（可留空使用默认示意图）"
          />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="tagsInput" placeholder="多个标签用「、」或空格分隔，如：精装、朝南、近地铁" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.table-link {
  margin: 0 var(--spacing-xs);
}

.cascade {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.form-unit {
  margin-left: var(--spacing-sm);
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}
</style>
