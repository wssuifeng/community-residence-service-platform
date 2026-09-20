<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import { getCommunityList, getBuildingList, getUnitList } from '@/api/community'
import { createHousing, updateHousing, updateHousingStatus, getHousingDetail } from '@/api/housing'
import { http } from '@/utils/request'
import type { ICommunity, IBuilding, IUnit } from '@/types/modules/community'
import type { HousingRentType, HousingStatus } from '@/types/modules/housing'
import { housingRentTypeLabels, housingStatusLabels } from '@/types/modules/housing'

/**
 * 房屋与房源一体化（R62，v1.5；2026-09-20 用户反馈改版为带图卡片 + 筛选）：
 * 主体为「房屋为主线」的卡片流（GET /communities/houses-manage 分页），
 * 顶部筛选（社区/楼栋/单元三级联动 + 房号关键字 + 挂牌状态）；
 * 卡片带房源首图（无图/未挂牌用占位插画），行内完成挂牌/编辑/上下架；
 * 「批量挂牌」弹窗沿用层级选择（整社区/按楼栋/按单元 + 标题后缀）。
 */

/* 契约：GET /communities/houses-manage → 分页房屋主线项（R62 v1.5，接口设计.md §9.15.3） */
interface HousingBrief {
  id: number
  title: string
  status: HousingStatus
  monthlyRent: number
  deposit: number | null
  rentType: HousingRentType
  coverImage: string | null
}

interface HouseManageItem {
  houseId: number
  communityId: number
  communityName: string
  buildingId: number | null
  buildingName: string | null
  unitId: number | null
  unitName: string | null
  houseNumber: string
  floor: number
  area: number | null
  layout: string | null
  houseStatus: string
  /** null=未挂牌 */
  housing: HousingBrief | null
}

/** 房源占位插画（未挂牌或无图时使用，frontend/public 静态资产） */
const HOUSING_PLACEHOLDER = '/housing-placeholder.png'

/* ===================== 筛选 + 卡片列表 ===================== */

const filters = reactive({
  communityId: undefined as number | undefined,
  buildingId: undefined as number | undefined,
  unitId: undefined as number | undefined,
  keyword: '',
  listing: 'all' as 'all' | 'listed' | 'unlisted'
})

const page = ref(1)
const size = ref(12)
const total = ref(0)
const items = ref<HouseManageItem[]>([])
const listLoading = ref(false)

/** 社区选项（管理端数据级权限内全量） */
const communities = ref<ICommunity[]>([])
const buildings = ref<IBuilding[]>([])
const units = ref<IUnit[]>([])

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 200 })
    communities.value = result.records
  } catch (error) {
    communities.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载社区列表失败')
  }
}

/** 社区变更：清空下级并拉楼栋 */
async function handleCommunityChange(): Promise<void> {
  filters.buildingId = undefined
  filters.unitId = undefined
  buildings.value = []
  units.value = []
  if (filters.communityId != null) {
    try {
      const list = await getBuildingList(filters.communityId, { page: 1, size: 200 })
      buildings.value = list.records
    } catch {
      buildings.value = []
    }
  }
  reload()
}

/** 楼栋变更：清空单元并拉取 */
async function handleBuildingChange(): Promise<void> {
  filters.unitId = undefined
  units.value = []
  if (filters.buildingId != null) {
    try {
      const list = await getUnitList(filters.buildingId, { page: 1, size: 200 })
      units.value = list.records
    } catch {
      units.value = []
    }
  }
  reload()
}

async function loadItems(): Promise<void> {
  listLoading.value = true
  try {
    const result = await http.get<{ records: HouseManageItem[]; total: number }>(
      '/communities/houses-manage',
      {
        communityId: filters.communityId,
        buildingId: filters.buildingId,
        unitId: filters.unitId,
        keyword: filters.keyword.trim() === '' ? undefined : filters.keyword.trim(),
        listing: filters.listing,
        page: page.value,
        size: size.value
      }
    )
    items.value = result.records
    total.value = result.total
  } catch (error) {
    items.value = []
    total.value = 0
    ElMessage.error(error instanceof Error ? error.message : '加载房屋与房源失败')
  } finally {
    listLoading.value = false
  }
}

function reload(): void {
  page.value = 1
  void loadItems()
}

function resetFilters(): void {
  filters.communityId = undefined
  filters.buildingId = undefined
  filters.unitId = undefined
  filters.keyword = ''
  filters.listing = 'all'
  buildings.value = []
  units.value = []
  reload()
}

/** 卡片图：优先房源首图，未挂牌/无图回落占位插画 */
function coverOf(item: HouseManageItem): string {
  const cover = item.housing?.coverImage
  return cover && cover.trim() !== '' ? cover : HOUSING_PLACEHOLDER
}

function locationOf(item: HouseManageItem): string {
  return `${item.buildingName ?? ''}${item.unitName ?? ''}${item.houseNumber}`
}

function formatRent(value: number): string {
  return Number(value).toLocaleString('zh-CN')
}

onMounted(async () => {
  await loadCommunities()
  void loadItems()
})

/* ===================== 挂牌 / 编辑房源 ===================== */

const dialogVisible = ref(false)
const saving = ref(false)
const dialogContext = ref<{
  houseId: number
  houseLabel: string
  housingId: number | null
  images: string
} | null>(null)

const form = reactive({
  title: '',
  monthlyRent: undefined as number | undefined,
  deposit: undefined as number | undefined,
  rentType: 'RENT' as HousingRentType,
  description: ''
})

/* 本期仅出租（R62 收敛）：出售模式无售价字段与买卖流程支撑，表单不再提供；
   历史 SALE 数据读侧标签仍兼容（housingRentTypeLabels 未动） */
const rentTypeOptions: Array<{ value: HousingRentType; label: string }> = [
  { value: 'RENT', label: housingRentTypeLabels.RENT }
]

/**
 * 打开挂牌/编辑：无房源走挂牌（标题默认「社区·楼栋单元房号·精装房源」可改）；
 * 已有房源先拉详情回显描述与图片（卡片项不含），失败以卡片摘要兜底。
 */
async function openHousingDialog(item: HouseManageItem): Promise<void> {
  const label = `${item.communityName} ${locationOf(item)}`
  const brief = item.housing
  dialogContext.value = {
    houseId: item.houseId,
    houseLabel: label,
    housingId: brief?.id ?? null,
    images: ''
  }
  Object.assign(form, {
    title: brief ? brief.title : `${locationOf(item)}·精装房源`,
    monthlyRent: brief ? brief.monthlyRent : undefined,
    deposit: brief ? (brief.deposit ?? undefined) : undefined,
    rentType: brief ? brief.rentType : 'RENT',
    description: ''
  })
  dialogVisible.value = true

  if (brief) {
    try {
      const detail = await getHousingDetail(brief.id)
      form.description = detail.description
      /* 更新载荷须回传既有图片串（后端按整串落值），避免详情拉取后图片被清空 */
      dialogContext.value.images = detail.images.join(',')
    } catch {
      /* 详情拉取失败：摘要兜底已回显关键字段 */
    }
  }
}

async function handleSave(): Promise<void> {
  const context = dialogContext.value
  if (!context) return
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
    const payload = {
      houseId: context.houseId,
      title: form.title.trim(),
      description: form.description,
      monthlyRent: form.monthlyRent,
      deposit: form.deposit,
      images: context.images,
      rentType: form.rentType
    }
    if (context.housingId === null) {
      await createHousing(payload)
      ElMessage.success('房源已挂牌')
    } else {
      await updateHousing(context.housingId, payload)
      ElMessage.success('房源已更新')
    }
    dialogVisible.value = false
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存房源失败')
  } finally {
    saving.value = false
  }
}

/* ===================== 上架 / 下架 ===================== */

/** 上架/下架：口径与全局房源列表一致（OFFLINE ↔ AVAILABLE，下架二次确认） */
async function handleToggleStatus(item: HouseManageItem): Promise<void> {
  const brief = item.housing
  if (!brief) return
  if (brief.status !== 'OFFLINE') {
    try {
      await ElMessageBox.confirm(`确认下架「${brief.title}」？下架后居民端不再展示`, '下架房源', {
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await updateHousingStatus(brief.id, { status: 'OFFLINE' })
      ElMessage.success('房源已下架')
      await loadItems()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      ElMessage.error(error instanceof Error ? error.message : '下架失败')
    }
    return
  }
  try {
    await updateHousingStatus(brief.id, { status: 'AVAILABLE' })
    ElMessage.success('房源已上架')
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上架失败')
  }
}

/* ===================== 批量挂牌（层级范围 + 标题后缀） ===================== */

/* 契约：POST /housings/batch-generate
   {communityId, monthlyRent, deposit, rentType?, buildingIds?, unitIds?, houseIds?, titleSuffix?}
   → {created, skipped}（R62 v1.5；收窄链 houseIds > unitIds > buildingIds > 整社区） */
interface BatchTreeHouse {
  id: number
  houseNumber: string
  housing: unknown | null
}
interface BatchTreeUnit {
  id: number
  name: string
  houses: BatchTreeHouse[]
}
interface BatchTreeBuilding {
  id: number
  name: string
  units: BatchTreeUnit[]
}

const batchVisible = ref(false)
const batchSubmitting = ref(false)
const batchCommunity = ref<ICommunity | null>(null)
const batchScopeMode = ref<'community' | 'buildings' | 'units'>('community')
const batchBuildingFilter = ref<number | null>(null)
const batchTree = ref<BatchTreeBuilding[]>([])
const batchTreeLoading = ref(false)
const batchForm = reactive({
  monthlyRent: undefined as number | undefined,
  deposit: undefined as number | undefined,
  rentType: 'RENT' as HousingRentType,
  titleSuffix: '',
  buildingIds: [] as number[],
  unitIds: [] as number[]
})

const batchBuildings = computed<BatchTreeBuilding[]>(() => batchTree.value)

/** 按单元模式：受楼栋筛选联动的单元列表（未选楼栋=全部楼栋） */
const batchUnits = computed<BatchTreeUnit[]>(() => {
  const filtered = batchBuildingFilter.value == null
    ? batchTree.value
    : batchTree.value.filter((b) => b.id === batchBuildingFilter.value)
  return filtered.flatMap((b) => b.units)
})

function unlistedCount(unit: BatchTreeUnit): number {
  return unit.houses.filter((h) => h.housing == null).length
}

function buildingUnlistedCount(building: BatchTreeBuilding): number {
  return building.units.reduce((sum, u) => sum + unlistedCount(u), 0)
}

/** 打开批量挂牌：默认以当前筛选社区为范围（未选社区时要求先选一个） */
async function openBatchDialog(): Promise<void> {
  const target = filters.communityId != null
    ? communities.value.find((c) => c.id === filters.communityId) ?? null
    : null
  if (!target) {
    ElMessage.info('请先在筛选中选择社区，或从社区卡片进入批量挂牌')
    return
  }
  await openBatchDialogFor(target)
}

/** 从社区维度直接打开批量挂牌（卡片空态/社区筛选场景） */
async function openBatchDialogFor(community: ICommunity): Promise<void> {
  batchCommunity.value = community
  batchForm.monthlyRent = undefined
  batchForm.deposit = undefined
  batchForm.rentType = 'RENT'
  batchForm.titleSuffix = ''
  batchForm.buildingIds = []
  batchForm.unitIds = []
  batchBuildingFilter.value = null
  batchScopeMode.value = 'community'
  batchVisible.value = true
  /* 层级选择依赖结构树（含未挂牌计数） */
  batchTreeLoading.value = true
  try {
    batchTree.value = await http.get<BatchTreeBuilding[]>(
      `/communities/${community.id}/houses-with-housing`
    )
  } catch (error) {
    batchTree.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载社区结构失败')
  } finally {
    batchTreeLoading.value = false
  }
}

function handleScopeModeChange(): void {
  if (batchScopeMode.value !== 'buildings') batchForm.buildingIds = []
  if (batchScopeMode.value !== 'units') {
    batchForm.unitIds = []
    batchBuildingFilter.value = null
  }
}

function handleBuildingFilterChange(): void {
  batchForm.unitIds = []
}

function selectAllUnits(): void {
  batchForm.unitIds = batchUnits.value.map((u) => u.id)
}

const batchScopeText = computed(() => {
  switch (batchScopeMode.value) {
    case 'community':
      return '整社区：全部无在架房源的房屋'
    case 'buildings': {
      if (batchForm.buildingIds.length === 0) return '请选择楼栋'
      const unlisted = batchBuildings.value
        .filter((b) => batchForm.buildingIds.includes(b.id))
        .reduce((sum, b) => sum + buildingUnlistedCount(b), 0)
      return `已选 ${batchForm.buildingIds.length} 个楼栋，其中无在架房源房屋 ${unlisted} 套`
    }
    default: {
      if (batchForm.unitIds.length === 0) return '请选择单元'
      const unlisted = batchUnits.value
        .filter((u) => batchForm.unitIds.includes(u.id))
        .reduce((sum, u) => sum + unlistedCount(u), 0)
      return `已选 ${batchForm.unitIds.length} 个单元，其中无在架房源房屋 ${unlisted} 套`
    }
  }
})

async function handleBatchSubmit(): Promise<void> {
  const community = batchCommunity.value
  if (!community) return
  if (batchForm.monthlyRent == null) {
    ElMessage.warning('请填写默认月租金')
    return
  }
  if (batchScopeMode.value === 'buildings' && batchForm.buildingIds.length === 0) {
    ElMessage.warning('请选择楼栋')
    return
  }
  if (batchScopeMode.value === 'units' && batchForm.unitIds.length === 0) {
    ElMessage.warning('请选择单元')
    return
  }
  batchSubmitting.value = true
  try {
    const result = await http.post<{ created: number; skipped: number }>(
      '/housings/batch-generate',
      {
        communityId: community.id,
        monthlyRent: batchForm.monthlyRent,
        deposit: batchForm.deposit ?? null,
        rentType: batchForm.rentType,
        buildingIds: batchScopeMode.value === 'buildings' ? batchForm.buildingIds : undefined,
        unitIds: batchScopeMode.value === 'units' ? batchForm.unitIds : undefined,
        titleSuffix: batchForm.titleSuffix.trim() === '' ? undefined : batchForm.titleSuffix.trim()
      }
    )
    ElMessage.success(`批量挂牌完成：新建 ${result.created} 套，跳过 ${result.skipped} 套（已有房源）`)
    batchVisible.value = false
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量挂牌失败')
  } finally {
    batchSubmitting.value = false
  }
}
</script>

<template>
  <section class="houses-housing">
    <header class="page-lead">
      <div class="page-lead-text">
        <h2 class="page-lead-title">房屋与房源</h2>
        <p class="page-lead-sub">按社区筛选查看房屋与挂牌状态，卡片内直接挂牌、编辑与上下架</p>
      </div>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openBatchDialog">
        批量挂牌
      </el-button>
    </header>

    <!-- 筛选条：社区 / 楼栋 / 单元三级联动 + 房号关键字 + 挂牌状态 -->
    <div class="filter-bar">
      <el-select
        v-model="filters.communityId"
        class="filter-community"
        placeholder="全部社区"
        clearable
        filterable
        @change="handleCommunityChange"
      >
        <el-option v-for="c in communities" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select
        v-model="filters.buildingId"
        class="filter-select"
        placeholder="全部楼栋"
        clearable
        :disabled="filters.communityId == null"
        @change="handleBuildingChange"
      >
        <el-option v-for="b in buildings" :key="b.id" :label="b.name" :value="b.id" />
      </el-select>
      <el-select
        v-model="filters.unitId"
        class="filter-select"
        placeholder="全部单元"
        clearable
        :disabled="filters.buildingId == null"
        @change="reload"
      >
        <el-option v-for="u in units" :key="u.id" :label="u.name" :value="u.id" />
      </el-select>
      <el-input
        v-model="filters.keyword"
        class="filter-keyword"
        placeholder="按房号搜索"
        clearable
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filters.listing" class="filter-select" @change="reload">
        <el-option label="全部状态" value="all" />
        <el-option label="已挂牌" value="listed" />
        <el-option label="未挂牌" value="unlisted" />
      </el-select>
      <el-button link type="primary" @click="reload">查询</el-button>
      <el-button link @click="resetFilters">重置</el-button>
    </div>

    <!-- 卡片流 -->
    <div v-loading="listLoading" class="card-grid">
      <article v-for="item in items" :key="item.houseId" class="house-card">
        <div
          class="card-cover"
          :class="{ 'is-placeholder': !item.housing?.coverImage }"
          :data-variant="item.houseId % 5"
        >
          <img :src="coverOf(item)" :alt="locationOf(item)" loading="lazy" />
          <span
            class="cover-state"
            :data-state="item.housing ? item.housing.status : 'UNLISTED'"
          >
            {{ item.housing ? housingStatusLabels[item.housing.status] : '未挂牌' }}
          </span>
          <span v-if="item.housing" class="cover-type">{{ housingRentTypeLabels[item.housing.rentType] }}</span>
        </div>

        <div class="card-body">
          <h3 class="card-title">{{ locationOf(item) }}</h3>
          <p class="card-community">{{ item.communityName }}</p>
          <p class="card-meta">
            <span>{{ item.floor }} 层</span>
            <span>{{ item.area != null ? `${item.area} ㎡` : '—' }}</span>
            <span class="card-layout">{{ item.layout || '—' }}</span>
          </p>
          <p class="card-price">
            <template v-if="item.housing">
              ¥{{ formatRent(item.housing.monthlyRent) }}<span class="price-unit">/月</span>
            </template>
            <template v-else>
              <span class="price-empty">未挂牌</span>
            </template>
          </p>
        </div>

        <footer class="card-actions">
          <el-button
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            size="small"
            :type="item.housing ? 'default' : 'primary'"
            @click="openHousingDialog(item)"
          >
            {{ item.housing ? '编辑房源' : '挂牌' }}
          </el-button>
          <el-button
            v-if="item.housing"
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            size="small"
            :type="item.housing.status === 'OFFLINE' ? 'success' : 'warning'"
            plain
            @click="handleToggleStatus(item)"
          >
            {{ item.housing.status === 'OFFLINE' ? '上架' : '下架' }}
          </el-button>
        </footer>
      </article>

      <el-empty
        v-if="!listLoading && items.length === 0"
        class="grid-empty"
        description="所选条件下没有房屋，调整筛选或先到「结构总览」新建"
      />
    </div>

    <!-- 分页（卡片流） -->
    <div v-if="total > 0" class="grid-pager">
      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        layout="prev, pager, next, sizes, total"
        @update:page="loadItems"
        @update:size="reload"
      />
    </div>

    <!-- 挂牌 / 编辑房源 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogContext?.housingId ? '编辑房源' : '房屋挂牌'"
      width="560px"
      destroy-on-close
    >
      <p class="dialog-context">{{ dialogContext?.houseLabel }}</p>
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="60" show-word-limit placeholder="如：精装两居 · 拎包入住" />
        </el-form-item>
        <el-form-item label="月租金" required>
          <el-input-number v-model="form.monthlyRent" :min="0" :step="100" />
          <span class="form-unit">元/月</span>
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number v-model="form.deposit" :min="0" :step="100" />
          <span class="form-unit">元（不填则面议）</span>
        </el-form-item>
        <el-form-item label="租售类型" required>
          <el-radio-group v-model="form.rentType">
            <el-radio v-for="option in rentTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
          <span class="form-unit">本期仅支持出租</span>
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

    <!-- 批量挂牌：层级范围（整社区/按楼栋/按单元）+ 标题后缀 -->
    <el-dialog v-model="batchVisible" title="批量挂牌" width="560px" destroy-on-close>
      <p class="dialog-context">
        为「{{ batchCommunity?.name }}」范围内无在架房源的房屋按默认参数生成在售（可租）房源，
        已有房源的房屋自动跳过。
      </p>
      <el-form v-loading="batchTreeLoading" label-width="90px">
        <el-form-item label="挂牌范围">
          <div class="batch-scope">
            <el-radio-group v-model="batchScopeMode" size="small" @change="handleScopeModeChange">
              <el-radio-button value="community">整社区</el-radio-button>
              <el-radio-button value="buildings">按楼栋</el-radio-button>
              <el-radio-button value="units">按单元</el-radio-button>
            </el-radio-group>
            <p class="batch-scope-text">{{ batchScopeText }}</p>
          </div>
        </el-form-item>

        <el-form-item v-if="batchScopeMode === 'buildings'" label="选择楼栋">
          <el-checkbox-group v-model="batchForm.buildingIds" class="pick-list">
            <el-checkbox v-for="building in batchBuildings" :key="building.id" :value="building.id">
              {{ building.name }}
              <span class="pick-count">
                （{{ building.units.length }} 单元 · {{ buildingUnlistedCount(building) }} 套未挂牌）
              </span>
            </el-checkbox>
          </el-checkbox-group>
          <p v-if="batchBuildings.length === 0" class="pick-empty">该社区暂无楼栋</p>
        </el-form-item>

        <template v-if="batchScopeMode === 'units'">
          <el-form-item label="楼栋筛选">
            <el-select
              v-model="batchBuildingFilter"
              clearable
              placeholder="全部楼栋"
              style="width: 220px"
              @change="handleBuildingFilterChange"
            >
              <el-option
                v-for="building in batchBuildings"
                :key="building.id"
                :label="`${building.name}（${building.units.length} 单元）`"
                :value="building.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="选择单元">
            <div class="pick-panel">
              <div class="pick-panel-tools">
                <span class="pick-panel-hint">共 {{ batchUnits.length }} 个单元</span>
                <el-button link type="primary" size="small" @click="selectAllUnits">全选</el-button>
                <el-button link size="small" @click="batchForm.unitIds = []">清空</el-button>
              </div>
              <el-checkbox-group v-model="batchForm.unitIds" class="pick-list pick-list-scroll">
                <el-checkbox v-for="unit in batchUnits" :key="unit.id" :value="unit.id">
                  {{ unit.name }}
                  <span class="pick-count">（{{ unlistedCount(unit) }} 套未挂牌）</span>
                </el-checkbox>
              </el-checkbox-group>
              <p v-if="batchUnits.length === 0" class="pick-empty">所选楼栋下暂无单元</p>
            </div>
          </el-form-item>
        </template>

        <el-form-item label="默认月租" required>
          <el-input-number v-model="batchForm.monthlyRent" :min="0" :step="100" />
          <span class="form-unit">元/月</span>
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number v-model="batchForm.deposit" :min="0" :step="100" />
          <span class="form-unit">元（不填则面议）</span>
        </el-form-item>
        <el-form-item label="租售类型">
          <el-radio-group v-model="batchForm.rentType">
            <el-radio v-for="option in rentTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题后缀">
          <el-input
            v-model="batchForm.titleSuffix"
            maxlength="32"
            show-word-limit
            placeholder="选填，如：特价 / 朝南 / 近地铁"
          />
          <p class="form-hint">
            生成标题形如「1 号楼1 单元101·精装房源{{ batchForm.titleSuffix.trim() ? '·' + batchForm.titleSuffix.trim() : '' }}」
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="handleBatchSubmit">
          确认批量挂牌
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.houses-housing {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 320px;
}

/* ---------- 页首 ---------- */

.page-lead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.page-lead-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.page-lead-sub {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* ---------- 筛选条 ---------- */

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  padding: var(--spacing-sm) var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.filter-community {
  width: 200px;
}

.filter-select {
  width: 150px;
}

.filter-keyword {
  width: 180px;
}

/* ---------- 卡片流（3:2 封面 + 信息 + 操作） ---------- */

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--spacing-md);
  min-height: 240px;
}

.house-card {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.15s ease;
}

.house-card:hover {
  border-color: var(--color-primary-light);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

/* 封面：稳定 3:2 比例，状态角标压在图上（实心底保证可读） */
.card-cover {
  position: relative;
  aspect-ratio: 3 / 2;
  background: var(--color-bg-hover);
  overflow: hidden;
}

.card-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 占位封面（无实拍图）：按房号取模叠加柔和色相层，避免整屏卡片视觉重复 */
.card-cover.is-placeholder::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  mix-blend-mode: multiply;
}

.card-cover.is-placeholder[data-variant='0']::after {
  background: linear-gradient(150deg, rgba(59, 109, 255, 0.16), rgba(59, 109, 255, 0) 70%);
}

.card-cover.is-placeholder[data-variant='1']::after {
  background: linear-gradient(150deg, rgba(16, 185, 129, 0.16), rgba(16, 185, 129, 0) 70%);
}

.card-cover.is-placeholder[data-variant='2']::after {
  background: linear-gradient(150deg, rgba(245, 158, 11, 0.14), rgba(245, 158, 11, 0) 70%);
}

.card-cover.is-placeholder[data-variant='3']::after {
  background: linear-gradient(150deg, rgba(99, 102, 241, 0.16), rgba(99, 102, 241, 0) 70%);
}

.card-cover.is-placeholder[data-variant='4']::after {
  background: linear-gradient(150deg, rgba(14, 165, 233, 0.16), rgba(14, 165, 233, 0) 70%);
}

.cover-state {
  position: absolute;
  top: var(--spacing-xs);
  left: var(--spacing-xs);
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: rgba(15, 23, 42, 0.72);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
}

.cover-state[data-state='AVAILABLE'] {
  background: var(--color-success);
}

.cover-state[data-state='RESERVED'] {
  background: var(--color-warning);
}

.cover-state[data-state='RENTED'] {
  background: var(--color-primary);
}

.cover-state[data-state='UNLISTED'] {
  background: rgba(100, 116, 139, 0.85);
}

.cover-type {
  position: absolute;
  top: var(--spacing-xs);
  right: var(--spacing-xs);
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.9);
  color: var(--color-text-secondary);
  font-size: 11px;
  line-height: 18px;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--spacing-sm) var(--spacing-md) var(--spacing-xs);
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

.card-community {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.card-layout {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-price {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  font-family: var(--font-family-mono);
}

.price-unit {
  margin-left: 2px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-disabled);
}

.price-empty {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-disabled);
  font-family: inherit;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-md) var(--spacing-md);
  margin-top: auto;
}

.grid-empty {
  grid-column: 1 / -1;
  padding: var(--spacing-xl) 0;
}

.grid-pager {
  display: flex;
  justify-content: flex-end;
}

/* ---------- 对话框 ---------- */

.dialog-context {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.form-unit {
  margin-left: var(--spacing-sm);
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}

.form-hint {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.batch-scope {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.batch-scope-text {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.pick-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
}

.pick-list-scroll {
  max-height: 180px;
  overflow-y: auto;
}

.pick-count {
  color: var(--color-text-disabled);
  font-size: 11px;
}

.pick-empty {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.pick-panel {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm);
}

.pick-panel-tools {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-xs);
}

.pick-panel-hint {
  margin-right: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 响应式：窄屏筛选条纵向堆叠、卡片单列 */
@media (max-width: 768px) {
  .filter-community,
  .filter-select,
  .filter-keyword {
    width: 100%;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
