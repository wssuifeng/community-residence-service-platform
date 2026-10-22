import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import LeaseListView from '@/resident/lease/views/LeaseListView.vue'
import ResidenceApplicationListView from '@/resident/resident/views/ResidenceApplicationListView.vue'
import { actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeLease } from '@/test/fakeBackend'

/**
 * 居民端入住链路入口补齐：
 * ① 会话入口——「具体租约」的租约卡在 `lease_record.conversation_id` 有值时给「进入租约群聊」，
 *    无群不渲染；「入住申请入口」已通过项同样能进会话（有群进群、无群走居民侧直通会话）。
 * ② 支付租金/押金——租约展开区把「首期费用」拆成月租金/押金两行明示，并给出可点击的支付入口。
 */
vi.mock('@/resident/lease/api/summaries', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))
vi.mock('@/common/lease/api/lease', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))
vi.mock('@/common/resident/api/resident', async () => (await import('@/test/fakeBackend')).fakeApi('resident'))
vi.mock('@/common/agreement/api/agreement', async () => (await import('@/test/fakeBackend')).fakeApi('agreement'))
vi.mock('@/common/payment/api/payment', async () => (await import('@/test/fakeBackend')).fakeApi('payment'))
vi.mock('@/common/conversation/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/common/upload/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

const backend = () => getFakeBackend()

function seedLease(partial: Partial<FakeLease> = {}): void {
  backend().state.leases.push({
    id: 1,
    tenantId: 1,
    communityId: 1,
    houseId: 1,
    houseLocation: '1号楼-1单元-101',
    startDate: '2026-01-01',
    endDate: '2027-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    status: 'ACTIVE',
    conversationId: null,
    createdAt: '2026-01-01T10:00:00',
    ...partial
  } as FakeLease)
}

function seedResidentLeaseGroup(conversationId: number): void {
  backend().state.conversations.push({
    id: conversationId,
    type: 'LEASE_GROUP',
    relatedId: 1,
    title: '租约群聊 · 1号楼-1单元-101',
    status: 'ACTIVE',
    participantIds: [1, 1],
    participantRefs: [
      { id: 1, space: 'RESIDENT', name: '演示居民', role: 'RESIDENT' },
      { id: 1, space: 'USER', name: '小区管理员', role: 'ADMIN' }
    ],
    createdAt: '2026-01-01T10:00:00'
  })
}

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resident/housing-lease', component: LeaseListView },
      { path: '/resident/residence-applications', component: ResidenceApplicationListView },
      { path: '/resident/conversations/:id', component: { template: '<div class="conv-stub" />' } }
    ]
  })
}

async function mountAt(router: Router, path: string, component: unknown): Promise<VueWrapper> {
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

/** 展开租约卡（卡片主行 role=button，点击后展开明细区） */
async function expandFirstLease(wrapper: VueWrapper): Promise<void> {
  const row = wrapper.find('.lease-row')
  expect(row.exists(), '未找到租约卡主行').toBe(true)
  await row.trigger('click')
  await flushPromises()
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().trim() === text)
}

beforeEach(() => {
  installFakeBackend({ role: 'RESIDENT', id: 1 })
  backend().state.residents.push({ id: 1, name: '演示居民', phone: '13800000001', communityId: 1, status: 'ACTIVE' } as never)
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('居民端租约卡 · ① 会话入口 + ② 租金/押金入口', () => {
  it('已建群（conversationId）时展开区出现「进入租约群聊」并可跳转会话详情', async () => {
    seedLease({ conversationId: 1 })
    seedResidentLeaseGroup(1)
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/housing-lease', LeaseListView)
    await expandFirstLease(wrapper)

    const entry = buttonByText(wrapper, '进入租约群聊')
    expect(entry, '未渲染租约群聊入口').toBeTruthy()
    await entry?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/resident/conversations/1')
  })

  it('未建群（conversationId 为空）时不渲染会话入口', async () => {
    seedLease({ conversationId: null })
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/housing-lease', LeaseListView)
    await expandFirstLease(wrapper)

    expect(wrapper.text()).not.toContain('进入租约群聊')
    expect(wrapper.find('.lease-chat-entry').exists()).toBe(false)
  })

  it('费用与支付把首期费用拆成月租金/押金两行，入口语义为首期付费（不暗示独立押金支付）', async () => {
    seedLease({ conversationId: null })
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/housing-lease', LeaseListView)
    await expandFirstLease(wrapper)

    const text = wrapper.text()
    expect(text).toContain('费用与支付')
    expect(text).toContain('月租金')
    expect(text).toContain('¥ 3,000')
    expect(text).toContain('押金')
    expect(text).toContain('¥ 6,000')
    /* 入口语义 = 首期付费/续租付费；不得出现"独立支付押金"的入口 */
    expect(buttonByText(wrapper, '待支付首期费用'), '缺少首期付费入口').toBeTruthy()
    expect(buttonByText(wrapper, '支付押金')).toBeFalsy()
    expect(buttonByText(wrapper, '支付租金与押金')).toBeFalsy()
  })

  it('已付首期的租约卡仍有可见支付入口（禁用态 + 「首期已结清」说明），不整条消失', async () => {
    seedLease({ conversationId: null })
    /* 该租约已有成功首期单 → 修复前「支付首期」整块消失、卡片上没有任何支付入口 */
    backend().state.payments.push({
      id: 1,
      leaseId: 1,
      paymentNo: 'PAY-1',
      paymentType: 'FIRST',
      amount: 9000,
      status: 'SUCCESS',
      channel: 'ALIPAY',
      createdAt: '2026-01-02T10:00:00',
      paidAt: '2026-01-02T10:05:00'
    } as never)
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/housing-lease', LeaseListView)

    /* 卡片行（未展开）就有入口：支付入口区始终渲染 */
    expect(wrapper.find('.payment-entry').exists()).toBe(true)
    const entry = wrapper.find('.payment-entry')
    expect(entry.text()).toBe('支付首期费用')
    expect(entry.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('首期已结清')
    /* 续租入口仍可达（金额/续期由「续租」弹窗承载） */
    expect(buttonByText(wrapper, '续租')).toBeTruthy()
  })

  it('无可支付项（已归档租约）时入口为禁用态 + 原因，而非消失', async () => {
    seedLease({ status: 'ARCHIVED', conversationId: null })
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/housing-lease', LeaseListView)

    const entry = wrapper.find('.payment-entry')
    expect(entry.exists(), '终态租约也必须有可见支付入口').toBe(true)
    expect(entry.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('租约已结束，无可支付项')
  })
})

describe('居民端入住申请 · 租约/会话入口（V26 强关联 lease_id）', () => {
  /** V26：`lease_id` 由后端在审批通过时写死；为空表示无关联（不回退猜测） */
  function seedApplication(status: string, leaseId: number | null = null): void {
    backend().state.residenceApplications.push({
      id: 1,
      residentId: 1,
      communityId: 1,
      houseId: 1,
      houseLocation: '1号楼-1单元-101',
      relationType: 'TENANT',
      status,
      leaseId,
      createdAt: '2026-01-01T09:00:00'
    } as never)
  }

  it('申请已通过且关联租约已建群：入口为「进入租约会话」并跳转会话详情', async () => {
    seedApplication('APPROVED', 1)
    seedLease({ conversationId: 7 })
    seedResidentLeaseGroup(7)
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/residence-applications', ResidenceApplicationListView)

    const entry = buttonByText(wrapper, '进入租约会话')
    expect(entry, '未渲染「进入租约会话」').toBeTruthy()
    await entry?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/resident/conversations/7')
  })

  it('申请已通过但尚无群（存量租约）：入口降级为「咨询小区管理员」，走居民侧直通会话', async () => {
    seedApplication('APPROVED', 1)
    seedLease({ conversationId: null })
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/residence-applications', ResidenceApplicationListView)

    const entry = buttonByText(wrapper, '咨询小区管理员')
    expect(entry, '未渲染「咨询小区管理员」').toBeTruthy()
    await entry?.trigger('click')
    await flushPromises()

    const direct = backend().state.conversations.find((item) => item.type === 'DIRECT')
    expect(direct, '未创建直通会话').toBeTruthy()
    expect(router.currentRoute.value.path).toBe(`/resident/conversations/${direct!.id}`)
  })

  it('已通过可深链到「租住契约」Tab 的对应租约（leaseId 取申请自身列）', async () => {
    seedApplication('APPROVED', 1)
    seedLease({ conversationId: null })
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/residence-applications', ResidenceApplicationListView)

    const entry = buttonByText(wrapper, '查看我的租约')
    expect(entry, '未渲染「查看我的租约」').toBeTruthy()
    await entry?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/resident/housing-lease')
    expect(router.currentRoute.value.query.leaseId).toBe('1')
  })

  it('同屋多租约时，跳转的是本申请关联的那一份（不是同屋最新一份）', async () => {
    /* 本申请关联租约 id=1（会话 7）；同屋另有一份更新的租约 id=2（会话 8）
       —— 修复前按 houseId 反查最新租约会指到 2，V26 强关联后必须指到 1 */
    seedApplication('APPROVED', 1)
    seedLease({ id: 1, conversationId: 7 })
    backend().state.leases.push({
      id: 2,
      tenantId: 1,
      communityId: 1,
      houseId: 1,
      houseLocation: '1号楼-1单元-101',
      startDate: '2025-01-01',
      endDate: '2025-12-31',
      monthlyRent: 2800,
      deposit: 5600,
      status: 'ARCHIVED',
      conversationId: 8,
      createdAt: '2025-01-01T10:00:00'
    } as never)
    seedResidentLeaseGroup(7)
    seedResidentLeaseGroup(8)

    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/residence-applications', ResidenceApplicationListView)

    await buttonByText(wrapper, '进入租约会话')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/resident/conversations/7')

    await router.push('/resident/residence-applications')
    await flushPromises()
    await buttonByText(wrapper, '查看我的租约')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.leaseId).toBe('1')
  })

  it('leaseId 为空时给中性兜底（暂无关联租约 + 去租约列表），不出指向具体租约的死链', async () => {
    /* 申请无关联租约，但同屋确实存在一份带群的租约——不得据此猜测出链路 */
    seedApplication('APPROVED', null)
    seedLease({ conversationId: 7 })
    seedResidentLeaseGroup(7)
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/residence-applications', ResidenceApplicationListView)

    expect(buttonByText(wrapper, '查看我的租约')).toBeFalsy()
    expect(buttonByText(wrapper, '进入租约会话')).toBeFalsy()
    expect(wrapper.text()).toContain('暂无关联租约')

    const listEntry = buttonByText(wrapper, '去租约列表')
    expect(listEntry, '未给中性兜底入口').toBeTruthy()
    await listEntry?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/resident/housing-lease')
    expect(router.currentRoute.value.query.leaseId).toBeUndefined()
  })

  it('待审核/已驳回申请即使行上带出租约 ID，也不给任何租约/会话入口', async () => {
    for (const status of ['PENDING', 'REJECTED']) {
      backend().state.residenceApplications = []
      seedApplication(status, 1)
      seedLease({ conversationId: 7 })
      seedResidentLeaseGroup(7)
      const router = buildRouter()
      const wrapper = await mountAt(
        router,
        '/resident/residence-applications',
        ResidenceApplicationListView
      )

      expect(buttonByText(wrapper, '进入租约会话'), `${status} 不应有会话入口`).toBeFalsy()
      expect(buttonByText(wrapper, '查看我的租约'), `${status} 不应有租约入口`).toBeFalsy()
      expect(buttonByText(wrapper, '咨询小区管理员')).toBeFalsy()
    }
  })

  it('假后端对齐 V26：审批通过时写死 lease_id，VO 直接取该列', async () => {
    const residentApi = await import('@/common/resident/api/resident')
    backend().state.residenceApplications.push({
      id: 1,
      residentId: 1,
      communityId: 1,
      houseId: 1,
      houseLocation: '1号楼-1单元-101',
      relationType: 'TENANT',
      status: 'PENDING',
      leaseId: null,
      createdAt: '2026-01-01T09:00:00'
    } as never)

    actAs('ADMIN', 1)
    await residentApi.approveResidenceApplication(1, { remark: '通过' } as never)

    const created = backend().state.leases.at(-1)
    const list = await residentApi.getResidenceApplicationList({ page: 1, size: 10 })
    expect(backend().state.residenceApplications[0].leaseId).toBe(created?.id)
    expect(list.records[0].leaseId).toBe(created?.id)
  })
})
