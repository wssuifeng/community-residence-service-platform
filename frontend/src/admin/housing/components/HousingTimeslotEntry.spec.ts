import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import HousingDetailView from '@/admin/housing/views/HousingDetailView.vue'
import HousingListView from '@/admin/housing/views/HousingListView.vue'
import HousesWithHousingView from '@/admin/community/views/HousesWithHousingView.vue'

const mocks = vi.hoisted(() => ({
  role: 'ADMIN', detail: vi.fn(), timeslots: vi.fn(), housings: vi.fn(), houses: vi.fn()
}))
vi.mock('@/common/housing/api/housing', () => ({
  getHousingDetail: mocks.detail, listHousingTimeslots: mocks.timeslots, listHousings: mocks.housings,
  listViewingAppointments: async () => ({ records: [], total: 0 }),
  createHousing: vi.fn(), updateHousing: vi.fn(), deleteHousing: vi.fn(),
  updateHousingStatus: vi.fn(), createHousingTimeslot: vi.fn(),
  updateHousingTimeslot: vi.fn(), deleteHousingTimeslot: vi.fn()
}))
vi.mock('@/common/community/api/community', () => ({
  getCommunityList: async () => ({ records: [], total: 0 }),
  getBuildingList: async () => ({ records: [], total: 0 }),
  getUnitList: async () => ({ records: [], total: 0 }),
  getHouseList: async () => ({ records: [], total: 0 })
}))
vi.mock('@/common/web/utils/request', () => ({ http: { get: mocks.houses } }))

const housing = {
  id: 7, houseId: 42, communityId: 1, communityName: '测试小区', houseLocation: '1号楼101',
  title: '测试房源', description: '', monthlyRent: 3000, deposit: 6000, rentType: 'RENT',
  status: 'AVAILABLE', images: [], viewCount: 0, publishTime: '2026-10-10T09:00:00',
  createdAt: '2026-10-10T09:00:00'
}
const house = {
  houseId: 42, communityId: 1, communityName: '测试小区', buildingId: 1, buildingName: '1号楼',
  unitId: 1, unitName: '1单元', houseNumber: '101', floor: 1, area: 80, layout: '2室1厅',
  houseStatus: 'VACANT', housing
}
const wrappers: VueWrapper[] = []
const scroll = vi.fn()
const originalScroll = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollIntoView')

async function render(component: typeof HousingDetailView | typeof HousingListView | typeof HousesWithHousingView, path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/admin/housings/:id', component: HousingDetailView },
    { path: '/admin/housing-lease', component: HousesWithHousingView }
  ] })
  await router.push(path)
  await router.isReady()
  sessionStorage.setItem('crsp_session', JSON.stringify({ token: 'test-token', expiresAt: Date.now() + 60_000, user: { id: 1, role: mocks.role } }))
  const wrapper = mount(component, { attachTo: document.body, global: { plugins: [createPinia(), ElementPlus, router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  mocks.role = 'ADMIN'
  mocks.detail.mockReset().mockResolvedValue(housing)
  mocks.timeslots.mockReset().mockResolvedValue([])
  mocks.housings.mockReset().mockResolvedValue({ records: [housing], total: 1 })
  mocks.houses.mockReset().mockResolvedValue({ records: [house, { ...house, houseId: 43, housing: null }], total: 2 })
  scroll.mockClear()
  Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, value: scroll })
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
  sessionStorage.clear()
  if (originalScroll) Object.defineProperty(Element.prototype, 'scrollIntoView', originalScroll)
  else Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
})

describe('看房时段入口与异步定位（DEF-153）', () => {
  it('详情延迟返回后才定位到真实时段区域', async () => {
    let resolveDetail!: (value: typeof housing) => void
    mocks.detail.mockImplementation(() => new Promise(resolve => { resolveDetail = resolve }))
    const { wrapper } = await render(HousingDetailView, '/admin/housings/7#timeslots')
    expect(wrapper.find('#timeslots').exists()).toBe(false)
    expect(scroll).not.toHaveBeenCalled()
    resolveDetail(housing)
    await flushPromises()
    expect(wrapper.find('#timeslots').exists()).toBe(true)
    expect(scroll).toHaveBeenCalledTimes(1)
    expect(scroll.mock.contexts[0]).toBe(wrapper.get('#timeslots').element)
    expect(scroll).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
  })

  it('普通详情不抢滚动，同页切换到时段锚点也能定位', async () => {
    const { wrapper, router } = await render(HousingDetailView, '/admin/housings/7')
    expect(scroll).not.toHaveBeenCalled()
    await router.push('/admin/housings/7#timeslots')
    await flushPromises()
    expect(scroll).toHaveBeenCalledTimes(1)
    expect(scroll.mock.contexts[0]).toBe(wrapper.get('#timeslots').element)
  })

  it.each(['ADMIN', 'SUPER_ADMIN'])('%s 的已挂牌卡片显示入口，未挂牌不显示，路由使用房源 ID', async (role) => {
    mocks.role = role
    const { wrapper, router } = await render(HousesWithHousingView, '/admin/housing-lease?tab=housings&view=houses')
    const entries = wrapper.findAll('button').filter(button => button.text().trim() === '看房时段')
    expect(entries).toHaveLength(1)
    await entries[0]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/admin/housings/7#timeslots')
  })

  it('全局房源列表使用同一入口名称与目标', async () => {
    const { wrapper, router } = await render(HousingListView, '/admin/housing-lease?tab=housings&view=list')
    const entry = wrapper.findAll('button').find(button => button.text().trim() === '看房时段')
    expect(entry).toBeDefined()
    await entry!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/admin/housings/7#timeslots')
  })

  it('非管理角色不显示配置入口与详情新增按钮', async () => {
    mocks.role = 'STAFF'
    const { wrapper: list } = await render(HousesWithHousingView, '/admin/housing-lease?tab=housings&view=houses')
    expect(list.findAll('button').some(button => button.text().includes('看房时段'))).toBe(false)
    const { wrapper: detail } = await render(HousingDetailView, '/admin/housings/7')
    expect(detail.findAll('button').some(button => button.text().includes('新增时段'))).toBe(false)
  })
})
