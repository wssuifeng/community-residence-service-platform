import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import LeaseListView from '../LeaseListView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import { ApiError } from '@/utils/request'
import type { FakeLease } from '@/test/fakeBackend'

/**
 * 承约语义（用户裁决 2026-09-22，推翻上批的 DEF-077 硬前置）：
 * - **承约不设前置**：看房与租约沟通群都只是自愿的"交通手段"，无看房、无群也能正常承约；
 * - 旧构建/旁路数据若仍返回 5607/5608，只作**非阻断提示**（不弹阻断弹窗、不强制跳转）；
 * - 承约后**由管理方直接推进协议确认**（DEF-078 去重：管理方确认时引用承约留痕），
 *   居民侧**没有二次确认入口/提示**。
 */
vi.mock('@/api/lease', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))
vi.mock('@/api/agreement', async () => (await import('@/test/fakeBackend')).fakeApi('agreement'))
vi.mock('@/api/payment', async () => (await import('@/test/fakeBackend')).fakeApi('payment'))
vi.mock('@/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

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

describe('居民端租约 · 承约不设前置（无看房、无群也可正常承约）', () => {
  it('既无看房预约、也无租约沟通群时，确认承租仍然成功并落承租留痕', async () => {
    seedToSignLease(null)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const warningMock = vi.spyOn(ElMessage, 'warning')
    const alertMock = vi.spyOn(ElMessageBox, 'alert')

    const router = buildRouter()
    const wrapper = await mountList(router)
    await buttonByText(wrapper, '确认承租')?.trigger('click')
    await flushPromises()

    expect(backend().state.leases[0].tenantConfirmAt).toBeTruthy()
    expect(backend().state.leases[0].tenantConfirmBy).toBe(1)
    /* 全程无阻断：既没有 5607/5608 的阻断弹窗，也没有警告提示 */
    expect(alertMock).not.toHaveBeenCalled()
    expect(warningMock).not.toHaveBeenCalled()
    /* 承约仍留在租约页（不因前置被强制跳去预约看房） */
    expect(router.currentRoute.value.path).toBe('/resident/housing-lease')
    expect(wrapper.text()).toContain('已确认承租')
  })

  it('看房与沟通群只作自愿提示：承约引导旁给出可选说明，且不表现为阻断', async () => {
    seedToSignLease(null)
    const router = buildRouter()
    const wrapper = await mountList(router)
    await wrapper.find('.lease-row').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('自愿手段')
    expect(wrapper.text()).toContain('不构成承约前置')
    /* 没有"必须先看房/先建群"的阻断式文案 */
    expect(wrapper.text()).not.toContain('请先完成本房源的看房预约后再确认承租')
    expect(wrapper.text()).not.toContain('租约沟通会话未建立')
  })

  it('旧构建仍返回 5607 时只是非阻断提示：不弹阻断弹窗、不强制跳转、状态不变', async () => {
    seedToSignLease(null)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const alertMock = vi.spyOn(ElMessageBox, 'alert')
    const warningMock = vi.spyOn(ElMessage, 'warning')
    /* 模拟旧后端：承约接口仍抛 5607 */
    const leaseApi = await import('@/api/lease')
    const original = leaseApi.confirmTenancy
    ;(leaseApi as unknown as Record<string, unknown>).confirmTenancy = async () => {
      throw new ApiError(5607, '请先完成本房源的看房预约后再确认承租', 200)
    }

    try {
      const router = buildRouter()
      const wrapper = await mountList(router)
      await buttonByText(wrapper, '确认承租')?.trigger('click')
      await flushPromises()

      /* 只用一句非阻断提示，不出现"去预约看房"的必经引导弹窗 */
      expect(warningMock).toHaveBeenCalledTimes(1)
      expect(String(warningMock.mock.calls[0][0])).toContain('自愿手段')
      expect(alertMock).not.toHaveBeenCalled()
      expect(router.currentRoute.value.path).toBe('/resident/housing-lease')
      expect(backend().state.leases[0].tenantConfirmAt).toBeFalsy()
      /* 也没有把用户弹去预约看房 */
      expect(document.body.textContent).not.toContain('去预约看房')
    } finally {
      ;(leaseApi as unknown as Record<string, unknown>).confirmTenancy = original
    }
  })
})

describe('承约后协议由管理方直接推进（DEF-078 去重）', () => {
  it('承约后管理方确认一次即 SIGNED 并联动租约生效，无需居民再次确认', async () => {
    const leaseApi = await import('@/api/lease')
    const agreementApi = await import('@/api/agreement')
    seedToSignLease(null)

    await leaseApi.confirmTenancy(1)
    /* 管理方发起协议（前置=已承约） */
    const agreement = (await agreementApi.createLeaseAgreement({ leaseId: 1, templateId: 1 } as never)) as {
      id: number
    }
    /* 仅管理方确认：后端/假后端引用承约留痕补齐居民侧 → 直接 SIGNED */
    const signed = (await agreementApi.confirmLeaseAgreement(agreement.id, {
      party: 'ADMIN'
    } as never)) as unknown as { status: string; tenantConfirmedAt: string | null }

    expect(signed.status).toBe('SIGNED')
    expect(signed.tenantConfirmedAt).toBe(backend().state.leases[0].tenantConfirmAt)
    expect(backend().state.leases[0].status).toBe('ACTIVE')
  })

  it('居民侧协议面板不再渲染「确认协议」入口，改为说明承约已作留痕', async () => {
    seedToSignLease(null)
    backend().state.leases[0].tenantConfirmAt = '2026-01-02T10:00:00'
    backend().state.agreements.push({
      id: 1,
      leaseId: 1,
      templateId: 1,
      status: 'PARTIAL',
      content: '租约正文',
      tenantConfirmedAt: null,
      adminConfirmedAt: '2026-01-03T10:00:00',
      createdAt: '2026-01-02T11:00:00'
    } as never)

    const router = buildRouter()
    const wrapper = await mountList(router)
    await wrapper.find('.lease-row').trigger('click')
    await flushPromises()

    /* 居民侧没有二次确认按钮，并给出"无需再次确认"的说明 */
    expect(buttonByText(wrapper, '确认协议')).toBeFalsy()
    expect(wrapper.text()).toContain('承租确认（承约）即您的签署意愿留痕')
    expect(wrapper.text()).toContain('您无需再次确认')
  })
})
