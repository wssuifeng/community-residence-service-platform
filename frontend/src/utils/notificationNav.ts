import type { Role } from '@/types/api'

/**
 * 通知 → 目标路由共享映射（消息中心列表与铃铛下拉共用，验收修复第二轮 R1）。
 * sourceType 前缀匹配、大小写不敏感；sourceId 为空或当前角色无对应详情页时
 * 返回 null（调用方不跳转，仅做已读标记等本地处理）。
 *
 * 三端路由现状（router/resident|staff|admin.ts，2026-09-12 核实）：
 * - 居民端：work-orders/:id、feedbacks/:id、notices/:id 详情页齐全，reservations 列表页；
 * - 服务人员端：仅 work-orders/:id 有详情页，其余来源不跳转；
 * - 管理端：work-orders/:id 为详情页；feedbacks/:id、notices/:id 为既有 redirect
 *   落对应管理列表页；reservations 列表页。
 */

/** 角色 → 路由前缀（GUEST 无导航语境，恒不跳转） */
function prefixOf(role: Role): '/resident' | '/staff' | '/admin' | null {
  if (role === 'RESIDENT') return '/resident'
  if (role === 'STAFF') return '/staff'
  if (role === 'ADMIN' || role === 'SUPER_ADMIN') return '/admin'
  return null
}

/** 通知 → 目标路由；sourceId 为空返回 null（不跳转） */
export function notificationTarget(
  item: { sourceType?: string | null; sourceId?: number | null },
  role: Role
): string | null {
  if (item.sourceId === null || item.sourceId === undefined) return null
  const prefix = prefixOf(role)
  if (prefix === null) return null

  const type = (item.sourceType ?? '').toUpperCase()
  /* 服务人员端仅工单有详情页，其余来源一律不跳 */
  if (type.startsWith('WORK_ORDER')) return `${prefix}/work-orders/${item.sourceId}`
  if (role === 'STAFF') return null

  if (type.startsWith('FEEDBACK')) return `${prefix}/feedbacks/${item.sourceId}`
  if (type.startsWith('NOTICE')) return `${prefix}/notices/${item.sourceId}`
  /* 预约类无逐条详情页，落预约列表页 */
  if (type === 'RESERVATION' || type === 'RESOURCE_RESERVATION') {
    return prefix === '/admin' ? '/admin/reservations' : '/resident/reservations'
  }
  /* LEASE / SYSTEM 等无详情语境 */
  return null
}
