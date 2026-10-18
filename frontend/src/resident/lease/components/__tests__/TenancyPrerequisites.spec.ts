import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import LeaseListView from '@/resident/lease/views/LeaseListView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeLease } from '@/test/fakeBackend'

/**
 * 承约语义（用户裁决 2026-09-22，推翻上批的 DEF-077 硬前置）：
 * - **承约不设前置**：看房与租约沟通群都只是自愿的"交通手段"，无看房、无群也能正常承约；
 * - 旧构建/旁路数据若仍返回 5607/5608，只作**非阻断提示**（不弹阻断弹窗、不强制跳转）；
 * - 承约后**由管理方直接推进协议确认**（DEF-078 去重：管理方确认时引用承约留痕），
 *   居民侧**没有二次确认入口/提示**。
 */
vi.mock('@/common/lease/api/lease', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))
vi.mock('@/common/agreement/api/agreement', async () => (await import('@/test/fakeBackend')).fakeApi('agreement'))
vi.mock('@/common/payment/api/payment', async () => (await import('@/test/fakeBackend')).fakeApi('payment'))
vi.mock('@/common/conversation/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/common/upload/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

const backend = () => getFakeBackend()

function seedToSignLease(conversationId: number | null): void {
  backend().state.leases.push({
    id: 1,
    tenantId: 1,
    tenantName: '演示居民',
    communityId: 1,
    houseId: 1,
    houseLocation: '1号楼-1单元-101',
    startDate: '2026-01-01',
    endDate: '2027-12-31',
    monthlyRent: 3000,
    deposit: 6000,
    status: 'TO_SIGN',
    conversationId,
    createdAt: '2026-01-01T10:00:00'
  } as FakeLease)
}

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resident/housing-lease', component: LeaseListView },
      { path: '/resident/reservations', component: { template: '<div class="reservation-stub" />' } },
      { path: '/resident/conversations/:id', component: { template: '<div />' } }
    ]
  })
}

async function mountList(router: Router): Promise<VueWrapper> {
  await router.push('/resident/housing-lease')
  await router.isReady()
  const wrapper = mount(LeaseListView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().trim() === text)
}

beforeEach(() => {
  installFakeBackend({ role: 'RESIDENT', id: 1 })
  backend().state.residents.push({
    id: 1,
    name: '演示居民',
    phone: '13800000001',
    communityId: 1,
    status: 'ACTIVE'
  } as never)
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('协议意愿去重（DEF-168）', () => {
  it('无看房/群聊的待签约租约不再要求确认承租', async () => {
    seedToSignLease(null)
    const wrapper = await mountList(buildRouter())
    expect(buttonByText(wrapper, '确认承租')).toBeFalsy()
    await wrapper.find('.lease-row').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('在“查看协议”中阅读条款并确认同意')
  })
  it('旧承租留痕不再渲染独立徽标或确认按钮', async () => {
    seedToSignLease(null)
    backend().state.leases[0].tenantConfirmAt = '2026-01-02T10:00:00'
    const wrapper = await mountList(buildRouter())
    expect(buttonByText(wrapper, '确认承租')).toBeFalsy()
    expect(wrapper.text()).not.toContain('已确认承租')
  })
})
