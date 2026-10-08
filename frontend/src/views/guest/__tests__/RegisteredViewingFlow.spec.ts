import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import HousingDetailView from '../HousingDetailView.vue'
import HousingReservationView from '@/views/resident/HousingReservationView.vue'
import { fakeLogin } from '@/test/fakeBackend'

const mocks = vi.hoisted(() => ({
  getHousingDetail: vi.fn(),
  listViewingAvailableSlots: vi.fn(),
  recordHousingView: vi.fn(),
  getMyProfile: vi.fn(),
  createViewingAppointment: vi.fn()
}))

vi.mock('@/api/housing', () => ({
  getHousingDetail: mocks.getHousingDetail,
  listViewingAvailableSlots: mocks.listViewingAvailableSlots,
  recordHousingView: mocks.recordHousingView,
  createViewingAppointment: mocks.createViewingAppointment
}))

vi.mock('@/api/resident', () => ({
  getMyProfile: mocks.getMyProfile
}))

const housing = {
  id: 1,
  communityId: 1,
  communityName: '阳光花园',
  houseId: 1,
  houseLocation: '1号楼-1单元-101',
  title: '精装两室一厅',
  description: '采光良好',
  monthlyRent: 3000,
  deposit: 6000,
  rentType: 'RENT',
  layout: '2室1厅',
  images: ['/housing.png'],
  status: 'AVAILABLE',
  viewCount: 12,
  publishTime: '2026-10-01T09:00:00',
  createdAt: '2026-10-01T09:00:00',
  tags: [],
  contactPhone: null
} as const

function tomorrowISO(): string {
  const date = new Date(Date.now() + 24 * 60 * 60 * 1000)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const slot = () => ({
  timeslotId: 1,
  date: tomorrowISO(),
  startTime: '10:00:00',
  endTime: '11:00:00',
  maxBookings: 2,
  currentBookings: 0,
  status: 'AVAILABLE' as const
})

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/guest/housings/:id', component: HousingDetailView },
      { path: '/auth/login', component: { template: '<div>login</div>' } },
      { path: '/resident/housings/:id/reserve', component: HousingReservationView },
      { path: '/resident/viewing-appointments/:id', component: { template: '<div>detail</div>' } }
    ]
  })
}

async function mountAt(router: Router, path: string, component: typeof HousingDetailView | typeof HousingReservationView) {
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  sessionStorage.clear()
  mocks.getHousingDetail.mockResolvedValue({ ...housing })
  mocks.listViewingAvailableSlots.mockResolvedValue([slot()])
  mocks.recordHousingView.mockResolvedValue(13)
  mocks.getMyProfile.mockResolvedValue({ id: 1, realName: '张伟', phone: '13800000001' })
  mocks.createViewingAppointment.mockResolvedValue({ id: 42, status: 'TO_CONFIRM' })
})

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('方案 B · 看房预约入口与居民身份', () => {
  it('游客只读浏览时段，点击预约跳登录并携带预约页回跳地址', async () => {
    sessionStorage.clear()
    const router = buildRouter()
    const wrapper = await mountAt(router, '/guest/housings/1', HousingDetailView)

    expect(wrapper.text()).toContain('登录 / 注册后预约看房')
    expect(wrapper.text()).toContain('可约时段')
    await wrapper.findAll('button').find((button) => button.text() === '登录 / 注册后预约看房')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/auth/login')
    expect(router.currentRoute.value.query.redirect).toBe('/resident/housings/1/reserve')
  })
})

describe('方案 B · 居民预约表单', () => {
  it('预约页不展示游客字段，提交 DTO 只携带居民身份契约', async () => {
    fakeLogin('RESIDENT', { id: 1 })
    const router = buildRouter()
    const wrapper = await mountAt(router, '/resident/housings/1/reserve', HousingReservationView)

    const initialText = wrapper.text()
    expect(initialText).not.toContain('游客姓名')
    expect(initialText).not.toContain('游客人数')
    expect(initialText).not.toContain('短信验证码')

    const availableDay = wrapper.findAll('button.calendar-cell').find((button) => !button.classes('is-off') && !button.classes('is-blank'))
    expect(availableDay, '没有可选日期').toBeTruthy()
    await availableDay!.trigger('click')
    await flushPromises()

    const availableSlot = wrapper.find('button.slot-card:not([disabled])')
    expect(availableSlot.exists(), '没有可选时段').toBe(true)
    await availableSlot.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '下一步')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('确认看房预约')
    expect(wrapper.text()).not.toContain('游客姓名')
    expect(wrapper.text()).not.toContain('游客人数')
    await wrapper.findAll('button').find((button) => button.text() === '提交预约')!.trigger('click')
    await flushPromises()

    expect(mocks.createViewingAppointment).toHaveBeenCalledWith({
      housingId: 1,
      appointmentDate: tomorrowISO(),
      startTime: '10:00:00',
      endTime: '11:00:00',
      contactPhone: '13800000001'
    })
    expect(router.currentRoute.value.path).toBe('/resident/viewing-appointments/42')
  })
})
