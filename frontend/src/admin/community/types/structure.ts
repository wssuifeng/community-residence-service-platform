import type { IHouse, IUnit, IBuilding } from '@/common/community/types/community'
export interface UnitNode {
  unit: IUnit
  houses: IHouse[]
  housesLoaded: boolean
}

export interface BuildingNode {
  building: IBuilding
  units: UnitNode[]
}

export interface HouseEntry {
  house: IHouse
  buildingId: number
  buildingName: string
  unitId: number
  unitName: string
}

export interface GridGroup {
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
