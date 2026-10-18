/** 服务人员端只展示工单VO已经提供的真实资料；不推测性别或居住身份。 */
export function residentInitial(residentName: string): string {
  return residentName?.trim().slice(0, 1) || '居'
}

export function residentDisplayName(order: { id: number; residentName: string }): string {
  return order.residentName?.trim() || '居民'
}

/** 工单未关联唯一居住关系时，仅表示提交人的居民角色，不冒充关系类型。 */
export function residentRelationLabel(_order: { id: number }): string {
  return '居民'
}
