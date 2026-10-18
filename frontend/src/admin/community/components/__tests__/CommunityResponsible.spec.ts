import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import CommunityEditDialog from '@/admin/community/components/CommunityEditDialog.vue'
import { getResponsibleAdmins } from '@/common/community/api/community'

vi.mock('@/common/community/api/community', () => ({
  getResponsibleAdmins: vi.fn(), createCommunity: vi.fn(), updateCommunity: vi.fn()
}))
const community = { id: 1, name: '测试社区', address: '测试地址', responsibleAdminId: 2,
  contactPerson: '其他社区管理员', contactPhone: '13800000000' } as any
const dialogStub = { template: '<div><slot /><slot name="footer" /></div>' }
async function open() {
  const wrapper = mount(CommunityEditDialog, { props: { modelValue: false, community },
    global: { plugins: [ElementPlus], stubs: { ElDialog: dialogStub } } })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}
describe('社区责任人回显', () => {
  beforeEach(() => vi.clearAllMocks())
  it('未加载到的当前责任人不伪造本社区绑定', async () => {
    vi.mocked(getResponsibleAdmins).mockResolvedValue({ records: [], total: 0 } as any)
    const wrapper = await open()
    const groups = wrapper.findAllComponents({ name: 'ElOptionGroup' })
    const current = groups.find(item => item.props('label') === '当前责任人')!
    expect(current.findComponent({ name: 'ElOption' }).props('value')).toBe(2)
    const bound = groups.find(item => item.props('label') === '已绑定本社区')!
    expect(bound.findAllComponents({ name: 'ElOption' })).toHaveLength(0)
    wrapper.unmount()
  })
  it('候选返回真实绑定状态后取消临时回显分组', async () => {
    vi.mocked(getResponsibleAdmins).mockResolvedValue({ records: [
      { id: 2, realName: '其他社区管理员', phone: '13800000000', boundToCommunity: false }
    ], total: 1 } as any)
    const wrapper = await open()
    expect(wrapper.findAllComponents({ name: 'ElOptionGroup' }).map(item => item.props('label')))
      .not.toContain('当前责任人')
    expect(wrapper.text()).toContain('13800000000')
    wrapper.unmount()
  })
  it('关闭后迟到的候选响应不能覆盖当前回显', async () => {
    let resolve!: (value: any) => void
    vi.mocked(getResponsibleAdmins).mockReturnValue(new Promise(done => { resolve = done }))
    const wrapper = await open()
    await wrapper.setProps({ modelValue: false })
    resolve({ records: [{ id: 99, realName: '迟到资料', phone: '', boundToCommunity: true }], total: 1 })
    await flushPromises()
    expect(wrapper.findAllComponents({ name: 'ElOption' }).map(item => item.props('value'))).not.toContain(99)
    wrapper.unmount()
  })
})
