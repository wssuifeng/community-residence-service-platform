<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import CommunityEditDialog from '@/views/admin/community/CommunityEditDialog.vue'
import BuildingEditDialog from '@/views/admin/community/BuildingEditDialog.vue'
import UnitEditDialog from '@/views/admin/community/UnitEditDialog.vue'
import BuildingCreateDialog from '@/views/admin/community/BuildingCreateDialog.vue'
import UnitBatchDialog from '@/views/admin/community/UnitBatchDialog.vue'
import HouseEditDialog from '@/views/admin/community/HouseEditDialog.vue'
import {
  deleteBuilding,
  deleteCommunity,
  deleteHouse,
  deleteUnit,
  getBuildingList,
  getCommunityList,
  getHouseList,
  getUnitList,
  updateCommunityStatus
} from '@/api/community'
import type { IBuilding, ICommunity, IHouse, IUnit } from '@/types/modules/community'
import { communityStatusLabels, houseStatusLabels } from '@/types/modules/community'
import type { CommunityStatus, HouseStatus } from '@/types/modules/community'
import { getDashboardStats } from '@/api/statistics'
import type { IDashboardStats } from '@/types/modules/statistics'
import { useGridSelection } from '@/composables/useGridSelection'

/**
 * 社区结构 Tab（对照设计稿 design-mockups/admin/01-社区结构.png）：
 * 顶部 4 统计卡 + 左右主从布局（左 300px 三级结构树 / 右详情卡 + 房屋状态网格）。
 * CommunityListView/BuildingListView/UnitListView 的 CRUD 与二次确认逻辑
 * 收编于此（对话框抽为独立组件，删除确认文案原样保留）。
 * 楼栋删除自 R4-D2 升级：空楼栋直删确认；非空走级联范围确认 + 前端自底向上
 * 编排（房屋→单元→楼栋，非后端事务，失败汇报剩余明细，见后端适配清单）。
 */

const route = useRoute()
const router = useRouter()

/* ===================== 图标（24×24 stroke path） ===================== */

const ICONS = {
  building: [
    'M5 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16',
    'M15 9h3a1 1 0 0 1 1 1v11',
    'M3 21h18',
    'M8 7h2',
    'M8 11h2',
    'M8 15h2'
  ],
  unit: ['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z'],
  home: ['M3 11l9-8 9 8', 'M5 9.7V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.7', 'M10 21v-6h4v6'],
  donut: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M12 3v9h9'],
  sitemap: ['M9 3h6v5H9z', 'M3 16h6v5H3z', 'M15 16h6v5h-6z', 'M12 8v3', 'M6 16v-2a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v2']
}

const NODE_ICONS = {
  community: ['M3 11l9-8 9 8', 'M5 9.7V20h14V9.7'],
  building: ['M5 21V7a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v14', 'M3 21h18', 'M8 10h2', 'M8 14h2'],
  unit: ['M5 21V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16', 'M3 21h18', 'M9 21v-5a3 3 0 0 1 6 0v5']
}

const ACTION_ICONS = {
  plus: ['M12 5v14', 'M5 12h14'],
  pen: ['M12 20h9', 'M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z'],
  trash: ['M3 6h18', 'M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2', 'M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6', 'M10 11v6', 'M14 11v6'],
  chevron: ['M9 6l6 6-6 6']
}

/* ===================== 结构树数据（社区→楼栋→单元三级） ===================== */

interface BuildingNode {
  building: IBuilding
  units: IUnit[]
}

interface CommunityNode {
  community: ICommunity
  buildings: BuildingNode[]
}

const tree = ref<CommunityNode[]>([])
const treeLoading = ref(false)

/* 并发防护：树为三级串行加载，重载时旧序列的后续写入作废 */
let treeSeq = 0

async function loadTree(): Promise<void> {
  const seq = ++treeSeq
  treeLoading.value = true
  try {
    /* 管理端社区规模有限（数据级权限过滤后更少），单页拉取（Building/Unit 列表视图同口径） */
    const result = await getCommunityList({ page: 1, size: 200 })
    if (seq !== treeSeq) return
    const communities = result.records
    tree.value = communities.map((community) => ({ community, buildings: [] }))

    /* 楼栋层并行填充（每社区整体赋值，保证经 reactive 代理触发更新） */
    await Promise.all(
      communities.map(async (community, index) => {
        let buildingNodes: BuildingNode[] = []
        try {
          const buildingResult = await getBuildingList(community.id, { page: 1, size: 200 })
          if (seq !== treeSeq) return
          buildingNodes = await Promise.all(
            buildingResult.records.map(async (building) => {
              let units: IUnit[] = []
              try {
                units = (await getUnitList(building.id, { page: 1, size: 200 })).records
              } catch {
                units = []
              }
              return { building, units }
            })
          )
        } catch {
          buildingNodes = []
        }
        if (seq !== treeSeq) return
        const node = tree.value[index]
        if (node) node.buildings = buildingNodes
      })
    )
    if (seq !== treeSeq) return
    ensureSelection()
  } catch {
    if (seq !== treeSeq) return
    tree.value = []
    selected.value = null
  } finally {
    if (seq === treeSeq) treeLoading.value = false
  }
}

/** 单元总数：由树数据聚合（统计接口无单元项；分页上限 200/楼栋，量级内为精确值） */
const unitTotal = computed(() =>
  tree.value.reduce(
    (sum, communityNode) =>
      sum + communityNode.buildings.reduce((buildingSum, b) => buildingSum + b.units.length, 0),
    0
  )
)

/* ===================== 选中态与展开态 ===================== */

type Selection =
  | { type: 'community'; communityId: number }
  | { type: 'building'; communityId: number; buildingId: number }
  | { type: 'unit'; communityId: number; buildingId: number; unitId: number }
  | null

const selected = ref<Selection>(null)
const expanded = ref<Record<string, boolean>>({})

const selectedCommunity = computed(
  () => tree.value.find((node) => node.community.id === selected.value?.communityId) ?? null
)
const selectedBuildingNode = computed(() => {
  const sel = selected.value
  if (!sel || sel.type === 'community') return null
  return (
    selectedCommunity.value?.buildings.find((node) => node.building.id === sel.buildingId) ?? null
  )
})
const selectedUnit = computed(() => {
  const sel = selected.value
  if (!sel || sel.type !== 'unit') return null
  return selectedBuildingNode.value?.units.find((unit) => unit.id === sel.unitId) ?? null
})

type ActiveView = 'community' | 'building' | 'unit' | 'none'
const activeView = computed<ActiveView>(() => {
  if (selected.value?.type === 'unit' && selectedUnit.value) return 'unit'
  if (selected.value?.type === 'building' && selectedBuildingNode.value) return 'building'
  if (selected.value?.type === 'community' && selectedCommunity.value) return 'community'
  return 'none'
})

const crumbText = computed(() => {
  const parts: string[] = []
  if (selectedCommunity.value) parts.push(selectedCommunity.value.community.name)
  if (selectedBuildingNode.value) parts.push(selectedBuildingNode.value.building.name)
  if (selectedUnit.value) parts.push(selectedUnit.value.name)
  return parts.join('  /  ')
})

function expandCommunity(communityNode: CommunityNode): void {
  expanded.value[`c${communityNode.community.id}`] = true
  const firstBuilding = communityNode.buildings[0]
  if (firstBuilding) expanded.value[`b${firstBuilding.building.id}`] = true
}

function selectCommunityNode(communityNode: CommunityNode): void {
  expanded.value[`c${communityNode.community.id}`] = true
  selected.value = { type: 'community', communityId: communityNode.community.id }
}

function selectBuildingNode(buildingNode: BuildingNode): void {
  expanded.value[`c${buildingNode.building.communityId}`] = true
  expanded.value[`b${buildingNode.building.id}`] = true
  selected.value = {
    type: 'building',
    communityId: buildingNode.building.communityId,
    buildingId: buildingNode.building.id
  }
}

function selectUnit(unit: IUnit, buildingNode: BuildingNode): void {
  expanded.value[`c${buildingNode.building.communityId}`] = true
  expanded.value[`b${buildingNode.building.id}`] = true
  selected.value = {
    type: 'unit',
    communityId: buildingNode.building.communityId,
    buildingId: buildingNode.building.id,
    unitId: unit.id
  }
}

/* 加载完成后的选中维护：深链 ?communityId= 优先，否则首个社区下钻首楼栋；
   重载后选中节点已删除时逐级回退 */
function ensureSelection(): void {
  const sel = selected.value
  if (sel) {
    const communityNode = tree.value.find((node) => node.community.id === sel.communityId)
    if (!communityNode) {
      selected.value = null
      selectFirst()
      return
    }
    if (sel.type !== 'community') {
      const selBuildingId = sel.buildingId
      const selUnitId = sel.type === 'unit' ? sel.unitId : null
      const buildingNode = communityNode.buildings.find(
        (node) => node.building.id === selBuildingId
      )
      if (!buildingNode) {
        selectCommunityNode(communityNode)
        return
      }
      if (selUnitId !== null && !buildingNode.units.some((unit) => unit.id === selUnitId)) {
        selectBuildingNode(buildingNode)
      }
    }
    return
  }
  selectFirst()
}

function selectFirst(): void {
  if (tree.value.length === 0) {
    selected.value = null
    return
  }
  /* 旧路径 /admin/buildings?communityId=x redirect 承接的 query 在此消费：预选指定社区 */
  const queryCommunityId = Number(route.query.communityId)
  const target =
    (Number.isFinite(queryCommunityId) && queryCommunityId > 0
      ? tree.value.find((node) => node.community.id === queryCommunityId)
      : undefined) ?? tree.value[0]
  expandCommunity(target)
  /* 对照设计稿首屏：默认下钻到首个楼栋（右栏展示楼栋详情 + 房屋网格） */
  const firstBuilding = target.buildings[0]
  if (firstBuilding) {
    selectBuildingNode(firstBuilding)
  } else {
    selectCommunityNode(target)
  }
}

/* ===================== 房屋数据（随选中节点懒加载） ===================== */

const houses = ref<IHouse[]>([])
const housesLoading = ref(false)
let housesSeq = 0

/* ---------- 房屋网格多选（R4-D3）：Ctrl/Cmd+点选 + 空白区框选 + 批量删除 ----------
   单元视图网格与楼栋视图多单元区块网格由 gridGroups 两种数据形态流经同一渲染容器，
   消费同一份 useGridSelection 状态；组合框本身以容器元素为作用域，可独立复用 */
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

/* 切换社区/楼栋/单元（含树重载后的选中回退）时清空选区 */
watch(selected, async (sel) => {
  const seq = ++housesSeq
  activeFloor.value = null
  houses.value = []
  clearHouseSelection()
  if (!sel || sel.type === 'community') return
  housesLoading.value = true
  try {
    if (sel.type === 'unit') {
      const result = await getHouseList(sel.unitId, { page: 1, size: 200 })
      if (seq !== housesSeq) return
      houses.value = result.records
    } else {
      const units = selectedBuildingNode.value?.units ?? []
      if (units.length === 0) return
      const results = await Promise.all(
        units.map((unit) => getHouseList(unit.id, { page: 1, size: 200 }).catch(() => null))
      )
      if (seq !== housesSeq) return
      houses.value = results.flatMap((result) => result?.records ?? [])
    }
  } finally {
    if (seq === housesSeq) housesLoading.value = false
  }
})

/* 局部刷新（第三轮 C4）：网格增删改后仅重拉受影响单元的房屋，
   楼栋视图按 unitId 原地替换（不整页重载、不重置楼层筛选）；楼层被删空时筛选自动回落全部 */
async function reloadUnitHouses(unitId: number): Promise<void> {
  const sel = selected.value
  if (!sel) return
  const seq = ++housesSeq
  housesLoading.value = true
  try {
    const result = await getHouseList(unitId, { page: 1, size: 200 })
    if (seq !== housesSeq) return
    if (sel.type === 'unit') {
      houses.value = result.records
    } else {
      houses.value = houses.value.filter((house) => house.unitId !== unitId).concat(result.records)
    }
    if (
      activeFloor.value !== null &&
      !houses.value.some((house) => house.floor === activeFloor.value)
    ) {
      activeFloor.value = null
    }
  } catch {
    /* 局部刷新失败保留现列表（树切换会整体重载），不打断用户操作 */
  } finally {
    if (seq === housesSeq) housesLoading.value = false
  }
}

/* ===================== 房屋状态网格（楼层行 × 房号列） ===================== */

/* 真实 HouseStatus 枚举映射（设计稿例图含「欠费」，系统无此状态，按实际枚举对齐；
   配色与运营看板 houseStatusDistribution 同一口径：绿=已入住/灰=空置/紫=预留/橙=维护中） */
const HOUSE_BLOCK_CLASS: Record<HouseStatus, string> = {
  OCCUPIED: 'is-occupied',
  VACANT: 'is-vacant',
  RESERVED: 'is-reserved',
  MAINTENANCE: 'is-maintenance'
}

/* 图例按「已入住→空置→预留→维护中」排序（对照设计稿阅读顺序，非枚举字面序） */
const LEGEND_ORDER: HouseStatus[] = ['OCCUPIED', 'VACANT', 'RESERVED', 'MAINTENANCE']

const LEGEND_ITEMS = LEGEND_ORDER.map((value) => ({ value, label: houseStatusLabels[value] }))

const activeFloor = ref<number | null>(null)

/* 楼层筛选变化时清空选区：避免操作条对当前不可见（被筛出）的选中房屋执行删除 */
watch(activeFloor, () => clearHouseSelection())

/* 局部刷新后剪除已不存在的房屋 id（悬浮钮单删选中项时保持操作条计数真实） */
watch(houses, (list) => {
  if (selectedHouseIds.value.size === 0) return
  const alive = new Set(list.map((house) => house.id))
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

const floorOptions = computed(() =>
  [...new Set(houses.value.map((house) => house.floor))].sort((a, b) => b - a)
)

const scopeHouses = computed(() =>
  activeFloor.value === null
    ? houses.value
    : houses.value.filter((house) => house.floor === activeFloor.value)
)

const scopeOccupiedCount = computed(
  () => scopeHouses.value.filter((house) => house.status === 'OCCUPIED').length
)

interface GridRow {
  floor: number
  houses: IHouse[]
}

interface GridGroup {
  key: string
  title: string
  /** 分组所属单元（「＋」快速添加房屋的目标单元，第三轮 C4） */
  unit: IUnit
  rows: GridRow[]
}

function floorRowsOf(list: IHouse[]): GridRow[] {
  const byFloor = new Map<number, IHouse[]>()
  for (const house of list) {
    const bucket = byFloor.get(house.floor)
    if (bucket) {
      bucket.push(house)
    } else {
      byFloor.set(house.floor, [house])
    }
  }
  return [...byFloor.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([floor, list]) => ({
      floor,
      houses: [...list].sort((a, b) => a.houseNumber.localeCompare(b.houseNumber, undefined, { numeric: true }))
    }))
}

const gridGroups = computed<GridGroup[]>(() => {
  if (activeView.value === 'unit' && selectedUnit.value) {
    return [
      {
        key: `u${selectedUnit.value.id}`,
        title: selectedUnit.value.name,
        unit: selectedUnit.value,
        rows: floorRowsOf(scopeHouses.value)
      }
    ]
  }
  if (activeView.value === 'building' && selectedBuildingNode.value) {
    /* 不再过滤无房单元：空单元保留分组标题与「＋」入口（网格内直接给空单元添第一套房） */
    return selectedBuildingNode.value.units.map((unit) => ({
      key: `u${unit.id}`,
      title: unit.name,
      unit,
      rows: floorRowsOf(scopeHouses.value.filter((house) => house.unitId === unit.id))
    }))
  }
  return []
})

/* 房屋块点击：无房屋详情路由 → 弹信息卡（简报允许的交互，见任务报告）。
   楼栋/单元展示名取树上下文（后端 HouseVO 无名称字段，unitName 可空） */
const houseInfoVisible = ref(false)
const houseInfo = ref<IHouse | null>(null)

const houseInfoBuildingName = computed(() => selectedBuildingNode.value?.building.name ?? '-')

function unitNameOfHouse(house: IHouse): string {
  return (
    selectedBuildingNode.value?.units.find((unit) => unit.id === house.unitId)?.name ??
    selectedUnit.value?.name ??
    house.unitName ??
    '-'
  )
}

const houseInfoUnitName = computed(() => (houseInfo.value ? unitNameOfHouse(houseInfo.value) : '-'))

function openHouseInfo(house: IHouse): void {
  houseInfo.value = house
  houseInfoVisible.value = true
}

/* 卡片点击分流（R4-D3）：Ctrl/Cmd+左键切换多选；普通左键保持开信息卡；
   悬浮编辑/删除钮自带 .stop，不受分流影响 */
function onHouseBlockClick(event: MouseEvent, house: IHouse): void {
  if (event.ctrlKey || event.metaKey) {
    toggleHouseSelection(house.id)
    return
  }
  openHouseInfo(house)
}

/* ============ 房屋便捷操作（第三轮 C4）：卡片悬浮编辑/删除 + 单元「＋」快速添加 ============ */

const houseDialogVisible = ref(false)
const houseEditing = ref<IHouse | null>(null)
const houseUnitContext = ref<{
  buildingName: string
  unitId: number
  unitName: string
} | null>(null)

/** 网格分组标题「＋」：目标单元即分组单元（单元网格头与楼栋详情卡每单元区块共用） */
function openHouseCreate(unit: IUnit): void {
  houseEditing.value = null
  houseUnitContext.value = {
    buildingName: selectedBuildingNode.value?.building.name ?? '-',
    unitId: unit.id,
    unitName: unit.name
  }
  houseDialogVisible.value = true
}

function openHouseEdit(house: IHouse): void {
  houseEditing.value = house
  houseUnitContext.value = {
    buildingName: selectedBuildingNode.value?.building.name ?? '-',
    unitId: house.unitId,
    unitName: unitNameOfHouse(house)
  }
  houseDialogVisible.value = true
}

/** 保存后局部刷新受影响单元（房屋总数统计卡同步校准） */
function handleHouseSaved(unitId: number): void {
  reloadUnitHouses(unitId)
  loadStats()
}

/* 房屋删除：确认文案与 HouseListView 原样一致（引用保护由后端报错） */
async function handleHouseDelete(house: IHouse): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除房屋「${house.houseNumber}」？若该房屋存在居住/租住关系，删除将被拒绝。`,
      '删除房屋',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteHouse(house.id)
    ElMessage.success('房屋已删除')
    reloadUnitHouses(house.unitId)
    loadStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* 批量删除（R4-D3）：一次确认（口径沿用单删的引用保护提示，带数量）→ 逐条调用
   删除接口（单条失败不中断）→ 汇报成功/失败明细 → 局部刷新受影响单元 → 选区清空 */
const batchDeleteRunning = ref(false)

async function handleHouseBatchDelete(): Promise<void> {
  const targets = houses.value.filter((house) => selectedHouseIds.value.has(house.id))
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
  for (const house of targets) {
    try {
      await deleteHouse(house.id)
      deleted += 1
    } catch (error) {
      failed.push({
        houseNumber: house.houseNumber,
        reason: error instanceof Error ? error.message : '删除失败'
      })
    }
  }
  batchDeleteRunning.value = false
  if (failed.length === 0) {
    ElMessage.success(`已删除 ${deleted} 套房屋`)
  } else {
    /* 部分失败：alert 非阻塞展示明细（失败房屋留存在网格中），刷新与清选紧随其后 */
    ElMessageBox.alert(
      `批量删除完成：成功 ${deleted} 套，失败 ${failed.length} 套。失败房屋：${failed
        .map((item) => `${item.houseNumber}（${item.reason}）`)
        .join('、')}`,
      '批量删除结果',
      { type: 'warning', confirmButtonText: '知道了' }
    ).catch(() => {})
  }
  const affectedUnitIds = [...new Set(targets.map((house) => house.unitId))]
  affectedUnitIds.forEach((unitId) => reloadUnitHouses(unitId))
  loadStats()
  clearHouseSelection()
}

/* 信息卡内直达编辑/删除（触屏无悬浮态时的兜底路径），先收卡再走同一处理器 */
function editFromInfo(): void {
  const house = houseInfo.value
  if (!house) return
  houseInfoVisible.value = false
  openHouseEdit(house)
}

function deleteFromInfo(): void {
  const house = houseInfo.value
  if (!house) return
  houseInfoVisible.value = false
  void handleHouseDelete(house)
}

function houseStatusTagType(status: HouseStatus): 'info' | 'completed' | 'pending' | 'processing' {
  if (status === 'OCCUPIED') return 'completed'
  if (status === 'RESERVED') return 'pending'
  if (status === 'MAINTENANCE') return 'processing'
  return 'info'
}

/* ===================== 统计卡（楼栋/房屋/入住率走看板统计接口，单元由树聚合） ===================== */

const dashboard = ref<IDashboardStats | null>(null)
const statsLoading = ref(false)

async function loadStats(): Promise<void> {
  statsLoading.value = true
  try {
    dashboard.value = await getDashboardStats()
  } catch {
    dashboard.value = null
  } finally {
    statsLoading.value = false
  }
}

const occupancyNum = computed(() => {
  const total = dashboard.value?.houseCount ?? 0
  if (!total) return null
  return ((dashboard.value?.occupiedHouseCount ?? 0) / total) * 100
})

const occupancyLabel = computed(() =>
  occupancyNum.value === null ? '-' : occupancyNum.value.toFixed(1)
)

/* ===================== 社区/楼栋/单元 增删改（对话框 + 级联删除确认） ===================== */

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

const buildingDialogVisible = ref(false)
const buildingEditing = ref<IBuilding | null>(null)
const buildingDefaultCommunityId = ref<number | null>(null)

function openBuildingCreate(community: ICommunity): void {
  buildingEditing.value = null
  buildingDefaultCommunityId.value = community.id
  buildingDialogVisible.value = true
}

function openBuildingEdit(building: IBuilding): void {
  buildingEditing.value = building
  buildingDefaultCommunityId.value = building.communityId
  buildingDialogVisible.value = true
}

const unitDialogVisible = ref(false)
const unitEditing = ref<IUnit | null>(null)
const unitDefaultCommunityId = ref<number | null>(null)
const unitDefaultBuildingId = ref<number | null>(null)

function openUnitCreate(buildingNode: BuildingNode): void {
  unitEditing.value = null
  unitDefaultCommunityId.value = buildingNode.building.communityId
  unitDefaultBuildingId.value = buildingNode.building.id
  unitDialogVisible.value = true
}

function openUnitEdit(unit: IUnit, buildingNode: BuildingNode): void {
  unitEditing.value = unit
  unitDefaultCommunityId.value = buildingNode.building.communityId
  unitDefaultBuildingId.value = buildingNode.building.id
  unitDialogVisible.value = true
}

/* ============ 整栋创建（第三轮 C3，升级自 A7 批量建楼）/ 批量建单元（A7 7.2） ============ */

const buildingCreateVisible = ref(false)
const unitBatchVisible = ref(false)

/** 整栋创建入口（树工具条）：一次生成楼栋+单元+房屋基础数据，预选当前选中社区 */
function openWholeBuildingCreate(): void {
  buildingCreateVisible.value = true
}

const buildingCreateDefaultCommunityId = computed<number | null>(
  () => selected.value?.communityId ?? tree.value[0]?.community.id ?? null
)

/** 批量建单元入口（楼栋详情卡）：目标楼栋为当前选中楼栋 */
const unitBatchTargetBuilding = computed<IBuilding | null>(
  () => selectedBuildingNode.value?.building ?? null
)

function openUnitBatchCreate(): void {
  if (!selectedBuildingNode.value) return
  unitBatchVisible.value = true
}

/* 保存后：展开目标社区/楼栋并刷新树与统计（选中节点由 ensureSelection 维护） */
function handleBuildingCreateSaved(communityId: number): void {
  expanded.value[`c${communityId}`] = true
  loadTree()
  loadStats()
}

function handleUnitBatchSaved(buildingId: number): void {
  const building = selectedBuildingNode.value?.building
  if (building) {
    expanded.value[`c${building.communityId}`] = true
  }
  expanded.value[`b${buildingId}`] = true
  loadTree()
  loadStats()
}

/* 保存后刷新树/统计，并展开到被保存的节点（新建后立即可见） */
function handleCommunitySaved(entity: ICommunity): void {
  expanded.value[`c${entity.id}`] = true
  loadTree()
  loadStats()
}

function handleBuildingSaved(entity: IBuilding): void {
  expanded.value[`c${entity.communityId}`] = true
  expanded.value[`b${entity.id}`] = true
  loadTree()
  loadStats()
}

function handleUnitSaved(entity: IUnit): void {
  expanded.value[`c${entity.communityId}`] = true
  expanded.value[`b${entity.buildingId}`] = true
  loadTree()
  loadStats()
}

/* 社区删除：级联删除能力（R1/R6 v1.1），确认文案与 CommunityListView 原样一致 */
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
    loadTree()
    loadStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* 社区启用/停用：沿用 CommunityListView 确认文案与接口 */
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
    loadTree()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${action}失败`)
  }
}

/* ============ 楼栋删除（R4-D2）：空楼栋直删 + 非空级联范围确认与自底向上编排 ============

   后端现状（BuildingService.delete）：无单元直接软删成功，有单元即拒（5102），
   无事务性级联端点（BEAUTIFY_NOTES「后端适配清单·楼栋删除事务级联」）。
   非空楼栋由前端自底向上编排：逐单元删房屋 → 删单元 → 最后删楼栋。
   该编排不是后端事务：任一步失败即停止并汇报剩余明细，树刷新到实际状态，
   已删除部分不可自动回滚——后端级联端点就绪后本编排可整体替换。 */

type BuildingDeletePhase = 'loading' | 'error' | 'confirm' | 'running' | 'done'

const buildingDeleteVisible = ref(false)
const buildingDeletePhase = ref<BuildingDeletePhase>('loading')
const buildingDeleteNode = ref<BuildingNode | null>(null)

/** 级联计划：树内单元顺序 + 逐单元房屋清单（范围展示与执行共用同一数据源） */
interface BuildingCascadeGroup {
  unit: IUnit
  houses: IHouse[]
}

const buildingCascadePlan = ref<BuildingCascadeGroup[]>([])
const buildingCascadeError = ref('')
const buildingCascadeCompleted = ref(0)
const buildingCascadeTotal = ref(0)

/** 中断定位：houseIndex=该单元房屋数表示房屋已清但单元删除失败；
    unitIndex=计划长度表示单元全删、仅剩楼栋本体删除失败 */
const buildingCascadeFailure = ref<{
  unitIndex: number
  houseIndex: number
  reason: string
} | null>(null)

/** 范围统计序列号：对话框取消/关闭后，在途统计的回写作废 */
let cascadeSeq = 0

const buildingCascadeHouseCount = computed(() =>
  buildingCascadePlan.value.reduce((sum, group) => sum + group.houses.length, 0)
)

const buildingCascadePercent = computed(() =>
  buildingCascadeTotal.value === 0
    ? 0
    : Math.round((buildingCascadeCompleted.value / buildingCascadeTotal.value) * 100)
)

/* 中断汇报的已完成数：失败单元之前的单元已全部删除，房屋按失败定位累计 */
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

/* 中断汇报的剩余明细：自失败单元起，房屋自失败房号起
   （单元删除失败时该单元房屋已清空，仅剩单元本身） */
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

/* 楼栋删除统一入口（树节点悬浮钮 + 详情卡删除钮）：空楼栋走原直删确认，非空走级联对话框 */
async function handleBuildingDelete(buildingNode: BuildingNode): Promise<void> {
  if (buildingNode.units.length === 0) {
    /* 空楼栋：后端无单元即软删成功，直删路径（口径：为空直删、不可恢复） */
    try {
      await ElMessageBox.confirm(
        `该楼栋为空（无单元），将直接删除楼栋「${buildingNode.building.name}」，删除后不可恢复。确定删除？`,
        '删除楼栋',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await deleteBuilding(buildingNode.building.id)
      ElMessage.success('楼栋已删除')
      loadTree()
      loadStats()
    } catch (error) {
      ElMessage.error(buildingCascadeErrorText(error))
    }
    return
  }
  buildingDeleteNode.value = buildingNode
  buildingCascadeFailure.value = null
  buildingCascadeCompleted.value = 0
  buildingDeleteVisible.value = true
  await prepareBuildingCascade(buildingNode)
}

/* 范围统计：逐单元拉齐房屋清单（与树单元加载同口径 size 上限），作为展示与执行计划 */
async function prepareBuildingCascade(buildingNode: BuildingNode): Promise<void> {
  const seq = ++cascadeSeq
  buildingDeletePhase.value = 'loading'
  buildingCascadeError.value = ''
  try {
    const plan = await Promise.all(
      buildingNode.units.map(async (unit) => ({
        unit,
        houses: (await getHouseList(unit.id, { page: 1, size: 200 })).records
      }))
    )
    if (seq !== cascadeSeq) return
    buildingCascadePlan.value = plan
    /* 进度分母 = 房屋 + 单元 + 楼栋本体 */
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

/* 对话框任意路径关闭后在途统计作废（loading 态取消不留脏状态） */
function handleBuildingDeleteClose(): void {
  cascadeSeq += 1
}

/* 自底向上编排：逐单元删房屋 → 删单元 → 删楼栋。任一步失败即停止（停止点即剩余起点），
   树与统计先刷新到实际状态，再于对话框内汇报剩余明细（非事务，不回滚已删部分） */
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

  loadTree()
  loadStats()

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

/* 单元删除：确认文案与 UnitListView 原样一致（引用保护由后端报错） */
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
    loadTree()
    loadStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* ===================== 社区设置入口（隐藏下钻 /admin/communities/:id） ===================== */

function goCommunitySettings(): void {
  if (!selectedCommunity.value) return
  router.push(`/admin/communities/${selectedCommunity.value.community.id}`)
}

onMounted(() => {
  loadTree()
  loadStats()
})
</script>

<template>
  <section class="tree-pane">
    <!-- 顶部 4 统计卡 -->
    <div v-loading="statsLoading" class="stat-row">
      <AdminStatCard
        label="楼栋"
        :value="dashboard?.buildingCount ?? 0"
        unit="栋"
        :icon="ICONS.building"
        accent="primary"
      />
      <AdminStatCard
        label="单元"
        :value="unitTotal"
        unit="个"
        :icon="ICONS.unit"
        accent="success"
      />
      <AdminStatCard
        label="房屋总数"
        :value="dashboard?.houseCount ?? 0"
        unit="套"
        :icon="ICONS.home"
        accent="warning"
      />
      <AdminStatCard
        label="入住率"
        :value="occupancyLabel"
        unit="%"
        :icon="ICONS.donut"
        accent="primary"
      >
        <template #extra>
          <span
            v-if="occupancyNum !== null"
            class="occupancy-donut"
            :style="{ '--pct': occupancyNum }"
            :title="`已入住 ${dashboard?.occupiedHouseCount ?? 0} / 共 ${dashboard?.houseCount ?? 0} 套`"
          />
        </template>
      </AdminStatCard>
    </div>

    <!-- 左右主从布局 -->
    <div class="master-detail">
      <!-- 左栏：社区→楼栋→单元三级树 -->
      <aside class="tree-panel">
        <header class="tree-head">
          <h3 class="panel-title">
            <span class="panel-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in ICONS.sitemap" :key="i" :d="d" />
              </svg>
            </span>
            社区结构
          </h3>
          <div class="tree-head-actions">
            <el-button
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              size="small"
              @click="openWholeBuildingCreate"
            >
              整栋创建
            </el-button>
            <el-button
              v-permission="['SUPER_ADMIN']"
              type="primary"
              size="small"
              @click="openCommunityCreate"
            >
              新增社区
            </el-button>
          </div>
        </header>

        <div v-loading="treeLoading" class="tree-body">
          <p v-if="!treeLoading && tree.length === 0" class="tree-empty">暂无社区，请先新建</p>
          <ul v-else class="tree-root" role="tree" aria-label="社区结构树">
            <li v-for="communityNode in tree" :key="communityNode.community.id">
              <div
                class="tree-node is-community"
                :class="{
                  selected: activeView === 'community' && selectedCommunity?.community.id === communityNode.community.id,
                  'is-inactive': communityNode.community.status === 'INACTIVE'
                }"
                role="treeitem"
                :aria-expanded="!!expanded[`c${communityNode.community.id}`]"
              >
                <button
                  type="button"
                  class="node-toggle"
                  :aria-label="expanded[`c${communityNode.community.id}`] ? '收起' : '展开'"
                  @click="expanded[`c${communityNode.community.id}`] = !expanded[`c${communityNode.community.id}`]"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :class="{ 'is-open': expanded[`c${communityNode.community.id}`] }">
                    <path v-for="(d, i) in ACTION_ICONS.chevron" :key="i" :d="d" />
                  </svg>
                </button>
                <span class="node-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path v-for="(d, i) in NODE_ICONS.community" :key="i" :d="d" />
                  </svg>
                </span>
                <button type="button" class="node-label" @click="selectCommunityNode(communityNode)">
                  {{ communityNode.community.name }}
                </button>
                <span class="node-actions">
                  <button
                    v-permission="['ADMIN', 'SUPER_ADMIN']"
                    type="button"
                    class="node-action"
                    title="新增楼栋"
                    aria-label="新增楼栋"
                    @click="openBuildingCreate(communityNode.community)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.plus" :key="i" :d="d" />
                    </svg>
                  </button>
                  <button
                    v-permission="['ADMIN', 'SUPER_ADMIN']"
                    type="button"
                    class="node-action"
                    title="编辑社区"
                    aria-label="编辑社区"
                    @click="openCommunityEdit(communityNode.community)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                    </svg>
                  </button>
                  <button
                    v-permission="['SUPER_ADMIN']"
                    type="button"
                    class="node-action is-danger"
                    title="删除社区"
                    aria-label="删除社区"
                    @click="handleCommunityDelete(communityNode.community)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                    </svg>
                  </button>
                </span>
              </div>

              <ul v-if="expanded[`c${communityNode.community.id}`]" class="tree-children" role="group">
                <li v-if="communityNode.buildings.length === 0" class="tree-branch-empty">暂无楼栋</li>
                <li v-for="buildingNode in communityNode.buildings" :key="buildingNode.building.id">
                  <div
                    class="tree-node is-building"
                    :class="{
                      /* 楼栋为选中单元的祖先时同步高亮（对照设计稿选中链路） */
                      selected: selectedBuildingNode?.building.id === buildingNode.building.id
                    }"
                    role="treeitem"
                    :aria-expanded="!!expanded[`b${buildingNode.building.id}`]"
                  >
                    <button
                      type="button"
                      class="node-toggle"
                      :aria-label="expanded[`b${buildingNode.building.id}`] ? '收起' : '展开'"
                      @click="expanded[`b${buildingNode.building.id}`] = !expanded[`b${buildingNode.building.id}`]"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :class="{ 'is-open': expanded[`b${buildingNode.building.id}`] }">
                        <path v-for="(d, i) in ACTION_ICONS.chevron" :key="i" :d="d" />
                      </svg>
                    </button>
                    <span class="node-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path v-for="(d, i) in NODE_ICONS.building" :key="i" :d="d" />
                      </svg>
                    </span>
                    <button type="button" class="node-label" @click="selectBuildingNode(buildingNode)">
                      {{ buildingNode.building.name }}
                    </button>
                    <span class="node-actions">
                      <button
                        v-permission="['ADMIN', 'SUPER_ADMIN']"
                        type="button"
                        class="node-action"
                        title="新增单元"
                        aria-label="新增单元"
                        @click="openUnitCreate(buildingNode)"
                      >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <path v-for="(d, i) in ACTION_ICONS.plus" :key="i" :d="d" />
                        </svg>
                      </button>
                      <button
                        v-permission="['ADMIN', 'SUPER_ADMIN']"
                        type="button"
                        class="node-action"
                        title="编辑楼栋"
                        aria-label="编辑楼栋"
                        @click="openBuildingEdit(buildingNode.building)"
                      >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                        </svg>
                      </button>
                      <button
                        v-permission="['ADMIN', 'SUPER_ADMIN']"
                        type="button"
                        class="node-action is-danger"
                        title="删除楼栋"
                        aria-label="删除楼栋"
                        @click="handleBuildingDelete(buildingNode)"
                      >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                        </svg>
                      </button>
                    </span>
                  </div>

                  <ul v-if="expanded[`b${buildingNode.building.id}`]" class="tree-children" role="group">
                    <li v-if="buildingNode.units.length === 0" class="tree-branch-empty">暂无单元</li>
                    <li v-for="unit in buildingNode.units" :key="unit.id">
                      <div
                        class="tree-node is-unit"
                        :class="{ selected: activeView === 'unit' && selectedUnit?.id === unit.id }"
                        role="treeitem"
                      >
                        <span class="node-toggle is-leaf" aria-hidden="true" />
                        <span class="node-icon" aria-hidden="true">
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in NODE_ICONS.unit" :key="i" :d="d" />
                          </svg>
                        </span>
                        <button type="button" class="node-label" @click="selectUnit(unit, buildingNode)">
                          {{ unit.name }}
                        </button>
                        <span class="node-actions">
                          <button
                            v-permission="['ADMIN', 'SUPER_ADMIN']"
                            type="button"
                            class="node-action"
                            title="编辑单元"
                            aria-label="编辑单元"
                            @click="openUnitEdit(unit, buildingNode)"
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                              <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                            </svg>
                          </button>
                          <button
                            v-permission="['ADMIN', 'SUPER_ADMIN']"
                            type="button"
                            class="node-action is-danger"
                            title="删除单元"
                            aria-label="删除单元"
                            @click="handleUnitDelete(unit)"
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                              <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                            </svg>
                          </button>
                        </span>
                      </div>
                    </li>
                  </ul>
                </li>
              </ul>
            </li>
          </ul>
        </div>
      </aside>

      <!-- 右栏：详情卡 + 房屋状态网格 -->
      <div class="detail-col">
        <div class="detail-toolbar">
          <span class="detail-crumb">{{ crumbText || '未选择节点' }}</span>
          <el-button v-if="selectedCommunity" link type="primary" @click="goCommunitySettings">
            社区设置
          </el-button>
        </div>

        <!-- 社区详情卡 -->
        <article v-if="activeView === 'community' && selectedCommunity" class="panel">
          <header class="panel-header">
            <h3 class="panel-title">
              {{ selectedCommunity.community.name }}
              <StatusTag
                :label="communityStatusLabels[selectedCommunity.community.status]"
                :type="selectedCommunity.community.status === 'ACTIVE' ? 'completed' : 'canceled'"
              />
            </h3>
            <div class="card-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                @click="openCommunityEdit(selectedCommunity.community)"
              >
                编辑
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                :type="selectedCommunity.community.status === 'ACTIVE' ? 'danger' : 'success'"
                @click="handleCommunityToggle(selectedCommunity.community)"
              >
                {{ selectedCommunity.community.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
              <el-button
                v-permission="['SUPER_ADMIN']"
                size="small"
                type="danger"
                plain
                @click="handleCommunityDelete(selectedCommunity.community)"
              >
                删除
              </el-button>
            </div>
          </header>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="社区地址" :span="2">
              {{ selectedCommunity.community.address }}
            </el-descriptions-item>
            <el-descriptions-item label="联系人">
              {{ selectedCommunity.community.contactPerson || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="联系电话">
              {{ selectedCommunity.community.contactPhone || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="社区简介" :span="2">
              {{ selectedCommunity.community.description || '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </article>

        <!-- 楼栋详情卡 -->
        <article v-else-if="activeView === 'building' && selectedBuildingNode" class="panel">
          <header class="panel-header">
            <h3 class="panel-title">{{ selectedBuildingNode.building.name }} · 详情</h3>
            <div class="card-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                type="primary"
                @click="openUnitBatchCreate"
              >
                批量建单元
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                type="primary"
                plain
                @click="openBuildingEdit(selectedBuildingNode.building)"
              >
                编辑
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                type="danger"
                plain
                @click="handleBuildingDelete(selectedBuildingNode)"
              >
                删除
              </el-button>
            </div>
          </header>
          <div class="metric-row">
            <div class="metric">
              <span>楼号</span>
              <b>{{ selectedBuildingNode.building.name }}</b>
            </div>
            <div class="metric">
              <span>楼层数</span>
              <b>{{ selectedBuildingNode.building.floors }} 层</b>
            </div>
            <div class="metric">
              <span>单元数</span>
              <b>{{ selectedBuildingNode.units.length }} 单元</b>
            </div>
            <div v-if="selectedBuildingNode.building.description" class="metric is-wide">
              <span>描述</span>
              <b>{{ selectedBuildingNode.building.description }}</b>
            </div>
          </div>
        </article>

        <!-- 单元详情卡 -->
        <article v-else-if="activeView === 'unit' && selectedUnit" class="panel">
          <header class="panel-header">
            <h3 class="panel-title">{{ selectedUnit.name }} · 详情</h3>
            <div class="card-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                type="primary"
                @click="openUnitEdit(selectedUnit, selectedBuildingNode!)"
              >
                编辑
              </el-button>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                size="small"
                type="danger"
                plain
                @click="handleUnitDelete(selectedUnit)"
              >
                删除
              </el-button>
            </div>
          </header>
          <div class="metric-row">
            <div class="metric">
              <span>单元名称</span>
              <b>{{ selectedUnit.name }}</b>
            </div>
            <div class="metric">
              <span>所属楼栋</span>
              <b>{{ selectedBuildingNode?.building.name }}</b>
            </div>
            <div v-if="selectedUnit.description" class="metric is-wide">
              <span>描述</span>
              <b>{{ selectedUnit.description }}</b>
            </div>
          </div>
        </article>

        <!-- 空态 -->
        <article v-else class="panel">
          <el-empty
            :description="tree.length ? '请在左侧选择社区 / 楼栋 / 单元' : '暂无社区数据，请先新建社区'"
          />
        </article>

        <!-- 房屋状态网格（楼栋/单元选中时展示） -->
        <article
          v-if="activeView === 'building' || activeView === 'unit'"
          v-loading="housesLoading"
          class="panel grid-panel"
        >
          <header class="panel-header">
            <h3 class="panel-title">
              <span class="panel-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path v-for="(d, i) in ICONS.home" :key="i" :d="d" />
                </svg>
              </span>
              房屋状态
            </h3>
            <div v-if="floorOptions.length > 0" class="floor-chips" role="group" aria-label="楼层筛选">
              <button
                type="button"
                class="floor-chip"
                :class="{ active: activeFloor === null }"
                @click="activeFloor = null"
              >
                全部
              </button>
              <button
                v-for="floor in floorOptions"
                :key="floor"
                type="button"
                class="floor-chip"
                :class="{ active: activeFloor === floor }"
                @click="activeFloor = activeFloor === floor ? null : floor"
              >
                {{ floor }}层
              </button>
            </div>
          </header>

          <p v-if="houses.length > 0" class="grid-summary">
            共 {{ scopeHouses.length }} 套 · 已入住 {{ scopeOccupiedCount }} 套
          </p>

          <!-- 网格多选容器（R4-D3）：空白区按下左键拖拽 = 框选；卡片与按钮上的按下不启动框选 -->
          <div
            ref="gridBodyRef"
            class="grid-body"
            :class="{ 'is-marquee': gridMarquee.active }"
            @pointerdown="onHouseGridPointerDown"
            @pointermove="onHouseGridPointerMove"
            @pointerup="onHouseGridPointerUp"
            @pointercancel="onHouseGridPointerCancel"
          >
            <p v-if="!housesLoading && gridGroups.length === 0" class="grid-empty">
              {{
                activeView === 'building' && selectedBuildingNode?.units.length === 0
                  ? '该楼栋暂无单元，可在左侧楼栋节点新增'
                  : '暂无房屋数据'
              }}
            </p>
            <div v-for="group in gridGroups" :key="group.key" class="grid-group">
              <h4 class="grid-group-title">
                {{ group.title }}
                <button
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  type="button"
                  class="group-add"
                  title="添加房屋"
                  :aria-label="`在${group.title}添加房屋`"
                  @click="openHouseCreate(group.unit)"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path v-for="(d, i) in ACTION_ICONS.plus" :key="i" :d="d" />
                  </svg>
                </button>
              </h4>
              <p v-if="group.rows.length === 0" class="grid-group-empty">暂无房屋</p>
              <div class="grid-rows">
                <div v-for="row in group.rows" :key="row.floor" class="grid-row">
                  <span class="grid-floor">{{ row.floor }}层</span>
                  <div class="grid-cells">
                    <div
                      v-for="house in row.houses"
                      :key="house.id"
                      class="grid-block"
                      :class="[
                        HOUSE_BLOCK_CLASS[house.status],
                        { 'is-selected': isHouseGridSelected(house.id) }
                      ]"
                      role="button"
                      tabindex="0"
                      :data-house-id="house.id"
                      :title="`${house.houseNumber} · ${houseStatusLabels[house.status]}`"
                      @click="onHouseBlockClick($event, house)"
                      @keydown.enter.prevent="openHouseInfo(house)"
                      @keydown.space.prevent="openHouseInfo(house)"
                    >
                      {{ house.houseNumber }}
                      <!-- 多选勾选角标（R4-D3）：选中/框选预览时显形 -->
                      <span v-if="isHouseGridSelected(house.id)" class="block-check" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
                          <path d="M5 12.5l4.5 4.5L19 7.5" />
                        </svg>
                      </span>
                      <!-- 悬浮快捷操作（触屏走信息卡兜底入口）：遮罩随 hover/focus 显形 -->
                      <span class="block-actions">
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="block-action"
                          title="编辑房屋"
                          :aria-label="`编辑房屋 ${house.houseNumber}`"
                          @click.stop="openHouseEdit(house)"
                        >
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                          </svg>
                        </button>
                        <button
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          type="button"
                          class="block-action is-danger"
                          title="删除房屋"
                          :aria-label="`删除房屋 ${house.houseNumber}`"
                          @click.stop="handleHouseDelete(house)"
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

            <!-- 橡皮筋选框（R4-D3）：拖拽中的半透明品牌蓝矩形，仅视觉不拦截事件 -->
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
          </ul>

          <!-- 批量操作条（R4-D3）：选中 ≥1 套时浮出，sticky 于网格底部 -->
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
      </div>
    </div>

    <!-- 社区新建/编辑 -->
    <CommunityEditDialog
      v-model="communityDialogVisible"
      :community="communityEditing"
      @saved="handleCommunitySaved"
    />

    <!-- 楼栋新建/编辑 -->
    <BuildingEditDialog
      v-model="buildingDialogVisible"
      :communities="tree.map((node) => node.community)"
      :building="buildingEditing"
      :default-community-id="buildingDefaultCommunityId"
      @saved="handleBuildingSaved"
    />

    <!-- 单元新建/编辑 -->
    <UnitEditDialog
      v-model="unitDialogVisible"
      :communities="tree.map((node) => node.community)"
      :unit="unitEditing"
      :default-community-id="unitDefaultCommunityId"
      :default-building-id="unitDefaultBuildingId"
      @saved="handleUnitSaved"
    />

    <!-- 整栋创建（第三轮 C3，升级自 A7 批量建楼）：循环既有创建接口，逐个失败不中断 -->
    <BuildingCreateDialog
      v-model="buildingCreateVisible"
      :communities="tree.map((node) => node.community)"
      :default-community-id="buildingCreateDefaultCommunityId"
      @saved="handleBuildingCreateSaved"
    />
    <!-- 批量建单元（A7 7.2；第三轮 C3 前缀默认取楼栋名） -->
    <UnitBatchDialog
      v-model="unitBatchVisible"
      :building="unitBatchTargetBuilding"
      @saved="handleUnitBatchSaved"
    />

    <!-- 房屋快速添加/编辑（第三轮 C4：网格便捷操作；单元上下文由入口锁定） -->
    <HouseEditDialog
      v-model="houseDialogVisible"
      :house="houseEditing"
      :unit-context="houseUnitContext"
      @saved="handleHouseSaved"
    />

    <!-- 楼栋级联删除（R4-D2）：范围确认 → 编排进度 → 中断汇报；
         非后端事务，失败时剩余明细与树同步呈现实际状态 -->
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
          并非后端事务：中途失败将立即停止、停留在部分删除状态且已删除部分不会自动回滚，届时将列出剩余明细。
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
          本次删除并非后端事务，已删除部分不会回滚，剩余内容如下（树已刷新到实际状态）：
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
          <el-button
            v-if="buildingDeletePhase === 'error'"
            type="primary"
            @click="retryBuildingCascade"
          >
            重试
          </el-button>
          <el-button
            v-if="buildingDeletePhase === 'confirm'"
            type="danger"
            @click="runBuildingCascade"
          >
            确认删除
          </el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 房屋信息卡（无房屋详情路由，块点击弹卡） -->
    <el-dialog v-model="houseInfoVisible" title="房屋信息" width="480px">
      <el-descriptions v-if="houseInfo" :column="2" border>
        <el-descriptions-item label="门牌号">{{ houseInfo.houseNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag
            :label="houseStatusLabels[houseInfo.status]"
            :type="houseStatusTagType(houseInfo.status)"
          />
        </el-descriptions-item>
        <el-descriptions-item label="楼栋">{{ houseInfoBuildingName }}</el-descriptions-item>
        <el-descriptions-item label="单元">{{ houseInfoUnitName }}</el-descriptions-item>
        <el-descriptions-item label="所在楼层">{{ houseInfo.floor }} 层</el-descriptions-item>
        <el-descriptions-item label="建筑面积">
          {{ houseInfo.area != null ? `${houseInfo.area} ㎡` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="户型">{{ houseInfo.layout || '-' }}</el-descriptions-item>
        <el-descriptions-item label="朝向">{{ houseInfo.orientation || '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ houseInfo.description || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button
          v-permission="['ADMIN', 'SUPER_ADMIN']"
          type="primary"
          plain
          @click="editFromInfo"
        >
          编辑
        </el-button>
        <el-button
          v-permission="['ADMIN', 'SUPER_ADMIN']"
          type="danger"
          plain
          @click="deleteFromInfo"
        >
          删除
        </el-button>
        <el-button @click="houseInfoVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.tree-pane {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-height: 320px;
}

/* ---------- 通用白卡容器（与运营看板 panel 同款） ---------- */

.panel {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-header {
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

.card-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

/* ---------- 第一行：统计卡 ---------- */

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--spacing-lg);
}

/* 迷你入住率环：conic-gradient 按 token 着色（运营看板占用率卡同款） */
.occupancy-donut {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-circle);
  background: conic-gradient(var(--color-primary) calc(var(--pct) * 1%), var(--color-bg-subtle) 0);
  -webkit-mask: radial-gradient(closest-side, transparent 60%, currentColor 61%);
  mask: radial-gradient(closest-side, transparent 60%, currentColor 61%);
}

/* ---------- 主从布局 ---------- */

.master-detail {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: start;
}

/* ---------- 左栏：结构树 ---------- */

.tree-panel {
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.tree-head-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 0;
}

.tree-body {
  max-height: 620px;
  overflow: auto;
}

.tree-empty {
  margin: 0;
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.tree-root,
.tree-children {
  margin: 0;
  padding: 0;
  list-style: none;
}

.tree-children {
  padding-left: var(--spacing-lg);
}

.tree-node {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  height: 34px;
  padding: 0 var(--spacing-xs);
  border-radius: var(--radius-md);
  cursor: default;
}

.tree-node:hover {
  background-color: var(--color-bg-hover);
}

.tree-node.selected {
  background-color: var(--color-primary-bg);
}

.tree-node.selected .node-label,
.tree-node.selected .node-icon {
  color: var(--color-primary);
}

.tree-node.is-inactive .node-label {
  color: var(--color-text-disabled);
}

.node-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  background: none;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.node-toggle.is-leaf {
  pointer-events: none;
}

.node-toggle svg {
  width: 12px;
  height: 12px;
  transition: transform 0.15s ease;
}

.node-toggle svg.is-open {
  transform: rotate(90deg);
}

.node-icon {
  display: inline-flex;
  flex-shrink: 0;
  color: var(--color-text-secondary);
}

.node-icon svg {
  width: 14px;
  height: 14px;
}

.node-label {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-align: left;
  text-overflow: ellipsis;
  overflow: hidden;
  white-space: nowrap;
  cursor: pointer;
}

.node-actions {
  display: inline-flex;
  flex-shrink: 0;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.tree-node:hover .node-actions,
.tree-node.selected .node-actions {
  opacity: 1;
}

.node-action {
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

.node-action:hover {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.node-action.is-danger:hover {
  background-color: var(--color-danger-soft);
  color: var(--color-danger);
}

.node-action svg {
  width: 13px;
  height: 13px;
}

.tree-branch-empty {
  padding: var(--spacing-xs) 0 var(--spacing-xs) var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* ---------- 右栏 ---------- */

.detail-col {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-width: 0;
}

.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.detail-crumb {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-row {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-xl);
  flex-wrap: wrap;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 72px;
}

.metric.is-wide {
  flex: 1;
  min-width: 160px;
}

.metric span {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.metric b {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* ---------- 房屋状态网格 ---------- */

.floor-chips {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.floor-chip {
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

.floor-chip:hover {
  color: var(--color-text-primary);
}

.floor-chip.active {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.grid-summary {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.grid-body {
  position: relative;
  min-height: 120px;
}

.grid-empty {
  margin: 0;
  padding: var(--spacing-lg) 0;
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.grid-group {
  margin-bottom: var(--spacing-lg);
}

.grid-group:last-child {
  margin-bottom: 0;
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

/* 分组标题旁「＋」快速添加房屋（第三轮 C4）：与树节点操作钮同款交互 */
.group-add {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.group-add:hover {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.group-add svg {
  width: 13px;
  height: 13px;
}

.grid-group-empty {
  margin: 0;
  padding: var(--spacing-xs) 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
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
  width: 36px;
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
  min-width: 56px;
  height: 34px;
  padding: 0 var(--spacing-sm);
  border-radius: var(--radius-md);
  font-family: inherit;
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

.grid-block:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

/* 悬浮快捷操作遮罩（第三轮 C4）：visibility 联动 opacity，
   隐态不放行点击（不拦截块本体点击弹信息卡） */
.block-actions {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
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
  width: 22px;
  height: 22px;
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

/* ---------- 网格多选（R4-D3）：橡皮筋选框 / 选中态 / 批量操作条 ---------- */

/* 框选拖拽中：十字光标提示 + 禁文本选择 */
.grid-body.is-marquee {
  cursor: crosshair;
  user-select: none;
}

/* 橡皮筋：半透明品牌蓝矩形 + 品牌蓝描边（仅视觉，pointer-events 放行给容器） */
.marquee-rect {
  position: absolute;
  z-index: 4;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  background-color: color-mix(in srgb, var(--color-primary) 16%, transparent);
  pointer-events: none;
}

/* 选中态：品牌蓝描边环叠加在语义底色之上（不改底色，状态色仍可辨） */
.grid-block.is-selected,
.grid-block.is-selected:hover {
  box-shadow: 0 0 0 2px var(--color-primary);
}

/* 勾选角标：卡片右上角小圆点 + 对勾（token 取色，白用卡底色） */
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

/* 批量操作条：sticky 浮出于网格底部，滚动时保持可见 */
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
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-primary);
}

.selection-actions {
  display: inline-flex;
  align-items: center;
}

/* 操作条浮入/浮出（v-if 切换） */
.sel-bar-enter-active,
.sel-bar-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.sel-bar-enter-from,
.sel-bar-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* ---------- 楼栋级联删除对话框（R4-D2，token 取色） ---------- */

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

/* ---------- 响应式 ---------- */

@media (max-width: 1199px) {
  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .master-detail {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
