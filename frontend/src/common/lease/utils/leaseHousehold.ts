import { getHouseResidentList, getResidenceDependents } from '@/common/resident/api/resident'
import { loadAllPages } from '@/common/web/utils/paged'
/** 精确列出退租影响的成员；读取失败不能当作无成员继续提交。 */
export async function leaseHouseholdNotice(lease: { id: number; houseId: number; tenantId: number }) {
  const rows = await loadAllPages(q => getHouseResidentList(lease.houseId, { ...q, status: 'ACTIVE' }))
  const candidates = rows.filter(r => r.residentId === lease.tenantId && !r.anchorRelationId)
  const roots = candidates.filter(r => r.leaseId === lease.id || (!r.leaseId && candidates.length === 1))
  const groups = await Promise.all(roots.map(r => getResidenceDependents(r.id)))
  const members = groups.flat()
  return { confirmDependents: members.length > 0, text: members.length ? `同时结束以下 ${members.length} 名同住人员的居住关系：${members.map(m => m.residentName).join('、')}。独立账号与历史保留，请确认整户处理。` : '' }
}
