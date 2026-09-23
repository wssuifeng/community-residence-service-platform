import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkOrderDetailView from '../WorkOrderDetailView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeWorkOrder } from '@/test/fakeBackend'

/**
 * 居民端工单详情 · R74 工单临时群聊面板：复用 `ConversationChat`（不新增页面），
 * 受理后建群即出现（成员＝居民提交人 + 受理管理员），未受理的单不渲染面板。
 */
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
  })

  it('受理后出现面板：群名、群成员与历史消息可见，居民可发言', async () => {
    seedOrder({ withGroup: true })
    const wrapper = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('工单临时群聊')
    expect(text).toContain('工单沟通·WO202609220003')
    expect(text).toContain('演示居民（居民）')
    expect(text).toContain('社区管理员（社区管理员）')
    expect(text).toContain('已受理，正在安排服务人员')
    /* ACTIVE 态输入区可用（居民是参与者） */
    expect(wrapper.find('.chat-input').exists()).toBe(true)
    expect(wrapper.find('.chat-closed').exists()).toBe(false)
  })

  it('终审结束后面板只读：给出回收提示，输入区被提示条取代，历史仍可读', async () => {
    seedOrder({ withGroup: true, status: 'CLOSED' })
    const wrapper = await mountDetail()

    const text = wrapper.text()
    expect(text).toContain('工单已结束，会话资源已回收（历史可查、不可再发言）')
    expect(text).toContain('已受理，正在安排服务人员')
    expect(wrapper.find('.chat-closed').exists()).toBe(true)
    expect(wrapper.find('.chat-input').exists()).toBe(false)
  })
})
