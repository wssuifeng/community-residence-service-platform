/**
 * 【MOCK 临时占位区块】2026-09-11 —— 后端接口就绪后请整块删除，改读真实字段
 * ───────────────────────────────────────────────────────────────────────────
 * 用户指示：缺数据的元素不静默省略——临时造数据保证 UI 完整，但必须显式标识。
 * 全局检索标识：MOCK
 *
 * 待补接口 ②：居民性别（用于「姓 + 先生/女士」组合名）
 *   需要：WorkOrderVO 增补 gender 字段，或把 /residents/{id} 放行给 STAFF。
 *   现状：全后端无 gender（Resident 实体、ResidentVO 均无）。
 *
 * 待补接口 ③：居民居住身份（业主/租客/家属，工单不一定由业主发起）
 *   需要：WorkOrderVO 增补 relationType 字段。
 *   现状：RelationVO.relationType 有该数据，但 ResidenceRelationController.java:31
 *         白名单为 RESIDENT/ADMIN/SUPER_ADMIN，不含 STAFF。
 *
 * 两个取值都按工单 ID 取模：同一工单在服务人员端各页面渲染一致，刷新不跳动。
 */

const MOCK_GENDER_SUFFIX = ['先生', '女士']
const MOCK_RELATION_LABELS = ['业主', '租客', '家属']

/** 头像位字符（后端无头像字段），取真实姓名首字 */
export function residentInitial(residentName: string): string {
  return residentName ? residentName.slice(0, 1) : '居'
}

/** 组合显示名：真实姓名首字 + MOCK 性别称谓 */
export function residentDisplayName(order: { id: number; residentName: string }): string {
  return residentInitial(order.residentName) + MOCK_GENDER_SUFFIX[Math.abs(order.id) % MOCK_GENDER_SUFFIX.length]
}

/** MOCK 居住身份标签 */
export function residentRelationLabel(order: { id: number }): string {
  return MOCK_RELATION_LABELS[Math.abs(order.id) % MOCK_RELATION_LABELS.length]
}
