import { describe, expect, it } from 'vitest'
import { notificationTarget } from '@/utils/notificationNav'

/**
 * R74/R48 工单通知落点（`notificationNav.ts`）：8 个工单事件（提交/受理/派单/接单/
 * 开始处理/提交结果/居民确认/终审关闭）后端统一以 `sourceType='WORK_ORDER'` +
 * `sourceId=工单ID` 写入（WorkOrderService 各动作的 notify 调用点），
 * 故三端都必须落到各自的工单详情页——本用例是**只增不改**既有判定分支的守卫。
 */
describe('工单通知 → 工单详情落点（R74/R48）', () => {
  it.each([
    ['RESIDENT', '/resident/work-orders/7'],
    ['STAFF', '/staff/work-orders/7'],
    ['ADMIN', '/admin/work-orders/7'],
    ['SUPER_ADMIN', '/admin/work-orders/7']
  ] as const)('%s 的工单通知落到 %s', (role, expected) => {
    expect(notificationTarget({ sourceType: 'WORK_ORDER', sourceId: 7 }, role)).toBe(expected)
  })

  it('工单来源的历史/细分写法同样命中（前缀匹配，不因后缀漂移丢跳转）', () => {
    expect(notificationTarget({ sourceType: 'WORK_ORDER_GROUP', sourceId: 7 }, 'RESIDENT')).toBe(
      '/resident/work-orders/7'
    )
    expect(notificationTarget({ sourceType: 'work_order', sourceId: 7 }, 'ADMIN')).toBe(
      '/admin/work-orders/7'
    )
  })

  it('明细型来源缺 sourceId 时不跳转（停留在原页），GUEST 无导航语境同样不跳', () => {
    expect(notificationTarget({ sourceType: 'WORK_ORDER', sourceId: null }, 'RESIDENT')).toBeNull()
    expect(notificationTarget({ sourceType: 'WORK_ORDER', sourceId: 7 }, 'GUEST')).toBeNull()
  })
})

/**
 * 租约族通知深链口径（DEF-121）：LEASE_MOVE_OUT（退租申请提交/通过/驳回三通知）
 * 后端以 type=LEASE + sourceId=租约 ID 写入（LeaseMoveOutApplicationService），与
 * LEASE/LEASE_TENANCY 同处理——居民端深链租约 Tab、管理端落租约详情页。
 * LEASE_SIGN 为前端预占的死值（后端从不写入，签约完成走 LEASE_AGREEMENT/协议 ID），
 * 分支已删：不识别的来源不跳转。
 */
describe('租约族通知 → 租约深链（DEF-121）', () => {
  it('LEASE_MOVE_OUT 通知居民端深链 ?tab=lease&leaseId={租约ID}', () => {
    expect(
      notificationTarget({ sourceType: 'LEASE_MOVE_OUT', sourceId: 9 }, 'RESIDENT')
    ).toBe('/resident/housing-lease?tab=lease&leaseId=9')
  })

  it('LEASE_MOVE_OUT 通知管理端落租约详情页 /admin/leases/{租约ID}', () => {
    expect(notificationTarget({ sourceType: 'LEASE_MOVE_OUT', sourceId: 9 }, 'ADMIN')).toBe(
      '/admin/leases/9'
    )
    expect(notificationTarget({ sourceType: 'LEASE_MOVE_OUT', sourceId: 9 }, 'SUPER_ADMIN')).toBe(
      '/admin/leases/9'
    )
  })

  it('LEASE_MOVE_OUT 缺 sourceId 时两端都只落租约列表（不拼出 /undefined 详情链）', () => {
    expect(notificationTarget({ sourceType: 'LEASE_MOVE_OUT', sourceId: null }, 'RESIDENT')).toBe(
      '/resident/housing-lease?tab=lease'
    )
    expect(notificationTarget({ sourceType: 'LEASE_MOVE_OUT', sourceId: null }, 'ADMIN')).toBe(
      '/admin/leases'
    )
  })

  it('既有 LEASE / LEASE_TENANCY 深链行为保持不变（回归守卫）', () => {
    expect(notificationTarget({ sourceType: 'LEASE', sourceId: 3 }, 'RESIDENT')).toBe(
      '/resident/housing-lease?tab=lease&leaseId=3'
    )
    expect(notificationTarget({ sourceType: 'LEASE_TENANCY', sourceId: 3 }, 'ADMIN')).toBe(
      '/admin/leases/3'
    )
  })

  it('LEASE_SIGN 死分支已删除：不再按租约深链处理（不识别即不跳转）', () => {
    expect(notificationTarget({ sourceType: 'LEASE_SIGN', sourceId: 5 }, 'RESIDENT')).toBeNull()
    expect(notificationTarget({ sourceType: 'LEASE_SIGN', sourceId: 5 }, 'ADMIN')).toBeNull()
  })

  it('LEASE_AGREEMENT / LEASE_PAYMENT 的 sourceId 非租约 ID，仍只落列表页（严禁拼详情链）', () => {
    expect(notificationTarget({ sourceType: 'LEASE_AGREEMENT', sourceId: 5 }, 'ADMIN')).toBe(
      '/admin/leases'
    )
    expect(notificationTarget({ sourceType: 'LEASE_PAYMENT', sourceId: 5 }, 'RESIDENT')).toBe(
      '/resident/housing-lease?tab=lease'
    )
  })
})
