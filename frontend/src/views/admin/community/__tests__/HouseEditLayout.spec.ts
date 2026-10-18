import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import ElementPlus from 'element-plus'
import HouseEditDialog from '../HouseEditDialog.vue'
vi.mock('@/common/community/api/community', () => ({ createHouse: vi.fn(), updateHouse: vi.fn() }))
async function open(house: any) {
  const wrapper = mount(HouseEditDialog, { props: { modelValue: false, house,
    unitContext: { buildingName: '9层楼', unitId: 1, unitName: '1单元', floors: 9 } },
    global: { plugins: [ElementPlus], stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } } })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}
describe('房屋户型与楼层实际表单', () => {
  it('新建户型实际渲染，不能包在原生惰性template里', async () => {
    const wrapper = await open(null)
    expect(wrapper.find('template').exists()).toBe(false)
    expect(wrapper.find('input[placeholder="如：2室1厅1卫"]').exists()).toBe(true)
    expect(wrapper.findAllComponents({ name: 'ElInputNumber' })[0]!.props('max')).toBe(9)
    wrapper.unmount()
  })
  it('编辑使用字段意图面板，户型回显且楼层只读', async () => {
    const wrapper = await open({ id: 1, unitId: 1, houseNumber: '901', floor: 9, area: 80, layout: '三室一厅一卫' })
    expect(wrapper.find('template').exists()).toBe(false)
    expect((wrapper.findAllComponents({name:'ElInput'}).find(c=>c.props('modelValue')==='三室一厅一卫')!.get('input').element as HTMLInputElement).value).toBe('三室一厅一卫')
    expect(wrapper.findAllComponents({name:'ElCheckbox'}).map(c=>c.text())).not.toContain('楼层')
    wrapper.unmount()
  })
})
