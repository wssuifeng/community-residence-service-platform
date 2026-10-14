import { defineComponent, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConversationChat from '../ConversationChat.vue'

const api = vi.hoisted(() => ({ list: vi.fn(), send: vi.fn(), agreement: vi.fn() }))
vi.mock('@/api/conversation', () => ({ listConversationMessages: api.list, sendConversationMessage: api.send }))
vi.mock('@/api/agreement', () => ({ getLeaseAgreement: api.agreement }))
vi.mock('@/utils/websocket', () => ({ subscribe: vi.fn(() => vi.fn()), isWsConnected: () => false }))
const userStore = reactive({ user: { id: 1, role: 'RESIDENT' } })
vi.mock('@/store/user', () => ({ useUserStore: () => userStore }))
vi.mock('../AgreementSignPanel.vue', () => ({ default: defineComponent({
  name: 'AgreementSignPanel',
  props: ['leaseId', 'role', 'requestedAgreementId', 'readOnly', 'selfManaged'],
  template: '<div data-testid="agreement-snapshot">协议快照</div>'
}) }))
let wrapper: VueWrapper | undefined
async function render(listener?: (id: number) => void) {
  wrapper = mount(ConversationChat, { props: { conversationId: 7, closed: true,
    ...(listener ? { onOpenAgreement: listener } : {}) },
    global: { plugins: [ElementPlus] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  userStore.user.role = 'RESIDENT'
  api.list.mockResolvedValue([{ id: 4, conversationId: 7, senderId: 0, senderRole: 'SYSTEM',
    senderName: '系统', content: '协议已发出', messageType: 'LEASE_AGREEMENT', agreementId: 12,
    createdAt: '2026-10-15T08:00:00' }])
  api.agreement.mockResolvedValue({ id: 12, leaseId: 9 })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.clearAllMocks(); document.body.innerHTML = '' })

describe('嵌入式群聊协议卡片入口', () => {
  it('未接父层事件的居民入口打开精确只读快照，关闭群仍可查看', async () => {
    await render(); await wrapper!.get('button.agreement-message').trigger('click'); await flushPromises()
    expect(api.agreement).toHaveBeenCalledWith(12)
    const panel = wrapper!.findComponent({ name: 'AgreementSignPanel' })
    expect(panel.exists()).toBe(true)
    expect(panel.props()).toMatchObject({ leaseId: 9, requestedAgreementId: 12, role: 'RESIDENT', readOnly: true })
  })
  it('超管嵌入入口复用管理方只读面板', async () => {
    userStore.user.role = 'SUPER_ADMIN'
    await render(); await wrapper!.get('button.agreement-message').trigger('click'); await flushPromises()
    expect(wrapper!.findComponent({ name: 'AgreementSignPanel' }).props()).toMatchObject({ role: 'ADMIN', readOnly: true })
  })
  it('主入口仍委托父层，不另取协议或打开第二个面板', async () => {
    const listener = vi.fn(); await render(listener)
    await wrapper!.get('button.agreement-message').trigger('click'); await flushPromises()
    expect(listener).toHaveBeenCalledWith(12)
    expect(api.agreement).not.toHaveBeenCalled()
    expect(wrapper!.findComponent({ name: 'AgreementSignPanel' }).exists()).toBe(false)
  })
  it('服务人员仅看到协议事件说明，不提供正文按钮或请求', async () => {
    userStore.user.role = 'STAFF'; await render()
    expect(wrapper!.find('button.agreement-message').exists()).toBe(false)
    expect(wrapper!.text()).toContain('协议正文仅租约居民和管理方可查看')
    expect(api.agreement).not.toHaveBeenCalled()
  })
  it('后端拒绝读取时不打开空面板，不切换成其他协议', async () => {
    api.agreement.mockRejectedValue(new Error('无权查看此协议'))
    await render(); await wrapper!.get('button.agreement-message').trigger('click'); await flushPromises()
    expect(wrapper!.findComponent({ name: 'AgreementSignPanel' }).exists()).toBe(false)
    expect(document.body.textContent).toContain('无权查看此协议')
  })
  it('普通文本携带协议ID也不能显示协议业务按钮', async () => {
    api.list.mockResolvedValue([{ id: 4, conversationId: 7, senderId: 1, senderRole: 'RESIDENT',
      senderName: '居民', content: '假协议', messageType: 'TEXT', agreementId: 12, createdAt: '2026-10-15T08:00:00' }])
    await render()
    expect(wrapper!.find('.agreement-message').exists()).toBe(false)
  })
})
