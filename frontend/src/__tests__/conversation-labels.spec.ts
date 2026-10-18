import { defineComponent } from 'vue'
import { mount, RouterLinkStub } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ResidentLayout from '@/views/resident/ResidentLayout.vue'
import StaffLayout from '@/views/staff/StaffLayout.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import residentRoutes from '@/router/resident'
import staffRoutes from '@/router/staff'
import adminRoutes from '@/router/admin'

vi.mock('@/common/auth/store/user', () => ({ useUserStore: () => ({ role: 'ADMIN', user: { id: 1 } }) }))
vi.mock('@/store/adminTodo', () => ({ useAdminTodoStore: () => ({ counts: {}, refresh: vi.fn().mockResolvedValue(undefined) }) }))
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal<typeof import('vue-router')>(),
  useRoute: () => ({ path: '/admin/conversations', fullPath: '/admin/conversations' })
}))
const cleanups: Array<() => void> = []
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()) })
const header = defineComponent({ props: ['items'], template: '<nav />' })

describe('会话与消息中心展示口径', () => {
  it.each([
    ['居民', residentRoutes], ['服务人员', staffRoutes], ['管理', adminRoutes]
  ])('%s端的新旧会话路由统一称会话，通知仍称消息中心', (_, routes) => {
    const children = routes.flatMap(route => route.children ?? [])
    const conversations = children.filter(route => route.path.startsWith('conversations'))
    expect(conversations.length).toBeGreaterThan(0)
    conversations.forEach(route => expect(route.meta?.title).toBe('会话'))
    expect(children.find(route => route.path === 'notifications')?.meta?.title).toBe('消息中心')
  })

  it.each([
    ['居民', 'resident', ResidentLayout], ['服务人员', 'staff', StaffLayout]
  ])('%s端会话导航不冒充消息中心的选中项', (_, base, component) => {
    const wrapper = mount(component, { global: { stubs: { AppHeader: header, RouterView: true } } })
    cleanups.push(() => wrapper.unmount())
    const items = wrapper.findComponent(header).props('items') as Array<{ label: string; to: string; activeMatch?: string[] }>
    const conversation = items.find(item => item.to === `/${base}/conversations`)
    expect(conversation?.label).toBe('会话')
    expect(conversation?.activeMatch).toContain(`/${base}/conversations`)
    expect(conversation?.activeMatch).not.toContain(`/${base}/notifications`)
  })

  it('管理端同时保留会话与消息中心，不合并通知菜单', () => {
    const wrapper = mount(AppSidebar, { global: { stubs: { RouterLink: RouterLinkStub } } })
    cleanups.push(() => wrapper.unmount())
    const links = wrapper.findAllComponents(RouterLinkStub)
    expect(links.find(link => link.props('to') === '/admin/conversations')?.text()).toBe('会话')
    expect(links.find(link => link.props('to') === '/admin/notifications')?.text()).toBe('消息中心')
    expect(wrapper.text()).not.toContain('消息会话')
  })
})
