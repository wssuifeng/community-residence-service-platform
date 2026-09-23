import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkOrderDetailView from '../WorkOrderDetailView.vue'
import { actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeWorkOrder } from '@/test/fakeBackend'
import {
  acceptWorkOrder,
  assignWorkOrder,
  completeWorkOrder,
  confirmWorkOrder,
  intakeWorkOrder,
  processWorkOrder
} from '@/api/workorder'
import { sendConversationMessage } from '@/api/conversation'

/**
 * 管理端工单详情（R74）：受理 → 自动建群 → 面板出现（群成员可见）→ 终审结束 → 群聊回收只读。
 * 覆盖断言②③⑤⑥。
 */
vi.mock('@/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))

const backend = () => getFakeBackend()

function seedOrder(status: FakeWorkOrder['status'], assigneeId: number | null = null): void {
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO202609220003',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水龙头持续漏水',
    description: '水龙头根部渗水',
    status,
    assigneeId,
    assigneeName: assigneeId === null ? null : '刘伟民',
    createdAt: '2026-09-22T09:00:00'
  })
}

async function mountDetail(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/work-orders/:id', component: WorkOrderDetailView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push('/admin/work-orders/1')
  await router.isReady()
  const wrapper = mount(WorkOrderDetailView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().trim() === text)
}

/** 走到「已完成」并带回一个已有历史消息的工单群聊 */
async function driveToCompletedWithGroup(): Promise<number> {
  await intakeWorkOrder(1)
  const groupId = backend().state.conversations[0].id
  await sendConversationMessage(groupId, { content: '已受理，正在安排服务人员' })
  await assignWorkOrder(1, { assigneeId: 1 })
  actAs('STAFF', 1)
  await acceptWorkOrder(1)
  await processWorkOrder(1)
  await completeWorkOrder(1, { remark: '已更换密封圈' })
  actAs('RESIDENT', 1)
  await confirmWorkOrder(1)
  actAs('ADMIN', 1)
  return groupId
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('管理端工单详情 · R74 受理 / 终审 / 群聊面板', () => {
  it('受理前不渲染群聊面板；受理成功后状态转待派单、面板出现且群成员正确', async () => {
    seedOrder('PENDING')
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    const wrapper = await mountDetail()

    /* 断言①：未受理的单没有群 → 不渲染面板 */
    expect(wrapper.text()).not.toContain('工单临时群聊')

    const intake = buttonByText(wrapper, '受理')
    expect(intake, '未找到受理按钮').toBeTruthy()
    await intake?.trigger('click')
    await flushPromises()

    /* 受理成功：状态 TO_ASSIGN + conversationId 回填 + 面板出现 */
    expect(backend().state.workOrders[0].status).toBe('TO_ASSIGN')
    const group = backend().state.conversations[0]
    expect(backend().state.workOrders[0].conversationId).toBe(group.id)
    expect(success.mock.calls[0]?.[0]).toBe('工单已受理，工单沟通群已建立')

    const text = wrapper.text()
    expect(text).toContain('工单临时群聊')
    expect(text).toContain('工单沟通·WO202609220003')
    /* 群成员展示：居民提交人 + 受理管理员 */
    expect(text).toContain('演示居民（居民）')
    expect(text).toContain('社区管理员（社区管理员）')

    /* 群内可发言（ACTIVE 态输入区可用） */
    const input = wrapper.find('.chat-input input, .chat-input textarea')
    expect(input.exists()).toBe(true)
  })

  it('已受理的单再次受理 → 5302 文案提示，不重复建群', async () => {
    seedOrder('PENDING')
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    const wrapper = await mountDetail()

    /* 模拟并发：渲染后该单被他人受理 */
    backend().state.workOrders[0].status = 'TO_ASSIGN'
    await buttonByText(wrapper, '受理')?.trigger('click')
    await flushPromises()

    expect(String(error.mock.calls[0]?.[0])).toContain('不允许「受理」')
    expect(backend().state.conversations).toHaveLength(0)
  })

  it('终审结束入口仅已完成可见（待确认单不显示）', async () => {
    seedOrder('TO_CONFIRM')
    const wrapper = await mountDetail()

    expect(wrapper.text()).toContain('待确认')
    expect(buttonByText(wrapper, '终审结束')).toBeFalsy()
  })

  it('终审结束：状态转已关闭、会话回收只读、历史可读、再发言 5604', async () => {
    seedOrder('PENDING')
    const groupId = await driveToCompletedWithGroup()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)

    const wrapper = await mountDetail()
    /* 已完成行才有终审入口 */
    const finalize = buttonByText(wrapper, '终审结束')
    expect(finalize, '未找到终审结束按钮').toBeTruthy()
    await finalize?.trigger('click')
    await flushPromises()

    expect(backend().state.workOrders[0].status).toBe('CLOSED')
    expect(success.mock.calls[0]?.[0]).toBe('工单已终审结束，沟通群已回收为只读')

    /* 断言⑧：会话回收（CLOSED + closedAt）且历史消息仍在 */
    const group = backend().state.conversations.find((item) => item.id === groupId)!
    expect(group.status).toBe('CLOSED')
    expect(group.closedAt).toBeTruthy()
    expect(backend().state.conversationMessages.filter((item) => item.conversationId === groupId)).toHaveLength(1)

    const text = wrapper.text()
    expect(text).toContain('工单已结束，会话资源已回收（历史可查、不可再发言）')
    expect(text).toContain('已受理，正在安排服务人员')
    /* 输入框被关闭态提示条取代（只读） */
    expect(wrapper.find('.chat-input').exists()).toBe(false)

    /* 再发言被后端拒绝 5604 */
    await expect(sendConversationMessage(groupId, { content: '还能说话吗' })).rejects.toMatchObject({
      code: 5604
    })
  })
})
