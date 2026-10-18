import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LeaseDetailView from '../LeaseDetailView.vue'
import DepositRefundDialog from '../DepositRefundDialog.vue'
import { installFakeBackend } from '@/test/fakeBackend'
import type { ILeaseRecord, LeaseStatus } from '@/common/lease/types/lease'

/**
 * 管理端租约详情「打不开」回归（④）：
 * ① 正常数据必须渲染出详情主体；
 * ② 主数据（GET /leases/{id}）失败时给出**显式失败态**（原因 + 重试 + 返回列表），不再是只有面包屑的空白页；
 * ③ 辅助信息（GET /leases/{id}/changes）失败**不得连带整页空白**——这是修复前的主因：
 *    变更历史被塞进同一个 `Promise.all`，它一失败主详情也一起被判为失败。
 *
 * OCR DEF-123 增补：押金退还成功后的刷新链（单请求，不再双 fetchDeposit 乱序）、
 * TO_SIGN 租约的「置为生效」入口（与列表页同口径）。
 */

const api = vi.hoisted(() => {
  const lease: Record<string, unknown> = {
    id: 1,
    tenantId: 1,
    tenantName: '演示居民',
    communityId: 1,
    communityName: '阳光花园社区',
    houseId: 1,
    houseLocation: '1号楼-1单元-101',
    startDate: '2026-01-01',
    endDate: '2027-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    depositStatus: 'PAID',
    status: 'ACTIVE',
    agreementStatus: 'SIGNED',
    conversationId: null,
    createdAt: '2026-01-01T10:00:00'
  }
  return {
    getLease: vi.fn(async () => lease as unknown as ILeaseRecord),
    getLeaseChanges: vi.fn(async () => []),
    listDepositRefunds: vi.fn(async () => []),
    listLeasePayments: vi.fn(async () => []),
    listLeaseViewings: vi.fn(async () => []),
    renewLease: vi.fn(async () => ({})),
    updateLease: vi.fn(async () => ({})),
    updateLeaseStatus: vi.fn(async () => undefined),
    createDepositRefund: vi.fn(async () => ({}))
  }
})

vi.mock('@/common/lease/api/lease', () => api)

/* ③ 租约群聊面板：会话详情与消息由假后端/桩返回（仅参与者可见的业务口径由会话模块覆盖） */
vi.mock('@/common/conversation/api/conversation', () => ({
  getConversation: vi.fn(async (id: number) => ({
    id,
    type: 'LEASE_GROUP',
    title: '租约群聊 · 1号楼-1单元-101',
    status: 'ACTIVE',
    relatedId: 1,
    participants: [
      { userId: 1, name: '演示居民', role: 'RESIDENT', ownerType: 'RESIDENT' },
      { userId: 1, name: '社区管理员', role: 'ADMIN', ownerType: 'USER' }
    ],
    participantCount: 2,
    createdAt: '2026-01-01T10:00:00'
  })),
  listConversationMessages: vi.fn(async () => []),
  sendConversationMessage: vi.fn(async () => ({})),
  openDirectConversation: vi.fn(async () => ({ id: 9 })),
  /* ② 补建/直取租约沟通会话：DEF-076 幂等端点（成功即回写 lease.conversationId） */
  ensureLeaseGroupConversation: vi.fn(async () => ({
    id: 9,
    type: 'LEASE_GROUP',
    title: '租约沟通 · 1号楼-1单元-101',
    status: 'ACTIVE',
    relatedId: 1,
    participants: [
      { userId: 1, name: '演示居民', role: 'RESIDENT', ownerType: 'RESIDENT' },
      { userId: 1, name: '社区管理员', role: 'ADMIN', ownerType: 'USER' }
    ],
    participantCount: 2,
    createdAt: '2026-01-01T10:00:00'
  }))
}))

/** 用例内构造指定状态的租约（其余字段沿用演示租约） */
function leaseOf(status: LeaseStatus, extra: Record<string, unknown> = {}): ILeaseRecord {
  return {
    id: 1,
    tenantId: 1,
    tenantName: '演示居民',
    communityId: 1,
    communityName: '阳光花园社区',
    houseId: 1,
    houseLocation: '1号楼-1单元-101',
    startDate: '2026-01-01',
    endDate: '2027-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    depositStatus: 'PAID',
    status,
    agreementStatus: status === 'TO_SIGN' ? 'NONE' : 'SIGNED',
    conversationId: null,
    createdAt: '2026-01-01T10:00:00',
    ...extra
  } as ILeaseRecord
}

/**
 * @param stubDepositDialog 押金退还弹窗默认打桩（既有渲染/兜底用例不受弹窗内部取数影响）；
 * 刷新链用例传 false 挂真实弹窗，以便从弹窗组件 emit success 触发父层 handleRefundSuccess。
 */
const mounted: VueWrapper[] = []
async function mountDetail(stubDepositDialog = true): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/leases/:id', component: LeaseDetailView },
      { path: '/admin/housing-lease', component: { template: '<div />' } },
      { path: '/admin/conversations', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push('/admin/leases/1')
  await router.isReady()
  /* v-permission 会按角色把无权元素从 DOM 移除：先落管理员会话，再建 store/挂载 */
  installFakeBackend({ role: 'ADMIN', id: 1 })
  const wrapper = mount(LeaseDetailView, {
    global: {
      plugins: [createPinia(), router, ElementPlus],
      /* 协议面板有独立用例覆盖，这里只测详情页自身的渲染与兜底 */
      stubs: { AgreementSignPanel: true, ...(stubDepositDialog ? { DepositRefundDialog: true } : {}) }
    },
    attachTo: document.body
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  api.getLease.mockResolvedValue({
    id: 1,
    tenantId: 1,
    tenantName: '演示居民',
    communityId: 1,
    communityName: '阳光花园社区',
    houseId: 1,
    houseLocation: '1号楼-1单元-101',
    startDate: '2026-01-01',
    endDate: '2027-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    depositStatus: 'PAID',
    status: 'ACTIVE',
    agreementStatus: 'SIGNED',
    conversationId: null,
    createdAt: '2026-01-01T10:00:00'
  } as unknown as ILeaseRecord)
  api.getLeaseChanges.mockResolvedValue([])
})

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('管理端租约详情 · 打开与失败兜底（④）', () => {
  it('主数据正常时渲染详情主体（房屋/租户/租金）', async () => {
    const wrapper = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('1号楼-1单元-101')
    expect(text).toContain('演示居民')
    expect(text).toContain('阳光花园社区')
    expect(text).toContain('ZL0001')
  })

  it('主数据失败（403/404）时给出显式失败态与原因，而非空白页', async () => {
    api.getLease.mockRejectedValue(new Error('无权查看他人租住记录'))
    const wrapper = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('无权查看他人租住记录')
    expect(text).toContain('重新加载')
    expect(text).toContain('返回租约列表')
    /* 失败态下不再渲染详情主体 */
    expect(text).not.toContain('ZL0001')
  })

  it('变更历史失败不阻断详情渲染（修复前会整页空白）', async () => {
    api.getLeaseChanges.mockRejectedValue(new Error('变更历史服务暂不可用'))
    const wrapper = await mountDetail()
    await wrapper.findAll('button').find(b => b.text().trim() === '业务记录')!.trigger('click'); await flushPromises()

    const text = wrapper.text()
    /* 主体仍在 */
    expect(text).toContain('1号楼-1单元-101')
    expect(text).toContain('ZL0001')
    /* 变更历史降级提示 + 可重试 */
    expect(text).toContain('变更历史暂不可用')
    expect(text).toContain('变更历史服务暂不可用')
    expect(api.getLeaseChanges).toHaveBeenCalledTimes(1)
  })
})

describe('管理端租约详情 · ③ 会话相关操作界面', () => {
  it('租约已建群：以精确链接进入原群，不再内嵌完整聊天', async () => {
    api.getLease.mockResolvedValue({
      id: 1,
      tenantId: 1,
      tenantName: '演示居民',
      communityId: 1,
      communityName: '阳光花园社区',
      houseId: 1,
      houseLocation: '1号楼-1单元-101',
      startDate: '2026-01-01',
      endDate: '2027-12-31',
      monthlyRent: 3000,
      deposit: 6000,
      depositStatus: 'PAID',
      status: 'ACTIVE',
      agreementStatus: 'SIGNED',
      conversationId: 9,
      createdAt: '2026-01-01T10:00:00'
    } as unknown as ILeaseRecord)
    const wrapper = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('进入沟通')
    const link = wrapper.get('.conversation-entry a')
    expect(link.attributes('href')).toContain('id=9')
    expect(link.attributes('href')).toContain('source=CONVERSATION')
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
  })

  it('租约未建群：说明来源并给会话中心入口，不渲染会话组件', async () => {
    const wrapper = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('该租约尚未建立沟通群')
    expect(text).toContain('建立/进入租约沟通群')
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
  })
})

describe('管理端租约详情 · ② 补建租约沟通群（DEF-076）', () => {
  it('无群时可一键「建立/进入租约沟通群」，成功后回写 conversation_id 并显示原群入口', async () => {
    /* 补建成功后后端回写 lease_record.conversation_id，重拉详情应带出会话 */
    let conversationId: number | null = null
    api.getLease.mockImplementation(async () => {
      return {
        id: 1,
        tenantId: 1,
        tenantName: '演示居民',
        communityId: 1,
        communityName: '阳光花园社区',
        houseId: 1,
        houseLocation: '1号楼-1单元-101',
        startDate: '2026-01-01',
        endDate: '2027-12-31',
        monthlyRent: 3000,
        deposit: 6000,
        depositStatus: 'PAID',
        status: 'TO_SIGN',
        agreementStatus: 'NONE',
        conversationId,
        createdAt: '2026-01-01T10:00:00'
      } as unknown as ILeaseRecord
    })
    const conversationApi = await import('@/common/conversation/api/conversation')
    vi.mocked(conversationApi.ensureLeaseGroupConversation).mockImplementation(async () => {
      conversationId = 9
      return { id: 9, type: 'LEASE_GROUP', title: '租约沟通 · 1号楼-1单元-101', status: 'ACTIVE' } as never
    })

    const wrapper = await mountDetail()
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)

    const build = wrapper.findAll('button').find((item) => item.text().trim() === '建立/进入租约沟通群')
    expect(build, '无群时未提供补建入口').toBeTruthy()
    await build?.trigger('click')
    await flushPromises()

    expect(conversationApi.ensureLeaseGroupConversation).toHaveBeenCalledWith(1)
    /* 面板出现（会话组件 + 群成员由会话详情提供） */
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
    expect(wrapper.get('.conversation-entry a').attributes('href')).toContain('id=9')
  })

  it('补建失败（非绑定社区 403）时如实提示，不改变无群空态', async () => {
    const conversationApi = await import('@/common/conversation/api/conversation')
    vi.mocked(conversationApi.ensureLeaseGroupConversation).mockRejectedValueOnce(
      new Error('无权操作未绑定社区的数据')
    )

    const wrapper = await mountDetail()
    const build = wrapper.findAll('button').find((item) => item.text().trim() === '建立/进入租约沟通群')
    await build?.trigger('click')
    await flushPromises()

    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
    expect(wrapper.text()).toContain('该租约尚未建立沟通群')
    expect(document.body.textContent).toContain('无权操作未绑定社区的数据')
  })
})

describe('管理端租约详情 · 押金退还成功刷新链（OCR DEF-123）', () => {
  it('退还成功后走单请求链：fetchDetail 一次完成主数据与押金流水回读（不再并发双请求）', async () => {
    api.getLease.mockResolvedValue(leaseOf('MOVED_OUT'))
    /* 真实弹窗：从弹窗 emit success 触发父层 handleRefundSuccess（与真实链路同一条边） */
    const wrapper = await mountDetail(false)
    expect(api.listDepositRefunds).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text().trim() === '费用记录')!.trigger('click'); await flushPromises()

    /* 挂载基线：详情主数据 1 次 + 押金流水/支付单各 1 次（fetchDetail 内部的 fetchDeposit） */
    expect(api.getLease).toHaveBeenCalledTimes(1)
    expect(api.listDepositRefunds).toHaveBeenCalledTimes(1)
    expect(api.listLeasePayments).toHaveBeenCalledTimes(1)

    wrapper.findComponent(DepositRefundDialog).vm.$emit('success')
    await flushPromises()

    /* 修复前：Promise.all([fetchDetail(), fetchDeposit()]) —— fetchDetail 内部已含 fetchDeposit，
       双请求各跑两遍（流水/支付单 3 次）且并发乱序可能把旧流水后写回 */
    expect(api.getLease).toHaveBeenCalledTimes(2)
    expect(api.listDepositRefunds).toHaveBeenCalledTimes(2)
    expect(api.listLeasePayments).toHaveBeenCalledTimes(2)
  })
})

describe('管理端租约详情 · 不得绕过居民协议确认', () => {
  it('待签约租约不再提供直接生效或重复审核按钮', async () => {
    api.getLease.mockResolvedValue(leaseOf('TO_SIGN'))
    const wrapper = await mountDetail()
    expect(wrapper.findAll('button').some(b => ['置为生效', '审核通过'].includes(b.text().trim()))).toBe(false)
    expect(api.updateLeaseStatus).not.toHaveBeenCalled()
  })
  it('待审核只进入待签约，不直接生效', async () => {
    api.getLease.mockResolvedValue(leaseOf('PENDING'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountDetail()
    await wrapper.findAll('button').find(b => b.text().trim() === '审核通过')!.trigger('click')
    await flushPromises()
    expect(api.updateLeaseStatus).toHaveBeenCalledWith(1, { status: 'TO_SIGN', remark: undefined })
  })
})


describe('租约四分区按需读取', () => {
 it('默认只读主详情，不自动拉费用/历史/看房，也不加载完整聊天', async () => {
  const wrapper = await mountDetail()
  expect(api.getLeaseChanges).not.toHaveBeenCalled(); expect(api.listDepositRefunds).not.toHaveBeenCalled(); expect(api.listLeasePayments).not.toHaveBeenCalled(); expect(api.listLeaseViewings).not.toHaveBeenCalled()
  expect(wrapper.find('.conversation-chat').exists()).toBe(false)
  expect(wrapper.findAll('.lease-section-nav button').map(b => b.text())).toEqual(['概览','合同与协议','费用记录','业务记录'])
 })
 it('费用读取失败不冒充零条成功，仍显示主体并可重试', async () => {
  api.listDepositRefunds.mockRejectedValueOnce(new Error('费用离线'))
  const wrapper = await mountDetail(); await wrapper.findAll('button').find(b => b.text().trim() === '费用记录')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('押金退还记录读取失败'); expect(wrapper.text()).not.toContain('尚无押金退还流水')
  expect(wrapper.text()).toContain('1号楼-1单元-101'); expect(api.getLeaseChanges).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(b => b.text().trim() === '重试费用记录')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).not.toContain('押金退还记录读取失败')
 })
 it('业务记录只在进入后读取，再回概览不重复加载', async () => {
  const wrapper = await mountDetail(); const tab = (text: string) => wrapper.findAll('.lease-section-nav button').find(b => b.text() === text)!
  await tab('业务记录').trigger('click'); await flushPromises(); expect(api.getLeaseChanges).toHaveBeenCalledTimes(1); expect(api.listLeaseViewings).toHaveBeenCalledTimes(1)
  await tab('概览').trigger('click'); await tab('业务记录').trigger('click'); await flushPromises(); expect(api.getLeaseChanges).toHaveBeenCalledTimes(1); expect(api.listLeasePayments).not.toHaveBeenCalled()
 })
})
