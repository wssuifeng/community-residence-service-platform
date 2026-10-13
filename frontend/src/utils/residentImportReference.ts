import type { HouseManageItem } from '@/types/modules/housing'
/** 导出的位置顺序不改变内部自增ID，表格软件公式字符只按文本显示。 */
export function sortHouseReference(rows: HouseManageItem[]): HouseManageItem[] {
  return [...rows].sort((a, b) => a.communityId - b.communityId ||
    (a.buildingName ?? '').localeCompare(b.buildingName ?? '', 'zh-CN', { numeric: true }) ||
    (a.unitName ?? '').localeCompare(b.unitName ?? '', 'zh-CN', { numeric: true }) ||
    a.floor - b.floor || a.houseNumber.localeCompare(b.houseNumber, 'zh-CN', { numeric: true }) || a.houseId - b.houseId)
}
export function houseReferenceCsv(rows: HouseManageItem[]): string {
  const cell = (value: unknown) => {
    let text = String(value ?? '')
    if (/^[=+\-@\t\r]/.test(text)) text = "'" + text
    return '"' + text.replaceAll('"', '""') + '"'
  }
  return [['社区ID', '社区', '楼栋', '单元', '楼层', '房号', '房屋ID', '房屋状态'],
    ...sortHouseReference(rows).map(r => [r.communityId, r.communityName, r.buildingName, r.unitName, r.floor, r.houseNumber, r.houseId, r.houseStatus])]
    .map(row => row.map(cell).join(',')).join('\r\n')
}
