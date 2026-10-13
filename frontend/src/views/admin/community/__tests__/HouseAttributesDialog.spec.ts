import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import HouseAttributesDialog from '../HouseAttributesDialog.vue'
import { updateHouseAttributes } from '@/api/community'
vi.mock('@/api/community', () => ({ updateHouseAttributes: vi.fn() }))
const houses = [{ id: 1, unitId: 10, houseNumber: '101', floor: 1, area: 80, layout: '两室一厅' }, { id: 2, unitId: 11, houseNumber: '201', floor: 2, area: 90, layout: '三室一厅一卫' }] as any
async function open(selection = houses) {
 const w = mount(HouseAttributesDialog, { props: { modelValue: false, houses: selection }, global: { plugins: [ElementPlus], stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } } })
 await w.setProps({ modelValue: true }); await flushPromises(); return w
}
function save(w: ReturnType<typeof mount>) { return w.findAll('button').find(b => b.text() === '保存房屋数据')! }
describe('房屋自身属性选区编辑', () => {
 beforeEach(() => vi.clearAllMocks())
 it('默认不覆盖，多选没有统一房号字段', async () => { const w=await open(); expect(save(w).attributes('disabled')).toBeDefined(); expect(w.findAllComponents({name:'ElCheckbox'}).map(c=>c.text())).not.toContain('房号'); w.unmount() })
 it('只提交勾选的户型，空字符串明确清除', async () => { vi.mocked(updateHouseAttributes).mockResolvedValue(2); const w=await open(); await w.findAllComponents({name:'ElCheckbox'}).find(c=>c.text()==='户型')!.get('input').setValue(true); await save(w).trigger('click'); await flushPromises(); expect(updateHouseAttributes).toHaveBeenCalledWith({houseIds:[1,2], fields:['layout'], layout:''}); expect(w.emitted('saved')).toEqual([[[10,11]]]); w.unmount() })
 it('启用面积后数值输入的可访问状态同步', async () => {const w=await open();const row=w.findAll('.el-form-item').find(r=>r.text().includes('面积（㎡）'))!; expect(row.get('input[role=spinbutton]').attributes('aria-disabled')).toBe('true');await row.get('input[type=checkbox]').setValue(true);await flushPromises();expect(row.get('input[role=spinbutton]').attributes('aria-disabled')).toBe('false');w.unmount() })
 it('单选提供房号，保存失败保留窗口不刷新', async () => { vi.mocked(updateHouseAttributes).mockRejectedValue(new Error('房号重复')); const w=await open([houses[0]]); await w.findAllComponents({name:'ElCheckbox'}).find(c=>c.text()==='房号')!.get('input').setValue(true); await save(w).trigger('click'); await flushPromises(); expect(w.emitted('saved')).toBeUndefined(); expect(w.emitted('update:modelValue')).toBeUndefined(); w.unmount() })
})
