import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import HouseLayoutDialog from '../HouseLayoutDialog.vue'
import { updateHouseLayouts } from '@/common/community/api/community'
vi.mock('@/common/community/api/community', () => ({ updateHouseLayouts: vi.fn() }))
const houses = [{ id: 1, unitId: 10, houseNumber: '101', layout: '两室一厅' }, { id: 2, unitId: 11, houseNumber: '201', layout: '三室一厅一卫' }] as any
async function open() {
 const wrapper = mount(HouseLayoutDialog, { props: { modelValue: false, houses }, global: { plugins: [ElementPlus], stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } } })
 await wrapper.setProps({ modelValue: true }); await flushPromises(); return wrapper
}
function save(wrapper: ReturnType<typeof mount>) { return wrapper.findAll('button').find(b => b.text() === '保存')! }
describe('原子批量户型交互', () => {
 beforeEach(() => vi.clearAllMocks())
 it('明确显示选区与多个不同户型', async () => { const w = await open(); expect(w.text()).toContain('已选 2 套'); expect(w.text()).toContain('多个不同户型'); w.unmount() })
 it('提交明确ID与户型，并刷新所有受影响单元', async () => { vi.mocked(updateHouseLayouts).mockResolvedValue(2); const w=await open(); await w.get('input').setValue(' 三室一厅一卫 '); await save(w).trigger('click'); await flushPromises(); expect(updateHouseLayouts).toHaveBeenCalledWith([1,2], '三室一厅一卫'); expect(w.emitted('saved')).toEqual([[[10,11]]]); expect(w.emitted('update:modelValue')).toEqual([[false]]); w.unmount() })
 it('保存失败不关闭不发成功刷新', async () => { vi.mocked(updateHouseLayouts).mockRejectedValue(new Error('选区无效')); const w=await open(); await save(w).trigger('click'); await flushPromises(); expect(w.emitted('saved')).toBeUndefined(); expect(w.emitted('update:modelValue')).toBeUndefined(); w.unmount() })
 it('取消不会调用写接口', async () => { const w=await open(); await w.findAll('button').find(b=>b.text()==='取消')!.trigger('click'); expect(updateHouseLayouts).not.toHaveBeenCalled(); w.unmount() })
})
