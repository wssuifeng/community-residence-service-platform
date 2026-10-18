import { mount } from '@vue/test-utils'
import { nextTick, reactive, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminLayout from './AdminLayout.vue'

const state = vi.hoisted(() => ({ tablet: null as ReturnType<typeof ref<boolean>> | null, route: null as { path: string; fullPath: string; meta: { title: string } } | null }))
vi.mock('vue-router', () => ({ useRoute: () => state.route }))
vi.mock('@/store/user', () => ({ useUserStore: () => ({ user: { realName: '管理员' } }) }))
vi.mock('@/composables/useResponsive', () => ({ useResponsive: () => ({ isTablet: state.tablet }) }))

function page() {
  return mount(AdminLayout, {
    global: { stubs: {
      AppHeader: { template: '<header><slot name="menu-toggle" /></header>' },
      AppSidebar: { template: '<aside data-testid="sidebar">管理菜单</aside>' },
      RouterView: true,
      ElDrawer: { props: ['modelValue', 'closeOnClickModal'], emits: ['update:modelValue'], template: '<div v-if="modelValue" data-testid="drawer" :data-modal-close="closeOnClickModal"><slot /><button data-testid="backdrop" @click="$emit(\'update:modelValue\', false)">关闭</button></div>' }
    } }
  })
}

describe('DEF-178 管理端平板侧栏按响应式规范默认收起', () => {
  beforeEach(() => {
    state.tablet = ref(true)
    state.route = reactive({ path: '/admin/community', fullPath: '/admin/community', meta: { title: '社区管理' } })
  })

  it('平板默认不占用侧栏宽度，菜单按钮可打开覆盖层', async () => {
    const wrapper = page()
    expect(wrapper.find('[data-testid="sidebar"]').exists()).toBe(false)
    const toggle = wrapper.find('[aria-label="打开管理菜单"]')
    expect(toggle.exists()).toBe(true)
    await toggle.trigger('click')
    expect(wrapper.find('[data-testid="drawer"]').exists()).toBe(true)
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('[data-testid="drawer"]').attributes('data-modal-close')).toBe('true')
    wrapper.unmount()
  })

  it('覆盖层关闭后不保留打开状态', async () => {
    const wrapper = page()
    await wrapper.find('[aria-label="打开管理菜单"]').trigger('click')
    await wrapper.find('[data-testid="backdrop"]').trigger('click')
    expect(wrapper.find('[data-testid="drawer"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('平板切换路由自动关闭侧栏', async () => {
    const wrapper = page()
    await wrapper.find('[aria-label="打开管理菜单"]').trigger('click')
    state.route!.fullPath = '/admin/work-orders'
    await nextTick()
    expect(wrapper.find('[data-testid="drawer"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('桌面保留侧栏，返回平板不继承之前打开状态', async () => {
    const wrapper = page()
    await wrapper.find('[aria-label="打开管理菜单"]').trigger('click')
    state.tablet!.value = false
    await nextTick()
    expect(wrapper.find('[data-testid="sidebar"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="打开管理菜单"]').exists()).toBe(false)
    state.tablet!.value = true
    await nextTick()
    expect(wrapper.find('[data-testid="sidebar"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
