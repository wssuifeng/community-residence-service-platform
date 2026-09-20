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
import { formatDateTime } from '@/utils/date'
import { useGridSelection } from '@/composables/useGridSelection'
import { useUserStore } from '@/store/user'

/**
 * 社区管理主面板（2026-09-21 布局重做，替代原「结构总览」三级平铺树）。
 *
 * 信息架构（左列表 + 右详情，窄屏上下堆叠）：
 *   ① 左栏社区列表：关键字/状态筛选 + 分页 + 每社区概览计数 + 选中态；超管可多选批量管理；
 *   ② 右栏选中社区详情：社区头部（状态、联系方式、入住申请自动化、关键数字）→
 *      结构目录（楼栋手风琴，展开到单元，两级均带 单元/房屋/空置 计数，点击即筛选）→
 *      房屋结果区（房号搜索 / 楼层 / 状态 / 关键字，网格展示与批量操作）。
 *
 * 为什么不再是平铺树：社区多时首屏是一长列，单元全部铺开更无法定位。现在列表可搜可筛可翻页，
 * 结构目录只呈现「当前选中社区」且默认折叠，任何层级都通过同层级筛选切换、筛选口径处处带计数。
 *
 * 三级结构的增删改与房屋状态变更/历史、批量建房等能力全部保留；创建链路由
 * StructureGenerateDialog（一次请求建成 楼栋→单元→房屋）承担主入口，
 * 单轴弹窗退为「已有楼栋/单元内补建」的专项入口。
 */

const ICONS = {
  home: ['M3 11l9-8 9 8', 'M5 9.7V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.7', 'M10 21v-6h4v6'],
  search: ['M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14z', 'M20 20l-4.2-4.2'],
  sitemap: [
    'M9 3h6v5H9z',
    'M3 16h6v5H3z',
    'M15 16h6v5h-6z',
    'M12 8v3',
    'M6 16v-2a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v2'
  ]
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
  chevron: ['M9 6l6 6-6 6']
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
  expandedBuildings.value = {}
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

/** 楼栋计数：单元数 / 房屋数 / 空置数（结构目录每行右侧的筛选依据） */
interface StructureCount {
  units: number
  houses: number
  vacant: number
}

const buildingCounts = computed<Map<number, StructureCount>>(() => {
  const map = new Map<number, StructureCount>()
  for (const building of buildings.value) {
    let houses = 0
    let vacant = 0
    for (const unitNode of building.units) {
      houses += unitNode.houses.length
      vacant += unitNode.houses.filter((house) => house.status === 'VACANT').length
    }
    map.set(building.building.id, { units: building.units.length, houses, vacant })
  }
  return map
})

const unitCounts = computed<Map<number, StructureCount>>(() => {
  const map = new Map<number, StructureCount>()
  for (const building of buildings.value) {
    for (const unitNode of building.units) {
      map.set(unitNode.unit.id, {
        units: 0,
        houses: unitNode.houses.length,
        vacant: unitNode.houses.filter((house) => house.status === 'VACANT').length
      })
    }
  }
  return map
})

/* ---------------- 筛选状态（同一层级用选择器切换，绝不平铺全部单元） ---------------- */

const expandedBuildings = ref<Record<number, boolean>>({})
const activeBuildingId = ref<number | null>(null)
const activeUnitId = ref<number | null>(null)
const houseKeyword = ref('')
const activeFloor = ref<number | null>(null)
const activeStatus = ref<HouseStatus | null>(null)

function toggleBuilding(buildingId: number): void {
  expandedBuildings.value[buildingId] = !expandedBuildings.value[buildingId]
}

function selectBuilding(buildingId: number | null): void {
  activeBuildingId.value = buildingId
  activeUnitId.value = null
  if (buildingId !== null) expandedBuildings.value[buildingId] = true
}

function selectUnit(unitId: number | null): void {
  activeUnitId.value = unitId
}

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
  entries: HouseEntry[]
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
      entries: [entry]
    })
  }
  /* 分组内按楼层与房号排序（与结构总览楼层行阅读顺序一致） */
  return [...groups.values()].map((group) => ({
    ...group,
    entries: [...group.entries].sort(
      (a, b) =>
        b.house.floor - a.house.floor ||
        a.house.houseNumber.localeCompare(b.house.houseNumber, undefined, { numeric: true })
    )
  }))
})

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
  expandedBuildings.value[entity.id] = true
  selectBuilding(entity.id)
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
  expandedBuildings.value[entity.buildingId] = true
  refreshStructure()
}

function refreshStructure(): void {
  if (selectedCommunityId.value === null) return
  loadStructure(selectedCommunityId.value)
  refreshSelectedRowStats()
}

/* ---------------- 单元批量建 / 房屋批量建 ---------------- */

const unitBatchVisible = ref(false)
const unitBatchTarget = ref<IBuilding | null>(null)

function openUnitBatchCreate(building: IBuilding): void {
  unitBatchTarget.value = building
  unitBatchVisible.value = true
}

function handleUnitBatchSaved(buildingId: number): void {
  expandedBuildings.value[buildingId] = true
  refreshStructure()
}

const houseBatchVisible = ref(false)

/** 单元内补建房屋：预选当前筛选到的楼栋/单元（无筛选时留空由用户选择） */
function openHouseBatchCreate(): void {
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
                type="primary"
                size="small"
                @click="openStructureGenerate"
              >
                批量建房
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                @click="openCommunityEdit(selectedCommunity)"
              >
                编辑
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

        <!-- 结构目录：楼栋 → 单元两级选择器（每级带计数，点击即筛选） -->
        <article class="panel outline-panel">
          <header class="panel-head">
            <h3 class="panel-title">
              <span class="panel-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path v-for="(d, i) in ICONS.sitemap" :key="i" :d="d" />
                </svg>
              </span>
              结构目录
            </h3>
            <div class="panel-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                @click="openBuildingCreate"
              >
                新增楼栋
              </el-button>
              <el-button
                v-if="filtersActive"
                size="small"
                link
                type="primary"
                @click="clearAllFilters"
              >
                清除筛选
              </el-button>
            </div>
          </header>

          <div v-if="structureLoading && !structureLoaded" class="outline-skeleton">
            <el-skeleton :rows="4" animated />
          </div>

          <div v-else-if="structureError" class="outline-state">
            <p class="state-title">结构加载失败</p>
            <p class="state-text">{{ structureError }}</p>
            <el-button size="small" @click="retryStructure">重试</el-button>
          </div>

          <div v-else-if="!structureLoading && buildings.length === 0" class="outline-state">
            <p class="state-title">该社区还没有楼栋</p>
            <p class="state-text">
              用右上角「批量建房」一次生成 楼栋 → 单元 → 房屋；也可以点「新增楼栋」单独建一栋。
            </p>
          </div>

          <ul v-else-if="buildings.length > 0" class="outline">
            <!-- 全部楼栋：清除楼栋筛选，回到整个社区 -->
            <li>
              <div class="outline-row is-root" :class="{ 'is-active': activeBuildingId === null }">
                <span class="row-toggle is-leaf" aria-hidden="true" />
                <button type="button" class="row-label" @click="selectBuilding(null)">
                  全部楼栋
                </button>
                <span class="row-counts">
                  <b>{{ buildings.length }}</b> 栋 · <b>{{ communityMetrics.units }}</b> 单元 ·
                  <b>{{ communityMetrics.houses }}</b> 套 · 空置 <b>{{ communityMetrics.vacant }}</b>
                </span>
              </div>
            </li>

            <li v-for="node in buildings" :key="node.building.id">
              <div
                class="outline-row is-building"
                :class="{
                  'is-active': activeBuildingId === node.building.id,
                  'is-open': expandedBuildings[node.building.id]
                }"
              >
                <button
                  type="button"
                  class="row-toggle"
                  :aria-label="expandedBuildings[node.building.id] ? '收起单元' : '展开单元'"
                  :aria-expanded="!!expandedBuildings[node.building.id]"
                  @click="toggleBuilding(node.building.id)"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    :class="{ 'is-open': expandedBuildings[node.building.id] }"
                  >
                    <path v-for="(d, i) in ACTION_ICONS.chevron" :key="i" :d="d" />
                  </svg>
                </button>
                <button type="button" class="row-label" @click="selectBuilding(node.building.id)">
                  {{ node.building.name }}
                </button>
                <span class="row-counts">
                  <b>{{ buildingCounts.get(node.building.id)?.units ?? 0 }}</b> 单元 ·
                  <b>{{ buildingCounts.get(node.building.id)?.houses ?? 0 }}</b> 套 · 空置
                  <b>{{ buildingCounts.get(node.building.id)?.vacant ?? 0 }}</b>
                  <span class="row-floors">{{ node.building.floors }} 层</span>
                </span>
                <span class="row-actions">
                  <button
                    v-permission="['ADMIN', 'SUPER_ADMIN']"
                    type="button"
                    class="icon-btn"
                    title="新增单元"
                    aria-label="新增单元"
                    @click="openUnitCreate(node.building.id)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.plus" :key="i" :d="d" />
                    </svg>
                  </button>
                  <button
                    v-permission="['ADMIN', 'SUPER_ADMIN']"
                    type="button"
                    class="icon-btn"
                    title="批量建单元"
                    aria-label="批量建单元"
                    @click="openUnitBatchCreate(node.building)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.layers" :key="i" :d="d" />
                    </svg>
                  </button>
                  <button
                    v-permission="['ADMIN', 'SUPER_ADMIN']"
                    type="button"
                    class="icon-btn"
                    title="编辑楼栋"
                    aria-label="编辑楼栋"
                    @click="openBuildingEdit(node.building)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                    </svg>
                  </button>
                  <button
                    v-permission="['ADMIN', 'SUPER_ADMIN']"
                    type="button"
                    class="icon-btn is-danger"
                    title="删除楼栋"
                    aria-label="删除楼栋"
                    @click="handleBuildingDelete(node)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                    </svg>
                  </button>
                </span>
              </div>

              <ul v-if="expandedBuildings[node.building.id]" class="outline-children">
                <li v-if="node.units.length === 0" class="outline-hint">
                  该楼栋暂无单元，用行内「批量建单元」按序号区间生成，或点「＋」单独新增。
                </li>
                <template v-else>
                  <li>
                    <div
                      class="outline-row is-unit is-root"
                      :class="{ 'is-active': activeBuildingId === node.building.id && activeUnitId === null }"
                    >
                      <span class="row-toggle is-leaf" aria-hidden="true" />
                      <button type="button" class="row-label" @click="selectUnit(null)">
                        全部单元
                      </button>
                      <span class="row-counts">
                        <b>{{ buildingCounts.get(node.building.id)?.houses ?? 0 }}</b> 套 · 空置
                        <b>{{ buildingCounts.get(node.building.id)?.vacant ?? 0 }}</b>
                      </span>
                    </div>
                  </li>
                  <li v-for="unitNode in node.units" :key="unitNode.unit.id">
                    <div
                      class="outline-row is-unit"
                      :class="{ 'is-active': activeUnitId === unitNode.unit.id }"
                    >
                      <span class="row-toggle is-leaf" aria-hidden="true" />
                      <button type="button" class="row-label" @click="selectUnit(unitNode.unit.id)">
                        {{ unitNode.unit.name }}
                      </button>
                      <span class="row-counts">
                        <b>{{ unitCounts.get(unitNode.unit.id)?.houses ?? 0 }}</b> 套 · 空置
                        <b>{{ unitCounts.get(unitNode.unit.id)?.vacant ?? 0 }}</b>
                      </span>
                      <span class="row-actions">
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="icon-btn"
                          title="新增房屋"
                          aria-label="新增房屋"
                          @click="openHouseCreate(unitNode.unit, node.building.name)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ICONS.home" :key="i" :d="d" />
                          </svg>
                        </button>
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="icon-btn"
                          title="批量建房"
                          aria-label="在该单元批量建房"
                          @click="selectUnit(unitNode.unit.id); openHouseBatchCreate()"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.layers" :key="i" :d="d" />
                          </svg>
                        </button>
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="icon-btn"
                          title="编辑单元"
                          aria-label="编辑单元"
                          @click="openUnitEdit(unitNode.unit, node.building.id)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                          </svg>
                        </button>
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="icon-btn is-danger"
                          title="删除单元"
                          aria-label="删除单元"
                          @click="handleUnitDelete(unitNode.unit)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                          </svg>
                        </button>
                      </span>
                    </div>
                  </li>
                </template>
              </ul>
            </li>
          </ul>
        </article>

        <!-- 房屋结果区：筛选（楼栋/单元来自结构目录，此处为房号/楼层/状态） -->
        <article class="panel houses-panel">
          <header class="panel-head">
            <h3 class="panel-title">
              <span class="panel-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path v-for="(d, i) in ICONS.home" :key="i" :d="d" />
                </svg>
              </span>
              房屋
            </h3>
            <div class="panel-actions">
              <el-input
                v-model="houseKeyword"
                size="small"
                class="number-search"
                placeholder="搜索房号，如 101 / 12"
                clearable
              />
              <el-button v-if="filtersActive" size="small" link type="primary" @click="clearAllFilters">
                清除筛选
              </el-button>
            </div>
          </header>

          <!-- 筛选回路：楼栋 / 单元来自结构目录；这里只呈现结果口径与剩余维度 -->
          <div class="filter-scope">
            <span class="scope-crumb">
              {{ activeBuildingId === null ? '全部楼栋' : activeBuildingName }}
              <template v-if="activeUnitId !== null"> / {{ activeUnitName }}</template>
            </span>
            <span class="scope-summary">
              共 {{ scopeSummary.total }} 套 · 已入住 {{ scopeSummary.occupied }} · 空置
              {{ scopeSummary.vacant }}
              <template v-if="!structureLoading"> · 全部 {{ houseEntries.length }} 套</template>
            </span>
          </div>

          <div class="filter-row">
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
          </div>

          <div class="filter-row">
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

            <div v-else-if="!structureLoading && gridGroups.length === 0" class="grid-state">
              <template v-if="houseEntries.length === 0">
                <p class="state-title">该社区还没有房屋</p>
                <p class="state-text">
                  用「批量建房」按楼层与户数一次生成，或在单元行内点房屋图标单独添加。
                </p>
              </template>
              <template v-else>
                <p class="state-title">当前筛选下没有房屋</p>
                <p class="state-text">放宽楼层 / 状态筛选，或清空房号关键字后再看。</p>
                <el-button size="small" @click="clearAllFilters">清除筛选</el-button>
              </template>
            </div>

            <div v-for="group in gridGroups" :key="group.key" class="grid-group">
              <h4 class="grid-group-title">
                {{ group.title }}
                <span class="grid-group-count">{{ group.entries.length }} 套</span>
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
                      {{ entry.house.houseNumber }}
                      <span v-if="isHouseGridSelected(entry.house.id)" class="block-check" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
                          <path d="M5 12.5l4.5 4.5L19 7.5" />
                        </svg>
                      </span>
                      <span class="block-actions">
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="block-action"
                          title="编辑房屋"
                          :aria-label="`编辑房屋 ${entry.house.houseNumber}`"
                          @click.stop="openHouseEdit(entry)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                          </svg>
                        </button>
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="block-action"
                          title="状态变更"
                          :aria-label="`变更房屋状态 ${entry.house.houseNumber}`"
                          @click.stop="openHouseStatus(entry.house)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.swap" :key="i" :d="d" />
                          </svg>
                        </button>
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="block-action is-danger"
                          title="删除房屋"
                          :aria-label="`删除房屋 ${entry.house.houseNumber}`"
                          @click.stop="handleHouseDelete(entry)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                          </svg>
                        </button>
                      </span>
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
            <li class="legend-note">Ctrl / Cmd + 点击 或 在空白处拖拽可多选</li>
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
      @saved="handleBuildingSaved"
    />

    <UnitEditDialog
      v-model="unitDialogVisible"
      :communities="selectedCommunity ? [selectedCommunity] : []"
      :unit="unitEditing"
      :default-community-id="selectedCommunityId"
      :default-building-id="unitDefaultBuildingId"
      @saved="handleUnitSaved"
    />

    <!-- 批量建单元：只对已有楼栋按序号区间补单元（结构生成弹窗不覆盖此场景） -->
    <UnitBatchDialog v-model="unitBatchVisible" :building="unitBatchTarget" @saved="handleUnitBatchSaved" />

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
      :initial-building-id="activeBuildingId ?? ''"
      :initial-unit-id="activeUnitId ?? ''"
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

    <!-- 房屋信息卡（含状态变更历史） -->
    <el-dialog v-model="houseInfoVisible" title="房屋信息" width="480px">
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
.outline-state,
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

/* ---------- 结构目录（两级选择器） ---------- */

.outline,
.outline-children {
  margin: 0;
  padding: 0;
  list-style: none;
}

.outline-children {
  margin-left: var(--spacing-md);
  padding-left: var(--spacing-md);
  border-left: 1px solid var(--color-border);
}

.outline-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-height: 36px;
  padding: 0 var(--spacing-xs) 0 var(--spacing-xs);
  border-radius: var(--radius-md);
}

.outline-row:hover {
  background-color: var(--color-bg-hover);
}

.outline-row.is-active {
  background-color: var(--color-primary-bg);
}

.outline-row.is-active .row-label {
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.outline-row.is-root .row-label {
  color: var(--color-text-secondary);
}

.outline-row.is-root.is-active .row-label {
  color: var(--color-primary);
}

.row-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  background: none;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.row-toggle.is-leaf {
  pointer-events: none;
}

.row-toggle svg {
  width: 12px;
  height: 12px;
  transition: transform 0.15s ease;
}

.row-toggle svg.is-open {
  transform: rotate(90deg);
}

.row-label {
  flex-shrink: 0;
  max-width: 40%;
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-align: left;
  cursor: pointer;
}

.row-counts {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-counts b {
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.row-floors {
  margin-left: var(--spacing-sm);
  color: var(--color-text-disabled);
}

.outline-row.is-unit {
  padding-left: var(--spacing-lg);
}

.row-actions {
  display: inline-flex;
  flex-shrink: 0;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.outline-row:hover .row-actions,
.outline-row.is-active .row-actions {
  opacity: 1;
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

.icon-btn.is-danger:hover {
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
}

.icon-btn svg {
  width: 13px;
  height: 13px;
}

.outline-hint {
  padding: var(--spacing-xs) var(--spacing-sm);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-secondary);
}

/* ---------- 房屋结果区 ---------- */

.number-search {
  width: 180px;
}

.filter-scope {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-sm);
}

.scope-crumb {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.scope-summary {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.filter-row + .filter-row {
  margin-top: var(--spacing-xs);
}

.grid-body {
  position: relative;
  min-height: 120px;
  margin-top: var(--spacing-md);
}

.grid-group + .grid-group {
  margin-top: var(--spacing-lg);
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

.grid-group-count {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
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

.block-actions {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border-radius: inherit;
  background-color: color-mix(in srgb, var(--admin-card-bg) 82%, transparent);
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.15s ease, visibility 0.15s ease;
}

.grid-block:hover .block-actions,
.grid-block:focus-within .block-actions {
  opacity: 1;
  visibility: visible;
}

.block-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background-color: var(--admin-card-bg);
  color: var(--color-text-secondary);
  cursor: pointer;
  box-shadow: var(--shadow-sm);
}

.block-action:hover {
  color: var(--color-primary);
}

.block-action.is-danger:hover {
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
}

.block-action svg {
  width: 12px;
  height: 12px;
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

.block-check {
  position: absolute;
  top: -6px;
  right: -6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary);
  color: var(--admin-card-bg);
  box-shadow: var(--shadow-sm);
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

/* 触屏无悬浮态：行内操作钮常驻显示（否则楼栋/单元操作在移动端不可达） */
@media (hover: none) {
  .row-actions,
  .block-actions {
    opacity: 1;
    visibility: visible;
  }
}

/* ---------- 响应式：< 1200px 退化为上下堆叠 ---------- */

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

  .number-search {
    width: 100%;
  }

  .metric-strip {
    gap: var(--spacing-md);
  }
}

/* 降低动效偏好：交互反馈退化为瞬时变化（本页面动效仅用于状态切换提示） */
@media (prefers-reduced-motion: reduce) {
  .community-item,
  .chip-btn,
  .grid-block,
  .row-toggle svg,
  .row-actions,
  .block-actions,
  .sel-bar-enter-active,
  .sel-bar-leave-active {
    transition: none;
  }

  .cascade-spinner {
    animation: none;
  }
}
</style>
