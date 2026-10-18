import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ResourceListView from '@/resident/reservation/views/ResourceListView.vue'
import ResourceDetailView from '@/resident/reservation/views/ResourceDetailView.vue'
import ReservationListView from '@/resident/reservation/views/ReservationListView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeResource } from '@/test/fakeBackend'

/**
 * 居民端公共资源展示图片（2026-09-29 用户裁决 · 前端接入）：
 * - 资源卡 / 详情主视觉 / 预约卡**有图显示图片、无图回退资源类型 SVG 图标**（不空白、不报错）；
 * - 列表与预约卡多图取首图；详情多图为主图 + 缩略图切换（参照房源详情画廊写法）；
 * - 契约：`public_resource.images` 逗号分隔存储、接口返回 `string[]`，无图为空数组 `[]`（不是 null）。
 */
vi.mock('@/common/community/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))
vi.mock('@/common/resident/api/resident', async () => (await import('@/test/fakeBackend')).fakeApi('resident'))
vi.mock('@/common/reservation/api/reservation', async () => (await import('@/test/fakeBackend')).fakeApi('reservation'))
vi.mock('@/common/housing/api/housing', async () => (await import('@/test/fakeBackend')).fakeApi('housing'))

const backend = () => getFakeBackend()

/** 生效居住关系（居民端资源页的社区来源：无此关系则显示引导空态） */
function seedActiveRelation(): void {
  backend().state.residenceRelations.push({
    id: 1,
    residentId: 1,
    communityId: 1,
    houseId: 1,
    status: 'ACTIVE',
    moveOutAt: null
  })
}

/** 造一份公共资源（默认无图，按需覆盖；种子资源为 id=1「健身房」，无图） */
function seedResource(partial: Partial<FakeResource> & { id: number; name: string }): FakeResource {
  const resource: FakeResource = {
    communityId: 1,
    images: [],
    status: 'AVAILABLE',
    ...partial
  }
  backend().state.resources.push(resource)
  return resource
}

/** 挂载居民端页面（组件直挂 + 路由提供 useRoute 的 params） */
async function mountPage(component: unknown, path: string): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resident/resources', component: { template: '<div />' } },
      { path: '/resident/resources/:id', component: { template: '<div />' } },
      { path: '/resident/reservations', component: { template: '<div />' } },
      { path: '/resident/viewing-appointments/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  installFakeBackend({ role: 'RESIDENT', id: 1 })
  seedActiveRelation()
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('居民端公共资源图片 · 资源列表（ResourceListView）', () => {
  it('有图资源卡显示首图（多图取首图），无图资源卡回退类型图标', async () => {
    seedResource({
      id: 2,
      name: '瑜伽室',
      type: 'GYM',
      images: ['/uploads/yoga-1.png', '/uploads/yoga-2.png']
    })
    const wrapper = await mountPage(ResourceListView, '/resident/resources')

    const yogaCard = wrapper.findAll('.resource-card').find((card) => card.text().includes('瑜伽室'))
    expect(yogaCard, '未渲染「瑜伽室」资源卡').toBeTruthy()
    const yogaCover = yogaCard!.find('.resource-media img')
    expect(yogaCover.exists()).toBe(true)
    expect(yogaCover.attributes('src')).toBe('/uploads/yoga-1.png')
    expect(yogaCard!.find('.resource-media svg').exists()).toBe(false)

    /* 种子资源「健身房」无图（images=[]）：回退类型图标，卡片内容照常渲染（不空白） */
    const gymCard = wrapper.findAll('.resource-card').find((card) => card.text().includes('健身房'))
    expect(gymCard, '未渲染「健身房」资源卡').toBeTruthy()
    expect(gymCard!.find('.resource-media img').exists()).toBe(false)
    expect(gymCard!.find('.resource-media svg').exists()).toBe(true)
    expect(gymCard!.text()).toContain('健身房')
  })

  it('images 为空数组或字段缺失（旧后端）时同样回退图标，不报错', async () => {
    seedResource({ id: 3, name: '地下车位', type: 'PARKING', images: [] })
    /* 防御旧后端：契约要求无图为 []，字段整体缺失时也不得空白/报错 */
    backend().state.resources.push({
      id: 4,
      communityId: 1,
      name: '会议室A',
      type: 'MEETING_ROOM',
      status: 'AVAILABLE'
    } as unknown as FakeResource)

    const wrapper = await mountPage(ResourceListView, '/resident/resources')

    for (const name of ['地下车位', '会议室A']) {
      const card = wrapper.findAll('.resource-card').find((item) => item.text().includes(name))
      expect(card, `未渲染「${name}」资源卡`).toBeTruthy()
      expect(card!.find('.resource-media img').exists()).toBe(false)
      expect(card!.find('.resource-media svg').exists()).toBe(true)
    }
  })
})

describe('居民端公共资源图片 · 资源详情画廊（ResourceDetailView）', () => {
  it('多图：默认主图取第一张，缩略图切换后主图与选中态同步切换', async () => {
    seedResource({
      id: 2,
      name: '瑜伽室',
      type: 'GYM',
      images: ['/uploads/yoga-1.png', '/uploads/yoga-2.png']
    })
    const wrapper = await mountPage(ResourceDetailView, '/resident/resources/2')

    expect(wrapper.find('.gallery-main').attributes('src')).toBe('/uploads/yoga-1.png')
    const thumbs = wrapper.findAll('.gallery-thumb')
    expect(thumbs).toHaveLength(2)
    expect(thumbs[0].classes()).toContain('active')

    await thumbs[1].trigger('click')
    expect(wrapper.find('.gallery-main').attributes('src')).toBe('/uploads/yoga-2.png')
    expect(wrapper.findAll('.gallery-thumb')[1].classes()).toContain('active')
    expect(wrapper.findAll('.gallery-thumb')[0].classes()).not.toContain('active')
  })

  it('单图：渲染主图但不显示缩略图条', async () => {
    seedResource({ id: 3, name: '会议室A', type: 'MEETING_ROOM', images: ['/uploads/room-1.png'] })
    const wrapper = await mountPage(ResourceDetailView, '/resident/resources/3')

    expect(wrapper.find('.gallery-main').attributes('src')).toBe('/uploads/room-1.png')
    expect(wrapper.find('.gallery-thumbs').exists()).toBe(false)
  })

  it('无图：回退类型图标大块占位（不渲染主图、详情主体不空白）', async () => {
    const wrapper = await mountPage(ResourceDetailView, '/resident/resources/1')

    expect(wrapper.find('.gallery-main').exists()).toBe(false)
    expect(wrapper.find('.gallery svg').exists()).toBe(true)
    expect(wrapper.find('.gallery-type').text()).toBe('健身房')
    /* 详情主体照常渲染（资源介绍 / 每周开放时段），不因无图空白 */
    expect(wrapper.text()).toContain('资源介绍')
    expect(wrapper.text()).toContain('每周开放时段')
  })
})

describe('居民端公共资源图片 · 我的预约资源卡（ReservationListView）', () => {
  it('有图资源卡显示首图，无图资源卡保持类型占位块', async () => {
    seedResource({ id: 2, name: '瑜伽室', type: 'GYM', images: ['/uploads/yoga-cover.png'] })
    backend().state.timeslots.push(
      {
        id: 2,
        resourceId: 1,
        slotDate: '2026-09-30',
        startTime: '09:00',
        endTime: '10:00',
        capacity: 1,
        status: 'AVAILABLE'
      },
      {
        id: 3,
        resourceId: 2,
        slotDate: '2026-09-30',
        startTime: '11:00',
        endTime: '12:00',
        capacity: 1,
        status: 'AVAILABLE'
      }
    )
    backend().state.reservations.push(
      {
        id: 1,
        residentId: 1,
        residentName: '演示居民',
        resourceId: 1,
        timeslotId: 2,
        status: 'PENDING',
        reason: null,
        createdAt: '2026-09-29T10:00:00'
      },
      {
        id: 2,
        residentId: 1,
        residentName: '演示居民',
        resourceId: 2,
        timeslotId: 3,
        status: 'PENDING',
        reason: null,
        createdAt: '2026-09-29T10:00:00'
      }
    )
    const wrapper = await mountPage(ReservationListView, '/resident/reservations')

    const gymCard = wrapper.findAll('.reservation-card').find((card) => card.text().includes('健身房'))
    expect(gymCard, '未渲染「健身房」预约卡').toBeTruthy()
    expect(gymCard!.find('.resource-thumb img').exists()).toBe(false)
    expect(gymCard!.find('.resource-thumb svg').exists()).toBe(true)

    const yogaCard = wrapper.findAll('.reservation-card').find((card) => card.text().includes('瑜伽室'))
    expect(yogaCard, '未渲染「瑜伽室」预约卡').toBeTruthy()
    const yogaCover = yogaCard!.find('.resource-thumb img')
    expect(yogaCover.exists()).toBe(true)
    expect(yogaCover.attributes('src')).toBe('/uploads/yoga-cover.png')
    expect(yogaCard!.find('.resource-thumb svg').exists()).toBe(false)
  })
})
