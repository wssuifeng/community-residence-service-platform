import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import StructureTreePane from '@/admin/community/components/StructureTreePane.vue'

const mocks = vi.hoisted(() => ({ communities: vi.fn(), buildings: vi.fn(), units: vi.fn(), houses: vi.fn(), stats: vi.fn() }))
vi.mock('@/common/community/api/community', async (original) => ({ ...await original<object>(), getCommunityList: mocks.communities, getBuildingList: mocks.buildings, getUnitList: mocks.units, getHouseList: mocks.houses }))
vi.mock('@/common/statistics/api/statistics', () => ({ getDashboardStats: mocks.stats }))
const community = { id: 1, name: '大社区', status: 'ACTIVE', address: '测试地址' }
const page = (records: unknown[], total = records.length, current = 1) => ({ records, total, page: current, size: 100, pages: Math.ceil(total / 100) })
const building = (id: number) => ({ id, communityId: 1, name: `${id}栋`, floors: 20 })
const unit = (id: number, buildingId: number) => ({ id, buildingId, name: `${id}单元` })
const house = (id: number, unitId: number) => ({ id, unitId, houseNumber: String(id), floor: 1, status: 'VACANT', area: 60, rooms: 2 })
let wrapper: VueWrapper | undefined
async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/admin/community')
  wrapper = mount(StructureTreePane, { global: { plugins: [createPinia(), router, ElementPlus], stubs: { CommunityEditDialog: true, BuildingEditDialog: true, UnitEditDialog: true, UnitBatchDialog: true, HouseEditDialog: true, HouseBatchDialog: true, StructureGenerateDialog: true, BatchResultDialog: true } } })
  await flushPromises()
  return wrapper
}
async function chooseBuilding(value: number | null) {
  const select = wrapper!.findAllComponents({ name: 'ElSelect' }).find(s => s.props('placeholder') === '全部楼栋')!
  expect(select).toBeTruthy()
  select.vm.$emit('update:modelValue', value)
  select.vm.$emit('change', value)
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.communities.mockResolvedValue(page([community]))
  mocks.buildings.mockResolvedValue(page([building(2), building(1)]))
  mocks.units.mockImplementation(async (id: number) => page([unit(id * 10, id)]))
  mocks.houses.mockImplementation(async (id: number) => page([house(id * 10, id)]))
  mocks.stats.mockResolvedValue({ buildingCount: 2, houseCount: 624, occupiedHouseCount: 124, houseStatusDistribution: { VACANT: 500, OCCUPIED: 124 } })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; sessionStorage.clear() })
describe('DEF-154 社区结构按范围读取', () => {
  it('初次仅读取序号第一栋房屋，社区计数不以局部缓存冒充全量', async () => {
    const w = await mountPage()
    expect(mocks.houses.mock.calls.map(c => c[0])).toEqual([10])
    expect(w.find('.metric-strip').text()).toContain('624')
    expect(w.find('.metric-strip').text()).toContain('500')
    expect(w.findAll('.grid-block')).toHaveLength(1)
  })
  it('切换楼栋按需读取，显式全部不遗漏楼栋，回切复用缓存', async () => {
    await mountPage()
    await chooseBuilding(2)
    expect(mocks.houses.mock.calls.map(c => c[0])).toEqual([10, 20])
    await chooseBuilding(null)
    expect(wrapper!.findAll('.grid-block')).toHaveLength(2)
    await chooseBuilding(1)
    expect(mocks.houses).toHaveBeenCalledTimes(2)
  })
  it('单元超过服务端单页上限时读到下一页，不静默漏房', async () => {
    mocks.houses.mockImplementation(async (id: number, q: { page: number }) => page(q.page === 1 ? Array.from({ length: 100 }, (_, i) => house(i + 1, id)) : [house(101, id)], 101, q.page))
    await mountPage()
    expect(mocks.houses.mock.calls.map(c => c[1].page)).toEqual([1, 2])
    expect(wrapper!.findAll('.grid-block')).toHaveLength(101)
  })
  it('当前范围读取失败保留错误入口，重试不把局部结果冒充完整', async () => {
    await mountPage()
    mocks.houses.mockRejectedValueOnce(new Error('房屋读取失败'))
    await chooseBuilding(2)
    expect(wrapper!.text()).toContain('房屋读取失败')
    expect(wrapper!.findAll('.grid-block')).toHaveLength(0)
    const retry = wrapper!.findAll('button').find(button => button.text() === '重试')!
    await retry.trigger('click'); await flushPromises()
    expect(wrapper!.findAll('.grid-block')).toHaveLength(1)
    expect(wrapper!.text()).not.toContain('房屋读取失败')
  })
  it('楼栋及单元分页读完整，显式全部范围不漏目录', async () => {
    mocks.buildings.mockImplementation(async (_id: number, q: { page: number }) => page(q.page === 1 ? [building(1)] : [building(2)], 2, q.page))
    mocks.units.mockImplementation(async (id: number, q: { page: number }) => page(q.page === 1 ? [unit(id * 10, id)] : [unit(id * 10 + 1, id)], 2, q.page))
    await mountPage(); await chooseBuilding(null)
    expect(mocks.buildings.mock.calls.map(c => c[1].page)).toEqual([1, 2])
    expect(mocks.houses.mock.calls.map(c => c[0]).sort()).toEqual([10, 11, 20, 21])
    expect(wrapper!.findAll('.grid-block')).toHaveLength(4)
  })
  it('切换中的失败/迟到结果不盖住已选楼栋', async () => {
    await mountPage()
    let reject!: (e: Error) => void
    mocks.houses.mockImplementationOnce(() => new Promise((_, r) => { reject = r }))
    await chooseBuilding(2)
    await chooseBuilding(1)
    reject(new Error('旧范围读取失败'))
    await flushPromises()
    expect(wrapper!.findAll('.grid-block')).toHaveLength(1)
    expect(wrapper!.text()).not.toContain('旧范围读取失败')
  })
})
