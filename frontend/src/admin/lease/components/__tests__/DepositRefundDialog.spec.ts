import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import DepositRefundDialog from '@/admin/lease/components/DepositRefundDialog.vue'
import { CODES, actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { IDepositRefund, ILeasePayment, ILeaseRecord } from '@/common/lease/types/lease'
import { ApiError } from '@/common/web/utils/request'

/**
 * 押金退还弹窗（R72）· 前端前置校验与幂等键口径。
 *
 * 契约依据：接口设计 §9.20.1（发起押金退还）+ 后端定稿错误码
 * （5703 押金状态不允许退还 / 5704 金额超实缴 / 5705 渠道不一致 / 5706 请求号冲突）。
 * 断言以可见文本与真实 DOM 为准（金额输入框、只读渠道框、幂等请求号）。
 *
 * OCR DEF-123 增补：FAILED 回执停留弹窗（不 emit success，父层不误刷新）、
 * 押金状态全五态中文渲染、lease 为 null 时的显式错误文案。
 */

vi.mock('@/common/lease/api/lease', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))

const backend = () => getFakeBackend()

/** 造一份「已搬出 + 押金已缴 + 有成功首期单」的租约：押金退还的合法前置 */
function seedRefundableLease(overrides: Partial<ILeaseRecord> = {}): ILeaseRecord {
  const state = backend().state
  const lease = {
    id: 1,
    tenantId: 1,
    tenantName: '演示居民',
    communityId: 1,
    houseId: 1,
    houseLocation: '1 号楼1 单元101',
    startDate: '2026-01-01',
    endDate: '2026-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    status: 'MOVED_OUT' as const,
    agreementStatus: 'SIGNED' as const,
    depositStatus: 'PAID' as const,
    createdAt: '2026-01-01T10:00:00',
    ...overrides
  }
  state.leases.push(lease as (typeof state.leases)[number])
  /* 成功首期单：实缴押金与原渠道的唯一依据（押金部分 6000 / 租金部分 3000） */
  state.payments.push({
    paymentNo: 'FIRST20260922001',
    leaseId: lease.id,
    paymentType: 'FIRST',
    channel: 'ALIPAY',
    months: 1,
    amount: 9000,
    rentAmount: 3000,
    depositAmount: 6000,
    status: 'SUCCESS',
    createdAt: '2026-01-01T10:05:00',
    paidAt: '2026-01-01T10:06:00',
    channelTradeNo: 'ALI-TRADE-001'
  })
  return lease as ILeaseRecord
}

async function mountDialog(lease: ILeaseRecord): Promise<VueWrapper> {
  /* 弹窗的取数与默认金额全在「打开」这个跃迁里完成（watch modelValue），
     故必须从关闭态起挂载再打开，直接以 modelValue=true 挂载不会触发 watch。
     支付单由父层传入（实缴押金与原渠道的唯一依据），与真实调用方一致。 */
  /* 支付单由父层传入（实缴押金与原渠道的唯一依据），与真实调用方一致 */
  const payments = backend().state.payments.filter(
    (item) => item.leaseId === lease.id
  ) as unknown as ILeasePayment[]
  const wrapper = mount(DepositRefundDialog, {
    props: { lease, firstPayments: payments, modelValue: false },
    global: { plugins: [createPinia(), ElementPlus] },
    attachTo: document.body
  })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}

/** 金额输入框（el-input-number 的 inner input）：弹窗内唯一的 number 输入 */
function amountInput(wrapper: VueWrapper) {
  const input = wrapper.findAll('input').find((item) => item.element.getAttribute('role') === 'spinbutton')
  expect(input, '未找到金额输入框').toBeTruthy()
  return input!
}

/** 只读渠道框：readonly 且值为渠道中文名 */
function channelInput(wrapper: VueWrapper) {
  return wrapper.findAll('input').find((item) => item.element.hasAttribute('readonly'))
}

function requestNoText(wrapper: VueWrapper): string {
  return wrapper.find('.req-no').text()
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('DepositRefundDialog · 金额默认全额与上限拦截（R72）', () => {
  it('打开即默认全额（= 实缴押金），渠道只读展示原渠道支付宝', async () => {
    const lease = seedRefundableLease()
    const wrapper = await mountDialog(lease)

    /* eslint-disable-next-line no-console */
    expect((amountInput(wrapper).element as HTMLInputElement).value).toBe('6000')
    /* 渠道只读且为实缴渠道，不给选择控件 */
    const channel = channelInput(wrapper)
    expect(channel, '渠道未以只读框呈现').toBeTruthy()
    expect((channel!.element as HTMLInputElement).value).toBe('支付宝')
    expect(wrapper.text()).toContain('只读：按实缴支付单原路退回至支付宝')
    expect(wrapper.text()).toContain('可退金额')
  })

  it('手填金额 > 实缴可退：前端即拦截并给出可退金额，且不发起请求', async () => {
    const lease = seedRefundableLease()
    const wrapper = await mountDialog(lease)

    await amountInput(wrapper).setValue('6001')
    await flushPromises()

    expect(wrapper.text()).toContain('退还金额不得超过实缴可退金额 ¥ 6,000')
    /* 未提交：假后端无任何退还单 */
    expect(backend().state.depositRefunds).toHaveLength(0)
  })

  it('部分退还后可退金额按「实缴 − 已成功退还」收敛；失败单不计入', async () => {
    const lease = seedRefundableLease()
    backend().state.depositRefunds.push(
      {
        id: 901,
        leaseId: lease.id,
        amount: 2000,
        channel: 'ALIPAY',
        outRequestNo: 'DR-OK-1',
        status: 'SUCCESS',
        createdAt: '2026-09-22T10:00:00',
        refundedAt: '2026-09-22T10:01:00'
      },
      {
        id: 902,
        leaseId: lease.id,
        amount: 1000,
        channel: 'ALIPAY',
        outRequestNo: 'DR-FAIL-1',
        status: 'FAILED',
        failReason: '渠道余额不足',
        createdAt: '2026-09-22T10:02:00',
        refundedAt: null
      }
    )
    const wrapper = await mountDialog(lease)

    /* 已退 2000（失败单不计），可退 4000 → 默认全额 4000 */
    expect((amountInput(wrapper).element as HTMLInputElement).value).toBe('4000')
    expect(wrapper.text()).toContain('失败原因：渠道余额不足')
  })
})

describe('DepositRefundDialog · 幂等键与失败重试（R72 §9.20.1）', () => {
  it('同一请求号重复提交返回既有记录，不产生第二条流水', async () => {
    const lease = seedRefundableLease()
    const wrapper = await mountDialog(lease)

    const firstNo = requestNoText(wrapper)
    await wrapper.findAll('button').find((item) => item.text().includes('确认发起退还'))!.trigger('click')
    await flushPromises()

    expect(backend().state.depositRefunds).toHaveLength(1)
    expect(backend().state.depositRefunds[0].outRequestNo).toBe(firstNo)
    /* 回执常驻弹窗：状态标签 + 金额 + 请求号（与提交时同一个） */
    expect(wrapper.find('.result-box').text()).toContain('押金退还已退还')
    expect(wrapper.find('.result-box').text()).toContain(firstNo)
  })

  it('失败后重试：换新请求号（不复用失败号），并保留失败原因可见', async () => {
    const lease = seedRefundableLease()
    const wrapper = await mountDialog(lease)
    const usedNo = requestNoText(wrapper)

    /* 模拟渠道失败：让假后端这一次抛业务错（凭据门控 5901 的等价场景） */
    const spy = vi
      .spyOn(backend().depositRefund, 'createDepositRefund')
      .mockRejectedValueOnce(new ApiError(CODES.PAYMENT_CHANNEL_DISABLED, '该支付渠道未配置', 200))
    await wrapper.findAll('button').find((item) => item.text().includes('确认发起退还'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('押金退还失败')
    expect(wrapper.text()).toContain('该支付渠道未配置')

    /* 重试：走真实实现，请求号必须换新 */
    spy.mockRestore()
    await wrapper.findAll('button').find((item) => item.text().includes('换新请求号重试'))!.trigger('click')
    await flushPromises()

    expect(backend().state.depositRefunds).toHaveLength(1)
    expect(backend().state.depositRefunds[0].outRequestNo).not.toBe(usedNo)
    expect(backend().state.depositRefunds[0].amount).toBe(6000)
  })
})

describe('DepositRefundDialog · 服务端口径（后端定稿）', () => {
  it('全额退清后租约押金状态置 REFUNDED，并写 DEPOSIT_REFUND 变更留痕', async () => {
    const lease = seedRefundableLease()
    const wrapper = await mountDialog(lease)

    await wrapper.findAll('button').find((item) => item.text().includes('确认发起退还'))!.trigger('click')
    await flushPromises()

    const state = backend().state
    expect(state.leases.find((item) => item.id === lease.id)?.depositStatus).toBe('REFUNDED')
    expect(state.leaseChanges.some((item) => item.changeType === 'DEPOSIT_REFUND')).toBe(true)
  })

  it('渠道与实缴渠道不一致由后端拒绝 5705（前端不提供改渠道入口）', async () => {
    const lease = seedRefundableLease()
    /* 直接调假后端验证门禁语义：前端 UI 无渠道选择控件，不存在改渠道路径 */
    await expect(
      backend().depositRefund.createDepositRefund(lease.id, {
        amount: 100,
        channel: 'WECHAT',
        outRequestNo: 'DR-CHANNEL-1'
      })
    ).rejects.toMatchObject({ code: CODES.DEPOSIT_CHANNEL_MISMATCH })
  })

  it('押金非 PAID 时后端拒绝 5703', async () => {
    const lease = seedRefundableLease({ depositStatus: 'REFUNDED' })
    await expect(
      backend().depositRefund.createDepositRefund(lease.id, { outRequestNo: 'DR-NOT-PAID' })
    ).rejects.toMatchObject({ code: CODES.DEPOSIT_STATUS_NOT_REFUNDABLE })
  })

  it('金额超过实缴由后端拒绝 5704（报文含可退金额）', async () => {
    seedRefundableLease()
    await expect(
      backend().depositRefund.createDepositRefund(1, { amount: 9999, outRequestNo: 'DR-OVER' })
    ).rejects.toMatchObject({ code: CODES.DEPOSIT_AMOUNT_EXCEEDED })
  })

  it('同号但内容不一致由后端拒绝 5706', async () => {
    seedRefundableLease()
    const state = backend().state
    state.depositRefunds.push({
      id: 903,
      leaseId: 1,
      amount: 1000,
      channel: 'ALIPAY',
      outRequestNo: 'DR-SAME',
      status: 'SUCCESS',
      createdAt: '2026-09-22T11:00:00',
      refundedAt: '2026-09-22T11:01:00'
    })
    await expect(
      backend().depositRefund.createDepositRefund(1, { amount: 2000, outRequestNo: 'DR-SAME' })
    ).rejects.toMatchObject({ code: CODES.DEPOSIT_OUT_REQUEST_CONFLICT })
  })

  it('非绑定社区管理员不可发起（403）', async () => {
    seedRefundableLease()
    actAs('ADMIN', 1)
    backend().state.leases[0].communityId = 99
    await expect(
      backend().depositRefund.createDepositRefund(1, { outRequestNo: 'DR-FORBIDDEN' })
    ).rejects.toMatchObject({ code: CODES.FORBIDDEN })
  })
})

describe('DepositRefundDialog · 失败停留与押金状态中文（OCR DEF-123）', () => {
  it('渠道失败（FAILED 回执）不 emit success：失败原因与重试常驻弹窗，换号重试成功才 emit 一次', async () => {
    const lease = seedRefundableLease()
    const wrapper = await mountDialog(lease)

    /* 渠道失败回执：单据落 FAILED + 原因可见（不抛错，靠回执状态区分） */
    const failedRefund: IDepositRefund = {
      id: 910,
      leaseId: lease.id,
      amount: 6000,
      channel: 'ALIPAY',
      outRequestNo: requestNoText(wrapper),
      channelRefundNo: null,
      status: 'FAILED',
      failReason: '渠道余额不足，退款未成功',
      createdAt: '2026-09-23T10:00:00',
      refundedAt: null
    }
    const spy = vi
      .spyOn(backend().depositRefund, 'createDepositRefund')
      .mockResolvedValueOnce(failedRefund as never)
    await wrapper.findAll('button').find((item) => item.text().includes('确认发起退还'))!.trigger('click')
    await flushPromises()

    /* 停留弹窗：失败原因常驻、可重试；不通知父层（父层按成功刷新会把回落 PAID 的租约误标「退还中」） */
    expect(wrapper.text()).toContain('押金退还失败')
    expect(wrapper.text()).toContain('渠道余额不足')
    expect(wrapper.emitted('success')).toBeUndefined()
    expect(wrapper.findAll('button').some((item) => item.text().includes('换新请求号重试'))).toBe(true)

    /* 换新请求号重试（真实实现成功）后才 emit success，且只有这一次 */
    spy.mockRestore()
    await wrapper.findAll('button').find((item) => item.text().includes('换新请求号重试'))!.trigger('click')
    await flushPromises()

    expect(backend().state.depositRefunds).toHaveLength(1)
    expect(wrapper.emitted('success')).toHaveLength(1)
  })

  it.each([
    ['UNPAID', '未缴'],
    ['PAID', '已缴'],
    ['REFUNDING', '退还中'],
    ['REFUNDED', '已退还'],
    ['OFFSET', '已抵扣']
  ] as const)('押金状态 %s 渲染中文标签「%s」（不裸出枚举值）', async (status, label) => {
    const lease = seedRefundableLease({ depositStatus: status })
    const wrapper = await mountDialog(lease)

    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).not.toContain(status)
  })

  it('lease 为 null（租约加载失败）：弹窗体渲染明确错误文案，不再是空白弹窗', async () => {
    const wrapper = mount(DepositRefundDialog, {
      props: { lease: null, firstPayments: [], modelValue: false },
      global: { plugins: [createPinia(), ElementPlus] },
      attachTo: document.body
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.text()).toContain('租约信息加载失败')
    expect(wrapper.text()).toContain('请刷新重试')
  })
})
