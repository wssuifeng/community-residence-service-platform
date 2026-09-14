<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
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

/**
 * 房源列表（管理端 Tab 内容）：摄影卡片网格 + 社区/状态/关键字筛选 + 分页 + 新建/编辑对话框。
 * 上下架切换、删除、时段入口经卡片操作行；对照设计稿 09-房源管理。
 * 保存载荷对齐后端 CreateHousingDTO（images 逗号分隔串、押金字段 deposit）；
 * 房源创建后/状态变更后 emit changed 供容器刷新统计卡。
 */

const emit = defineEmits<{
  changed: []
}>()

const router = useRouter()

/** 房源状态 → StatusTag 语义色（可租=绿 / 已预订=黄 / 已出租=蓝 / 已下架=灰） */
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
  size: 12,
  keyword: '',
  communityId: undefined as number | undefined,
  status: undefined as HousingStatus | undefined
})

const total = ref(0)
const records = ref<IHousing[]>([])
const loading = ref(false)

const communities = ref<ICommunity[]>([])

/** 图片兜底：images 为空时按列表序号取模轮换三张示例图（与游客端口径一致） */
function coverImage(housing: IHousing, index: number): string {
  return housing.images[0] ?? `/images/housing-sample-${(index % 3) + 1}.png`
}

/** 金额千分位（mock 价格行口径 ¥2,800/月） */
function formatRent(value: number): string {
  return Number(value).toLocaleString('zh-CN')
}

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
/* 保存载荷仅含后端 CreateHousingDTO 认可的字段：houseId/title/description/monthlyRent/deposit/images；
   communityId 只作级联定位房屋的本地状态；编辑模式下后端不允许变更关联房屋，级联置为只读展示 */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const editingHouse = reactive({
  communityName: '',
  houseLocation: ''
})
const saving = ref(false)

const emptyForm = (): HousingSaveDTO => ({
  houseId: undefined as unknown as number,
  title: '',
  description: '',
  monthlyRent: undefined as unknown as number,
  deposit: undefined,
  images: ''
})

const form = reactive<HousingSaveDTO>(emptyForm())
const imagesText = ref('')

/* 级联选择：社区 → 楼栋 → 单元 → 房屋（仅新建模式） */
const cascade = reactive({
  communityId: undefined as number | undefined,
  buildingId: undefined as number | undefined,
  unitId: undefined as number | undefined
})
const buildings = ref<IBuilding[]>([])
const units = ref<IUnit[]>([])
const houses = ref<IHouse[]>([])

function resetCascade(): void {
  cascade.communityId = undefined
  cascade.buildingId = undefined
  cascade.unitId = undefined
  buildings.value = []
  units.value = []
  houses.value = []
}

async function loadBuildings(communityId: number): Promise<void> {
  resetCascade()
  cascade.communityId = communityId
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

/** 图片串：界面一行一个 URL，载荷按后端口径以逗号分隔存储 */
function imagesPayload(): string {
  return imagesText.value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .join(',')
}

function openCreate(): void {
  editingId.value = null
  editingHouse.communityName = ''
  editingHouse.houseLocation = ''
  Object.assign(form, emptyForm())
  imagesText.value = ''
  resetCascade()
  dialogVisible.value = true
}

function openEdit(row: IHousing): void {
  editingId.value = row.id
  editingHouse.communityName = row.communityName
  editingHouse.houseLocation = row.houseLocation
  Object.assign(form, {
    houseId: row.houseId,
    title: row.title,
    description: row.description,
    monthlyRent: row.monthlyRent,
    deposit: row.deposit ?? undefined,
    images: ''
  })
  imagesText.value = row.images.join('\n')
  resetCascade()
  dialogVisible.value = true
}

async function handleSave(): Promise<void> {
  if (!form.houseId) {
    ElMessage.warning(editingId.value === null ? '请通过级联选择关联房屋' : '关联房屋信息缺失')
    return
  }
  if (!form.title.trim()) {
    ElMessage.warning('请填写房源标题')
    return
  }
  if (form.monthlyRent == null) {
    ElMessage.warning('请填写月租金')
    return
  }
  saving.value = true
  try {
    const payload: HousingSaveDTO = {
      houseId: form.houseId,
      title: form.title.trim(),
      description: form.description,
      monthlyRent: form.monthlyRent,
      deposit: form.deposit,
      images: imagesPayload()
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
    emit('changed')
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
    emit('changed')
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
    emit('changed')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '删除房源失败')
  }
}

/** 时段管理入口：现位于详情页时段区，卡片跳详情并锚定 #timeslots */
function goTimeslots(row: IHousing): void {
  router.push({ path: `/admin/housings/${row.id}`, hash: '#timeslots' })
}

onMounted(() => {
  loadCommunities()
  loadList()
})
</script>

<template>
  <section class="housing-list-admin">
    <FilterPanel resettable @reset="handleReset">
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
      <!-- 后端关键字真实匹配范围为 标题/描述（HousingService.page），占位如实标注 -->
      <SearchBar v-model="query.keyword" placeholder="搜索房源标题 / 描述" @search="handleSearch" />
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openCreate">
        ＋ 新增房源
      </el-button>
    </FilterPanel>

    <!-- 摄影卡片网格：宽屏 3 列 → 中屏 2 列 → 窄屏 1 列 -->
    <div v-loading="loading" class="housing-grid">
      <article v-for="(housing, index) in records" :key="housing.id" class="housing-card">
        <router-link :to="`/admin/housings/${housing.id}`" class="card-main">
          <div class="card-media">
            <img :src="coverImage(housing, index)" :alt="housing.title" loading="lazy" />
            <StatusTag
              class="card-status"
              on-image
              :label="housingStatusLabels[housing.status]"
              :type="tagTypeMap[housing.status]"
            />
          </div>
          <div class="card-body">
            <h3 class="card-title">{{ housing.communityName }} {{ housing.houseLocation }}</h3>
            <p class="card-sub">{{ housing.title }}</p>
            <div class="card-price-row">
              <p class="card-rent">
                <span class="card-rent-amount">¥{{ formatRent(housing.monthlyRent) }}</span>
                <span class="card-rent-unit">/月</span>
              </p>
              <span class="card-views">{{ housing.viewCount }} 次浏览</span>
            </div>
          </div>
        </router-link>
        <div class="card-actions">
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" @click="openEdit(housing)">编辑</el-button>
          <el-button link type="primary" @click="goTimeslots(housing)">时段</el-button>
          <el-button
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            link
            :type="housing.status === 'OFFLINE' ? 'success' : 'warning'"
            @click="handleToggleStatus(housing)"
          >
            {{ housing.status === 'OFFLINE' ? '上架' : '下架' }}
          </el-button>
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" @click="handleDelete(housing)">删除</el-button>
        </div>
      </article>

      <!-- 空状态：示例图 + 引导文案，而非一行灰字 -->
      <div v-if="!loading && records.length === 0" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无房源" />
        <h3>没有找到符合条件的房源</h3>
        <p>换个关键字或状态试试，或者点击右上角「新增房源」发布第一套房源。</p>
      </div>
    </div>

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
          <!-- 编辑模式：后端不允许变更关联房屋，只读展示 -->
          <el-input
            v-if="editingId !== null"
            :model-value="`${editingHouse.communityName} ${editingHouse.houseLocation}`"
            disabled
            style="width: 540px"
          />
          <div v-else class="cascade">
            <el-select
              :model-value="cascade.communityId"
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
          <el-input-number v-model="form.deposit" :min="0" :step="100" />
          <span class="form-unit">元（不填则面议）</span>
        </el-form-item>
        <el-form-item label="房源图片">
          <el-input
            v-model="imagesText"
            type="textarea"
            :rows="3"
            placeholder="图片地址，每行一个（可留空使用默认示意图）"
          />
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
/* 卡片网格：宽屏 3 列 → 中屏 2 列 → 窄屏 1 列 */
.housing-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-md);
  min-height: 200px;
}

.housing-card {
  display: flex;
  flex-direction: column;
  background: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.housing-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.card-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  text-decoration: none;
}

/* 3:2 摄影封面（与设计稿比例契约一致） */
.card-media {
  position: relative;
  aspect-ratio: 3 / 2;
  overflow: hidden;
  background-color: var(--color-bg);
}

.card-media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 状态角标压图右上（设计稿 09 口径），on-image 实色变体保证可读 */
.card-status {
  position: absolute;
  top: var(--spacing-sm);
  right: var(--spacing-sm);
  z-index: 1;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  flex: 1;
}

.card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-sub {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-price-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.card-rent {
  margin: 0;
}

.card-rent-amount {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.card-rent-unit {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.card-views {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  white-space: nowrap;
}

/* 操作行：顶部细分隔线 + 均布文字动作（编辑/时段/上下架/删除） */
.card-actions {
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: var(--spacing-xs) var(--spacing-md);
  border-top: 1px solid var(--color-border);
}

.card-actions .el-button + .el-button {
  margin-left: 0;
}

/* 空状态横跨整行 */
.empty-state {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xxl) var(--spacing-md);
  background: var(--admin-card-bg);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  text-align: center;
}

.empty-state img {
  width: 180px;
}

.empty-state h3 {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.empty-state p {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
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

@media (max-width: 1024px) {
  .housing-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .housing-grid {
    grid-template-columns: 1fr;
  }
}
</style>
