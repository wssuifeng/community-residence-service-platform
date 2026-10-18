import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkOrderDetailView from '../WorkOrderDetailView.vue'
import { actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeWorkOrder } from '@/test/fakeBackend'
import { assignWorkOrder, intakeWorkOrder } from '@/common/workorder/api/workorder'

/**
 * 服务人员端工单详情 · R74：派单后自动入群，可从详情进入统一会话；
 * **未入群（未派单）时非参与者不可见（403）→ 不渲染面板**，不把「无权」包装成空群聊。
 */
vi.mock('@/common/workorder/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/common/conversation/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/common/upload/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

const backend = () => getFakeBackend()

function seedOrder(): void {
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO202609220003',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水龙头持续漏水',
    description: '水龙头根部渗水',
    status: 'PENDING' as FakeWorkOrder['status'],
    assigneeId: null,
    assigneeName: null,
    createdAt: '2026-09-22T09:00:00'
  })
}

async function mountDetail(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/staff/work-orders/:id', component: WorkOrderDetailView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push('/staff/work-orders/1')
  await router.isReady()
  const wrapper = mount(WorkOrderDetailView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('服务人员端工单详情 · R74 工单临时群聊面板', () => {
  it('受理后未派单：服务人员不在群 → 不渲染群聊面板（非参与者 403）', async () => {
    seedOrder()
    await intakeWorkOrder(1)
    const groupId = backend().state.conversations[0].id

    actAs('STAFF', 1)
    /* 服务人员视角看得到该单（假后端未派单时按其社区口径放行），但会话不可见 */
    const wrapper = await mountDetail()

    expect(wrapper.text()).toContain('厨房水龙头持续漏水')
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
    /* 群成员里没有服务人员（派单才入群） */
    const refs = backend().state.conversations.find((item) => item.id === groupId)?.participantRefs ?? []
    expect(refs.some((item) => item.role === 'STAFF')).toBe(false)
    expect(wrapper.find('.conversation-entry').exists()).toBe(false)
    wrapper.unmount()
  })

  it('派单后服务人员可从工单进入统一会话并返回原单', async () => {
    seedOrder()
    await intakeWorkOrder(1)
    await assignWorkOrder(1, { assigneeId: 1 })
    actAs('STAFF', 1)
    const wrapper = await mountDetail()
    const link = wrapper.find('.conversation-entry a')
    expect(link.text()).toContain('进入沟通')
    expect(link.attributes('href')).toContain('/staff/conversations?id=1&source=CONVERSATION')
    expect(link.attributes('href')).toContain('returnTo=')
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
    wrapper.unmount()
  })
})
