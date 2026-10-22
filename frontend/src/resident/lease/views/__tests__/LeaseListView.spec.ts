import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { ILeasePayment, ILeaseRecord } from '@/common/lease/types/lease'
import LeaseListView from '@/resident/lease/views/LeaseListView.vue'
import residentRoutes from '@/resident/routes'

/**
 * 我的租约卡片（DEF-069）：止期已过时首期支付入口收口 + 「已到期/在租」关系说明；
 * DEF-111：首期结清只认 FIRST 口径、可点入口收敛 ACTIVE、unknown 兜底改重试、
 * tab 标签「支付记录」→「租约明细」。
 * api 层整体打桩，不打真后端；断言以可见文本与真实 DOM（button/span）为准。
 */

const mocks = vi.hoisted(() => ({
  paymentReads: vi.fn(),
  state: {
    leases: [] as unknown[],
    /** 'ERROR' = 该租约支付单回查失败（模拟接口异常，用于验证"不误判"） */
    payments: {} as Record<number, unknown[] | 'ERROR'>,
    /** 押金退还流水（R72）：按租约归档 */
    refunds: {} as Record<number, unknown[]>,
    /** 变更历史（R73 时间线源之一）：按租约归档 */
    changes: {} as Record<number, unknown[]>
  }
}))

vi.mock('@/resident/lease/api/summaries', () => ({
 getMyLeaseSummaries: async () => ({ records: (mocks.state.leases as ILeaseRecord[]).map(lease => {
  const payments=mocks.state.payments[lease.id]
  if(payments==='ERROR') return {lease,summary:null}
  const first=(payments ?? []).find((p:any)=>p.paymentType==='FIRST'&&p.status==='SUCCESS') as ILeasePayment|undefined
  return {lease,summary:{displayStatus:lease.status==='TO_SIGN'&&lease.agreementStatus==='SIGNED'?'待支付首期费用':lease.status==='ACTIVE'?'在租':undefined,
   firstPaymentSucceeded:!!first,firstPaymentPaidAmount:first?.amount,firstPaymentPaidAt:first?.paidAt,
   paidRentMonths:first?1:0,unpaidRentMonths:first?11:12,actions:{firstPayment:{enabled:!first,reason:'首期按约定首付月数加一次押金，支付成功后才正式入住'},rentPayment:{enabled:true,reason:''},extension:{enabled:true,reason:''},moveOut:{enabled:true,reason:''}}}}
 }), total:mocks.state.leases.length })
}))

vi.mock('@/common/lease/api/lease', () => ({
  getLeaseList: async () => ({
    records: mocks.state.leases,
    total: mocks.state.leases.length
  }),
  listLeasePayments: async (leaseId: number) => {
    mocks.paymentReads(leaseId)
    const value = mocks.state.payments[leaseId]
    if (value === 'ERROR') throw new Error('支付单查询失败')
    return value ?? []
  },
  listDepositRefunds: async (leaseId: number) => mocks.state.refunds[leaseId] ?? [],
  getLeaseChanges: async (leaseId: number) => mocks.state.changes[leaseId] ?? [],
  confirmTenancy: async () => ({}),
  getLease: async () => mocks.state.leases[0]
}))

vi.mock('@/common/payment/api/payment', () => ({
  listMyPayments: async () => ({ records: [], total: 0 }),
  createFirstPayment: async () => ({}),
  createRenewPayment: async () => ({}),
  closePayment: async () => null,
  getPaymentStatus: async () => ({}),
  listPaymentChannels: async () => []
}))

function lease(overrides: Partial<ILeaseRecord> = {}): ILeaseRecord {
  return {
    id: 1,
    communityId: 1,
    communityName: '演示小区',
    houseId: 1,
    houseLocation: '1 号楼1 单元101',
    tenantName: '演示居民',
    status: 'ACTIVE',
    startDate: '2025-01-01',
    endDate: '2027-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    createdAt: '2025-01-01T10:00:00',
    ...overrides
  } as ILeaseRecord
}

function firstPayment(status: 'PENDING' | 'SUCCESS'): ILeasePayment {
  return {
    paymentNo: 'FIRST20260921542744',
    leaseId: 1,
    channel: 'ALIPAY',
    paymentType: 'FIRST',
    months: 1,
    amount: 0.2,
    status,
    createdAt: '2026-09-21T18:00:00',
    paidAt: status === 'SUCCESS' ? '2026-09-21T18:05:00' : null
  } as ILeasePayment
}

function renewalPayment(status: 'PENDING' | 'SUCCESS'): ILeasePayment {
  return {
    paymentNo: 'RENEW20260921552744',
    leaseId: 1,
    channel: 'WECHAT',
    paymentType: 'RENEWAL',
    months: 3,
    amount: 9000,
    status,
    createdAt: '2026-09-21T18:00:00',
    paidAt: status === 'SUCCESS' ? '2026-09-21T18:05:00' : null
  } as ILeasePayment
}

let router: Router

async function mountList(): Promise<VueWrapper> {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/resident/housing-lease', component: LeaseListView }]
  })
  await router.push('/resident/housing-lease?tab=lease')
  await router.isReady()
  const wrapper = mount(LeaseListView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return wrapper
}

/** 可见按钮文本集合：用于断言"入口有无"，比只看整页文本更贴近「点了会不会失败」 */
function buttonTexts(wrapper: VueWrapper): string[] {
  return wrapper.findAll('button').map((item) => item.text().trim())
}

beforeEach(() => {
  mocks.paymentReads.mockClear()
  mocks.state.leases = []
  mocks.state.payments = {}
  mocks.state.refunds = {}
  mocks.state.changes = {}
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('LeaseListView · 首期费用入口与到期标注（DEF-069）', () => {
  it('未到期且未付：出现「待支付首期费用」入口，且无到期标注说明', async () => {
    mocks.state.leases = [lease({ id: 11, endDate: '2027-12-31' })]
    mocks.state.payments = { 11: [] }

    const wrapper = await mountList()

    expect(wrapper.text()).toContain('待支付首期费用')
    expect(buttonTexts(wrapper)).toContain('待支付首期费用')
    expect(wrapper.text()).not.toContain('为日期标注')
    expect(wrapper.text()).not.toContain('首期费用不可再支付')
  })

  it('已到期未付：无首期支付入口，显示不可支付原因与「日期标注非状态流转」说明', async () => {
    mocks.state.leases = [lease({ id: 12, endDate: '2025-08-17' })]
    mocks.state.payments = { 12: [] }

    const wrapper = await mountList()

    expect(wrapper.text()).toContain('已到期')
    expect(wrapper.text()).toContain('在租')
    expect(wrapper.text()).toContain('首期费用不可再支付')
    expect(wrapper.text()).toContain('为日期标注（止期已过），非状态流转，租约状态仍为「在租」')
    /* 入口收口：连兜底的「支付首期」按钮也不得出现 */
    expect(buttonTexts(wrapper)).not.toContain('待支付首期费用')
    expect(buttonTexts(wrapper)).not.toContain('支付首期')
    expect(wrapper.text()).not.toContain('首期已支付')
  })

  it('已到期已付：绿色「首期已支付」照常显示，且无不可支付拦截语', async () => {
    mocks.state.leases = [lease({ id: 13, endDate: '2025-08-17' })]
    mocks.state.payments = { 13: [firstPayment('SUCCESS')] }

    const wrapper = await mountList()

    expect(wrapper.text()).toContain('已到期')
    expect(wrapper.text()).toContain('首期已支付')
    expect(wrapper.text()).toContain('首期费用已支付，不受止期影响')
    expect(wrapper.text()).not.toContain('首期费用不可再支付')
    expect(buttonTexts(wrapper)).not.toContain('待支付首期费用')
  })

  it('已到期时续租与申请退租入口仍可达', async () => {
    mocks.state.leases = [lease({ id: 14, endDate: '2025-08-17' })]
    mocks.state.payments = { 14: [] }

    const wrapper = await mountList()

    expect(buttonTexts(wrapper)).toContain('续租')
    expect(buttonTexts(wrapper)).toContain('申请退租')
  })

  it('未到期已付：只显示已支付徽标，无日期标注说明', async () => {
    mocks.state.leases = [lease({ id: 15, endDate: '2027-12-31' })]
    mocks.state.payments = { 15: [firstPayment('SUCCESS')] }

    const wrapper = await mountList()

    expect(wrapper.text()).toContain('首期已支付')
    expect(wrapper.text()).not.toContain('为日期标注')
    expect(buttonTexts(wrapper)).not.toContain('待支付首期费用')
  })

  it('支付单回查失败：不误判为已支付或待支付，入口退化为重试加载（不给可点支付动作）', async () => {
    mocks.state.leases = [lease({ id: 16, endDate: '2027-12-31' })]
    /* 该租约支付单回查抛错 = 未落键：DEF-111 口径下不再给可点支付动作，
       改「支付记录加载失败」+ 重试入口（结清与否无法判定，盲发起只会把错误甩给后端） */
    mocks.state.payments = { 16: 'ERROR' }

    const wrapper = await mountList()

    expect(wrapper.text()).not.toContain('首期已支付')
    expect(wrapper.text()).not.toContain('待支付首期费用')
    expect(wrapper.text()).toContain('支付记录加载失败')
    expect(buttonTexts(wrapper)).not.toContain('支付首期')
    expect(buttonTexts(wrapper)).toContain('重试加载')
  })
})

/* ============================ DEF-111 首期结清口径与入口收敛 ============================ */

describe('LeaseListView · 首期结清口径与入口收敛（DEF-111）', () => {
  it('续租成功但首期未付：仍显示「待支付首期费用」可点入口（结清只认 FIRST）', async () => {
    mocks.state.leases = [lease({ id: 51, status: 'ACTIVE' })]
    /* 只有 RENEWAL+SUCCESS：续租成功不代表首期（含押金）已收 */
    mocks.state.payments = { 51: [renewalPayment('SUCCESS')] }

    const wrapper = await mountList()

    /* 原「任一 SUCCESS 即结清」会把该租约误判已结清、隐藏首期入口并堵死押金链路 */
    expect(wrapper.text()).not.toContain('首期已结清')
    expect(wrapper.text()).not.toContain('首期已支付')
    expect(buttonTexts(wrapper)).toContain('待支付首期费用')
  })

  it('TO_SIGN且SIGNED显示待支付首期费用并开放首期入口', async () => {
    mocks.state.leases = [lease({ id: 52, status: 'TO_SIGN', agreementStatus: 'SIGNED' })]
    mocks.state.payments = { 52: [] }
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('待支付首期费用')
    expect(buttonTexts(wrapper)).toContain('待支付首期费用')
    expect(wrapper.text()).toContain('支付成功后才正式入住')
  })

  it('TO_SIGN未确认协议不开放首期，提示先确认再支付', async () => {
    mocks.state.leases = [
      lease({ id: 52, status: 'TO_SIGN' })
    ]
    mocks.state.payments = { 52: [] }

    const wrapper = await mountList()

    expect(buttonTexts(wrapper)).not.toContain('待支付首期费用')
    expect(buttonTexts(wrapper)).not.toContain('支付首期')
    /* 入口保留禁用态并说明原因（不整条消失，点了不再 400） */
    expect(wrapper.text()).toContain('租约尚未生效')
    expect(wrapper.text()).toContain('再支付首期费用')
  })

  it('支付列表加载失败且租约已搬出：不给可点支付入口，仅重试加载', async () => {
    mocks.state.leases = [lease({ id: 53, status: 'MOVED_OUT' })]
    mocks.state.payments = { 53: 'ERROR' }

    const wrapper = await mountList()

    expect(buttonTexts(wrapper)).not.toContain('待支付首期费用')
    expect(buttonTexts(wrapper)).not.toContain('支付首期')
    /* unknown 兜底（DEF-111）：说明加载失败并提供重试入口，无 FIRST 动作 */
    expect(wrapper.text()).toContain('支付记录加载失败')
    expect(buttonTexts(wrapper)).toContain('重试加载')
  })

  it('取数失败时点「重试加载」重新回查：接口恢复后入口按真实结清态切换', async () => {
    mocks.state.leases = [lease({ id: 55, status: 'ACTIVE' })]
    mocks.state.payments = { 55: 'ERROR' }

    const wrapper = await mountList()
    expect(buttonTexts(wrapper)).toContain('重试加载')

    /* 接口恢复（回查成功）：入口从重试切为可支付，说明文字随之消失 */
    mocks.state.payments[55] = [renewalPayment('SUCCESS')]
    const retry = wrapper.findAll('button').find((item) => item.text().trim() === '重试加载')
    await retry?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('支付记录加载失败')
    expect(buttonTexts(wrapper)).toContain('待支付首期费用')
  })

  it('「支付记录」tab 标签改为「租约明细」（内容已是三源明细视图）', async () => {
    mocks.state.leases = [lease({ id: 54 })]
    mocks.state.payments = { 54: [] }

    const wrapper = await mountList()

    const tabLabels = wrapper.findAll('.el-radio-button').map((item) => item.text().trim())
    expect(tabLabels).toContain('租约明细')
    expect(tabLabels).not.toContain('支付记录')
  })
})

describe('LeaseListView · 旧路径 tab=payments 深链透传（路由兼容，DEF-111）', () => {
  /** /resident/leases 的函数式 redirect：旧书签/外链带 tab=payments 时须透传而非强改回 lease */
  function leasesRedirect(): (to: { query: Record<string, string> }) => { path: string; query: Record<string, string> } {
    const route = residentRoutes[0]?.children?.find((item) => item.path === 'leases')
    return route?.redirect as unknown as (
      to: { query: Record<string, string> }
    ) => { path: string; query: Record<string, string> }
  }

  it('旧链接 ?tab=payments 落到 Hub 租约分区并保留明细子 tab（其余 query 原样透传）', () => {
    const redirect = leasesRedirect()
    expect(redirect({ query: { tab: 'payments' } })).toEqual({
      path: '/resident/housing-lease',
      query: { tab: 'payments' }
    })
    /* leaseId 等其余 query 与既有口径一致：透传不被吞 */
    expect(redirect({ query: { tab: 'payments', leaseId: '9' } })).toEqual({
      path: '/resident/housing-lease',
      query: { tab: 'payments', leaseId: '9' }
    })
  })

  it('非 payments 的旧 tab 值与缺省一律落到租约分区（不被外部值劫持到其它分区）', () => {
    const redirect = leasesRedirect()
    expect(redirect({ query: {} })).toEqual({ path: '/resident/housing-lease', query: { tab: 'lease' } })
    expect(redirect({ query: { tab: 'housing' } })).toEqual({
      path: '/resident/housing-lease',
      query: { tab: 'lease' }
    })
  })
})

/* ============================ R72 押金状态与退还记录 ============================ */

/** 押金徽标（卡片与展开区共用同一 class，取首个即卡片上的） */
function depositPill(wrapper: VueWrapper) {
  return wrapper.find('.deposit-pill')
}

describe('LeaseListView · 押金状态徽标五态（R72）', () => {
  const cases: Array<[string, string]> = [
    ['UNPAID', '押金未缴'],
    ['PAID', '押金已缴'],
    ['REFUNDING', '押金退还中'],
    ['REFUNDED', '押金已退还'],
    ['OFFSET', '押金已抵扣']
  ]

  it.each(cases)('depositStatus=%s → 徽标文案「%s」', async (status, label) => {
    mocks.state.leases = [lease({ id: 21, depositStatus: status as never })]
    mocks.state.refunds = { 21: [] }

    const wrapper = await mountList()

    expect(depositPill(wrapper).exists()).toBe(true)
    expect(depositPill(wrapper).text()).toBe(label)
    /* 徽标带语义属性，样式按 data-deposit 分档（五态各有独立配色） */
    expect(depositPill(wrapper).attributes('data-deposit')).toBe(status)
  })

  it('depositStatus 缺省（后端未返回）按「未缴」兜底，不误报已缴', async () => {
    mocks.state.leases = [lease({ id: 22 })]
    mocks.state.refunds = { 22: [] }

    const wrapper = await mountList()

    expect(depositPill(wrapper).text()).toBe('押金未缴')
  })

  it('已抵扣时展示抵扣说明（徽标含义可解释）', async () => {
    mocks.state.leases = [
      lease({ id: 23, depositStatus: 'OFFSET', depositOffsetRemark: '抵扣水电欠费 800 元' })
    ]
    mocks.state.refunds = { 23: [] }

    const wrapper = await mountList()
    /* 展开卡片后可见抵扣说明（卡片徽标用 title 兜底提示） */
    await wrapper.find('.lease-row').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('抵扣水电欠费 800 元')
    expect(depositPill(wrapper).text()).toBe('押金已抵扣')
  })
})

describe('LeaseListView · 押金退还记录（R72）', () => {
  it('累计已退还只计 SUCCESS，失败单的金额不计入且失败原因可见', async () => {
    mocks.state.leases = [lease({ id: 31, status: 'MOVED_OUT', depositStatus: 'REFUNDING' })]
    mocks.state.payments = { 31: [] }
    mocks.state.refunds = {
      31: [
        {
          id: 1,
          leaseId: 31,
          amount: 3000,
          channel: 'ALIPAY',
          outRequestNo: 'DR31001',
          status: 'SUCCESS',
          createdAt: '2026-09-20T10:00:00',
          refundedAt: '2026-09-20T10:05:00'
        },
        {
          id: 2,
          leaseId: 31,
          amount: 3000,
          channel: 'ALIPAY',
          outRequestNo: 'DR31002',
          status: 'FAILED',
          failReason: '渠道余额不足',
          createdAt: '2026-09-21T10:00:00',
          refundedAt: null
        }
      ]
    }

    const wrapper = await mountList()
    await wrapper.find('.lease-row').trigger('click')
    await flushPromises()

    /* 累计只算成功的那 3000：失败单的 3000 并未退回，计入会误导居民 */
    expect(wrapper.text()).toContain('累计已退还 ¥ 3,000')
    expect(wrapper.text()).toContain('失败原因：渠道余额不足')
    expect(wrapper.text()).toContain('退还失败')
    /* 两条流水均在（成功 + 失败各一条），未做去重或吞掉 */
    expect(wrapper.findAll('.refund-item')).toHaveLength(2)
  })

  it('无退还记录时给出空态与后续动作说明', async () => {
    mocks.state.leases = [lease({ id: 32, depositStatus: 'PAID' })]
    mocks.state.payments = { 32: [] }
    mocks.state.refunds = { 32: [] }

    const wrapper = await mountList()
    await wrapper.find('.lease-row').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂无押金退还记录')
    expect(wrapper.text()).toContain('退租审核通过后由小区管理方发起原路退还')
  })
})

/* ============================ R73 租约明细时间线 ============================ */

async function gotoDetailTab(wrapper: VueWrapper): Promise<void> {
  const input = wrapper
    .findAll('input[type="radio"]')
    .find((item) => item.element.closest('label')?.textContent?.includes('租约明细'))
  await input?.setValue()
  await flushPromises()
}

describe('LeaseListView · 租约明细时间线（R73）', () => {
  it('三源合并按发生时间倒序，支付条目含金额拆分与覆盖租期', async () => {
    mocks.state.leases = [lease({ id: 41, status: 'ACTIVE', depositStatus: 'PAID' })]
    mocks.state.payments = {
      41: [
        {
          paymentNo: 'FIRST4101',
          leaseId: 41,
          paymentType: 'FIRST',
          channel: 'ALIPAY',
          months: 1,
          amount: 9000,
          rentAmount: 3000,
          depositAmount: 6000,
          periodStart: '2026-01-01',
          periodEnd: '2026-02-01',
          status: 'SUCCESS',
          createdAt: '2026-01-01T09:00:00',
          paidAt: '2026-01-01T09:05:00',
          channelTradeNo: 'ALI-4101'
        }
      ]
    }
    mocks.state.changes = {
      41: [
        {
          id: 1,
          leaseId: 41,
          changeType: 'STATUS',
          fieldName: 'status',
          fieldLabel: '租约状态',
          oldValue: 'TO_SIGN',
          newValue: 'ACTIVE',
          operatorType: 'SYSTEM',
          createdAt: '2026-01-01T09:06:00'
        }
      ]
    }
    mocks.state.refunds = {
      41: [
        {
          id: 9,
          leaseId: 41,
          amount: 6000,
          channel: 'ALIPAY',
          outRequestNo: 'DR41009',
          channelRefundNo: 'ALI-REFUND-41009',
          status: 'SUCCESS',
          createdAt: '2026-09-20T10:00:00',
          refundedAt: '2026-09-20T10:01:00'
        }
      ]
    }

    const wrapper = await mountList()
    await gotoDetailTab(wrapper)

    const items = wrapper.findAll('.tl-item')
    expect(items).toHaveLength(3)
    /* 时间倒序：退款(09-20) > 流转(01-01 09:06) > 支付(01-01 09:05) */
    expect(items[0].attributes('data-type')).toBe('DEPOSIT_REFUND')
    expect(items[1].attributes('data-type')).toBe('STATUS')
    expect(items[2].attributes('data-type')).toBe('PAYMENT')

    const payment = items[2].text()
    expect(payment).toContain('首期费用（首月租金 + 押金）')
    expect(payment).toContain('租金部分 ¥ 3,000')
    expect(payment).toContain('押金部分 ¥ 6,000')
    expect(payment).toContain('覆盖租期')
    expect(payment).toContain('业务单号 FIRST4101')
    expect(payment).toContain('渠道流水号 ALI-4101')

    const refund = items[0].text()
    expect(refund).toContain('押金退还')
    expect(refund).toContain('渠道退款单号 ALI-REFUND-41009')
    expect(refund).toContain('请求号 DR41009')

    const status = items[1].text()
    expect(status).toContain('租约状态')
    expect(status).toContain('TO_SIGN')
    expect(status).toContain('ACTIVE')
  })

  it('续期支付按类型给出租期项目，且退款失败原因在时间线内可见', async () => {
    mocks.state.leases = [lease({ id: 42, status: 'MOVED_OUT', depositStatus: 'PAID' })]
    mocks.state.payments = {
      42: [
        {
          paymentNo: 'RENEW4201',
          leaseId: 42,
          paymentType: 'RENEWAL',
          channel: 'WECHAT',
          months: 6,
          amount: 18000,
          rentAmount: 18000,
          depositAmount: 0,
          periodStart: '2026-04-01',
          periodEnd: '2026-10-01',
          status: 'SUCCESS',
          createdAt: '2026-03-25T09:00:00',
          paidAt: '2026-03-25T09:10:00'
        }
      ]
    }
    mocks.state.refunds = {
      42: [
        {
          id: 11,
          leaseId: 42,
          amount: 2000,
          channel: 'WECHAT',
          outRequestNo: 'DR42011',
          status: 'FAILED',
          failReason: '商户余额不足',
          createdAt: '2026-09-21T10:00:00',
          refundedAt: null
        }
      ]
    }

    const wrapper = await mountList()
    await gotoDetailTab(wrapper)

    expect(wrapper.text()).toContain('续期租金（6 个月）')
    expect(wrapper.text()).toContain('押金部分 ¥ 0')
    /* 失败条目：状态为「退还失败」且失败原因常驻可见 */
    const failed = wrapper.find('.tl-item[data-type="DEPOSIT_REFUND"]')
    expect(failed.text()).toContain('退还失败')
    expect(failed.text()).toContain('失败原因：商户余额不足')
  })

  it('无任何流水时时间线给出空态', async () => {
    mocks.state.leases = [lease({ id: 43 })]
    mocks.state.payments = { 43: [] }
    mocks.state.refunds = { 43: [] }
    mocks.state.changes = { 43: [] }

    const wrapper = await mountList()
    await gotoDetailTab(wrapper)

    expect(wrapper.text()).toContain('暂无租约明细流水')
  })
})

describe('租约摘要请求范围', () => {
 it('十条卡片初始读取不逐条请求支付历史，展开一条才读取该条流水', async () => {
  mocks.state.leases=Array.from({length:10},(_,i)=>lease({id:100+i,houseId:100+i}))
  const wrapper=await mountList()
  expect(mocks.paymentReads).not.toHaveBeenCalled()
  await wrapper.find('.lease-row').trigger('click'); await flushPromises()
  expect(mocks.paymentReads).toHaveBeenCalledTimes(1)
  expect(mocks.paymentReads).toHaveBeenCalledWith(100)
  wrapper.unmount()
 })
})
