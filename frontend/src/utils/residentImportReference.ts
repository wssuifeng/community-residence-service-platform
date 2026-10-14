import type { HouseManageItem } from '@/types/modules/housing'
/** 仅在界面查看业务编号；不提供结构数据导出。 */
export function sortHouseReference(rows: HouseManageItem[]): HouseManageItem[] {
  const compare=(a:string|null|undefined,b:string|null|undefined)=>(a??'').localeCompare(b??'','zh-CN',{numeric:true})
  return [...rows].sort((a,b)=>compare(a.communityCode,b.communityCode)||compare(a.buildingCode??a.buildingName,b.buildingCode??b.buildingName)||compare(a.unitCode??a.unitName,b.unitCode??b.unitName)||compare(a.houseNumber,b.houseNumber))
}
