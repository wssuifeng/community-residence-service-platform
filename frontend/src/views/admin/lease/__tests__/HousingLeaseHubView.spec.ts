import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import HousingLeaseHubView from '../HousingLeaseHubView.vue'

/**
 * 管理端「租住与房源」Hub（批次 g 导航合并）：Tab 取值回落、URL query 与子视图参数、
 * 协议模板 Tab 的角色可见性。
 *
 * 子视图打桩口径分两类：
 * - 「租约管理」与「房源管理」是 query 驱动分组的真实子视图，**不打桩**（只打桩它们各自的
 *   重子组件），以便断言「Hub 切 Tab 往返后子视图回到原分组」；
 * - 其余三个子视图打桩（本文件只测 Hub 的分区与路由行为）。
 */

/** 子视图自身依赖的 api 打桩：只保证挂载期不真请求，数据为空即可 */
const listMocks = vi.hoisted(() => ({
  getLeaseList: async () => ({ records: [], total: 0 }),
  getCommunityList: async () => [],
  listHousings: async () => ({ records: [], total: 0 }),
  listViewingAppointments: async () => ({ records: [], total: 0 })
}))

vi.mock('@/common/lease/api/lease', () => ({
  createLease: async () => ({}),
  getLeaseList: listMocks.getLeaseList,
  renewLease: async () => ({}),
  updateLease: async () => ({}),
  updateLeaseStatus: async () => ({}),
  listLeasePayments: async () => [],
  getLease: async () => null,
  confirmTenancy: async () => ({})
}))

vi.mock('@/common/community/api/community', () => ({
  getCommunityList: listMocks.getCommunityList
}))

vi.mock('@/common/housing/api/housing', () => ({
  listHousings: listMocks.listHousings,
  listViewingAppointments: listMocks.listViewingAppointments
}))

const CHILD_STUBS = {
  ResidenceApplicationListView: true,
  MoveOutApplicationView: true,
  AgreementTemplateView: true,
  /* 房源管理内部的重子组件（其自身不是本用例关注点） */
  HousesWithHousingView: true,
  HousingListView: true,
  ViewingAppointmentListView: true
}

/** 面板顺序与 TAB_ITEMS 一致：申请审核 → 租约 → 房屋房源 → 退租 → 协议模板 */
const PANEL_ORDER = ['applications', 'leases', 'housings', 'moveout', 'agreements']

function seedSession(role: string): void {
  sessionStorage.setItem(
    'crsp_session',
    JSON.stringify({
      token: 'test-token',
      expiresAt: Date.now() + 3600_000,
      user: { id: 1, username: 'admin1', realName: '管理员', role }
    })
  )
}

interface Hub {
  wrapper: VueWrapper
  router: Router
}

async function mountHub(query = ''): Promise<Hub> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/housing-lease', component: HousingLeaseHubView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push(`/admin/housing-lease${query}`)
  await router.isReady()
  const wrapper = mount(HousingLeaseHubView, {
    global: { plugins: [createPinia(), router, ElementPlus], stubs: CHILD_STUBS }
  })
  await flushPromises()
  return { wrapper, router }
}

/* Hub 与子视图都用 nav.tab-bar + .tab-item，故按 aria-label 精确定位各自的 Tab 条 */
const HUB_TABS = 'nav[aria-label="租住与房源分区"]'
const LEASE_STAGE_TABS = 'nav[aria-label="租住管理视图切换"]'
const HOUSING_VIEW_TABS = 'nav[aria-label="房源管理视图切换"]'

function barButtons(wrapper: VueWrapper, bar: string): string[] {
  const nav = wrapper.find(bar)
  return nav.exists() ? nav.findAll('.tab-item').map((item) => item.text()) : []
}

function barActiveLabel(wrapper: VueWrapper, bar: string): string {
  const nav = wrapper.find(bar)
  if (!nav.exists()) return ''
  const active = nav.findAll('.tab-item').find((item) => item.attributes('aria-selected') === 'true')
  return active ? active.text() : ''
}

function tabLabels(wrapper: VueWrapper): string[] {
  return barButtons(wrapper, HUB_TABS)
}

function activeTabLabel(wrapper: VueWrapper): string {
  return barActiveLabel(wrapper, HUB_TABS)
}

/** v-show 保挂载：只有当前 Tab 的面板不带 display:none（按面板原始顺序回报） */
function visiblePanels(wrapper: VueWrapper): string[] {
  return wrapper
    .findAll('.hub-panel')
    .map((panel, index) => ({ panel, index }))
    .filter(({ panel }) => !(panel.attributes('style') ?? '').includes('display: none'))
    .map(({ index }) => PANEL_ORDER[index])
}

function tabButton(wrapper: VueWrapper, label: string) {
  return wrapper.find(HUB_TABS).findAll('.tab-item').find((item) => item.text() === label)
}

/** 子视图内的分组按钮（租约 stage / 房源 view），按可见文本定位 */
function childTabButton(wrapper: VueWrapper, bar: string, label: string) {
  return wrapper.find(bar).findAll('.tab-item').find((item) => item.text().includes(label))
}

beforeEach(() => {
  seedSession('ADMIN')
})

afterEach(() => {
  sessionStorage.clear()
})

describe('AdminHousingLeaseHub · Tab 取值与 URL query（批次 g）', () => {
  it('无 ?tab：回落「入住申请」，只显示申请面板', async () => {
    const { wrapper } = await mountHub()

    expect(activeTabLabel(wrapper)).toBe('入住申请')
    expect(visiblePanels(wrapper)).toEqual(['applications'])
  })

  it('非法 ?tab：同样回落「入住申请」', async () => {
    const { wrapper } = await mountHub('?tab=bogus')

    expect(activeTabLabel(wrapper)).toBe('入住申请')
    expect(visiblePanels(wrapper)).toEqual(['applications'])
  })

  it('合法 ?tab=leases：选中租约管理且只显示租约面板', async () => {
    const { wrapper } = await mountHub('?tab=leases')

    expect(activeTabLabel(wrapper)).toBe('租约管理')
    expect(visiblePanels(wrapper)).toEqual(['leases'])
  })

  it('深链子视图参数原样透传：?tab=housings&view=viewings 不丢 view', async () => {
    const { wrapper, router } = await mountHub('?tab=housings&view=viewings')

    expect(activeTabLabel(wrapper)).toBe('房源管理')
    expect(visiblePanels(wrapper)).toEqual(['housings'])
    expect(router.currentRoute.value.query.view).toBe('viewings')
  })

  it('点击 Tab：URL query 更新为目标 tab，且面板随之切换', async () => {
    const { wrapper, router } = await mountHub('?tab=leases')

    await tabButton(wrapper, '退租申请')?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.tab).toBe('moveout')
    expect(activeTabLabel(wrapper)).toBe('退租申请')
    expect(visiblePanels(wrapper)).toEqual(['moveout'])
  })

  it('五个子视图均保持挂载（v-show 而非 v-if）', async () => {
    const { wrapper } = await mountHub('?tab=leases')

    expect(wrapper.findAll('.hub-panel')).toHaveLength(5)
  })

  it('租约分组往返保留：选「待签约」→ 切房源管理 → 切回，stage 保留且子视图回到该分组', async () => {
    const { wrapper, router } = await mountHub('?tab=leases')
    expect(barActiveLabel(wrapper, LEASE_STAGE_TABS)).toBe('在租租约')

    /* 子视图内选分组：真实子视图把分组写进 route.query.stage */
    await childTabButton(wrapper, LEASE_STAGE_TABS, '待签约')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.stage).toBe('toSign')
    expect(barActiveLabel(wrapper, LEASE_STAGE_TABS)).toBe('待签约 / 待支付')

    /* Hub 切走再切回 */
    await tabButton(wrapper, '房源管理')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.tab).toBe('housings')
    expect(router.currentRoute.value.query.stage).toBe('toSign')

    await tabButton(wrapper, '租约管理')?.trigger('click')
    await flushPromises()

    /* stage 未被 Hub 冲掉，且真实子视图确实回到「待签约」分组 */
    expect(router.currentRoute.value.query.stage).toBe('toSign')
    expect(barActiveLabel(wrapper, LEASE_STAGE_TABS)).toBe('待签约 / 待支付')
    expect(activeTabLabel(wrapper)).toBe('租约管理')
  })

  it('房源分区往返保留：选「看房预约」→ 切租约管理 → 切回，view 保留且子视图回到该分区', async () => {
    const { wrapper, router } = await mountHub('?tab=housings')
    expect(barActiveLabel(wrapper, HOUSING_VIEW_TABS)).toBe('房屋与房源')

    await childTabButton(wrapper, HOUSING_VIEW_TABS, '看房预约')?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.view).toBe('viewings')
    expect(barActiveLabel(wrapper, HOUSING_VIEW_TABS)).toBe('看房预约')

    await tabButton(wrapper, '租约管理')?.trigger('click')
    await flushPromises()
    await tabButton(wrapper, '房源管理')?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.view).toBe('viewings')
    expect(barActiveLabel(wrapper, HOUSING_VIEW_TABS)).toBe('看房预约')
    expect(activeTabLabel(wrapper)).toBe('房源管理')
  })

  it('两个子视图的分组参数互不干扰：stage 与 view 同时存在时各自保留', async () => {
    const { wrapper, router } = await mountHub('?tab=leases&stage=toSign&view=viewings')

    await tabButton(wrapper, '退租申请')?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({ tab: 'moveout', stage: 'toSign', view: 'viewings' })
    expect(barActiveLabel(wrapper, LEASE_STAGE_TABS)).toBe('待签约 / 待支付')
    expect(barActiveLabel(wrapper, HOUSING_VIEW_TABS)).toBe('看房预约')
  })
})

describe('AdminHousingLeaseHub · 协议模板 Tab 的角色可见性（批次 g）', () => {
  it('ADMIN：协议模板 Tab 可见，?tab=agreements 可直接进入', async () => {
    const { wrapper } = await mountHub('?tab=agreements')

    expect(tabLabels(wrapper)).toContain('协议模板')
    expect(activeTabLabel(wrapper)).toBe('协议模板')
    expect(visiblePanels(wrapper)).toEqual(['agreements'])
  })

  it('非 ADMIN 角色：协议模板 Tab 不可见，手工拼 ?tab=agreements 也回落入住申请', async () => {
    seedSession('STAFF')

    const { wrapper } = await mountHub('?tab=agreements')

    expect(tabLabels(wrapper)).not.toContain('协议模板')
    expect(activeTabLabel(wrapper)).toBe('入住申请')
    expect(visiblePanels(wrapper)).toEqual(['applications'])
  })

  it('非 ADMIN 角色：协议模板面板不挂载（角色门控 v-if，不触发 403 报错 toast）', async () => {
    seedSession('STAFF')

    const { wrapper } = await mountHub('?tab=leases')

    /* 面板 5 → 4：agreements 面板对不可见角色整体不渲染（修复前 v-show 常挂载，
       每次访问 Hub 都会请求协议模板接口并弹 403 报错） */
    expect(wrapper.findAll('.hub-panel')).toHaveLength(4)
    expect(wrapper.find('agreement-template-view-stub').exists()).toBe(false)
    /* 其余四个面板不受门控影响，仍保持 v-show 常挂载 */
    expect(wrapper.find('residence-application-list-view-stub').exists()).toBe(true)
  })
})
