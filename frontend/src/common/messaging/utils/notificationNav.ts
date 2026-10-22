import type { Role } from '@/common/web/types/api'

/**
 * 通知 → 目标路由共享映射（消息中心列表与铃铛下拉共用，验收修复第二轮 R1；
 * 批次 f 补 R66~R71 租约类并修正 P0 跳转）。
 *
 * 判定顺序与防错口径：
 * 1. sourceId 为空不再一律不跳——列表型来源（租约/协议/支付/预约/入住申请）
 *    有可落地的列表页，照常返回列表路由；
 * 2. 明细型来源（工单/反馈/公告）必须有 sourceId 才跳详情页，否则返回 null 停留在原页；
 * 3. `sourceType='LEASE'` / `LEASE_TENANCY` / `LEASE_MOVE_OUT` 时 `sourceId` **是租约 ID**
 *    （后端 LeaseService / LeaseMoveOutApplicationService / DepositRefundService /
 *    ResidenceApplicationService 的写入口径），故居民端可拼 `?tab=lease&leaseId={id}`
 *    深链定位、管理端拼 `/admin/leases/{id}`；
 * 4. `VIEWING_APPOINTMENT` 的 `sourceId` 是看房预约 ID：居民落预约详情，
 *    管理端落房源管理的看房预约分区，服务人员落「我的带看」工作台；
 * 5. `LEASE_AGREEMENT` / `LEASE_PAYMENT` 的 `sourceId` **分别是协议 ID 与支付单 ID，
 *    不是租约 ID**（见 AgreementService / PaymentService 的通知写入）——**严禁**拼
 *    `/leases/{id}`，否则必然落到别人的租约或 404；这两类一律只落列表页。
 *
 * 三端路由现状（router/resident|staff|admin.ts，批次 g 核实）：
 * - 居民端：work-orders/:id、feedbacks/:id、notices/:id 详情页齐全；租约落
 *   /resident/housing-lease?tab=lease（Hub）并可带 leaseId 深链；入住申请落
 *   /resident/housing-lease?tab=application；reservations 列表页；
 * - 服务人员端：仅 work-orders/:id 有详情页，其余来源不跳转；
 * - 管理端：work-orders/:id、leases/:id 为详情页；租住/房源/退租/协议模板/入住申请
 *   均收口在 /admin/housing-lease?tab=… 的 Hub（旧路径仍以 redirect 可用）；
 *   feedbacks/:id、notices/:id 为既有 redirect 落对应管理列表页。
 */

/** 账号角色 → 路由前缀；未登录公开访问没有通知导航目标 */
function prefixOf(role: Role | null): '/resident' | '/staff' | '/admin' | null {
  if (role === 'RESIDENT') return '/resident'
  if (role === 'STAFF') return '/staff'
  if (role === 'ADMIN' || role === 'SUPER_ADMIN') return '/admin'
  return null
}

/** 居民端租约列表落地地址（Hub 的租住契约 Tab）；带 leaseId 时同时作为深链定位参数 */
function residentLeaseList(leaseId?: number): string {
  return leaseId === undefined
    ? '/resident/housing-lease?tab=lease'
    : `/resident/housing-lease?tab=lease&leaseId=${leaseId}`
}

/** 通知 → 目标路由；无可靠落地页时返回 null（调用方不跳转，仅做已读标记等本地处理） */
export function notificationTarget(
  item: { sourceType?: string | null; sourceId?: number | null },
  role: Role | null
): string | null {
  const prefix = prefixOf(role)
  if (prefix === null) return null

  const sourceId = item.sourceId ?? null
  const type = (item.sourceType ?? '').toUpperCase()

  /* 服务人员端仅工单有详情页，其余来源一律不跳（与既有一致） */
  if (type.startsWith('WORK_ORDER')) {
    return sourceId === null ? null : `${prefix}/work-orders/${sourceId}`
  }
  if (type === 'VIEWING_APPOINTMENT') {
    if (role === 'STAFF') return '/staff/viewings'
    if (prefix === '/admin') return '/admin/housing-lease?tab=housings&view=viewings'
    return sourceId === null ? '/resident/reservations?tab=viewing' : `/resident/viewing-appointments/${sourceId}`
  }
  if (role === 'STAFF') return null

  /* ---- 租约类（R66~R71）---- */

  /*
   * LEASE_TENANCY（待签约/承租确认）与 LEASE_MOVE_OUT（退租申请提交/通过/驳回，
   * OCR DEF-121 补）的 sourceId 与 LEASE 同义 = 租约 ID，与 LEASE 同处理；
   * 后端从不写 LEASE_SIGN（签约完成通知实际走 LEASE_AGREEMENT/协议 ID），该死分支已删。
   */
  if (type === 'LEASE' || type === 'LEASE_TENANCY' || type === 'LEASE_MOVE_OUT') {
    if (prefix === '/admin') {
      return sourceId === null ? '/admin/leases' : `/admin/leases/${sourceId}`
    }
    return residentLeaseList(sourceId ?? undefined)
  }
  /* 协议 / 支付单：sourceId 非租约 ID，只落列表页（管理端同样落租约列表） */
  if (type === 'LEASE_AGREEMENT' || type === 'LEASE_PAYMENT') {
    return prefix === '/admin' ? '/admin/leases' : residentLeaseList()
  }
  /* 到期提醒任务 LeaseExpiryReminderTask 写入 sourceType=LEASE_RECORD（type 参数为 LEASE，
     sourceId=租约 ID），历史数据兜底 */
  if (type === 'LEASE_RECORD') {
    if (prefix === '/admin') {
      return sourceId === null ? '/admin/leases' : `/admin/leases/${sourceId}`
    }
    return residentLeaseList(sourceId ?? undefined)
  }

  /* ---- 入住申请（R62 补/R66：审批结果通知，无逐条详情页，落 Hub 的入住申请 Tab） ----
     批次 g 起两端都有该落地页：居民端 /resident/housing-lease?tab=application（本人申请与撤回），
     管理端 /admin/housing-lease?tab=applications（审核队列） */
  if (type.startsWith('RESIDENCE')) {
    return prefix === '/admin'
      ? '/admin/housing-lease?tab=applications'
      : '/resident/housing-lease?tab=application'
  }

  /* ---- 明细型来源：必须有 sourceId 才能拼详情页 ---- */
  if (type.startsWith('FEEDBACK')) {
    return sourceId === null ? null : `${prefix}/feedbacks/${sourceId}`
  }
  if (type.startsWith('NOTICE')) {
    return sourceId === null ? null : `${prefix}/notices/${sourceId}`
  }

  /* 预约类无逐条详情页，落预约列表页 */
  if (type === 'RESERVATION' || type === 'RESOURCE_RESERVATION') {
    return prefix === '/admin' ? '/admin/reservations' : '/resident/reservations'
  }

  /* SYSTEM / TASK 等无跳转语境 */
  return null
}
