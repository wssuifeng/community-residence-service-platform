import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import MoveOutApplicationView from '@/views/admin/lease/MoveOutApplicationView.vue'
import DepositRefundDialog from '@/views/admin/lease/DepositRefundDialog.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { IDepositRefund, ILeasePayment, ILeaseRecord, DepositStatus } from '@/common/lease/types/lease'

/**
 * 退租审核弹窗（含「通过后不可撤销」提示）属长表单重灾区：蒙层点击不得关闭
 * （组件库设计规范 §6.3.2，收口见 `src/common/web/utils/elementPlusDefaults.ts`）。
 *
 * 押金退还本地标记（OCR DEF-112）：发起成功后本页回读租约详情收口「退还中/已退还」
 * 标记，映射必须是三态（REFUNDED→SUCCESS / REFUNDING→PENDING / 其余清除）——
 * 渠道失败后端回落 PAID、部分退款成功也保持 PAID，原「非 REFUNDED 即 PENDING」
 * 会让失败/部分退款永远显示「退还中」。租约接口按用例显式打桩以精确控制回读结果。
 */
vi.mock('@/common/lease/api/leaseMoveOut', async () => (await import('@/test/fakeBackend')).fakeApi('leaseMoveOut'))
vi.mock('@/common/community/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))

const leaseApi = vi.hoisted(() => ({
  getLease: vi.fn(),
  listLeasePayments: vi.fn(async () => [] as ILeasePayment[]),
  listDepositRefunds: vi.fn(async () => [] as IDepositRefund[]),
  createDepositRefund: vi.fn(async () => ({}) as IDepositRefund)
}))
vi.mock('@/common/lease/api/lease', () => leaseApi)

const backend = () => getFakeBackend()

/** 蒙层点击手势：mousedown + mouseup + click 同落在 `.el-overlay-dialog`（EP 的判定链） */
async function clickMask(wrapper: VueWrapper): Promise<void> {
  const maskArea = wrapper.find('.el-overlay-dialog')
  await maskArea.trigger('mousedown')
  await maskArea.trigger('mouseup')
  await maskArea.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
  backend().state.moveOutApplications.push({
    id: 1,
    leaseId: 1,
    residentId: 1,
    communityId: 1,
    moveOutDate: '2026-10-01',
    reason: '工作调动',
    status: 'PENDING',
    reviewRemark: null,
    createdAt: '2026-09-22T09:00:00'
  })
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('退租审核弹窗 · 蒙层点击不关闭', () => {
  it('点蒙层后弹窗保持打开、已选房源回收方式不丢，且未提交审核', async () => {
    const wrapper = mount(MoveOutApplicationView, {
      global: { plugins: [createPinia(), ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()

    /* 打开「通过退租申请」弹窗（含「通过后不可撤销」提示） */
    const approveButton = wrapper.findAll('button').find((item) => item.text().trim() === '通过')
    expect(approveButton, '未找到「通过」按钮').toBeTruthy()
    await approveButton?.trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').isVisible()).toBe(true)
    expect(wrapper.text()).toContain('通过后不可撤销')

    /* 选择「下架」回收方式后误点蒙层 */
    const offline = wrapper.findAll('input[type="radio"]').find((item) => item.element.getAttribute('value') === 'OFFLINE')
    expect(offline, '未找到「下架」单选项').toBeTruthy()
    await offline?.setValue()
    await clickMask(wrapper)

    /* 弹窗仍在、选择仍在、审核未提交（申请保持待审核、未写入审核留痕） */
    expect(wrapper.find('.el-dialog').isVisible()).toBe(true)
    expect((offline?.element as HTMLInputElement).checked).toBe(true)
    expect(backend().state.moveOutApplications[0].status).toBe('PENDING')
    expect(backend().state.moveOutApplications[0].reviewRemark ?? null).toBeNull()
  })
})

describe('退租审核 · 押金退还本地标记三态映射（OCR DEF-112）', () => {
  /** 已通过的退租申请：押金退还入口只对 APPROVED 行开放 */
  function seedApprovedRow(): void {
    backend().state.moveOutApplications.push({
      id: 2,
      leaseId: 5,
      residentId: 1,
      communityId: 1,
      moveOutDate: '2026-10-01',
      reason: '工作调动',
      status: 'APPROVED',
      reviewRemark: null,
      createdAt: '2026-09-20T09:00:00'
    })
  }

  /** 弹窗打开时 openRefund 取到、成功后再回读一次的租约（depositStatus 由用例控制） */
  function refundableLease(depositStatus: DepositStatus): ILeaseRecord {
    return {
      id: 5,
      tenantId: 1,
      tenantName: '演示居民',
      communityId: 1,
      houseId: 1,
      houseLocation: '1 号楼1 单元101',
      startDate: '2026-01-01',
      endDate: '2026-12-31',
      monthlyRent: 3000,
      deposit: 6000,
      status: 'MOVED_OUT',
      agreementStatus: 'SIGNED',
      depositStatus,
      createdAt: '2026-01-01T10:00:00'
    }
  }

  /** 挂载 → 切「已通过」Tab → 打开押金退还弹窗（父层 handleRefundSuccess 的触发前置） */
  async function mountAndOpenRefundDialog(): Promise<VueWrapper> {
    const wrapper = mount(MoveOutApplicationView, {
      global: { plugins: [createPinia(), ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()

    const approvedTab = wrapper.findAll('button').find((item) => item.text().trim() === '已通过')
    expect(approvedTab, '未找到「已通过」Tab').toBeTruthy()
    await approvedTab!.trigger('click')
    await flushPromises()

    const refundButton = wrapper.findAll('button').find((item) => item.text().trim() === '发起押金退还')
    expect(refundButton, '未找到「发起押金退还」按钮').toBeTruthy()
    await refundButton!.trigger('click')
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    seedApprovedRow()
  })

  it('回读 PAID（部分退款成功/渠道失败回落）：本地标记清除，押金列不再误显「退还中」', async () => {
    leaseApi.getLease.mockResolvedValue(refundableLease('PAID'))
    const wrapper = await mountAndOpenRefundDialog()

    wrapper.findComponent(DepositRefundDialog).vm.$emit('success')
    await flushPromises()

    expect(wrapper.text()).not.toContain('退还中')
    expect(wrapper.text()).toContain('待核对')
    /* 标记清除后退还入口不收口：剩余可退余额可再次发起 */
    expect(wrapper.findAll('button').some((item) => item.text().trim() === '发起押金退还')).toBe(true)
  })

  it('回读 REFUNDED：本地标记 SUCCESS，押金列显示「已退还」且入口收口为已办结', async () => {
    leaseApi.getLease.mockResolvedValue(refundableLease('REFUNDED'))
    const wrapper = await mountAndOpenRefundDialog()

    wrapper.findComponent(DepositRefundDialog).vm.$emit('success')
    await flushPromises()

    expect(wrapper.text()).toContain('已退还')
    expect(wrapper.text()).not.toContain('退还中')
    expect(wrapper.findAll('button').some((item) => item.text().trim() === '发起押金退还')).toBe(false)
  })

  it('回读失败（catch 分支）同样按缓存租约三态映射：PAID 不误标「退还中」', async () => {
    /* 第一次（openRefund）成功取到 PAID 租约，第二次（成功后回读）网络失败走 catch */
    leaseApi.getLease.mockResolvedValueOnce(refundableLease('PAID'))
    leaseApi.getLease.mockRejectedValueOnce(new Error('网络异常'))
    const wrapper = await mountAndOpenRefundDialog()

    wrapper.findComponent(DepositRefundDialog).vm.$emit('success')
    await flushPromises()

    expect(wrapper.text()).not.toContain('退还中')
    expect(wrapper.text()).toContain('待核对')
  })
})
