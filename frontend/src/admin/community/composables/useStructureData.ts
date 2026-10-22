import { ref, computed, onBeforeUnmount, type Ref } from 'vue'
import { getBuildingList, getUnitList, getHouseList } from '@/common/community/api/community'
import { getDashboardStats } from '@/common/statistics/api/statistics'
import type { IHouse } from '@/common/community/types/community'
import type { IDashboardStats } from '@/common/statistics/types/statistics'
import type { BuildingNode, HouseEntry } from '@/admin/community/types/structure'
import { loadAllPages, mapLimited, StaleStructureLoad } from '@/admin/community/utils/structureData'
/** 范围目录与房屋缓存由同一请求版本管理，保持按需读取及4并发。 */
export function useStructureData(selectedCommunityId: Ref<number | null>, activeBuildingId: Ref<number | null>, activeUnitId: Ref<number | null>, activeFloor: Ref<number | null>) {
const buildings = ref<BuildingNode[]>([])
const structureLoading = ref(false)
const structureError = ref('')
const scopeLoading = ref(false)
const structureStats = ref<IDashboardStats | null>(null)
let houseScopeSeq = 0
/** 是否已成功加载过当前小区结构（区分「未加载」与「确实为空」两种空态） */
const structureLoaded = ref(false)

let structureSeq = 0

/* 元数据用于范围选择/结构维护；房屋仅按用户所选范围读取。
   小区统计独立取聚合接口，不能把首楼栋缓存解释为小区全量。 */
async function loadStructure(communityId: number, initial = false): Promise<void> {
  const seq = ++structureSeq
  houseScopeSeq += 1
  unitReloadSeq += 1
  structureLoading.value = true
  scopeLoading.value = false
  structureError.value = ''
  structureLoaded.value = false
  structureStats.value = null
  buildings.value = []
  const current = () => seq === structureSeq
  try {
    const [buildingList, stats] = await Promise.all([
      loadAllPages(query => getBuildingList(communityId, query), current),
      getDashboardStats({ communityId })
    ])
    buildingList.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN', { numeric: true }) || a.id - b.id)
    const nodes = await mapLimited(buildingList, async building => ({
      building,
      units: (await loadAllPages(query => getUnitList(building.id, query), current))
        .map(unit => ({ unit, houses: [] as IHouse[], housesLoaded: false }))
    }))
    if (!current()) return
    buildings.value = nodes
    structureStats.value = stats
    if (initial || (activeBuildingId.value !== null && !nodes.some(n => n.building.id === activeBuildingId.value))) {
      activeBuildingId.value = nodes[0]?.building.id ?? null
      activeUnitId.value = null
    }
    if (activeUnitId.value !== null && !nodes.some(n => n.units.some(u => u.unit.id === activeUnitId.value))) activeUnitId.value = null
    structureLoaded.value = true
    await loadScopeHouses()
  } catch (error) {
    if (!current() || error instanceof StaleStructureLoad) return
    structureError.value = error instanceof Error ? error.message : '加载结构失败'
  } finally {
    if (current()) structureLoading.value = false
  }
}

async function loadScopeHouses(): Promise<void> {
  const seq = ++houseScopeSeq
  const directorySeq = structureSeq
  const current = () => seq === houseScopeSeq && directorySeq === structureSeq
  scopeLoading.value = true
  structureError.value = ''
  const units = buildings.value
    .filter(node => activeBuildingId.value === null || node.building.id === activeBuildingId.value)
    .flatMap(node => node.units)
    .filter(node => (activeUnitId.value === null || node.unit.id === activeUnitId.value) && !node.housesLoaded)
  try {
    const results = await mapLimited(units, async node => ({
      node, houses: await loadAllPages(query => getHouseList(node.unit.id, query), current)
    }))
    if (!current()) return
    for (const { node, houses } of results) {
      node.houses = houses
      node.housesLoaded = true
    }
  } catch (error) {
    if (!current() || error instanceof StaleStructureLoad) return
    structureError.value = error instanceof Error ? error.message : '加载房屋失败'
  } finally {
    if (current()) scopeLoading.value = false
  }
}

function retryStructure(): void {
  if (selectedCommunityId.value !== null) loadStructure(selectedCommunityId.value)
}

/* 局部刷新单个单元的房屋（房屋增删改后不整小区重载，保持筛选与展开态）。
   独立序列号：与结构整体加载互不作废，避免两侧并发时把对方结果丢弃 */
let unitReloadSeq = 0

async function reloadUnitHouses(unitId: number): Promise<void> {
  const seq = ++unitReloadSeq
  try {
    const directorySeq = structureSeq
    const houses = await loadAllPages(query => getHouseList(unitId, query), () => seq === unitReloadSeq && directorySeq === structureSeq)
    if (seq !== unitReloadSeq) return
    for (const building of buildings.value) {
      const unitNode = building.units.find((item) => item.unit.id === unitId)
      if (unitNode) {
        unitNode.houses = houses
        unitNode.housesLoaded = true
        break
      }
    }
    const communityId = selectedCommunityId.value
    if (communityId !== null) {
      const stats = await getDashboardStats({ communityId })
      if (seq === unitReloadSeq && directorySeq === structureSeq) structureStats.value = stats
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
function resetStructureData() { structureSeq++; houseScopeSeq++; unitReloadSeq++; structureLoading.value = false; scopeLoading.value = false; structureLoaded.value = false; structureStats.value = null; buildings.value = []; structureError.value = '' }
onBeforeUnmount(resetStructureData)
return {buildings, structureLoading, structureError, scopeLoading, structureStats, structureLoaded, houseEntries, loadStructure, loadScopeHouses, retryStructure, reloadUnitHouses, resetStructureData}
}
