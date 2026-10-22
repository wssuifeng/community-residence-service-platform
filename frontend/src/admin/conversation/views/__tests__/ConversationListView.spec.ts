import { reactive, defineComponent } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConversationListView from '@/admin/conversation/views/ConversationListView.vue'
const api = vi.hoisted(() => ({ list: vi.fn(), direct: vi.fn(), communities: vi.fn(), residents: vi.fn() }))
vi.mock('@/common/conversation/api/conversationInbox', () => ({ listConversationInbox: api.list }))
vi.mock('@/common/conversation/api/conversation', () => ({ openDirectConversation: api.direct }))
vi.mock('@/common/community/api/community', () => ({ getCommunityList: api.communities }))
vi.mock('@/common/resident/api/resident', () => ({ getResidentList: api.residents }))
vi.mock('@/common/lease/api/lease', () => ({ getLeaseList: vi.fn() }))
vi.mock('@/common/conversation/components/ConversationPane.vue', () => ({ default: defineComponent({ props: ['source','id'], template: '<div class="pane">{{source}}:{{id}}</div>' }) }))
const user = reactive({ role: 'ADMIN', user: { id: 1, role: 'ADMIN' } })
vi.mock('@/common/auth/store/user', () => ({ useUserStore: () => user }))
const row = (source = 'CONVERSATION', id = 1) => ({ source, id, title: `${source}会话`, type: source === 'FEEDBACK' ? 'FEEDBACK' : 'WORK_ORDER_GROUP', status: 'ACTIVE', communityName: '测试小区', unreadCount: 2, activityAt: '2026-10-17T10:00:00' })
let wrapper: VueWrapper, router: Router
beforeEach(() => {
 api.list.mockResolvedValue({ records: [row(), row('FEEDBACK')], total: 2, page: 1, size: 30, pages: 1 }); api.direct.mockResolvedValue({ id: 9 });
 api.communities.mockResolvedValue({ records: [{ id: 1, name: '测试小区' }] }); api.residents.mockResolvedValue({ records: [{ id: 2, realName: '测试居民', username: '居民2' }] }); user.role = 'ADMIN'
})
afterEach(() => { wrapper?.unmount(); vi.clearAllMocks(); document.body.innerHTML = '' })
async function render(path = '/admin/conversations') {
 router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/conversations', component: ConversationListView }, { path: '/admin/notifications', component: { template: '<div />' } }] })
 await router.push(path); await router.isReady(); wrapper = mount(ConversationListView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body }); await flushPromises()
}
const button = (text: string) => wrapper.findAll('button').find(b => b.text().trim() === text)!
describe('三段工作区的真实入口与服务端列表', () => {
 it('工作区标题称会话，通知入口明确称消息中心', async () => { await render(); expect(wrapper.get('h1').text()).toBe('会话'); const center = wrapper.findAll('a').find(link => link.text() === '消息中心'); expect(center?.attributes('href')).toBe('/admin/notifications'); expect(wrapper.text()).not.toContain('消息会话') })
 it('保留业务来源及真实未读，同号反馈不覆盖群聊', async () => { await render(); expect(wrapper.findAll('.inbox-row')).toHaveLength(2); expect(wrapper.findAll('.unread')).toHaveLength(2); await wrapper.findAll('.inbox-row')[1].trigger('click'); await flushPromises(); expect(router.currentRoute.value.query.source).toBe('FEEDBACK'); expect(wrapper.get('.pane').text()).toBe('FEEDBACK:1') })
 it('深链不用在第一页中猜测查找会话', async () => { await render('/admin/conversations?id=900'); expect(wrapper.get('.pane').text()).toBe('CONVERSATION:900') })
 it('分类及未读按钮提交后端筛选，不做第一页假过滤', async () => { await render(); await wrapper.findAll('.source-nav button').find(b => b.text().endsWith('反馈'))!.trigger('click'); await button('未读').trigger('click'); await new Promise(r => setTimeout(r, 300)); await flushPromises(); expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'FEEDBACK', unreadOnly: true })) })
 it('完整标题搜索提交后端，不搜索匿名隐藏姓名', async () => { await render(); await wrapper.get('input[type=search]').setValue('四段编号'); await new Promise(r => setTimeout(r, 300)); await flushPromises(); expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '四段编号' })) })
 it('空会话明确说明来源，而不是空白页', async () => { api.list.mockResolvedValue({ records: [], total: 0, pages: 0 }); await render(); expect(wrapper.text()).toContain('业务产生沟通群后会在这里显示') })
 it('失败显示可重试，重试成功后有会话', async () => { api.list.mockRejectedValueOnce(new Error('列表离线')); await render(); expect(wrapper.text()).toContain('列表离线'); await button('重试').trigger('click'); await flushPromises(); expect(wrapper.findAll('.inbox-row')).toHaveLength(2) })
 it('管理方仍可代居民发起直通，并精确选中原会话', async () => {
  await render(); await wrapper.get('.direct-launch').trigger('click'); await flushPromises()
  const selects = wrapper.findAllComponents({ name: 'ElSelect' }); selects[0].vm.$emit('update:modelValue', 1); selects[1].vm.$emit('update:modelValue', 2); await flushPromises()
  const submit = [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === '发起会话')!; submit.click(); await flushPromises()
  expect(api.direct).toHaveBeenCalledWith({ communityId: 1, residentId: 2 }); expect(router.currentRoute.value.query.id).toBe('9')
 })

 it('未读筛选保留当前刚读完的会话，切换后才移走', async () => {
  await render(); await button('未读').trigger('click'); await new Promise(r => setTimeout(r, 300)); await flushPromises()
  await wrapper.findAll('.inbox-row')[0].trigger('click'); await flushPromises()
  api.list.mockResolvedValue({ records: [row('FEEDBACK')], total: 1, page: 1, size: 30, pages: 1 })
  await button('刷新').trigger('click'); await flushPromises()
  expect(wrapper.findAll('.inbox-row')).toHaveLength(2); expect(wrapper.get('.inbox-row.active').text()).toContain('已读')
  await wrapper.findAll('.inbox-row')[1].trigger('click'); await flushPromises(); expect(wrapper.findAll('.inbox-row')).toHaveLength(1)
 })
})
