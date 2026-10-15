import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { ILeasePayment, ILeaseRecord } from '@/types/modules/lease'
import { ApiError } from '@/utils/request'
import LeaseFirstPaymentDialog from '../LeaseFirstPaymentDialog.vue'
import LeaseRenewDialog from '../LeaseRenewDialog.vue'

/**
 * 首期支付弹窗（DEF-068）：打开先回查既有首期单——
 * 命中「待支付」→ 以该单渠道幂等续接并直达第 2 步；命中「已支付」→ 明示完成并引导支付记录；
 * 接口 5904 → 以后端回查结果分流；回查异常 → 不误判，保留选渠道流程。
 * DEF-129：支付宝表单改 Blob URL 在新窗口加载，不再 document.write 注入文档流。
 * api 层打桩，不打真后端；断言以可见文本与真实 DOM（button/span）为准。
 */

const ALIPAY_FORM = '<form id="alipay"><input name="subject" value="租约费用（月租+押金）"></form>'
const PENDING_NO = 'FIRST20260921437051'
const PAID_NO = 'FIRST20260921403843'

const mocks = vi.hoisted(() => ({
  /** 依次返回的支付单列表（每次调用 listLeasePayments 取下一个；用尽取最后一个） */
  listQueue: [] as unknown[],
  listCalls: 0,
  listThrows: false,
  createCalls: [] as Array<{ leaseId: number; channel: unknown }>,
  /** createFirstPayment 的行为：'ok' 返回带凭据的待支付单 / '5904' 抛已完成或进行中 / 'error' 抛其它错 */
  createMode: 'ok' as 'ok' | '5904' | 'error',
  createResult: null as unknown,
  closeCalls: [] as string[],
  channels: [
    { channel: 'ALIPAY', enabled: true },
    { channel: 'WECHAT', enabled: false }
  ] as unknown[]
}))

vi.mock('@/api/lease', () => ({
  listLeasePayments: async () => {
    if (mocks.listThrows) throw new Error('网络异常')
    const index = Math.min(mocks.listCalls, mocks.listQueue.length - 1)
    const value = mocks.listQueue[index] ?? []
    mocks.listCalls += 1
    return value
  }
}))

vi.mock('@/api/payment', () => ({
  getRentQuote: async () => ({ months: 3, amount: 9000, periodStart: '2026-10-15', periodEnd: '2027-01-14' }),
  createRentPayment: async () => mocks.createResult,
  listPaymentChannels: async () => mocks.channels,
  createFirstPayment: async (leaseId: number, dto: { channel: unknown }) => {
    mocks.createCalls.push({ leaseId, channel: dto?.channel })
    if (mocks.createMode === '5904') {
      throw new ApiError(5904, '该类型支付单已完成或进行中', 200)
    }
    if (mocks.createMode === 'error') {
      throw new ApiError(5902, '支付单创建失败', 200)
    }
    return mocks.createResult
  },
  getPaymentStatus: async () => ({ status: 'PENDING' }),
  closePayment: async (paymentNo: string) => {
    mocks.closeCalls.push(paymentNo)
    return null
  },
  createRenewPayment: async () => mocks.createResult,
  listMyPayments: async () => ({ records: [], total: 0 })
}))

function lease(): ILeaseRecord {
  return {
    id: 4,
    communityId: 1,
    communityName: '演示社区',
    houseId: 3,
    houseLocation: '3 号楼1 单元101',
    tenantName: '演示居民',
    status: 'ACTIVE',
    startDate: '2025-01-01',
    endDate: '2027-12-31',
    monthlyRent: 0.1,
    deposit: 0.1,
    createdAt: '2025-01-01T10:00:00'
  } as ILeaseRecord
}

function order(status: 'PENDING' | 'SUCCESS', paymentNo: string): ILeasePayment {
  return {
    paymentNo,
    leaseId: 4,
    channel: 'ALIPAY',
    paymentType: 'FIRST',
    months: 1,
    amount: 0.2,
    status,
    createdAt: '2026-09-21T17:29:29',
    paidAt: status === 'SUCCESS' ? '2026-09-21T17:30:44' : null,
    alipayForm: null,
    qrCode: null
  } as unknown as ILeasePayment
}

/** 弹窗由调用方以 modelValue 开关；打开时才触发回查（与真实用法一致） */
async function openDialog(): Promise<VueWrapper> {
  const wrapper = mount(LeaseFirstPaymentDialog, {
    props: { modelValue: false, lease: lease() },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}

function bodyText(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, ' ')
}

function buttons(): HTMLButtonElement[] {
  return Array.from(document.body.querySelectorAll('button'))
}

function buttonWith(text: string): HTMLButtonElement | undefined {
  return buttons().find((item) => (item.textContent ?? '').includes(text))
}

/** 桩窗口（jsdom 无 window.open 实现）：document.open/write/close 挂 spy，
    供 DEF-129 断言新实现不再向新窗口文档流写入 */
interface FakeWindow {
  document: { open: Mock; write: Mock; close: Mock; forms: unknown[] }
  addEventListener: Mock
}

let fakeWin: FakeWindow

function stubAlipayWindow(): void {
  fakeWin = {
    document: { open: vi.fn(), write: vi.fn(), close: vi.fn(), forms: [] },
    addEventListener: vi.fn()
  }
  vi.spyOn(window, 'open').mockReturnValue(fakeWin as unknown as Window)
  /* jsdom 的 Blob 与全局 URL.createObjectURL 实现互不兼容（Node URL 收 jsdom Blob
     直接抛错），mock 掉以对齐浏览器契约（DEF-129：Blob URL 加载支付宝表单） */
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock-alipay-form')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
}

beforeEach(() => {
  mocks.listQueue = []
  mocks.listCalls = 0
  mocks.listThrows = false
  mocks.createCalls = []
  mocks.createMode = 'ok'
  mocks.createResult = null
  mocks.closeCalls = []
  stubAlipayWindow()
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('LeaseFirstPaymentDialog · 打开回查与续接（DEF-068）', () => {
  it('打开命中既有 FIRST+PENDING 单：以该单渠道幂等续接并直达支付步，不停在选渠道', async () => {
    mocks.listQueue = [[order('PENDING', PENDING_NO)]]
    mocks.createResult = { ...order('PENDING', PENDING_NO), alipayForm: ALIPAY_FORM }

    const wrapper = await openDialog()

    /* 用既有单渠道发起（而非用户新选），保证不会建出第二张单 */
    expect(mocks.createCalls).toEqual([{ leaseId: 4, channel: 'ALIPAY' }])
    /* 直达第 2 步：出现单号、续接说明与支付动作，且不再出现第 1 步的渠道选择 */
    expect(bodyText()).toContain(PENDING_NO)
    expect(bodyText()).toContain('已续接您此前未支付的首期支付单，渠道以该单为准（支付宝）')
    expect(bodyText()).toContain('已在新窗口打开支付宝')
    expect(buttonWith('我已完成支付')).toBeTruthy()
    expect(bodyText()).not.toContain('支付渠道')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('打开命中既有 FIRST+SUCCESS 单：显示「首期费用已支付完成」并可跳支付记录', async () => {
    mocks.listQueue = [[order('SUCCESS', PAID_NO)]]

    const wrapper = await openDialog()

    expect(bodyText()).toContain('首期费用已支付完成')
    expect(bodyText()).toContain(PAID_NO)
    /* 不报错、不卡在选渠道，也不发起任何建单请求 */
    expect(mocks.createCalls).toEqual([])
    expect(bodyText()).not.toContain('支付渠道')

    const jump = buttonWith('查看支付记录')
    expect(jump).toBeTruthy()
    jump?.click()
    await flushPromises()
    expect(wrapper.emitted('viewPayments')).toHaveLength(1)
    /* 引导到位后关闭弹窗 */
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })

  it('接口返回 5904 且后端已有 SUCCESS 单：走已支付分支，不停在选渠道', async () => {
    /* 打开时回查为空（未建单），点击「去支付」时后端已有成功单 → 5904 → 回查后明示已支付 */
    mocks.listQueue = [[], [order('SUCCESS', PAID_NO)]]
    mocks.createMode = '5904'

    await openDialog()
    expect(bodyText()).toContain('支付渠道')

    buttonWith('去支付')?.click()
    await flushPromises()

    expect(bodyText()).toContain('首期费用已支付完成')
    expect(bodyText()).toContain(PAID_NO)
    expect(bodyText()).not.toContain('支付渠道')
  })

  it('接口返回 5904 且仍是 PENDING 单：明示单号与渠道，并给出关闭重发的兜底动作', async () => {
    mocks.listQueue = [[], [order('PENDING', PENDING_NO)]]
    mocks.createMode = '5904'

    const wrapper = await openDialog()
    buttonWith('去支付')?.click()
    await flushPromises()

    expect(bodyText()).toContain('已存在未支付的首期支付单（支付宝 · ' + PENDING_NO + '）')
    expect(bodyText()).toContain('当前无法重新打开该支付页')
    expect(bodyText()).toContain('支付渠道')
    expect(bodyText()).not.toContain('首期费用已支付完成')

    /* 兜底动作真的可用：关闭既有单 → 重新回查（第三次回查返回空） */
    const restart = buttonWith('关闭该支付单并重新发起')
    expect(restart).toBeTruthy()
    mocks.listQueue = [[], [order('PENDING', PENDING_NO)], []]
    restart?.click()
    await flushPromises()
    expect(mocks.closeCalls).toEqual([PENDING_NO])
    expect(bodyText()).not.toContain('已存在未支付的首期支付单')
    expect(bodyText()).toContain('支付渠道')
    expect(wrapper.exists()).toBe(true)
  })

  it('回查异常：不误判为已支付或待支付，保留选渠道流程并如实说明', async () => {
    mocks.listThrows = true

    const wrapper = await openDialog()

    expect(bodyText()).toContain('未能确认是否已有未支付的首期支付单')
    expect(bodyText()).toContain('支付渠道')
    expect(bodyText()).not.toContain('首期费用已支付完成')
    expect(bodyText()).not.toContain('已存在未支付的首期支付单')
    expect(buttonWith('去支付')).toBeTruthy()
    expect(mocks.createCalls).toEqual([])
    expect(wrapper.exists()).toBe(true)
  })
})

describe('LeaseFirstPaymentDialog · 支付宝表单注入方式（DEF-129）', () => {
  it('支付宝表单经 Blob URL 交给新窗口加载，不再 document.write 注入文档流', async () => {
    mocks.listQueue = [[order('PENDING', PENDING_NO)]]
    mocks.createResult = { ...order('PENDING', PENDING_NO), alipayForm: ALIPAY_FORM }

    await openDialog()

    /* 表单 HTML 装入 text/html Blob，新窗口以 blob: URL 加载 */
    const createUrl = vi.mocked(URL.createObjectURL)
    const revokeUrl = vi.mocked(URL.revokeObjectURL)
    expect(createUrl).toHaveBeenCalledTimes(1)
    const blob = createUrl.mock.calls[0][0] as Blob
    expect(blob.type).toBe('text/html;charset=utf-8')
    expect(blob.size).toBe(new TextEncoder().encode(ALIPAY_FORM).length)
    expect(window.open).toHaveBeenCalledWith('blob:mock-alipay-form', '_blank')
    /* 注入面关闭：不再向新窗口文档流 open/write（后端表单尾部自带 submit 脚本） */
    expect(fakeWin.document.open).not.toHaveBeenCalled()
    expect(fakeWin.document.write).not.toHaveBeenCalled()
    /* load 事件监听已挂（加载完成后释放 Blob URL）；事件触发前不提前 revoke */
    expect(fakeWin.addEventListener).toHaveBeenCalledWith('load', expect.any(Function))
    expect(revokeUrl).not.toHaveBeenCalled()
    expect(bodyText()).toContain('已在新窗口打开支付宝')
  })
})


describe('支付宝中文签名字段编码', () => {
  it.each([
    ['首期', LeaseFirstPaymentDialog],
    ['续约', LeaseRenewDialog]
  ] as const)('%s 支付窗口用 UTF-8 保留中文表单原文', async (_label, component) => {
    mocks.listQueue = [[]]
    mocks.createResult = { ...order('PENDING', PENDING_NO), alipayForm: ALIPAY_FORM }
    const wrapper = mount(component, {
      props: { modelValue: false, lease: lease() },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    try {
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      const submit = buttonWith('去支付')
      expect(submit).toBeDefined()
      submit!.click()
      await flushPromises()
      expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
      const blob = vi.mocked(URL.createObjectURL).mock.calls[0][0] as Blob
      expect(blob.type).toBe('text/html;charset=utf-8')
      const text = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result as string)
        reader.onerror = reject
        reader.readAsText(blob, 'UTF-8')
      })
      expect(text).toBe(ALIPAY_FORM)
    } finally {
      wrapper.unmount()
    }
  })
})
