import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkOrderDetailView from '../WorkOrderDetailView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeWorkOrder } from '@/test/fakeBackend'

/** 详情保留统一会话入口；消息与只读状态由共享会话工作区呈现。 */
vi.mock('@/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/api/evaluation', async () => (await import('@/test/fakeBackend')).fakeApi('evaluation'))
vi.mock('@/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))

const backend = () => getFakeBackend()

interface SeedOptions {
  status?: FakeWorkOrder['status']
  /** 是否已受理（已建群 + 回填 conversationId） */
  withGroup?: boolean
}

function seedOrder(options: SeedOptions = {}): void {
  const conversationId = options.withGroup ? 1 : null
  if (options.withGroup) {
    backend().state.conversations.push({
      id: 1,
      type: 'WORK_ORDER_GROUP',
      relatedId: 1,
      title: '工单沟通·WO202609220003',
      status: options.status === 'CLOSED' ? 'CLOSED' : 'ACTIVE',
      participantIds: [1, 1],
      participantRefs: [
        { id: 1, space: 'RESIDENT', name: '演示居民', role: 'RESIDENT' },
        { id: 1, space: 'USER', name: '社区管理员', role: 'ADMIN' }
      ],
      closedAt: options.status === 'CLOSED' ? '2026-09-22T18:00:00' : null,
      createdAt: '2026-09-22T09:00:00'
    })
    backend().state.conversationMessages.push({
      id: 1,
      conversationId: 1,
      senderType: 'ADMIN',
      senderId: 1,
      senderName: '社区管理员',
      content: '已受理，正在安排服务人员',
      createdAt: '2026-09-22T09:30:00'
    })
  }
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO202609220003',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水龙头持续漏水',
    description: '水龙头根部渗水',
    status: options.status ?? (options.withGroup ? 'TO_ASSIGN' : 'PENDING'),
    assigneeId: null,
    assigneeName: null,
    conversationId,
    createdAt: '2026-09-22T09:00:00'
  })
}

async function mountDetail(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resident/work-orders/:id', component: WorkOrderDetailView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push('/resident/work-orders/1')
  await router.isReady()
  const wrapper = mount(WorkOrderDetailView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  installFakeBackend({ role: 'RESIDENT', id: 1 })
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('居民端工单详情 · R74 工单临时群聊面板', () => {
  it('未受理的单不渲染群聊面板（受理前无群）', async () => {
    seedOrder()
    const wrapper = await mountDetail()

    expect(wrapper.text()).toContain('厨房水龙头持续漏水')
    expect(wrapper.text()).not.toContain('工单临时群聊')
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
    wrapper.unmount()
  })

  it('受理后显示进入会话入口并携带来源及返回路径', async () => {
    seedOrder({ withGroup: true })
    const wrapper = await mountDetail()
    const link = wrapper.find('.conversation-entry a')
    expect(link.text()).toContain('进入沟通')
    expect(link.attributes('href')).toContain('/resident/conversations?id=1&source=CONVERSATION')
    expect(link.attributes('href')).toContain('returnTo=')
    expect(wrapper.find('.conversation-chat').exists()).toBe(false)
    wrapper.unmount()
  })

  it('已关闭工单仍可进入会话查看历史', async () => {
    seedOrder({ withGroup: true, status: 'CLOSED' })
    const wrapper = await mountDetail()
    expect(wrapper.find('.conversation-entry a').text()).toContain('查看沟通记录')
    expect(wrapper.find('.conversation-entry a').attributes('href')).toContain('id=1')
    expect(wrapper.find('.chat-input').exists()).toBe(false)
    wrapper.unmount()
  })
})
