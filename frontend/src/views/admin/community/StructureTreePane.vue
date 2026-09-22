<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import CommunityEditDialog from '@/views/admin/community/CommunityEditDialog.vue'
import BuildingEditDialog from '@/views/admin/community/BuildingEditDialog.vue'
import UnitEditDialog from '@/views/admin/community/UnitEditDialog.vue'
import UnitBatchDialog from '@/views/admin/community/UnitBatchDialog.vue'
import HouseEditDialog from '@/views/admin/community/HouseEditDialog.vue'
import HouseBatchDialog from '@/views/admin/community/HouseBatchDialog.vue'
import StructureGenerateDialog from '@/views/admin/community/StructureGenerateDialog.vue'
import BatchResultDialog from '@/views/admin/community/BatchResultDialog.vue'
import {
  batchDeleteCommunities,
  batchUpdateCommunityStatus,
  deleteBuilding,
  deleteCommunity,
  deleteHouse,
  deleteUnit,
  getBuildingList,
  getCommunity,
  getCommunityList,
  getHouseList,
  getHouseStatusHistory,
  getUnitList,
  updateCommunityStatus,
  updateHouseStatus
} from '@/api/community'
import type {
  IBatchFailure,
  IBuilding,
  ICommunity,
  IHouse,
  IHouseStatusHistory,
  IUnit
} from '@/types/modules/community'
import { communityStatusLabels, houseStatusLabels } from '@/types/modules/community'
import type { CommunityStatus, HouseStatus } from '@/types/modules/community'
import { getDashboardStats } from '@/api/statistics'
import { getHouseResidentList, getResident } from '@/api/resident'
import type { IHouseResident, IResident } from '@/types/modules/resident'
import { relationTypeLabels, residenceRelationStatusLabels } from '@/types/modules/resident'
import { formatDateTime } from '@/utils/date'
import { useGridSelection } from '@/composables/useGridSelection'
import { useUserStore } from '@/store/user'

/**
 * 社区管理主面板（2026-09-21 布局重做；2026-09-22 批次 j/k 交互与布局再修）。
 *
 * 信息架构（两栏：社区栏 + 详情单列）：
 *   ① 社区栏：关键字/状态筛选 + 分页 + 每社区概览计数 + 选中态；超管可多选批量管理；
 *   ② 详情栏（单列，占满剩余宽度）：
 *      · 社区头部：社区名/状态/联系方式 + 社区级操作（编辑社区 · 停用启用 · 删除 · 社区设置）；
 *      · **建房带**：一个总入口「一键生成结构链」（楼栋→单元→房屋，先预览后落库）
 *        ＋ 三个按层级递进的分步入口「①建楼栋 / ②建单元与房屋 / ③建房屋」
 *        ＋ 右端低频「结构维护 ▾」（编辑楼栋/编辑单元/新增单个单元/删除楼栋/删除单元）；
 *      · 房屋面板：**唯一筛选区**（范围：楼栋/单元/房号 ＋ 条件：楼层/状态）＋ 缩放控件
 *        ＋ **单元平行列**（一单元一列，列多时横向滚动，缩放即可一屏多看几列）。
 *
 * 为什么建房要分区成带：新建（楼栋/单元与房屋/房屋/整链）是高频且成组的结构动作，
 * 混在社区级操作里会让头部堆成一片按钮；单独一条浅底带 + 主次分明的按钮层级
 * （实心总入口 → 序号分步按钮 → 右端低频维护菜单），既讲清"从哪一级开始建"，
 * 也不再和「编辑社区 / 停用 / 删除」抢视觉重量。
 *
 * 结构目录为什么取消：批量建房已一次性建成 楼栋→单元→房屋，常驻结构树的选择功能被筛选区
 * 完全替代，且它固定占掉半屏、把房屋列表挤到下面。维护动作并不常用，改为「先选目标、
 * 再进各自弹窗」的一次性入口（`structureDialogVisible`），能力零删减。
 *
 * 筛选区为什么只有一处：只有房屋栏顶部这一张筛选卡，层级固定为「范围 → 条件 → 结果」，
 * 不存在上下两处同类筛选按钮。
 *
 * 房屋小卡片为什么不盖悬浮按钮：66×34 的卡片被三个操作钮盖满后无法点开详情。
 * 现在卡片主体整块可点（进详情），卡片右上角一个「⋯」菜单承载 详情/编辑/状态/删除，
 * 操作区不入卡片内容区，也不再遮挡房号。
 *
 * 三级结构的增删改与房屋状态变更/历史、批量建房等能力全部保留；创建链路由
 * StructureGenerateDialog（一次请求建成 楼栋→单元→房屋）承担总入口，
 * 单轴弹窗退为「已有楼栋/单元内补建」的专项入口。
 */

const ICONS = {
  home: ['M3 11l9-8 9 8', 'M5 9.7V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.7', 'M10 21v-6h4v6'],
  search: ['M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14z', 'M20 20l-4.2-4.2']
}

const ACTION_ICONS = {
  plus: ['M12 5v14', 'M5 12h14'],
  pen: ['M12 20h9', 'M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z'],
  trash: [
    'M3 6h18',
    'M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2',
    'M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6',
    'M10 11v6',
    'M14 11v6'
  ],
  swap: ['M4 8h13', 'M14 5l3 3-3 3', 'M20 16H7', 'M10 13l-3 3 3 3'],
  layers: ['M12 3l8 4.5-8 4.5-8-4.5L12 3', 'M4 12.5L12 17l8-4.5', 'M4 16.5L12 21l8-4.5'],
  /** 卡片角标「更多」：三个圆点（round linecap 把零长度线段画成点） */
  more: ['M6 12h.01', 'M12 12h.01', 'M18 12h.01'],
  eye: [
    'M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6z',
    'M12 9.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z'
  ]
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 批量管理社区仅超管可用（数据级影响面最大，不允许社区管理员自助） */
const isSuperAdmin = computed(() => userStore.role === 'SUPER_ADMIN')

/* ===================== 左栏：社区列表（服务端筛选 + 分页 + 概览计数） ===================== */

/** 列表行 = 社区 + 概览计数（计数来自看板聚合接口的社区维度口径，非前端推算） */
interface CommunityRow {
  community: ICommunity
  buildingCount: number
  houseCount: number
  /** 入住率分子（已入住房屋数） */
  occupiedCount: number
  /** 概览计数加载中（展示占位而非 0，避免把「未知」读成「没有」） */
  statsLoading: boolean
}

const communityRows = ref<CommunityRow[]>([])
const listLoading = ref(false)
const listError = ref('')
const listTotal = ref(0)

const query = reactive({
  keyword: '',
  status: '' as '' | CommunityStatus,
  page: 1,
  size: 10
})

const statusFilters: { value: '' | CommunityStatus; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'ACTIVE', label: '运营中' },
  { value: 'INACTIVE', label: '已停用' }
]

const selectedCommunityId = ref<number | null>(null)
const selectedCommunity = ref<ICommunity | null>(null)

/* 并发防护：列表与行计数为两段加载，筛选/翻页切换后旧序列的写入作废 */
let listSeq = 0
let rowStatsSeq = 0

/** 列表筛选/翻页后重新拉取社区；选中社区不在本页时保持选中（详情与列表解耦） */
async function loadCommunities(): Promise<void> {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = ''
  try {
    const result = await getCommunityList({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined
    })
    if (seq !== listSeq) return
    communityRows.value = result.records.map((community) => ({
      community,
      buildingCount: 0,
      houseCount: 0,
      occupiedCount: 0,
      statsLoading: true
    }))
    listTotal.value = result.total
    /* 选中社区对象随最新列表刷新（编辑后名称/状态即时同步到详情头部） */
    const refreshed = result.records.find((item) => item.id === selectedCommunityId.value)
    if (refreshed) selectedCommunity.value = refreshed
    if (selectedCommunityId.value === null && result.records.length > 0) {
      selectCommunity(result.records[0])
    } else if (selectedCommunityId.value === null) {
      clearDetail()
    }
    loadRowStats(seq)
  } catch (error) {
    if (seq !== listSeq) return
    communityRows.value = []
    listTotal.value = 0
    listError.value = error instanceof Error ? error.message : '加载社区列表失败'
  } finally {
    if (seq === listSeq) listLoading.value = false
  }
}

/** 概览计数：逐社区取看板聚合（服务端精确值，前端不按分页数据推算） */
async function loadRowStats(seq: number): Promise<void> {
  const statsSeq = ++rowStatsSeq
  const rows = communityRows.value
  await Promise.all(
    rows.map(async (row) => {
      try {
        const stats = await getDashboardStats({ communityId: row.community.id })
        if (seq !== listSeq || statsSeq !== rowStatsSeq) return
        row.buildingCount = stats.buildingCount
        row.houseCount = stats.houseCount
        row.occupiedCount = stats.occupiedHouseCount
      } catch {
        /* 计数失败不打断列表：行内展示占位，用户仍可进入结构与操作 */
      } finally {
        if (seq === listSeq && statsSeq === rowStatsSeq) row.statsLoading = false
      }
    })
  )
}

function resetListQuery(): void {
  query.keyword = ''
  query.status = ''
  query.page = 1
  loadCommunities()
}

function handleSearch(): void {
  query.page = 1
  loadCommunities()
}

function handleStatusFilter(value: '' | CommunityStatus): void {
  if (query.status === value) return
  query.status = value
  query.page = 1
  loadCommunities()
}

function handlePageChange(page: number): void {
  query.page = page
  loadCommunities()
}

function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  loadCommunities()
}

const filtered = computed(() => query.keyword.trim() !== '' || query.status !== '')

function selectCommunity(community: ICommunity, force = false): void {
  const changed = selectedCommunityId.value !== community.id
  selectedCommunityId.value = community.id
  selectedCommunity.value = community
  if (!changed && !force) return
  resetHouseFilters()
  activeBuildingId.value = null
  activeUnitId.value = null
  loadStructure(community.id)
}

function clearDetail(): void {
  selectedCommunityId.value = null
  selectedCommunity.value = null
  buildings.value = []
  structureError.value = ''
  resetHouseFilters()
}

/** 深链 ?communityId= 与「查看该社区结构」：定位到指定社区（不在当前页则单独取回并置顶） */
async function focusCommunity(communityId: number): Promise<void> {
  const inPage = communityRows.value.find((row) => row.community.id === communityId)
  if (inPage) {
    selectCommunity(inPage.community)
    return
  }
  try {
    const community = await getCommunity(communityId)
    communityRows.value = [
      {
        community,
        buildingCount: 0,
        houseCount: 0,
        occupiedCount: 0,
        statsLoading: true
      },
      ...communityRows.value.filter((row) => row.community.id !== communityId)
    ]
    selectCommunity(community)
    const stats = await getDashboardStats({ communityId })
    const row = communityRows.value.find((item) => item.community.id === communityId)
    if (row) {
      row.buildingCount = stats.buildingCount
      row.houseCount = stats.houseCount
      row.occupiedCount = stats.occupiedHouseCount
      row.statsLoading = false
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '定位社区失败')
  }
}

/* ===================== 右栏：选中社区的结构（楼栋 → 单元 → 房屋） ===================== */

interface UnitNode {
  unit: IUnit
  houses: IHouse[]
}

interface BuildingNode {
  building: IBuilding
  units: UnitNode[]
}

const buildings = ref<BuildingNode[]>([])
const structureLoading = ref(false)
const structureError = ref('')
/** 是否已成功加载过当前社区结构（区分「未加载」与「确实为空」两种空态） */
const structureLoaded = ref(false)

let structureSeq = 0

/** 结构加载按选中社区整体拉取：计数与筛选得以在本地精确计算，且只覆盖一个社区 */
async function loadStructure(communityId: number): Promise<void> {
  const seq = ++structureSeq
  /* 整体重载即作废在途的单元级局部刷新，防止旧单元的房屋回写到新结构上 */
  unitReloadSeq += 1
  structureLoading.value = true
  structureError.value = ''
  structureLoaded.value = false
  buildings.value = []
  try {
    const buildingList = (await getBuildingList(communityId, { page: 1, size: 200 })).records
    const nodes = await Promise.all(
      buildingList.map(async (building) => {
        const units = (await getUnitList(building.id, { page: 1, size: 200 })).records
        const unitNodes = await Promise.all(
          units.map(async (unit) => ({
            unit,
            houses: (await getHouseList(unit.id, { page: 1, size: 200 })).records
          }))
        )
        return { building, units: unitNodes }
      })
    )
    if (seq !== structureSeq) return
    buildings.value = nodes
    structureLoaded.value = true
  } catch (error) {
    if (seq !== structureSeq) return
    buildings.value = []
    structureError.value = error instanceof Error ? error.message : '加载结构失败'
  } finally {
    if (seq === structureSeq) structureLoading.value = false
  }
}

function retryStructure(): void {
  if (selectedCommunityId.value !== null) loadStructure(selectedCommunityId.value)
}

/* 局部刷新单个单元的房屋（房屋增删改后不整社区重载，保持筛选与展开态）。
   独立序列号：与结构整体加载互不作废，避免两侧并发时把对方结果丢弃 */
let unitReloadSeq = 0

async function reloadUnitHouses(unitId: number): Promise<void> {
  const seq = ++unitReloadSeq
  try {
    const houses = (await getHouseList(unitId, { page: 1, size: 200 })).records
    if (seq !== unitReloadSeq) return
    for (const building of buildings.value) {
      const unitNode = building.units.find((item) => item.unit.id === unitId)
      if (unitNode) {
        unitNode.houses = houses
        break
      }
    }
    /* 楼层/状态筛选在新数据下可能已无命中：回落到全部，避免空网格无从解释 */
    if (activeFloor.value !== null && !houses.some((house) => house.floor === activeFloor.value)) {
      activeFloor.value = null
    }
  } catch {
    /* 局部刷新失败保留现列表，用户可整体刷新 */
  }
}

/** 房屋扁平清单：后端 HouseVO 不含楼栋/单元名，此处由结构上下文补全（展示与跳转都依赖它） */
interface HouseEntry {
  house: IHouse
  buildingId: number
  buildingName: string
  unitId: number
  unitName: string
}

const houseEntries = computed<HouseEntry[]>(() =>
  buildings.value.flatMap((building) =>
    building.units.flatMap((unitNode) =>
      unitNode.houses.map((house) => ({
        house,
        buildingId: building.building.id,
        buildingName: building.building.name,
        unitId: unitNode.unit.id,
        unitName: unitNode.unit.name
      }))
    )
  )
)

/* ---------------- 筛选状态（唯一筛选区读写；楼栋/单元用选择器切换） ---------------- */

const activeBuildingId = ref<number | null>(null)
const activeUnitId = ref<number | null>(null)
const houseKeyword = ref('')
const activeFloor = ref<number | null>(null)
const activeStatus = ref<HouseStatus | null>(null)

function selectBuilding(buildingId: number | null): void {
  activeBuildingId.value = buildingId
  activeUnitId.value = null
}

function selectUnit(unitId: number | null): void {
  activeUnitId.value = unitId
}

/* ---------------- 筛选区（唯一入口：范围选择器） ---------------- */

/** el-select 清空会给出 ''/undefined，统一折算为「全部」 */
function normalizeScopeId(value: unknown): number | null {
  if (value === '' || value === null || value === undefined) return null
  const id = Number(value)
  return Number.isFinite(id) ? id : null
}

function handleScopeBuilding(value: unknown): void {
  selectBuilding(normalizeScopeId(value))
}

/**
 * 单元选择：未选楼栋时选项跨楼栋并带楼栋前缀，选中后需把楼栋一并定位，
 * 否则会出现「单元属于 A 栋、楼栋筛选仍是全部」的口径矛盾。
 */
function handleScopeUnit(value: unknown): void {
  const unitId = normalizeScopeId(value)
  if (unitId === null) {
    selectUnit(null)
    return
  }
  const owner = buildings.value.find((building) =>
    building.units.some((unitNode) => unitNode.unit.id === unitId)
  )
  if (owner && activeBuildingId.value !== owner.building.id) {
    selectBuilding(owner.building.id)
  }
  selectUnit(unitId)
}

/** 单元选项：已选楼栋时只列该栋，否则跨楼栋列出并带楼栋名 */
const scopeUnitOptions = computed(() => {
  if (activeBuildingId.value !== null) {
    const building = buildings.value.find((item) => item.building.id === activeBuildingId.value)
    return (building?.units ?? []).map((unitNode) => ({
      value: unitNode.unit.id,
      label: unitNode.unit.name
    }))
  }
  return buildings.value.flatMap((building) =>
    building.units.map((unitNode) => ({
      value: unitNode.unit.id,
      label: `${building.building.name} · ${unitNode.unit.name}`
    }))
  )
})

/** 当前筛选是否偏离默认（用于「清除筛选」按钮的显隐） */
const filtersActive = computed(
  () =>
    activeBuildingId.value !== null ||
    activeUnitId.value !== null ||
    activeFloor.value !== null ||
    activeStatus.value !== null ||
    houseKeyword.value.trim() !== ''
)

function resetHouseFilters(): void {
  houseKeyword.value = ''
  activeFloor.value = null
  activeStatus.value = null
  clearHouseSelection()
}

function clearAllFilters(): void {
  resetHouseFilters()
  activeBuildingId.value = null
  activeUnitId.value = null
}

const activeBuildingName = computed(
  () => buildings.value.find((item) => item.building.id === activeBuildingId.value)?.building.name ?? ''
)

const activeUnitName = computed(() => {
  const unitId = activeUnitId.value
  if (unitId === null) return ''
  for (const building of buildings.value) {
    const hit = building.units.find((item) => item.unit.id === unitId)
    if (hit) return hit.unit.name
  }
  return ''
})

/** 楼栋 + 单元两级筛选后的房屋（楼层/状态/关键字在其上继续收窄） */
const scopeEntries = computed(() =>
  houseEntries.value.filter(
    (entry) =>
      (activeBuildingId.value === null || entry.buildingId === activeBuildingId.value) &&
      (activeUnitId.value === null || entry.unitId === activeUnitId.value)
  )
)

const filteredEntries = computed(() => {
  const keyword = houseKeyword.value.trim().toLowerCase()
  return scopeEntries.value.filter(
    (entry) =>
      (activeFloor.value === null || entry.house.floor === activeFloor.value) &&
      (activeStatus.value === null || entry.house.status === activeStatus.value) &&
      (keyword === '' || entry.house.houseNumber.toLowerCase().includes(keyword))
  )
})

/** 楼层选项取自「楼栋+单元」范围：切换楼层不会让其它楼层选项消失 */
const floorOptions = computed(() =>
  [...new Set(scopeEntries.value.map((entry) => entry.house.floor))].sort((a, b) => b - a)
)

const statusOptions = computed(() =>
  (['VACANT', 'OCCUPIED', 'RESERVED', 'MAINTENANCE'] as HouseStatus[]).map((value) => ({
    value,
    label: houseStatusLabels[value],
    count: scopeEntries.value.filter(
      (entry) => entry.house.status === value && (activeFloor.value === null || entry.house.floor === activeFloor.value)
    ).length
  }))
)

const scopeSummary = computed(() => {
  const entries = filteredEntries.value
  const occupied = entries.filter((entry) => entry.house.status === 'OCCUPIED').length
  const vacant = entries.filter((entry) => entry.house.status === 'VACANT').length
  return { total: entries.length, occupied, vacant }
})

/** 社区关键数字（空置与入住率在房屋状态面上，与结构计数同源） */
const communityMetrics = computed(() => {
  const total = houseEntries.value.length
  const occupied = houseEntries.value.filter((entry) => entry.house.status === 'OCCUPIED').length
  const vacant = houseEntries.value.filter((entry) => entry.house.status === 'VACANT').length
  return {
    buildings: buildings.value.length,
    units: buildings.value.reduce((sum, building) => sum + building.units.length, 0),
    houses: total,
    vacant,
    occupancy: total === 0 ? null : (occupied / total) * 100
  }
})

/* ---------------- 房屋网格（按单元分组，复用既有框选/多选能力） ---------------- */

const gridBodyRef = ref<HTMLElement | null>(null)
const {
  selectedIds: selectedHouseIds,
  selectedCount: selectedHouseCount,
  marquee: gridMarquee,
  isSelected: isHouseGridSelected,
  clearSelection: clearHouseSelection,
  toggleSelect: toggleHouseSelection,
  onGridPointerDown: onHouseGridPointerDown,
  onGridPointerMove: onHouseGridPointerMove,
  onGridPointerUp: onHouseGridPointerUp,
  onGridPointerCancel: onHouseGridPointerCancel
} = useGridSelection(gridBodyRef)

/* 筛选变化后清空选区：避免批量删除作用于当前不可见的房屋 */
watch([activeBuildingId, activeUnitId, activeFloor, activeStatus, houseKeyword], () => {
  clearHouseSelection()
})

/* 结构局部刷新后剪除已不存在的房屋 id（悬浮钮单删选中项时保持操作条计数真实） */
watch(houseEntries, (list) => {
  if (selectedHouseIds.value.size === 0) return
  const alive = new Set(list.map((entry) => entry.house.id))
  let changed = false
  const next = new Set<number>()
  selectedHouseIds.value.forEach((id) => {
    if (alive.has(id)) {
      next.add(id)
    } else {
      changed = true
    }
  })
  if (changed) selectedHouseIds.value = next
})

interface GridGroup {
  key: string
  title: string
  /** 分组所属单元（「＋」快速添加房屋的目标单元） */
  unit: IUnit
  /** 单元所属楼栋（列头「批量建房」预选用，避免改动筛选态） */
  buildingId: number
  entries: HouseEntry[]
  /** 当前筛选下的空置套数（列头计数，与 entries.length 同口径） */
  vacant: number
}

const gridGroups = computed<GridGroup[]>(() => {
  const groups = new Map<number, GridGroup>()
  for (const entry of filteredEntries.value) {
    const existing = groups.get(entry.unitId)
    if (existing) {
      existing.entries.push(entry)
      continue
    }
    const unitNode = buildings.value
      .find((building) => building.building.id === entry.buildingId)
      ?.units.find((item) => item.unit.id === entry.unitId)
    if (!unitNode) continue
    groups.set(entry.unitId, {
      key: `u${entry.unitId}`,
      /* 全部楼栋视图下带上楼栋名，避免同名单元在多栋间混淆 */
      title: activeBuildingId.value === null ? `${entry.buildingName} · ${entry.unitName}` : entry.unitName,
      unit: unitNode.unit,
      buildingId: entry.buildingId,
      entries: [entry],
      vacant: 0
    })
  }
  /* 分组内按楼层与房号排序（与结构总览楼层行阅读顺序一致）；
     列头计数在此一次性算好，避免模板里反复 filter */
  return [...groups.values()].map((group) => {
    const entries = [...group.entries].sort(
      (a, b) =>
        b.house.floor - a.house.floor ||
        a.house.houseNumber.localeCompare(b.house.houseNumber, undefined, { numeric: true })
    )
    return {
      ...group,
      entries,
      vacant: entries.filter((entry) => entry.house.status === 'VACANT').length
    }
  })
})

/* ---------------- 缩放（单元平行列的人眼适配：卡片小一点就能多排几列） ---------------- */

const MIN_ZOOM = 0.7
const MAX_ZOOM = 1.4
const ZOOM_STEP = 0.1

const gridZoom = ref(1)

function stepZoom(delta: number): void {
  const next = Math.round((gridZoom.value + delta) * 10) / 10
  gridZoom.value = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next))
}

function resetZoom(): void {
  gridZoom.value = 1
}

const HOUSE_BLOCK_CLASS: Record<HouseStatus, string> = {
  OCCUPIED: 'is-occupied',
  VACANT: 'is-vacant',
  RESERVED: 'is-reserved',
  MAINTENANCE: 'is-maintenance'
}

const LEGEND_ITEMS = (['OCCUPIED', 'VACANT', 'RESERVED', 'MAINTENANCE'] as HouseStatus[]).map(
  (value) => ({ value, label: houseStatusLabels[value] })
)

function houseStatusTagType(status: HouseStatus): 'info' | 'completed' | 'pending' | 'processing' {
  if (status === 'OCCUPIED') return 'completed'
  if (status === 'RESERVED') return 'pending'
  if (status === 'MAINTENANCE') return 'processing'
  return 'info'
}

/* ---------------- 房屋详情卡 / 状态变更 / 变更历史 ---------------- */

const houseInfoVisible = ref(false)
const houseInfoEntry = ref<HouseEntry | null>(null)

const houseHistory = ref<IHouseStatusHistory[]>([])
const houseHistoryLoading = ref(false)
let houseHistorySeq = 0

async function loadHouseHistory(houseId: number): Promise<void> {
  const seq = ++houseHistorySeq
  houseHistoryLoading.value = true
  houseHistory.value = []
  try {
    const result = await getHouseStatusHistory(houseId, { page: 1, size: 20 })
    if (seq !== houseHistorySeq) return
    houseHistory.value = result.records
  } catch {
    if (seq === houseHistorySeq) houseHistory.value = []
  } finally {
    if (seq === houseHistorySeq) houseHistoryLoading.value = false
  }
}

function openHouseInfo(entry: HouseEntry): void {
  houseInfoEntry.value = entry
  houseInfoVisible.value = true
  loadHouseHistory(entry.house.id)
  loadHouseResidents(entry.house.id)
}

/* ---------------- 在住人员 / 租客账号（房屋详情内展示） ----------------
   居住关系接口只给姓名与手机号，登录账号（username）与账号状态在居民档案里，
   故按关系里的 residentId 二次取档案；单条档案失败只让该行账号显示为「-」，
   不影响其余人员与关系本身。 */

const houseResidents = ref<IHouseResident[]>([])
const houseResidentProfiles = ref<Record<number, IResident>>({})
const houseResidentsLoading = ref(false)
const houseResidentsError = ref('')

let houseResidentsSeq = 0

/** 排序：居住中在前，租客先于业主/家属（本区块主要回答「谁住着」） */
const RELATION_ORDER: Record<string, number> = { TENANT: 0, OWNER: 1, FAMILY: 2 }

const sortedHouseResidents = computed(() =>
  [...houseResidents.value].sort(
    (a, b) =>
      (a.status === 'ACTIVE' ? 0 : 1) - (b.status === 'ACTIVE' ? 0 : 1) ||
      (RELATION_ORDER[a.relationType ?? ''] ?? 9) - (RELATION_ORDER[b.relationType ?? ''] ?? 9) ||
      a.residentId - b.residentId
  )
)

const activeResidentCount = computed(
  () => houseResidents.value.filter((item) => item.status === 'ACTIVE').length
)

function residentTypeLabel(type: IHouseResident['relationType']): string {
  return type ? relationTypeLabels[type] : '居住人'
}

async function loadHouseResidents(houseId: number): Promise<void> {
  const seq = ++houseResidentsSeq
  houseResidentsLoading.value = true
  houseResidentsError.value = ''
  houseResidents.value = []
  houseResidentProfiles.value = {}
  try {
    const result = await getHouseResidentList(houseId, { page: 1, size: 20 })
    if (seq !== houseResidentsSeq) return
    houseResidents.value = result.records
    const profiles = await Promise.all(
      result.records.map(async (relation) => {
        try {
          return [relation.residentId, await getResident(relation.residentId)] as const
        } catch {
          return null
        }
      })
    )
    if (seq !== houseResidentsSeq) return
    const map: Record<number, IResident> = {}
    profiles.forEach((item) => {
      if (item) map[item[0]] = item[1]
    })
    houseResidentProfiles.value = map
  } catch (error) {
    if (seq !== houseResidentsSeq) return
    houseResidentsError.value = error instanceof Error ? error.message : '加载在住人员失败'
  } finally {
    if (seq === houseResidentsSeq) houseResidentsLoading.value = false
  }
}

/* 小卡片角标菜单：操作移出卡片内容区，卡片主体保持「点击即进详情」 */
function handleHouseCommand(command: unknown, entry: HouseEntry): void {
  switch (command) {
    case 'info':
      openHouseInfo(entry)
      break
    case 'edit':
      openHouseEdit(entry)
      break
    case 'status':
      openHouseStatus(entry.house)
      break
    case 'delete':
      void handleHouseDelete(entry)
      break
    default:
      break
  }
}

/** 卡片点击分流：Ctrl/Cmd+左键切换多选；普通左键开信息卡；悬浮钮自带 .stop */
function onHouseBlockClick(event: MouseEvent, entry: HouseEntry): void {
  if (event.ctrlKey || event.metaKey) {
    toggleHouseSelection(entry.house.id)
    return
  }
  openHouseInfo(entry)
}

const statusDialogVisible = ref(false)
const statusSubmitting = ref(false)
const statusTarget = ref<IHouse | null>(null)
const statusFormRef = ref<FormInstance>()
const statusForm = reactive<{ status: HouseStatus; remark: string }>({
  status: 'VACANT',
  remark: ''
})

const statusRules: FormRules = {
  status: [{ required: true, message: '请选择房屋状态', trigger: 'change' }]
}

function openHouseStatus(house: IHouse): void {
  statusTarget.value = house
  statusForm.status = house.status
  statusForm.remark = ''
  statusDialogVisible.value = true
}

async function handleHouseStatusSubmit(): Promise<void> {
  const target = statusTarget.value
  if (!target) return
  const valid = await statusFormRef.value?.validate().catch(() => false)
  if (!valid) return
  statusSubmitting.value = true
  try {
    await updateHouseStatus(target.id, {
      status: statusForm.status,
      remark: statusForm.remark || undefined
    })
    ElMessage.success('房屋状态已更新')
    statusDialogVisible.value = false
    reloadUnitHouses(target.unitId)
    refreshSelectedRowStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态变更失败')
  } finally {
    statusSubmitting.value = false
  }
}

function statusFromInfo(): void {
  const entry = houseInfoEntry.value
  if (!entry) return
  houseInfoVisible.value = false
  openHouseStatus(entry.house)
}

function editFromInfo(): void {
  const entry = houseInfoEntry.value
  if (!entry) return
  houseInfoVisible.value = false
  openHouseEdit(entry)
}

function deleteFromInfo(): void {
  const entry = houseInfoEntry.value
  if (!entry) return
  houseInfoVisible.value = false
  void handleHouseDelete(entry)
}

/* ---------------- 房屋增删改 ---------------- */

const houseDialogVisible = ref(false)
const houseEditing = ref<IHouse | null>(null)
const houseUnitContext = ref<{ buildingName: string; unitId: number; unitName: string } | null>(null)

function openHouseCreate(unit: IUnit, buildingName: string): void {
  houseEditing.value = null
  houseUnitContext.value = { buildingName, unitId: unit.id, unitName: unit.name }
  houseDialogVisible.value = true
}

function openHouseEdit(entry: HouseEntry): void {
  houseEditing.value = entry.house
  houseUnitContext.value = {
    buildingName: entry.buildingName,
    unitId: entry.unitId,
    unitName: entry.unitName
  }
  houseDialogVisible.value = true
}

function handleHouseSaved(unitId: number): void {
  reloadUnitHouses(unitId)
  refreshSelectedRowStats()
}

async function handleHouseDelete(entry: HouseEntry): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除房屋「${entry.house.houseNumber}」？若该房屋存在居住/租住关系，删除将被拒绝。`,
      '删除房屋',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteHouse(entry.house.id)
    ElMessage.success('房屋已删除')
    reloadUnitHouses(entry.unitId)
    refreshSelectedRowStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

const batchDeleteRunning = ref(false)

/* 批量删除：一次确认（口径同单删的引用保护提示，带数量）→ 逐条删除（单条失败不中断）
   → 汇报成功/失败明细 → 局部刷新受影响单元 → 选区清空 */
async function handleHouseBatchDelete(): Promise<void> {
  const targets = filteredEntries.value.filter((entry) => selectedHouseIds.value.has(entry.house.id))
  if (targets.length === 0 || batchDeleteRunning.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${targets.length} 套房屋？若房屋存在居住/租住关系，删除将被拒绝。`,
      '批量删除房屋',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  batchDeleteRunning.value = true
  const failed: { houseNumber: string; reason: string }[] = []
  let deleted = 0
  for (const entry of targets) {
    try {
      await deleteHouse(entry.house.id)
      deleted += 1
    } catch (error) {
      failed.push({
        houseNumber: entry.house.houseNumber,
        reason: error instanceof Error ? error.message : '删除失败'
      })
    }
  }
  batchDeleteRunning.value = false
  if (failed.length === 0) {
    ElMessage.success(`已删除 ${deleted} 套房屋`)
  } else {
    ElMessageBox.alert(
      `批量删除完成：成功 ${deleted} 套，失败 ${failed.length} 套。失败房屋：${failed
        .map((item) => `${item.houseNumber}（${item.reason}）`)
        .join('、')}`,
      '批量删除结果',
      { type: 'warning', confirmButtonText: '知道了' }
    ).catch(() => {})
  }
  const affectedUnitIds = [...new Set(targets.map((entry) => entry.unitId))]
  affectedUnitIds.forEach((unitId) => reloadUnitHouses(unitId))
  refreshSelectedRowStats()
  clearHouseSelection()
}

/* ===================== 社区 / 楼栋 / 单元 增删改 ===================== */

const communityDialogVisible = ref(false)
const communityEditing = ref<ICommunity | null>(null)

function openCommunityCreate(): void {
  communityEditing.value = null
  communityDialogVisible.value = true
}

function openCommunityEdit(community: ICommunity): void {
  communityEditing.value = community
  communityDialogVisible.value = true
}

function handleCommunitySaved(entity: ICommunity): void {
  if (selectedCommunityId.value === entity.id) {
    selectedCommunity.value = entity
  } else {
    selectCommunity(entity, true)
  }
  loadCommunities()
}

/** 选中社区概览计数刷新（结构与房屋变动后行概览同步，避免两处数字打架） */
function refreshSelectedRowStats(): void {
  const row = communityRows.value.find((item) => item.community.id === selectedCommunityId.value)
  if (!row) return
  row.statsLoading = true
  const communityId = row.community.id
  getDashboardStats({ communityId })
    .then((stats) => {
      row.buildingCount = stats.buildingCount
      row.houseCount = stats.houseCount
      row.occupiedCount = stats.occupiedHouseCount
    })
    .catch(() => {})
    .finally(() => {
      row.statsLoading = false
    })
}

const buildingDialogVisible = ref(false)
const buildingEditing = ref<IBuilding | null>(null)

function openBuildingCreate(): void {
  buildingEditing.value = null
  buildingDialogVisible.value = true
}

function openBuildingEdit(building: IBuilding): void {
  buildingEditing.value = building
  buildingDialogVisible.value = true
}

function handleBuildingSaved(entity: IBuilding): void {
  selectBuilding(entity.id)
  refreshStructure()
}

/** 已存在楼栋名：批量建楼栋的重名提示用（后端无唯一约束，重名只会静默产出同名楼栋） */
const existingBuildingNames = computed(() => buildings.value.map((node) => node.building.name))

/**
 * 批量建楼栋完成：单栋新建定位到该楼栋（结果当屏可见），
 * 多栋新建回到「全部楼栋」——只聚焦一栋会让其余新楼栋藏在筛选之外，与批量语义相悖。
 */
function handleBuildingsCreated(buildingIds: number[]): void {
  selectBuilding(buildingIds.length === 1 ? buildingIds[0] : null)
  refreshStructure()
}

const unitDialogVisible = ref(false)
const unitEditing = ref<IUnit | null>(null)
const unitDefaultBuildingId = ref<number | null>(null)

function openUnitCreate(buildingId: number): void {
  unitEditing.value = null
  unitDefaultBuildingId.value = buildingId
  unitDialogVisible.value = true
}

function openUnitEdit(unit: IUnit, buildingId: number): void {
  unitEditing.value = unit
  unitDefaultBuildingId.value = buildingId
  unitDialogVisible.value = true
}

function handleUnitSaved(entity: IUnit): void {
  /* 保存后直接把筛选定位到该单元：新建/改名的结果当屏可见 */
  selectBuilding(entity.buildingId)
  selectUnit(entity.id)
  refreshStructure()
}

function refreshStructure(): void {
  if (selectedCommunityId.value === null) return
  loadStructure(selectedCommunityId.value)
  refreshSelectedRowStats()
}

/* ---------------- 单元批量建 / 房屋批量建 ---------------- */

const unitBatchVisible = ref(false)
/** 建单元与房屋的目标楼栋预选（打开前取当前筛选范围，弹窗内仍可改选） */
const unitBatchSubjectId = ref<number | null>(null)

function openUnitBatchCreate(buildingId?: number | null): void {
  unitBatchSubjectId.value = buildingId ?? activeBuildingId.value
  unitBatchVisible.value = true
}

/** 建房带可选楼栋（带单元/房屋规模，选错楼栋一眼可辨） */
const buildingOptions = computed(() =>
  buildings.value.map((node) => ({
    id: node.building.id,
    name: node.building.name,
    units: node.units.length,
    houses: node.units.reduce((sum, unitNode) => sum + unitNode.houses.length, 0)
  }))
)

function handleUnitBatchSaved(buildingId: number): void {
  /* 批量建单元后定位到该楼栋，新单元以平行列直接可见 */
  selectBuilding(buildingId)
  refreshStructure()
}

const houseBatchVisible = ref(false)
/** 批量建房弹窗的预选目标（列头点入时指向该单元，不牵动整页筛选态） */
const houseBatchBuildingId = ref<number | null>(null)
const houseBatchUnitId = ref<number | null>(null)

/** 单元内补建房屋：默认预选当前筛选到的楼栋/单元；从单元列头点入时显式传该单元 */
function openHouseBatchCreate(scope?: { buildingId: number; unitId: number }): void {
  houseBatchBuildingId.value = scope?.buildingId ?? activeBuildingId.value
  houseBatchUnitId.value = scope?.unitId ?? activeUnitId.value
  houseBatchVisible.value = true
}

/* 目标单元可能不在当前筛选内：整体重载结构，避免把当前结果误换成批量目标的数据 */
function handleHouseBatchSaved(_unitId: number): void {
  refreshStructure()
}

const structureGenerateVisible = ref(false)

function openStructureGenerate(): void {
  structureGenerateVisible.value = true
}

function handleStructureGenerated(): void {
  refreshStructure()
}

function handleViewStructure(communityId: number): void {
  focusCommunity(communityId)
}

/* ---------------- 结构维护（既有楼栋/单元的改与删；建房入口在「建房带」） ----------------
   为什么这样分工：新建类动作（楼栋 / 单元与房屋 / 房屋 / 整链）已成组出现在建房带，
   这里只保留**低频的既有结构维护**（编辑、单个新增、删除），避免头部与建房带功能重叠。 */

type StructureAction =
  | 'building-edit'
  | 'building-delete'
  | 'unit-create'
  | 'unit-edit'
  | 'unit-delete'

const STRUCTURE_ACTIONS: Record<
  StructureAction,
  { title: string; needsUnit: boolean; hint: string; confirmText: string }
> = {
  'building-edit': {
    title: '编辑楼栋',
    needsUnit: false,
    hint: '选择要编辑的楼栋，继续后打开楼栋编辑表单（名称 / 总层数 / 描述）。',
    confirmText: '继续编辑'
  },
  'building-delete': {
    title: '删除楼栋',
    needsUnit: false,
    hint: '楼栋下有单元时会级联删除其全部单元与房屋（前端自底向上编排，中途失败不回滚）。',
    confirmText: '继续删除'
  },
  'unit-create': {
    title: '新增单个单元',
    needsUnit: false,
    hint: '选择所属楼栋，继续后自由填写单元名称（批量按序号生成请用「建房 → 建单元与房屋」）。',
    confirmText: '继续新增'
  },
  'unit-edit': {
    title: '编辑单元',
    needsUnit: true,
    hint: '选择楼栋与单元，继续后打开单元编辑表单。',
    confirmText: '继续编辑'
  },
  'unit-delete': {
    title: '删除单元',
    needsUnit: true,
    hint: '单元下存在房屋时删除会被后端拒绝（需先清空该单元房屋）。',
    confirmText: '继续删除'
  }
}

const structureDialogVisible = ref(false)
const structureAction = ref<StructureAction>('building-edit')
const structureBuildingId = ref<number | null>(null)
const structureUnitId = ref<number | null>(null)

const structureActionMeta = computed(() => STRUCTURE_ACTIONS[structureAction.value])

/** 弹窗内单元选项：跟随所选楼栋联动 */
const structureUnitOptions = computed(() => {
  const node = buildings.value.find((item) => item.building.id === structureBuildingId.value)
  return (node?.units ?? []).map((unitNode) => ({ value: unitNode.unit.id, label: unitNode.unit.name }))
})

const structureTargetReady = computed(() => {
  if (structureBuildingId.value === null) return false
  if (!structureActionMeta.value.needsUnit) return true
  return structureUnitId.value !== null
})

/** 打开结构维护弹窗：默认预选当前筛选范围（用户正在看的楼栋/单元），
    未筛选时退化为「第一栋 + 其第一个单元」，少一次手工选择 */
function openStructureAction(action: StructureAction): void {
  structureAction.value = action
  const buildingId = activeBuildingId.value ?? buildings.value[0]?.building.id ?? null
  structureBuildingId.value = buildingId
  const presetUnit =
    activeUnitId.value ??
    buildings.value.find((item) => item.building.id === buildingId)?.units[0]?.unit.id ??
    null
  structureUnitId.value = presetUnit
  structureDialogVisible.value = true
}

/** 「结构维护」菜单：命令与动作同名，直接转目标选择弹窗（新增楼栋在建房带第 1 步） */
function handleStructureMenu(command: unknown): void {
  openStructureAction(command as StructureAction)
}

function handleStructureBuildingChange(): void {
  structureUnitId.value = null
}

function runStructureAction(): void {
  const meta = structureActionMeta.value
  const node = buildings.value.find((item) => item.building.id === structureBuildingId.value) ?? null
  if (!node) {
    ElMessage.warning('请选择楼栋')
    return
  }
  const unitNode = node.units.find((item) => item.unit.id === structureUnitId.value) ?? null
  if (meta.needsUnit && !unitNode) {
    ElMessage.warning('请选择单元')
    return
  }
  structureDialogVisible.value = false
  switch (structureAction.value) {
    case 'building-edit':
      openBuildingEdit(node.building)
      break
    case 'building-delete':
      void handleBuildingDelete(node)
      break
    case 'unit-create':
      openUnitCreate(node.building.id)
      break
    case 'unit-edit':
      if (unitNode) openUnitEdit(unitNode.unit, node.building.id)
      break
    case 'unit-delete':
      if (unitNode) void handleUnitDelete(unitNode.unit)
      break
    default:
      break
  }
}

/* ---------------- 社区删除 / 启用停用 ---------------- */

async function handleCommunityDelete(community: ICommunity): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除社区「${community.name}」将删除该社区全部楼栋/单元/房屋/公共资源/房源及关联业务数据，不可恢复。确定删除？`,
      '删除社区（级联删除）',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteCommunity(community.id)
    ElMessage.success('社区已删除')
    if (selectedCommunityId.value === community.id) clearDetail()
    loadCommunities()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

async function handleCommunityToggle(community: ICommunity): Promise<void> {
  const next: CommunityStatus = community.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  const action = next === 'ACTIVE' ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定${action}社区「${community.name}」？${next === 'INACTIVE' ? '停用后该社区相关业务将不可用。' : ''}`,
      `${action}社区`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateCommunityStatus(community.id, { status: next })
    ElMessage.success(`社区已${action}`)
    loadCommunities()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${action}失败`)
  }
}

/* ===================== 批量管理社区（仅超管） ===================== */

const batchSelection = ref<Set<number>>(new Set())
const batchRunning = ref(false)

/** 单次批量上限（后端约束：ids 非空且最多 50 个，越界直接拒绝） */
const MAX_BATCH = 50

const batchSelectedCount = computed(() => batchSelection.value.size)

/** 跨页累计选择可能超过后端单次上限：提交前拦住并明示原因 */
function batchOverLimit(ids: number[]): boolean {
  if (ids.length > MAX_BATCH) {
    ElMessage.error(`单次最多批量处理 ${MAX_BATCH} 个社区（当前已选 ${ids.length} 个），请减少选择后重试`)
    return true
  }
  return false
}

function isBatchSelected(communityId: number): boolean {
  return batchSelection.value.has(communityId)
}

function toggleBatchSelection(communityId: number): void {
  const next = new Set(batchSelection.value)
  if (next.has(communityId)) {
    next.delete(communityId)
  } else {
    next.add(communityId)
  }
  batchSelection.value = next
}

const pageAllSelected = computed(
  () => communityRows.value.length > 0 && communityRows.value.every((row) => isBatchSelected(row.community.id))
)

function togglePageSelection(): void {
  const next = new Set(batchSelection.value)
  if (pageAllSelected.value) {
    communityRows.value.forEach((row) => next.delete(row.community.id))
  } else {
    communityRows.value.forEach((row) => next.add(row.community.id))
  }
  batchSelection.value = next
}

function clearBatchSelection(): void {
  batchSelection.value = new Set()
}

/* 后端批量端点按「部分成功」返回：逐条原因必须落到用户可见的清单，不能只报成功/失败 */
const batchResultVisible = ref(false)
const batchResult = reactive({
  successCount: 0,
  successText: '',
  intro: '',
  warning: '',
  failures: [] as IBatchFailure[]
})

/** 失败项只带 ID 时补社区名，让「哪个失败了」无需另行查号 */
function resolveFailures(failures: IBatchFailure[]): IBatchFailure[] {
  return failures.map((item) => {
    if (item.name) return item
    const row = communityRows.value.find((entry) => entry.community.id === item.id)
    return row ? { ...item, name: row.community.name } : item
  })
}

function showBatchResult(payload: {
  successCount: number
  successText: string
  intro: string
  warning?: string
  failures: IBatchFailure[]
}): void {
  batchResult.successCount = payload.successCount
  batchResult.successText = payload.successText
  batchResult.intro = payload.intro
  batchResult.warning = payload.warning ?? ''
  batchResult.failures = resolveFailures(payload.failures)
  batchResultVisible.value = true
}

const batchSelectedNames = computed(() =>
  communityRows.value.filter((row) => isBatchSelected(row.community.id)).map((row) => row.community.name)
)

async function handleBatchStatus(status: CommunityStatus): Promise<void> {
  const ids = [...batchSelection.value]
  if (ids.length === 0 || batchRunning.value) return
  if (batchOverLimit(ids)) return
  const action = status === 'ACTIVE' ? '批量启用' : '批量停用'
  try {
    await ElMessageBox.confirm(
      `将${action}选中的 ${ids.length} 个社区${
        batchSelectedNames.value.length > 0 ? `：${batchSelectedNames.value.join('、')}` : ''
      }？${status === 'INACTIVE' ? '停用后这些社区的相关业务将不可用。' : ''}`,
      `${action}社区`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  batchRunning.value = true
  try {
    const result = await batchUpdateCommunityStatus(ids, status)
    showBatchResult({
      successCount: result.successIds.length,
      successText: `个社区已${status === 'ACTIVE' ? '启用' : '停用'}`,
      intro: `${action}：已提交 ${ids.length} 个社区。`,
      failures: result.failures
    })
    clearBatchSelection()
    loadCommunities()
    if (selectedCommunity.value && ids.includes(selectedCommunity.value.id)) {
      selectedCommunity.value = { ...selectedCommunity.value, status }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${action}失败`)
  } finally {
    batchRunning.value = false
  }
}

/* 批量删除是级联删除：两步确认，第二步写明不可恢复（数据面无回退路径） */
async function handleBatchDelete(): Promise<void> {
  const ids = [...batchSelection.value]
  if (ids.length === 0 || batchRunning.value) return
  if (batchOverLimit(ids)) return
  try {
    await ElMessageBox.confirm(
      `将【级联删除】选中的 ${ids.length} 个社区${
        batchSelectedNames.value.length > 0 ? `：${batchSelectedNames.value.join('、')}` : ''
      }。这会一并删除这些社区下的全部楼栋、单元、房屋、公共资源、房源及关联业务数据。`,
      '批量删除社区（级联删除）',
      { type: 'error', confirmButtonText: '我已了解，继续', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await ElMessageBox.confirm(
      `最终确认：这 ${ids.length} 个社区及其全部下级数据将被永久删除，不可恢复、不可撤销。确定执行？`,
      '不可恢复操作',
      { type: 'error', confirmButtonText: '确认级联删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  batchRunning.value = true
  try {
    const result = await batchDeleteCommunities(ids)
    showBatchResult({
      successCount: result.successIds.length,
      successText: '个社区已级联删除',
      intro: `批量删除：已提交 ${ids.length} 个社区。`,
      warning: '级联删除不可恢复；未完成的社区及其下级数据仍完整保留，可按失败原因处理后重试。',
      failures: result.failures
    })
    if (selectedCommunityId.value !== null && ids.includes(selectedCommunityId.value)) {
      clearDetail()
    }
    clearBatchSelection()
    loadCommunities()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量删除失败')
  } finally {
    batchRunning.value = false
  }
}

/* ===================== 楼栋删除（空楼栋直删 + 非空前端编排级联） =====================

   后端现状（BuildingService.delete）：无单元直接软删成功，有单元即拒（5102），
   无事务性级联端点。非空楼栋由前端自底向上编排：逐单元删房屋 → 删单元 → 最后删楼栋。
   该编排不是后端事务：任一步失败即停止并汇报剩余明细，已删部分不可自动回滚。 */

type BuildingDeletePhase = 'loading' | 'error' | 'confirm' | 'running' | 'done'

const buildingDeleteVisible = ref(false)
const buildingDeletePhase = ref<BuildingDeletePhase>('loading')
const buildingDeleteNode = ref<BuildingNode | null>(null)

interface BuildingCascadeGroup {
  unit: IUnit
  houses: IHouse[]
}

const buildingCascadePlan = ref<BuildingCascadeGroup[]>([])
const buildingCascadeError = ref('')
const buildingCascadeCompleted = ref(0)
const buildingCascadeTotal = ref(0)
const buildingCascadeFailure = ref<{ unitIndex: number; houseIndex: number; reason: string } | null>(
  null
)

let cascadeSeq = 0

const buildingCascadeHouseCount = computed(() =>
  buildingCascadePlan.value.reduce((sum, group) => sum + group.houses.length, 0)
)

const buildingCascadePercent = computed(() =>
  buildingCascadeTotal.value === 0
    ? 0
    : Math.round((buildingCascadeCompleted.value / buildingCascadeTotal.value) * 100)
)

const buildingCascadeDeletedUnits = computed(() => buildingCascadeFailure.value?.unitIndex ?? 0)

const buildingCascadeDeletedHouses = computed(() => {
  const fail = buildingCascadeFailure.value
  if (!fail) return 0
  let count = fail.houseIndex
  for (let ui = 0; ui < fail.unitIndex; ui++) {
    count += buildingCascadePlan.value[ui]?.houses.length ?? 0
  }
  return count
})

const buildingCascadeRemainingHouses = computed<BuildingCascadeGroup[]>(() => {
  const fail = buildingCascadeFailure.value
  if (!fail) return []
  const groups: BuildingCascadeGroup[] = []
  for (let ui = fail.unitIndex; ui < buildingCascadePlan.value.length; ui++) {
    const group = buildingCascadePlan.value[ui]
    const houses = group.houses.slice(ui === fail.unitIndex ? fail.houseIndex : 0)
    if (houses.length > 0) groups.push({ unit: group.unit, houses })
  }
  return groups
})

const buildingCascadeRemainingUnits = computed(() =>
  buildingCascadeFailure.value
    ? buildingCascadePlan.value.slice(buildingCascadeFailure.value.unitIndex).map((g) => g.unit)
    : []
)

function buildingCascadeErrorText(error: unknown): string {
  return error instanceof Error ? error.message : '删除失败'
}

async function handleBuildingDelete(node: BuildingNode): Promise<void> {
  if (node.units.length === 0) {
    try {
      await ElMessageBox.confirm(
        `该楼栋为空（无单元），将直接删除楼栋「${node.building.name}」，删除后不可恢复。确定删除？`,
        '删除楼栋',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await deleteBuilding(node.building.id)
      ElMessage.success('楼栋已删除')
      refreshStructure()
    } catch (error) {
      ElMessage.error(buildingCascadeErrorText(error))
    }
    return
  }
  buildingDeleteNode.value = node
  buildingCascadeFailure.value = null
  buildingCascadeCompleted.value = 0
  buildingDeleteVisible.value = true
  await prepareBuildingCascade(node)
}

/** 范围统计：逐单元拉齐房屋清单（与结构加载同口径），作为展示与执行计划 */
async function prepareBuildingCascade(node: BuildingNode): Promise<void> {
  const seq = ++cascadeSeq
  buildingDeletePhase.value = 'loading'
  buildingCascadeError.value = ''
  try {
    const plan = await Promise.all(
      node.units.map(async (unitNode) => ({
        unit: unitNode.unit,
        houses: (await getHouseList(unitNode.unit.id, { page: 1, size: 200 })).records
      }))
    )
    if (seq !== cascadeSeq) return
    buildingCascadePlan.value = plan
    buildingCascadeTotal.value =
      plan.reduce((sum, group) => sum + group.houses.length, 0) + plan.length + 1
    buildingDeletePhase.value = 'confirm'
  } catch (error) {
    if (seq !== cascadeSeq) return
    buildingCascadeError.value = buildingCascadeErrorText(error)
    buildingDeletePhase.value = 'error'
  }
}

function retryBuildingCascade(): void {
  const node = buildingDeleteNode.value
  if (node) void prepareBuildingCascade(node)
}

function handleBuildingDeleteClose(): void {
  cascadeSeq += 1
}

async function runBuildingCascade(): Promise<void> {
  const node = buildingDeleteNode.value
  if (!node || buildingDeletePhase.value === 'running') return
  buildingDeletePhase.value = 'running'
  buildingCascadeCompleted.value = 0
  buildingCascadeFailure.value = null

  const plan = buildingCascadePlan.value
  let fail: { unitIndex: number; houseIndex: number; reason: string } | null = null

  for (let ui = 0; ui < plan.length && !fail; ui++) {
    const group = plan[ui]
    for (let hi = 0; hi < group.houses.length; hi++) {
      try {
        await deleteHouse(group.houses[hi].id)
        buildingCascadeCompleted.value += 1
      } catch (error) {
        fail = { unitIndex: ui, houseIndex: hi, reason: buildingCascadeErrorText(error) }
        break
      }
    }
    if (fail) break
    try {
      await deleteUnit(group.unit.id)
      buildingCascadeCompleted.value += 1
    } catch (error) {
      fail = {
        unitIndex: ui,
        houseIndex: group.houses.length,
        reason: buildingCascadeErrorText(error)
      }
    }
  }

  if (!fail) {
    try {
      await deleteBuilding(node.building.id)
      buildingCascadeCompleted.value += 1
    } catch (error) {
      fail = { unitIndex: plan.length, houseIndex: 0, reason: buildingCascadeErrorText(error) }
    }
  }

  refreshStructure()

  if (fail) {
    buildingCascadeFailure.value = fail
    buildingDeletePhase.value = 'done'
    ElMessage.error('级联删除中断，剩余明细见对话框')
    return
  }
  buildingDeleteVisible.value = false
  ElMessage.success(
    `楼栋「${node.building.name}」已级联删除（${plan.length} 个单元、${buildingCascadeHouseCount.value} 套房屋）`
  )
}

async function handleUnitDelete(unit: IUnit): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除单元「${unit.name}」？若该单元下存在房屋，删除将被拒绝。`,
      '删除单元',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteUnit(unit.id)
    ElMessage.success('单元已删除')
    refreshStructure()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* ===================== 社区设置入口（隐藏下钻页） ===================== */

function goCommunitySettings(): void {
  if (selectedCommunity.value) {
    router.push(`/admin/communities/${selectedCommunity.value.id}`)
  }
}

/* ===================== 初始化 ===================== */

onMounted(async () => {
  await loadCommunities()
  /* 旧深链（/admin/houses?communityId=x 等 redirect 承接）与详情页「前往社区结构」在此消费 */
  const deepLink = Number(route.query.communityId)
  if (Number.isFinite(deepLink) && deepLink > 0) {
    focusCommunity(deepLink)
  }
})
</script>

<template>
  <section class="manage">
    <!-- ==================== 左栏：社区列表（筛选 → 分页 → 选中） ==================== -->
    <aside class="rail">
      <header class="rail-head">
        <div class="rail-title-line">
          <h3 class="rail-title">社区</h3>
          <span class="rail-total">共 {{ listTotal }} 个</span>
        </div>
        <el-button
          v-permission="['SUPER_ADMIN']"
          type="primary"
          size="small"
          @click="openCommunityCreate"
        >
          新增社区
        </el-button>
      </header>

      <div class="rail-filters">
        <el-input
          v-model="query.keyword"
          size="small"
          placeholder="搜索社区名称 / 地址"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <svg
              class="field-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path v-for="(d, i) in ICONS.search" :key="i" :d="d" />
            </svg>
          </template>
        </el-input>
        <div class="status-chips" role="group" aria-label="按状态筛选社区">
          <button
            v-for="item in statusFilters"
            :key="item.value"
            type="button"
            class="chip-btn"
            :class="{ active: query.status === item.value }"
            @click="handleStatusFilter(item.value)"
          >
            {{ item.label }}
          </button>
        </div>
      </div>

      <!-- 批量操作条（仅超管）：多选后浮出，动作全部按「部分成功」汇报 -->
      <div v-if="isSuperAdmin" class="batch-bar" :class="{ 'is-active': batchSelectedCount > 0 }">
        <template v-if="batchSelectedCount > 0">
          <span class="batch-count">已选 {{ batchSelectedCount }} 个</span>
          <div class="batch-actions">
            <button type="button" class="text-btn" :disabled="batchRunning" @click="handleBatchStatus('ACTIVE')">
              批量启用
            </button>
            <button type="button" class="text-btn" :disabled="batchRunning" @click="handleBatchStatus('INACTIVE')">
              批量停用
            </button>
            <button
              type="button"
              class="text-btn is-danger"
              :disabled="batchRunning"
              @click="handleBatchDelete"
            >
              批量删除
            </button>
            <button type="button" class="text-btn is-muted" @click="clearBatchSelection">取消选择</button>
          </div>
        </template>
        <template v-else>
          <el-checkbox
            :model-value="pageAllSelected"
            :indeterminate="batchSelectedCount > 0 && !pageAllSelected"
            :disabled="communityRows.length === 0"
            size="small"
            @change="togglePageSelection"
          >
            全选本页
          </el-checkbox>
          <span class="batch-tip">勾选社区后可批量启用 / 停用 / 删除</span>
        </template>
      </div>

      <div class="rail-body">
        <div v-if="listLoading" class="rail-skeleton">
          <el-skeleton :rows="5" animated />
        </div>

        <div v-else-if="listError" class="rail-state">
          <p class="state-title">社区列表加载失败</p>
          <p class="state-text">{{ listError }}</p>
          <el-button size="small" @click="loadCommunities">重试</el-button>
        </div>

        <div v-else-if="communityRows.length === 0" class="rail-state">
          <template v-if="filtered">
            <p class="state-title">没有匹配的社区</p>
            <p class="state-text">换个关键字，或把状态筛选切回「全部」。</p>
            <el-button size="small" @click="resetListQuery">清空筛选</el-button>
          </template>
          <template v-else>
            <p class="state-title">还没有社区</p>
            <p class="state-text">
              先新增一个社区，再进入「批量建房」一次生成楼栋、单元与房屋。
            </p>
            <el-button v-permission="['SUPER_ADMIN']" type="primary" size="small" @click="openCommunityCreate">
              新增社区
            </el-button>
          </template>
        </div>

        <ul v-else class="rail-list">
          <li v-for="row in communityRows" :key="row.community.id">
            <div
              class="community-item"
              :class="{ 'is-active': row.community.id === selectedCommunityId }"
            >
              <el-checkbox
                v-if="isSuperAdmin"
                class="item-check"
                :model-value="isBatchSelected(row.community.id)"
                @change="toggleBatchSelection(row.community.id)"
                @click.stop
              />
              <button type="button" class="item-main" @click="selectCommunity(row.community)">
                <span class="item-line">
                  <span class="item-name">{{ row.community.name }}</span>
                  <StatusTag
                    :label="communityStatusLabels[row.community.status]"
                    :type="row.community.status === 'ACTIVE' ? 'completed' : 'canceled'"
                  />
                </span>
                <span class="item-meta">
                  <template v-if="row.statsLoading">统计中…</template>
                  <template v-else>
                    {{ row.buildingCount }} 栋 · {{ row.houseCount }} 套 · 入住率
                    {{ row.houseCount === 0 ? '-' : `${Math.round((row.occupiedCount / row.houseCount) * 100)}%` }}
                  </template>
                </span>
                <span class="item-address">{{ row.community.address }}</span>
              </button>
            </div>
          </li>
        </ul>
      </div>

      <Pagination
        v-if="listTotal > query.size"
        :page="query.page"
        :size="query.size"
        :total="listTotal"
        layout="prev, pager, next"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      />
    </aside>

    <!-- ==================== 右栏：选中社区详情 ==================== -->
    <main class="detail">
      <article v-if="!selectedCommunity" class="panel empty-panel">
        <el-empty
          :description="
            communityRows.length === 0
              ? '还没有社区数据。新增社区后即可在这里管理楼栋、单元与房屋。'
              : '从左侧选择一个社区，查看其楼栋、单元与房屋结构。'
          "
        />
      </article>

      <template v-else>
        <!-- 社区头部：状态与联系方式 + 关键数字（视觉重心） -->
        <article class="panel head-card">
          <header class="head-top">
            <div class="head-identity">
              <h2 class="head-name">
                {{ selectedCommunity.name }}
                <StatusTag
                  :label="communityStatusLabels[selectedCommunity.status]"
                  :type="selectedCommunity.status === 'ACTIVE' ? 'completed' : 'canceled'"
                />
              </h2>
              <p class="head-meta">
                <span>{{ selectedCommunity.address }}</span>
                <span v-if="selectedCommunity.contactPerson">联系人 {{ selectedCommunity.contactPerson }}</span>
                <span v-if="selectedCommunity.contactPhone">{{ selectedCommunity.contactPhone }}</span>
                <span>
                  入住申请
                  {{ selectedCommunity.autoApproveResidence ? `提交即通过（默认 ${selectedCommunity.defaultLeaseMonths ?? 12} 个月）` : '人工审核' }}
                </span>
              </p>
            </div>
            <div class="head-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                @click="openCommunityEdit(selectedCommunity)"
              >
                编辑社区
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                :type="selectedCommunity.status === 'ACTIVE' ? 'danger' : 'success'"
                plain
                @click="handleCommunityToggle(selectedCommunity)"
              >
                {{ selectedCommunity.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
              <el-button
                v-permission="['SUPER_ADMIN']"
                size="small"
                type="danger"
                plain
                @click="handleCommunityDelete(selectedCommunity)"
              >
                删除
              </el-button>
              <el-button link type="primary" size="small" @click="goCommunitySettings">
                社区设置
              </el-button>
            </div>
          </header>

          <!-- 建房带：一个总入口（一次建成整条结构链）+ 三个按层级递进的入口，
               社区级操作留在上方头部，两类动作分区呈现而不是混排成一片按钮 -->
          <section class="build-bar" aria-label="建房入口">
            <span class="build-bar-title">
              <span class="panel-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path v-for="(d, i) in ACTION_ICONS.layers" :key="i" :d="d" />
                </svg>
              </span>
              建房
            </span>

            <el-tooltip
              content="按楼栋序号区间一次建成 楼栋 → 单元 → 房屋 整条结构链（先预览后落库）"
              placement="bottom"
            >
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                type="primary"
                class="build-main"
                @click="openStructureGenerate"
              >
                一键生成结构链
                <span class="build-main-sub">楼栋 → 单元 → 房屋</span>
              </el-button>
            </el-tooltip>

            <span class="build-sep" aria-hidden="true">或按层级分步建</span>

            <div class="build-steps" role="group" aria-label="按层级分步建房">
              <el-tooltip
                content="在指定社区批量建楼栋：按名称前缀与序号区间一次建多栋，并可同批建立每栋的单元与楼层房屋"
                placement="bottom"
              >
                <el-button
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  size="small"
                  class="build-step"
                  @click="openBuildingCreate"
                >
                  <span class="step-no">1</span>建楼栋
                </el-button>
              </el-tooltip>
              <el-tooltip
                content="在指定楼栋按序号批量建单元，并可同时生成各单元的 楼层 × 每层户数 房屋"
                placement="bottom"
              >
                <el-button
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  size="small"
                  class="build-step"
                  @click="openUnitBatchCreate()"
                >
                  <span class="step-no">2</span>建单元与房屋
                </el-button>
              </el-tooltip>
              <el-tooltip
                content="在指定单元按楼层区间与每层户数批量建房屋（可只建某几层）"
                placement="bottom"
              >
                <el-button
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  size="small"
                  class="build-step"
                  @click="openHouseBatchCreate()"
                >
                  <span class="step-no">3</span>建房屋
                </el-button>
              </el-tooltip>
            </div>

            <!-- 既有结构的改/删属低频动作，收在一个菜单里，不占建房带的视觉重量 -->
            <el-dropdown
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              class="build-maintenance"
              trigger="click"
              @command="handleStructureMenu"
            >
              <el-button size="small" text class="structure-menu-trigger">
                结构维护
                <svg class="caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M6 9l6 6 6-6" />
                </svg>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="building-edit">编辑楼栋</el-dropdown-item>
                  <el-dropdown-item command="unit-edit">编辑单元</el-dropdown-item>
                  <el-dropdown-item command="unit-create" divided>新增单个单元</el-dropdown-item>
                  <el-dropdown-item command="building-delete" divided>删除楼栋（级联）</el-dropdown-item>
                  <el-dropdown-item command="unit-delete">删除单元</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </section>

          <div v-if="structureLoading && !structureLoaded" class="metric-strip">
            <el-skeleton :rows="1" animated />
          </div>
          <div v-else class="metric-strip">
            <div class="metric-cell">
              <b>{{ communityMetrics.buildings }}</b>
              <span>栋楼栋</span>
            </div>
            <div class="metric-cell">
              <b>{{ communityMetrics.units }}</b>
              <span>个单元</span>
            </div>
            <div class="metric-cell">
              <b>{{ communityMetrics.houses }}</b>
              <span>套房屋</span>
            </div>
            <div class="metric-cell">
              <b>{{ communityMetrics.vacant }}</b>
              <span>套空置</span>
            </div>
            <div class="metric-cell">
              <b>{{ communityMetrics.occupancy === null ? '-' : communityMetrics.occupancy.toFixed(1) }}</b>
              <span>% 入住率</span>
              <i
                v-if="communityMetrics.occupancy !== null"
                class="occupancy-donut"
                :style="{ '--pct': communityMetrics.occupancy }"
                :title="`已入住房屋占全部房屋的比例`"
              />
            </div>
          </div>
        </article>

        <!-- 房屋结果区：全页唯一筛选区（范围 → 条件 → 结果），单元平行成列 -->
        <article class="panel houses-panel">
          <header class="panel-head">
            <h3 class="panel-title">
              <span class="panel-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path v-for="(d, i) in ICONS.home" :key="i" :d="d" />
                </svg>
              </span>
              房屋
              <span v-if="!structureLoading" class="panel-count">{{ houseEntries.length }} 套</span>
            </h3>
            <!-- 缩放：单元以平行列排布，缩小一点即可多排几列（1.0 为原始尺寸） -->
            <div class="panel-actions">
              <div class="zoom-control" role="group" aria-label="房屋卡片缩放">
                <button
                  type="button"
                  class="zoom-btn"
                  aria-label="缩小"
                  :disabled="gridZoom <= MIN_ZOOM"
                  @click="stepZoom(-ZOOM_STEP)"
                >
                  −
                </button>
                <button
                  type="button"
                  class="zoom-value"
                  :title="`点击恢复 100%（当前 ${Math.round(gridZoom * 100)}%）`"
                  @click="resetZoom"
                >
                  {{ Math.round(gridZoom * 100) }}%
                </button>
                <button
                  type="button"
                  class="zoom-btn"
                  aria-label="放大"
                  :disabled="gridZoom >= MAX_ZOOM"
                  @click="stepZoom(ZOOM_STEP)"
                >
                  ＋
                </button>
              </div>
            </div>
          </header>

          <!-- 筛选区：范围（楼栋/单元/房号）＋ 条件（楼层/状态）集中一处 -->
          <section class="filter-panel" aria-label="房屋筛选">
            <div class="filter-head">
              <span class="filter-title">筛选</span>
              <span v-if="activeBuildingId !== null" class="filter-scope-chip">
                {{ activeBuildingName }}<template v-if="activeUnitId !== null"> / {{ activeUnitName }}</template>
              </span>
              <span class="filter-result">
                共 {{ scopeSummary.total }} 套 · 已入住 {{ scopeSummary.occupied }} · 空置
                {{ scopeSummary.vacant }}
                <template v-if="!structureLoading"> · 全部 {{ houseEntries.length }} 套</template>
              </span>
              <el-button
                v-if="filtersActive"
                size="small"
                link
                type="primary"
                class="filter-reset"
                @click="clearAllFilters"
              >
                清除筛选
              </el-button>
            </div>

            <div class="filter-line">
              <span class="filter-label">范围</span>
              <el-select
                :model-value="activeBuildingId"
                class="filter-select"
                size="small"
                placeholder="全部楼栋"
                clearable
                @change="handleScopeBuilding"
              >
                <el-option
                  v-for="node in buildings"
                  :key="node.building.id"
                  :label="node.building.name"
                  :value="node.building.id"
                />
              </el-select>
              <el-select
                :model-value="activeUnitId"
                class="filter-select"
                size="small"
                placeholder="全部单元"
                clearable
                :disabled="scopeUnitOptions.length === 0"
                @change="handleScopeUnit"
              >
                <el-option
                  v-for="item in scopeUnitOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <el-input
                v-model="houseKeyword"
                class="filter-keyword"
                size="small"
                placeholder="搜索房号，如 101 / 12"
                clearable
              />
            </div>

            <div class="filter-line">
              <span class="filter-label">楼层</span>
              <div v-if="floorOptions.length > 0" class="chips" role="group" aria-label="按楼层筛选">
                <button
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeFloor === null }"
                  @click="activeFloor = null"
                >
                  全部楼层
                </button>
                <button
                  v-for="floor in floorOptions"
                  :key="floor"
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeFloor === floor }"
                  @click="activeFloor = activeFloor === floor ? null : floor"
                >
                  {{ floor }}层
                </button>
              </div>
              <span v-else class="filter-empty">当前范围暂无楼层</span>
            </div>

            <div class="filter-line">
              <span class="filter-label">状态</span>
              <div class="chips" role="group" aria-label="按房屋状态筛选">
                <button
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeStatus === null }"
                  @click="activeStatus = null"
                >
                  全部状态
                </button>
                <button
                  v-for="item in statusOptions"
                  :key="item.value"
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeStatus === item.value }"
                  @click="activeStatus = activeStatus === item.value ? null : item.value"
                >
                  {{ item.label }}
                  <span class="chip-count">{{ item.count }}</span>
                </button>
              </div>
            </div>
          </section>

          <div
            ref="gridBodyRef"
            class="grid-body"
            :class="{ 'is-marquee': gridMarquee.active }"
            @pointerdown="onHouseGridPointerDown"
            @pointermove="onHouseGridPointerMove"
            @pointerup="onHouseGridPointerUp"
            @pointercancel="onHouseGridPointerCancel"
          >
            <div v-if="structureLoading && !structureLoaded" class="grid-skeleton">
              <el-skeleton :rows="4" animated />
            </div>

            <!-- 结构加载失败：不落在「还没有房屋」的空态里（否则误导为"没数据"） -->
            <div v-else-if="structureError" class="grid-state">
              <p class="state-title">结构与房屋加载失败</p>
              <p class="state-text">{{ structureError }}</p>
              <el-button size="small" @click="retryStructure">重试</el-button>
            </div>

            <div v-else-if="gridGroups.length === 0" class="grid-state">
              <template v-if="houseEntries.length === 0">
                <p class="state-title">该社区还没有房屋</p>
                <p class="state-text">
                  用上方「批量建房」一次生成 楼栋 → 单元 → 房屋；也可在单元列头点「＋」单独添加。
                </p>
              </template>
              <template v-else>
                <p class="state-title">当前筛选下没有房屋</p>
                <p class="state-text">放宽楼层 / 状态筛选，或清空房号关键字后再看。</p>
                <el-button size="small" @click="clearAllFilters">清除筛选</el-button>
              </template>
            </div>

            <!-- 单元平行列：一个单元一列，列多时横向滚动；缩放作用在此层，
                 框选矩形仍定位在未缩放的 .grid-body 上，选中坐标不受影响 -->
            <div v-else class="grid-groups" :style="{ zoom: gridZoom }">
              <div v-for="group in gridGroups" :key="group.key" class="grid-group">
                <h4 class="grid-group-title">
                  <span class="grid-group-name" :title="group.title">{{ group.title }}</span>
                  <span class="grid-group-count">{{ group.entries.length }} 套 · 空 {{ group.vacant }}</span>
                  <span class="grid-group-actions">
                    <button
                      v-permission="['ADMIN', 'SUPER_ADMIN']"
                      type="button"
                      class="icon-btn"
                      title="添加房屋"
                      :aria-label="`在${group.title}添加房屋`"
                      @click="openHouseCreate(group.unit, activeBuildingName || selectedCommunity?.name || '')"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path v-for="(d, i) in ACTION_ICONS.plus" :key="i" :d="d" />
                      </svg>
                    </button>
                    <button
                      v-permission="['ADMIN', 'SUPER_ADMIN']"
                      type="button"
                      class="icon-btn"
                      title="该单元批量建房"
                      :aria-label="`在${group.title}批量建房`"
                      @click="openHouseBatchCreate({ buildingId: group.buildingId, unitId: group.unit.id })"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path v-for="(d, i) in ACTION_ICONS.layers" :key="i" :d="d" />
                      </svg>
                    </button>
                  </span>
                </h4>
                <div class="grid-rows">
                  <div
                    v-for="floor in [...new Set(group.entries.map((entry) => entry.house.floor))].sort((a, b) => b - a)"
                    :key="floor"
                    class="grid-row"
                  >
                    <span class="grid-floor">{{ floor }}层</span>
                    <div class="grid-cells">
                      <div
                        v-for="entry in group.entries.filter((item) => item.house.floor === floor)"
                        :key="entry.house.id"
                        class="grid-block"
                        :class="[
                          HOUSE_BLOCK_CLASS[entry.house.status],
                          { 'is-selected': isHouseGridSelected(entry.house.id) }
                        ]"
                        role="button"
                        tabindex="0"
                        :data-house-id="entry.house.id"
                        :title="`${entry.house.houseNumber} · ${houseStatusLabels[entry.house.status]}`"
                        @click="onHouseBlockClick($event, entry)"
                        @keydown.enter.prevent="openHouseInfo(entry)"
                        @keydown.space.prevent="openHouseInfo(entry)"
                      >
                        <span class="block-number">{{ entry.house.houseNumber }}</span>
                        <span v-if="isHouseGridSelected(entry.house.id)" class="block-check" aria-hidden="true">
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M5 12.5l4.5 4.5L19 7.5" />
                          </svg>
                        </span>
                        <!-- 操作区不入卡片内容区：角标「⋯」展开菜单，卡片主体永远可点 -->
                        <el-dropdown
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          class="block-menu"
                          trigger="click"
                          placement="bottom-end"
                          @command="handleHouseCommand($event, entry)"
                        >
                          <button
                            type="button"
                            class="block-menu-btn"
                            :aria-label="`${entry.house.houseNumber} 更多操作`"
                            @click.stop
                            @keydown.enter.stop
                            @keydown.space.stop.prevent
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                              <path v-for="(d, i) in ACTION_ICONS.more" :key="i" :d="d" />
                            </svg>
                          </button>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item command="info">
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.eye" :key="i" :d="d" />
                                </svg>
                                查看详情 / 在住人员
                              </el-dropdown-item>
                              <el-dropdown-item command="edit" divided>
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                                </svg>
                                编辑房屋
                              </el-dropdown-item>
                              <el-dropdown-item command="status">
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.swap" :key="i" :d="d" />
                                </svg>
                                状态变更
                              </el-dropdown-item>
                              <el-dropdown-item command="delete">
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                                </svg>
                                删除房屋
                              </el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div
              v-if="gridMarquee.active"
              class="marquee-rect"
              :style="{
                left: `${gridMarquee.left}px`,
                top: `${gridMarquee.top}px`,
                width: `${gridMarquee.width}px`,
                height: `${gridMarquee.height}px`
              }"
              aria-hidden="true"
            />
          </div>

          <ul class="grid-legend" aria-label="房屋状态图例">
            <li v-for="item in LEGEND_ITEMS" :key="item.value">
              <i class="legend-dot" :class="HOUSE_BLOCK_CLASS[item.value]" />
              {{ item.label }}
            </li>
            <li class="legend-note">点卡片看详情 · 右上角「⋯」为操作 · Ctrl / Cmd + 点击 或 在空白处拖拽可多选</li>
          </ul>

          <transition name="sel-bar">
            <div v-if="selectedHouseCount > 0" class="selection-bar" role="toolbar" aria-label="房屋批量操作">
              <span class="selection-count">已选 {{ selectedHouseCount }} 套</span>
              <span class="selection-actions">
                <el-button
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  size="small"
                  type="danger"
                  :loading="batchDeleteRunning"
                  @click="handleHouseBatchDelete"
                >
                  批量删除
                </el-button>
                <el-button size="small" @click="clearHouseSelection">取消选择</el-button>
              </span>
            </div>
          </transition>
        </article>
      </template>
    </main>

    <!-- ==================== 对话框 ==================== -->

    <CommunityEditDialog
      v-model="communityDialogVisible"
      :community="communityEditing"
      @saved="handleCommunitySaved"
    />

    <BuildingEditDialog
      v-model="buildingDialogVisible"
      :communities="selectedCommunity ? [selectedCommunity] : []"
      :building="buildingEditing"
      :default-community-id="selectedCommunityId"
      :existing-names="existingBuildingNames"
      @saved="handleBuildingSaved"
      @created="handleBuildingsCreated"
      @result="showBatchResult"
    />

    <UnitEditDialog
      v-model="unitDialogVisible"
      :communities="selectedCommunity ? [selectedCommunity] : []"
      :unit="unitEditing"
      :default-community-id="selectedCommunityId"
      :default-building-id="unitDefaultBuildingId"
      @saved="handleUnitSaved"
    />

    <!-- 建单元与房屋（建房带第 ② 级）：既有楼栋下批量补单元，并可同批生成楼层房屋 -->
    <UnitBatchDialog
      v-model="unitBatchVisible"
      :community-name="selectedCommunity?.name"
      :building-options="buildingOptions"
      :initial-building-id="unitBatchSubjectId ?? ''"
      @saved="handleUnitBatchSaved"
      @result="showBatchResult"
    />

    <!-- 结构生成（批量建房主入口）：一次请求建成 楼栋 → 单元 → 房屋 -->
    <StructureGenerateDialog
      v-model="structureGenerateVisible"
      :community="selectedCommunity"
      @saved="handleStructureGenerated"
      @view-structure="handleViewStructure"
    />

    <HouseEditDialog
      v-model="houseDialogVisible"
      :house="houseEditing"
      :unit-context="houseUnitContext"
      @saved="handleHouseSaved"
    />

    <HouseBatchDialog
      v-model="houseBatchVisible"
      :communities="selectedCommunity ? [selectedCommunity] : []"
      :initial-community-id="selectedCommunityId ?? ''"
      :initial-building-id="houseBatchBuildingId ?? ''"
      :initial-unit-id="houseBatchUnitId ?? ''"
      @saved="handleHouseBatchSaved"
    />

    <!-- 批量管理结果（部分成功语义：成功量 + 逐条失败原因） -->
    <BatchResultDialog
      v-model="batchResultVisible"
      :success-count="batchResult.successCount"
      :success-text="batchResult.successText"
      :intro="batchResult.intro"
      :warning="batchResult.warning"
      :failures="batchResult.failures"
    />

    <!-- 楼栋级联删除：范围确认 → 编排进度 → 中断汇报 -->
    <el-dialog
      v-model="buildingDeleteVisible"
      title="删除楼栋（级联删除）"
      width="540px"
      :close-on-click-modal="false"
      :close-on-press-escape="buildingDeletePhase !== 'running'"
      :show-close="buildingDeletePhase !== 'running'"
      @close="handleBuildingDeleteClose"
    >
      <div v-if="buildingDeletePhase === 'loading'" class="cascade-state">
        <span class="cascade-spinner" aria-hidden="true" />
        正在统计删除范围…
      </div>

      <div v-else-if="buildingDeletePhase === 'error'" class="cascade-state">
        <p class="cascade-error-text">删除范围统计失败：{{ buildingCascadeError }}</p>
      </div>

      <div v-else-if="buildingDeletePhase === 'confirm'" class="cascade-confirm">
        <p class="cascade-lead">
          楼栋「{{ buildingDeleteNode?.building.name }}」删除后将同时删除其全部下级单元与房屋，不可恢复。
        </p>
        <div class="cascade-scope">
          <p class="cascade-scope-title">将删除：</p>
          <p class="cascade-scope-line">
            {{ buildingCascadePlan.length }} 个单元、{{ buildingCascadeHouseCount }} 套房屋
          </p>
          <ul class="cascade-scope-list">
            <li v-for="group in buildingCascadePlan" :key="group.unit.id">
              {{ group.unit.name }} · {{ group.houses.length }} 套房屋
            </li>
          </ul>
        </div>
        <p class="cascade-warning">
          删除按自底向上执行（先清空房屋、再删单元、最后删楼栋）。当前由前端逐个调用删除接口完成，
          并非后端事务：中途失败将立即停止、停留在部分删除状态且已删除部分不会自动回滚。
        </p>
      </div>

      <div v-else-if="buildingDeletePhase === 'running'" class="cascade-running">
        <p class="cascade-progress-text">
          删除中 {{ buildingCascadeCompleted }}/{{ buildingCascadeTotal }}
        </p>
        <el-progress :percentage="buildingCascadePercent" :show-text="false" :stroke-width="10" />
        <p class="cascade-hint">正在按 房屋 → 单元 → 楼栋 顺序删除，请勿关闭窗口</p>
      </div>

      <div v-else class="cascade-report">
        <p class="cascade-error-text">删除中断：{{ buildingCascadeFailure?.reason }}</p>
        <p class="cascade-report-line">
          已删除 {{ buildingCascadeDeletedHouses }} 套房屋、{{ buildingCascadeDeletedUnits }} 个单元。
          本次删除并非后端事务，已删除部分不会回滚，剩余内容如下（结构已刷新到实际状态）：
        </p>
        <ul v-if="buildingCascadeRemainingHouses.length > 0" class="cascade-report-list">
          <li v-for="group in buildingCascadeRemainingHouses" :key="group.unit.id">
            房屋 · {{ group.unit.name }}：{{ group.houses.map((house) => house.houseNumber).join('、') }}
          </li>
        </ul>
        <p v-if="buildingCascadeRemainingUnits.length > 0" class="cascade-report-line">
          未删除单元：{{ buildingCascadeRemainingUnits.map((unit) => unit.name).join('、') }}
        </p>
        <p class="cascade-report-line">未删除楼栋：{{ buildingDeleteNode?.building.name }}</p>
        <p class="cascade-warning">
          可处理剩余数据后重试删除；事务性级联删除端点已记入后端适配清单，就绪后此编排将被整体替换。
        </p>
      </div>

      <template #footer>
        <template v-if="buildingDeletePhase === 'done'">
          <el-button @click="buildingDeleteVisible = false">知道了</el-button>
        </template>
        <template v-else-if="buildingDeletePhase !== 'running'">
          <el-button @click="buildingDeleteVisible = false">取消</el-button>
          <el-button v-if="buildingDeletePhase === 'error'" type="primary" @click="retryBuildingCascade">
            重试
          </el-button>
          <el-button v-if="buildingDeletePhase === 'confirm'" type="danger" @click="runBuildingCascade">
            确认删除
          </el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 房屋信息卡（在住人员 / 租客账号 + 状态变更历史） -->
    <el-dialog v-model="houseInfoVisible" title="房屋信息" width="640px">
      <el-descriptions v-if="houseInfoEntry" :column="2" border>
        <el-descriptions-item label="门牌号">{{ houseInfoEntry.house.houseNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag
            :label="houseStatusLabels[houseInfoEntry.house.status]"
            :type="houseStatusTagType(houseInfoEntry.house.status)"
          />
        </el-descriptions-item>
        <el-descriptions-item label="楼栋">{{ houseInfoEntry.buildingName }}</el-descriptions-item>
        <el-descriptions-item label="单元">{{ houseInfoEntry.unitName }}</el-descriptions-item>
        <el-descriptions-item label="所在楼层">{{ houseInfoEntry.house.floor }} 层</el-descriptions-item>
        <el-descriptions-item label="建筑面积">
          {{ houseInfoEntry.house.area != null ? `${houseInfoEntry.house.area} ㎡` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="户型">{{ houseInfoEntry.house.layout || '-' }}</el-descriptions-item>
        <el-descriptions-item label="朝向">{{ houseInfoEntry.house.orientation || '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">
          {{ houseInfoEntry.house.description || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 在住人员 / 租客账号：关系（谁住着）+ 居民档案（账号、账号状态） -->
      <div v-loading="houseResidentsLoading" class="info-residents">
        <h4 class="info-residents-title">
          在住人员 / 租客账号
          <span class="info-residents-count">
            居住中 {{ activeResidentCount }} / 记录 {{ houseResidents.length }}
          </span>
        </h4>

        <p v-if="houseResidentsError" class="info-residents-error">
          {{ houseResidentsError }}
        </p>

        <div v-else-if="sortedHouseResidents.length > 0" class="resident-table">
          <div class="resident-row is-head" aria-hidden="true">
            <span>姓名</span>
            <span>登录账号</span>
            <span>身份</span>
            <span>手机号</span>
            <span>入住 / 迁出</span>
            <span>状态</span>
          </div>
          <div
            v-for="item in sortedHouseResidents"
            :key="item.id"
            class="resident-row"
            :class="{ 'is-moved-out': item.status !== 'ACTIVE' }"
          >
            <span class="resident-name" :title="item.residentName">
              {{ item.residentName || `居民 #${item.residentId}` }}
            </span>
            <span class="resident-account" :title="houseResidentProfiles[item.residentId]?.username ?? '-'">
              {{ houseResidentProfiles[item.residentId]?.username ?? '-' }}
            </span>
            <span>
              <StatusTag
                :label="residentTypeLabel(item.relationType)"
                :type="item.relationType === 'TENANT' ? 'processing' : 'info'"
              />
            </span>
            <span class="resident-phone">
              {{ item.residentPhone || houseResidentProfiles[item.residentId]?.phone || '-' }}
            </span>
            <span class="resident-date">
              {{ item.moveInDate || '-' }}
              <template v-if="item.moveOutDate"> → {{ item.moveOutDate }}</template>
            </span>
            <span>
              <StatusTag
                :label="residenceRelationStatusLabels[item.status]"
                :type="item.status === 'ACTIVE' ? 'completed' : 'canceled'"
              />
            </span>
          </div>
        </div>

        <p v-else-if="!houseResidentsLoading" class="info-residents-empty">
          该房屋暂无居住关系登记（业主 / 租客 / 家属均未登记）。
        </p>
      </div>

      <div v-loading="houseHistoryLoading" class="info-history">
        <h4 class="info-history-title">状态变更历史</h4>
        <ul v-if="houseHistory.length > 0" class="info-history-list">
          <li v-for="item in houseHistory" :key="item.id" class="info-history-item">
            <span class="info-history-status">
              {{ item.oldStatus ? houseStatusLabels[item.oldStatus] : '初始' }}
              →
              {{ houseStatusLabels[item.newStatus] }}
            </span>
            <span class="info-history-meta">
              {{ formatDateTime(item.createdAt) }} · {{ item.operatorName }}
              <template v-if="item.remark"> · {{ item.remark }}</template>
            </span>
          </li>
        </ul>
        <p v-else-if="!houseHistoryLoading" class="info-history-empty">暂无状态变更记录</p>
      </div>

      <template #footer>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" plain @click="editFromInfo">
          编辑
        </el-button>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="warning" plain @click="statusFromInfo">
          状态变更
        </el-button>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="danger" plain @click="deleteFromInfo">
          删除
        </el-button>
        <el-button @click="houseInfoVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 结构维护：先选目标楼栋/单元，再进各自弹窗（替代原常驻结构目录的行内操作） -->
    <el-dialog
      v-model="structureDialogVisible"
      :title="structureActionMeta.title"
      width="460px"
      destroy-on-close
    >
      <el-form label-width="72px">
        <el-form-item label="楼栋">
          <el-select
            v-model="structureBuildingId"
            style="width: 100%"
            placeholder="请选择楼栋"
            @change="handleStructureBuildingChange"
          >
            <el-option
              v-for="node in buildings"
              :key="node.building.id"
              :label="`${node.building.name}（${node.units.length} 个单元 · ${node.units.reduce((sum, item) => sum + item.houses.length, 0)} 套）`"
              :value="node.building.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="structureActionMeta.needsUnit" label="单元">
          <el-select v-model="structureUnitId" style="width: 100%" placeholder="请选择单元">
            <el-option
              v-for="item in structureUnitOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="structure-hint">{{ structureActionMeta.hint }}</p>
      <p v-if="buildings.length === 0" class="structure-hint is-warning">
        该社区还没有楼栋，请先用「批量建房」生成结构。
      </p>
      <template #footer>
        <el-button @click="structureDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!structureTargetReady" @click="runStructureAction">
          {{ structureActionMeta.confirmText }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 房屋状态变更：备注留痕 -->
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
        <el-button type="primary" :loading="statusSubmitting" @click="handleHouseStatusSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* 圆角与层级一致：白卡 12px / 行与按钮 8px / 标签胶囊。数字一律等宽字体取对齐感。 */
.manage {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: start;
  min-height: 360px;
}

.panel {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  flex-wrap: wrap;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.panel-icon {
  display: inline-flex;
  color: var(--color-primary);
}

.panel-icon svg {
  width: 16px;
  height: 16px;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.field-icon {
  width: 13px;
  height: 13px;
  color: var(--color-text-secondary);
}

/* ---------- 左栏 ---------- */

.rail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.rail-title-line {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  min-width: 0;
}

.rail-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.rail-total {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.rail-filters {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.status-chips,
.chips {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.chip-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 12px;
  border: none;
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-hover);
  font-family: inherit;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.chip-btn:hover {
  color: var(--color-text-primary);
}

.chip-btn.active {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.chip-count {
  font-family: var(--font-family-mono);
  font-size: 11px;
}

/* 批量操作条：未选中时是一个安静的提示条，选中后升格为操作区 */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  min-height: 30px;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.batch-bar.is-active {
  background-color: var(--color-primary-bg);
}

.batch-count {
  flex-shrink: 0;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-primary);
}

.batch-tip {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.batch-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.text-btn {
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  cursor: pointer;
}

.text-btn.is-danger {
  color: var(--color-danger);
}

.text-btn.is-muted {
  color: var(--color-text-secondary);
}

.text-btn:disabled {
  color: var(--color-text-disabled);
  cursor: not-allowed;
}

.rail-body {
  min-height: 120px;
}

.rail-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.community-item {
  display: flex;
  align-items: stretch;
  gap: var(--spacing-xs);
  padding: 2px 0;
  border-radius: var(--radius-md);
  transition: background-color 0.15s ease;
}

.community-item:hover {
  background-color: var(--color-bg-hover);
}

/* 选中态：品牌浅底 + 左侧色条，与「当前正在看的社区」一一对应 */
.community-item.is-active {
  background-color: var(--color-primary-bg);
  box-shadow: inset 2px 0 0 var(--color-primary);
}

.item-check {
  display: flex;
  align-items: center;
  padding-left: var(--spacing-xs);
}

.item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
  padding: var(--spacing-sm);
  border: none;
  background: none;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.item-line {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.item-name {
  overflow: hidden;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-meta {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.item-address {
  overflow: hidden;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-state,
.grid-state {
  padding: var(--spacing-lg) 0;
  text-align: center;
}

.state-title {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.state-text {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-secondary);
}

/* ---------- 右栏 ---------- */

/* 详情区单列：社区头部 → 房屋（唯一筛选区 + 单元平行列）。
   原左侧结构目录已取消（批量建房一次性建成结构后，选择功能由筛选区完全替代），
   腾出的整列宽度全部交给房屋列表。 */
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-width: 0;
}

.empty-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 280px;
}

.head-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
}

.head-identity {
  min-width: 0;
}

.head-name {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.head-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

/* ---------- 建房带（总入口 + 分步入口 + 结构维护） ---------- */

/* 浅底横带：与「社区级操作」在视觉上分开——建房是本页最高频的结构动作，
   给它一条独立的带，而不是继续往头部右侧堆按钮 */
.build-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  margin-top: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.build-bar-title {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding-right: var(--spacing-md);
  border-right: 1px solid var(--color-border);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 总入口：比子按钮高一档（实心主色 + 副标题说明作用范围） */
.build-main {
  height: 36px;
  font-weight: var(--font-weight-medium);
}

.build-main-sub {
  margin-left: var(--spacing-sm);
  padding-left: var(--spacing-sm);
  border-left: 1px solid rgba(255, 255, 255, 0.45);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  opacity: 0.9;
}

.build-sep {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.build-steps {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

/* 分步入口：序号徽标把「社区 → 楼栋 → 单元」的层级顺序写在按钮上 */
.build-step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
}

.step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  font-family: var(--font-family-mono);
  font-size: 10px;
  color: var(--color-primary);
}

.build-step:hover .step-no {
  background-color: var(--color-primary);
  color: #fff;
}

/* 低频维护动作推到带的右端，与建房入口拉开距离 */
.build-maintenance {
  margin-left: auto;
}

/* 关键数字：大字等宽，单位退为小字，环显示入住率 */
.metric-strip {
  display: flex;
  align-items: flex-end;
  gap: var(--spacing-xl);
  flex-wrap: wrap;
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border);
}

.metric-cell {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  min-width: 0;
}

.metric-cell b {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
}

.metric-cell span {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.occupancy-donut {
  align-self: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-circle);
  background: conic-gradient(var(--color-primary) calc(var(--pct) * 1%), var(--color-bg-subtle) 0);
  -webkit-mask: radial-gradient(closest-side, transparent 60%, currentColor 61%);
  mask: radial-gradient(closest-side, transparent 60%, currentColor 61%);
}

.panel-count {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
}

/* 菜单项图标（下拉菜单内联 svg，随父组件 scope 生效） */
.menu-icon {
  width: 13px;
  height: 13px;
  margin-right: 6px;
  vertical-align: -2px;
  color: var(--color-text-secondary);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.icon-btn:hover {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.icon-btn svg {
  width: 13px;
  height: 13px;
}

/* ---------- 房屋结果区 · 唯一筛选区 ---------- */

/* 筛选区是一张浅底小卡：面板标题之下、结果之上，层级固定为「范围 → 条件 → 结果」 */
.filter-panel {
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.filter-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-xs);
}

.filter-title {
  flex-shrink: 0;
  padding-right: var(--spacing-sm);
  border-right: 1px solid var(--color-border);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.filter-result {
  flex: 1;
  min-width: 0;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.filter-reset {
  flex-shrink: 0;
}

/* 当前范围徽标：楼栋/单元选择器之外的「我现在看的是哪一栋」提示 */
.filter-scope-chip {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

/* ---------- 缩放控件（房屋面板标题右侧） ---------- */

.zoom-control {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-subtle);
}

.zoom-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: var(--radius-circle);
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  line-height: 1;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.zoom-btn:hover:not(:disabled) {
  background-color: var(--admin-card-bg);
  color: var(--color-primary);
}

.zoom-btn:disabled {
  color: var(--color-text-disabled);
  cursor: not-allowed;
}

.zoom-value {
  min-width: 46px;
  padding: 0 var(--spacing-xs);
  border: none;
  background: none;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
  cursor: pointer;
}

.zoom-value:hover {
  color: var(--color-primary);
}

/* 结构维护弹窗的说明文字 */
.structure-hint {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-secondary);
}

.structure-hint.is-warning {
  color: var(--color-warning);
}

/* 「结构维护」下拉触发钮：文字 + 小三角 */
.structure-menu-trigger .caret {
  width: 10px;
  height: 10px;
  margin-left: 4px;
  vertical-align: -1px;
}

.filter-line {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.filter-line + .filter-line {
  margin-top: var(--spacing-xs);
}

.filter-label {
  flex-shrink: 0;
  width: 30px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.filter-select {
  width: 150px;
}

.filter-keyword {
  width: 180px;
}

.filter-empty {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.grid-body {
  position: relative;
  min-height: 120px;
  margin-top: var(--spacing-md);
}

/* 单元平行列：一个单元一列，列多时横向滚动（不自动切换的"轮播"式浏览）。
   缩放（zoom）作用在此层，便于一屏多看几列；框选矩形仍定位在未缩放的 .grid-body 上。 */
.grid-groups {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-md);
  padding-bottom: var(--spacing-xs);
  overflow-x: auto;
  overscroll-behavior-x: contain;
}

.grid-group {
  flex: 0 0 auto;
  min-width: 160px;
  padding: var(--spacing-sm) var(--spacing-md) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background-color: var(--color-bg-subtle);
}

.grid-group-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.grid-group-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.grid-group-count {
  flex-shrink: 0;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
}

/* 列头操作默认隐形：列一多也不至于整屏按钮 */
.grid-group-actions {
  display: inline-flex;
  flex-shrink: 0;
  gap: 2px;
  margin-left: auto;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.grid-group:hover .grid-group-actions,
.grid-group:focus-within .grid-group-actions {
  opacity: 1;
}

.grid-rows {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.grid-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.grid-floor {
  flex-shrink: 0;
  width: 38px;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-align: right;
}

.grid-cells {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

.grid-block {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 66px;
  height: 34px;
  padding: 0 var(--spacing-sm);
  border-radius: var(--radius-md);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
  user-select: none;
  transition: filter 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}

.grid-block:hover {
  filter: brightness(0.95);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

.grid-block:active {
  transform: translateY(0);
}

.grid-block:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

/* 卡片主体：整块即「查看详情」热区，操作全部让位给右上角角标 */
.block-number {
  padding: 0 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 角标「⋯」：落在卡片右上角外沿（行/列间距内），不覆盖房号也不遮相邻卡片 */
.block-menu {
  position: absolute;
  top: -7px;
  right: -7px;
  z-index: 2;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.grid-block:hover .block-menu,
.grid-block:focus-within .block-menu,
.grid-block.is-selected .block-menu {
  opacity: 1;
}

.block-menu-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-circle);
  background-color: var(--admin-card-bg);
  color: var(--color-text-secondary);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
}

.block-menu-btn:hover,
.block-menu-btn:focus-visible {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.block-menu-btn svg {
  width: 10px;
  height: 10px;
}

/* 块色映射真实状态（token 派生，color-mix 混白向画布卡色，不可用回退浅灰底） */
.grid-block.is-occupied {
  background-color: var(--color-success-soft);
  background-color: color-mix(in srgb, var(--color-success) 45%, var(--admin-card-bg));
}

.grid-block.is-vacant {
  background-color: var(--chart-c6);
}

.grid-block.is-reserved {
  background-color: var(--color-bg-subtle);
  background-color: color-mix(in srgb, var(--chart-c4) 38%, var(--admin-card-bg));
}

.grid-block.is-maintenance {
  background-color: var(--color-warning-soft);
  background-color: color-mix(in srgb, var(--color-warning) 52%, var(--admin-card-bg));
}

.grid-legend {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
  margin: var(--spacing-md) 0 0;
  padding: 0;
  list-style: none;
}

.grid-legend li {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.legend-note {
  color: var(--color-text-secondary);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--radius-sm);
}

.legend-dot.is-occupied {
  background-color: var(--color-success);
}

.legend-dot.is-vacant {
  background-color: var(--chart-c6);
}

.legend-dot.is-reserved {
  background-color: var(--chart-c4);
}

.legend-dot.is-maintenance {
  background-color: var(--color-warning);
}

/* ---------- 网格多选 ---------- */

.grid-body.is-marquee {
  cursor: crosshair;
  user-select: none;
}

.marquee-rect {
  position: absolute;
  z-index: 4;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  background-color: color-mix(in srgb, var(--color-primary) 16%, transparent);
  pointer-events: none;
}

.grid-block.is-selected,
.grid-block.is-selected:hover {
  box-shadow: 0 0 0 2px var(--color-primary);
}

/* 选中勾标移到左上角：右上角留给角标菜单，两者不再打架 */
.block-check {
  position: absolute;
  top: -6px;
  left: -6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary);
  color: var(--admin-card-bg);
  box-shadow: var(--shadow-sm);
  z-index: 2;
}

.block-check svg {
  width: 9px;
  height: 9px;
}

.selection-bar {
  position: sticky;
  bottom: var(--spacing-md);
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-top: var(--spacing-md);
  padding: var(--spacing-xs) var(--spacing-sm) var(--spacing-xs) var(--spacing-md);
  background-color: var(--admin-card-bg);
  border: 1px solid color-mix(in srgb, var(--color-primary) 45%, var(--color-border));
  border-radius: var(--radius-pill);
  box-shadow: var(--shadow-md);
}

.selection-count {
  flex-shrink: 0;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-primary);
}

.selection-actions {
  display: inline-flex;
  align-items: center;
}

.sel-bar-enter-active,
.sel-bar-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.sel-bar-enter-from,
.sel-bar-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* ---------- 楼栋级联删除对话框 ---------- */

.cascade-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.cascade-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: var(--radius-circle);
  animation: cascade-spin 0.8s linear infinite;
}

@keyframes cascade-spin {
  to {
    transform: rotate(360deg);
  }
}

.cascade-lead {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.cascade-scope {
  margin-bottom: var(--spacing-md);
  padding: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.cascade-scope-title {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.cascade-scope-line {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.cascade-scope-list {
  max-height: 160px;
  margin: 0;
  padding-left: var(--spacing-lg);
  overflow: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  list-style: disc;
}

.cascade-scope-list li {
  line-height: 1.8;
}

.cascade-warning {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-danger-soft);
  font-size: var(--font-size-xs);
  line-height: 1.7;
  color: var(--color-danger);
}

.cascade-running {
  padding: var(--spacing-sm) 0;
}

.cascade-progress-text {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.cascade-hint {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.cascade-error-text {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-danger);
}

.cascade-report-line {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.cascade-report-list {
  margin: 0 0 var(--spacing-xs);
  padding-left: var(--spacing-lg);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  list-style: disc;
}

.cascade-report-list li {
  line-height: 1.8;
}

/* ---------- 房屋信息卡：在住人员 / 租客账号 ---------- */

.info-residents {
  margin-top: var(--spacing-md);
  min-height: 48px;
}

.info-residents-title {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.info-residents-count {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
}

.info-residents-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.info-residents-error {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

/* 六列对照：姓名 / 登录账号 / 身份 / 手机号 / 入住迁出 / 居住状态 */
.resident-table {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.resident-row {
  display: grid;
  grid-template-columns: 1.1fr 1.3fr 0.7fr 1.2fr 1.6fr 0.8fr;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
}

.resident-row + .resident-row {
  border-top: 1px solid var(--color-border);
}

.resident-row.is-head {
  background-color: var(--color-bg-subtle);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

/* 已迁出记录退为次要信息，但仍保留（回答「曾经谁住过」） */
.resident-row.is-moved-out {
  color: var(--color-text-secondary);
}

.resident-name,
.resident-account,
.resident-phone,
.resident-date {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resident-account {
  font-family: var(--font-family-mono);
}

/* ---------- 房屋信息卡：状态变更历史 ---------- */

.info-history {
  margin-top: var(--spacing-md);
  min-height: 48px;
}

.info-history-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.info-history-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin: 0;
  padding: 0;
  max-height: 200px;
  overflow: auto;
  list-style: none;
}

.info-history-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: var(--spacing-sm);
  border-left: 2px solid var(--color-border-light, var(--color-border));
}

.info-history-status {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.info-history-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.info-history-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 触屏无悬浮态：列头操作与卡片角标常驻显示（否则房屋操作在移动端不可达） */
@media (hover: none) {
  .grid-group-actions,
  .block-menu {
    opacity: 1;
  }
}

/* ---------- 响应式 ---------- */

/* < 1200px：社区栏与详情上下堆叠 */
@media (max-width: 1199px) {
  .manage {
    grid-template-columns: minmax(0, 1fr);
  }

  .metric-strip {
    gap: var(--spacing-lg);
  }
}

@media (max-width: 767px) {
  .head-actions,
  .panel-actions {
    width: 100%;
  }

  .filter-select,
  .filter-keyword {
    width: 100%;
  }

  .metric-strip {
    gap: var(--spacing-md);
  }

  /* 窄屏优先保住列宽：单元列改为按屏宽铺开，横向滚动浏览 */
  .grid-group {
    min-width: 140px;
  }
}

/* 降低动效偏好：交互反馈退化为瞬时变化（本页面动效仅用于状态切换提示） */
@media (prefers-reduced-motion: reduce) {
  .community-item,
  .chip-btn,
  .grid-block,
  .grid-group-actions,
  .block-menu,
  .sel-bar-enter-active,
  .sel-bar-leave-active {
    transition: none;
  }

  .cascade-spinner {
    animation: none;
  }
}
</style>
