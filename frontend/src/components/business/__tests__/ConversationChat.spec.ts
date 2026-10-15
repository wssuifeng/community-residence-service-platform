import { defineComponent, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConversationChat from '../ConversationChat.vue'

const api = vi.hoisted(() => ({ list: vi.fn(), send: vi.fn(), agreement: vi.fn(), read: vi.fn() }))
vi.mock('@/api/conversation', () => ({ listConversationMessages: api.list, sendConversationMessage: api.send, markConversationRead: api.read }))
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
  const originalBounds = HTMLElement.prototype.getBoundingClientRect
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.classList.contains('conversation-chat')) return { x: 0, y: 100, top: 100, left: 0, right: 600, bottom: 500, width: 600, height: 400, toJSON: () => ({}) } as DOMRect
    return originalBounds.call(this)
  })
  api.read.mockResolvedValue(undefined)
  userStore.user.role = 'RESIDENT'
  api.list.mockResolvedValue([{ id: 4, conversationId: 7, senderId: 0, senderRole: 'SYSTEM',
    senderName: '系统', content: '协议已发出', messageType: 'LEASE_AGREEMENT', agreementId: 12,
    createdAt: '2026-10-15T08:00:00' }])
  api.agreement.mockResolvedValue({ id: 12, leaseId: 9 })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.clearAllMocks(); vi.restoreAllMocks(); document.body.innerHTML = '' })

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


describe('会话已读持久化', () => {
  it('消息实际展示后调用已读接口，不能仅在列表临时清零', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    await render()
    expect(api.read).toHaveBeenCalledWith(7, 4)
    expect(wrapper!.emitted('read')).toEqual([[7, 4]])
  })
  it('后台页面不标已读，回到前台才确认已展示消息', async () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    await render(); expect(api.read).not.toHaveBeenCalled()
    visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); await flushPromises()
    expect(api.read).toHaveBeenCalledWith(7, 4)
  })
})


it('已读保存失败不虚清角标；回到前台可重新确认', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  api.read.mockRejectedValueOnce(new Error('temporary offline'))
  await render(); expect(wrapper!.emitted('read')).toBeUndefined()
  document.dispatchEvent(new Event('visibilitychange')); await flushPromises()
  expect(api.read).toHaveBeenCalledTimes(2)
  expect(wrapper!.emitted('read')).toEqual([[7, 4]])
})

it('切会话时旧已读请求不能把旧消息ID写入新会话', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  let finish: (() => void) | undefined
  api.read.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
  await render()
  api.list.mockResolvedValueOnce([{ id: 8, conversationId: 8, senderId: 2, senderRole: 'ADMIN', senderName: '管理员', content: '新会话', createdAt: '2026-10-15T08:00:00' }])
  await wrapper!.setProps({ conversationId: 8 }); await flushPromises()
  finish?.(); await flushPromises()
  expect(api.read.mock.calls).toEqual([[7, 4], [8, 8]])
})


it('文档在前台但聊天容器隐藏时不冒充已读', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, toJSON: () => ({}) } as DOMRect)
  await render(); expect(api.read).not.toHaveBeenCalled()
})
