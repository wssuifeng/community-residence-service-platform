import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import HousingLeaseHubView from '../HousingLeaseHubView.vue'

/**
 * 居民端「租住与房源」Hub（批次 f）：核对 Hub 切 Tab 是否会丢子视图 query
 * （`leaseId` / `fromLease` 是 Hub 级参数，`tab=payments` 是租约子视图自身的内部 Tab）。
 *
 * 「租住契约」子视图不打桩（它是本核对的关键：内部 Tab 是否被 Hub 切 Tab 冲掉），
 * 另两个子视图打桩；api 层打桩，不打真后端。
 */

const mocks = vi.hoisted(() => ({
  leases: [] as unknown[]
}))

vi.mock('@/api/lease', () => ({
  getLeaseList: async () => ({ records: mocks.leases, total: mocks.leases.length }),
  listLeasePayments: async () => [],
  confirmTenancy: async () => ({}),
  getLease: async () => null
}))

vi.mock('@/api/payment', () => ({
  listMyPayments: async () => ({ records: [], total: 0 }),
  createFirstPayment: async () => ({}),
  createRenewPayment: async () => ({}),
  closePayment: async () => null,
  getPaymentStatus: async () => ({}),
  listPaymentChannels: async () => []
}))

const CHILD_STUBS = {
  HousingListView: true,
  ResidenceApplicationListView: true
}

interface Hub {
  wrapper: VueWrapper
  router: Router
}

async function mountHub(query = ''): Promise<Hub> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resident/housing-lease', component: HousingLeaseHubView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push(`/resident/housing-lease${query}`)
  await router.isReady()
  const wrapper = mount(HousingLeaseHubView, {
    global: { plugins: [createPinia(), router, ElementPlus], stubs: CHILD_STUBS }
  })
  await flushPromises()
  return { wrapper, router }
}

function hubTab(wrapper: VueWrapper, label: string) {
  return wrapper
    .find('nav[aria-label="租住与房源分区"]')
    .findAll('button')
    .find((item) => item.text().includes(label))
}

function hubActiveLabel(wrapper: VueWrapper): string {
  const active = wrapper
    .find('nav[aria-label="租住与房源分区"]')
    .findAll('button')
    .find((item) => item.attributes('aria-selected') === 'true')
  return active ? active.text() : ''
}

/** 租约子视图的内部 Tab（el-radio-button）：点真实 radio 输入以触发 change */
async function pickInnerTab(wrapper: VueWrapper, label: string): Promise<void> {
  const input = wrapper
    .findAll('input[type="radio"]')
    .find((item) => item.element.closest('label')?.textContent?.includes(label))
  await input?.setValue()
  await flushPromises()
}

function innerPaymentsVisible(wrapper: VueWrapper): boolean {
  return wrapper.text().includes('首期与续期支付流水')
}

beforeEach(() => {
  mocks.leases = []
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ResidentHousingLeaseHub · Hub 切 Tab 与子视图 query（批次 f 核对）', () => {
  it('fromLease 跨 Hub Tab 往返保留（预约看房上下文不丢）', async () => {
    const { wrapper, router } = await mountHub('?tab=housing&fromLease=7')

    expect(wrapper.text()).toContain('正在为租约（ID 7）预约看房')

    await hubTab(wrapper, '入住申请')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ tab: 'application', fromLease: '7' })

    await hubTab(wrapper, '房源信息')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.fromLease).toBe('7')
    expect(wrapper.text()).toContain('正在为租约（ID 7）预约看房')
  })

  it('leaseId 属租住契约语境：切到其它 Tab 时按既有口径清除，切回时不复活', async () => {
    const { wrapper, router } = await mountHub('?tab=lease&leaseId=4')

    await hubTab(wrapper, '房源信息')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.leaseId).toBeUndefined()

    await hubTab(wrapper, '租住契约')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.leaseId).toBeUndefined()
    expect(hubActiveLabel(wrapper)).toContain('租住契约')
  })

  it('子视图内部 Tab（?tab=payments）不被 Hub 切 Tab 冲掉：切走再切回仍停在租约明细', async () => {
    const { wrapper, router } = await mountHub('?tab=lease')

    /* 子视图内切到「租约明细」（原「支付记录」，OCR DEF-111 更名，query 值仍为 payments）：
       它把 ?tab=payments 写进 URL（与 Hub 的 tab 共用键、
       值空间互不重叠：Hub 只认 housing/application，其余回落租住契约） */
    await pickInnerTab(wrapper, '租约明细')
    expect(router.currentRoute.value.query.tab).toBe('payments')
    expect(innerPaymentsVisible(wrapper)).toBe(true)

    /* Hub 切走再切回 */
    await hubTab(wrapper, '房源信息')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('housing')
    expect(innerPaymentsVisible(wrapper)).toBe(true)

    await hubTab(wrapper, '租住契约')?.trigger('click')
    await flushPromises()

    /* 子视图仍停在租约明细（内部 Tab 是组件状态 ref，非 query 响应式派生，故不被冲掉）；
       URL 的 tab 由 Hub 统一写为 lease —— 记入报告：URL 标记不持久，刷新会回落租约 Tab */
    expect(innerPaymentsVisible(wrapper)).toBe(true)
    expect(router.currentRoute.value.query.tab).toBe('lease')
  })
})
