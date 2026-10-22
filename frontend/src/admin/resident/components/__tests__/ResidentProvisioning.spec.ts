import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import ResidentAdminCreateDialog from '@/admin/resident/components/ResidentAdminCreateDialog.vue'
import ResidentHouseSelector from '@/admin/resident/components/ResidentHouseSelector.vue'
import { adminCreateResident } from '@/common/resident/api/resident'
import { getCommunityList, getBuildingList, getUnitList, getHouseList } from '@/common/community/api/community'
vi.mock('@/common/resident/api/resident', () => ({ adminCreateResident: vi.fn() }))
vi.mock('@/common/community/api/community', () => ({ getCommunityList: vi.fn(), getBuildingList: vi.fn(), getUnitList: vi.fn(), getHouseList: vi.fn() }))
const page = (records: any[]) => ({ records, total: records.length, page: 1, size: 100 })
const dialog = { template: '<div><slot /><slot name="footer" /></div>' }
async function open() {
  const wrapper = mount(ResidentAdminCreateDialog, { props: { modelValue: false }, global: { plugins: [ElementPlus], stubs: {
    ElDialog: dialog, ResidentHouseSelector: { props: ['modelValue','communityId'], emits: ['update:modelValue'], template: '<button @click="$emit(\'update:modelValue\', 5)">选择房屋</button>' }
  } } })
  await wrapper.setProps({ modelValue: true }); await flushPromises(); return wrapper
}
async function fillAccount(w: ReturnType<typeof mount>) {
  w.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 3)
  await w.get('input[placeholder="居民真实姓名"]').setValue('测试居民')
  await w.get('input[placeholder="用于登录与联系"]').setValue('13900000011')
  await w.get('input[placeholder="选填，与居民资料一致"]').setValue('resident@example.com')
}
async function submit(w: ReturnType<typeof mount>) { await w.findAll('button').find(b=>b.text()==='代建账号')!.trigger('click'); await flushPromises() }
describe('居民账号与关系交互', () => {
  beforeEach(()=>{vi.clearAllMocks();vi.mocked(getCommunityList).mockResolvedValue(page([{id:3,name:'测试小区'}]) as any)})
  it('默认要求登记关系，未选房屋不调用写接口',async()=>{const w=await open();await fillAccount(w);await submit(w);expect(adminCreateResident).not.toHaveBeenCalled();expect(w.text()).toContain('居住关系');w.unmount()})
  it('同一请求提交账号邮箱和关系，并显示保存后的关系',async()=>{vi.mocked(adminCreateResident).mockResolvedValue({residentId:99,username:'test',initialPassword:'000011',relationId:77,houseId:5});const w=await open();await fillAccount(w);await w.findAll('button').find(b=>b.text()==='选择房屋')!.trigger('click');await submit(w);expect(adminCreateResident).toHaveBeenCalledWith(expect.objectContaining({communityId:3,email:'resident@example.com',houseId:5,relationType:'OWNER'}));expect(w.text()).toContain('居住关系已登记');w.unmount()})
  it('切换仅账号不提交残留的关系字段',async()=>{vi.mocked(adminCreateResident).mockResolvedValue({residentId:99,username:'test',initialPassword:'000011'});const w=await open();await fillAccount(w);await w.findAll('button').find(b=>b.text()==='选择房屋')!.trigger('click');w.findComponent({name:'ElSwitch'}).vm.$emit('update:modelValue',false);await flushPromises();await submit(w);expect(adminCreateResident).toHaveBeenCalledWith(expect.not.objectContaining({houseId:5}));expect(w.text()).toContain('尚未登记居住关系');w.unmount()})
  it('代建失败保留表单并不发成功事件',async()=>{vi.mocked(adminCreateResident).mockRejectedValue(new Error('房屋已占用'));const w=await open();await fillAccount(w);await w.findAll('button').find(b=>b.text()==='选择房屋')!.trigger('click');await submit(w);expect(w.emitted('created')).toBeUndefined();expect(w.find('input[placeholder="居民真实姓名"]').exists()).toBe(true);w.unmount()})
})
describe('居民选房范围',()=>{
 beforeEach(()=>{vi.clearAllMocks();vi.mocked(getBuildingList).mockResolvedValue(page([{id:10,name:'一栋'}]) as any);vi.mocked(getUnitList).mockResolvedValue(page([{id:20,name:'一单元'}]) as any);vi.mocked(getHouseList).mockResolvedValue(page([{id:5,houseNumber:'101',floor:1,status:'VACANT'}]) as any)})
 it('按楼栋/单元请求空置房屋，不把第一页当全量',async()=>{vi.mocked(getBuildingList).mockResolvedValueOnce({records:[{id:10,name:'一栋'}],total:2} as any).mockResolvedValueOnce({records:[{id:11,name:'二栋'}],total:2} as any);const w=mount(ResidentHouseSelector,{props:{communityId:3},global:{plugins:[ElementPlus]}});await flushPromises();expect(getBuildingList).toHaveBeenCalledTimes(2);w.findAllComponents({name:'ElSelect'})[0]!.vm.$emit('update:modelValue',10);await flushPromises();w.findAllComponents({name:'ElSelect'})[1]!.vm.$emit('update:modelValue',20);await flushPromises();expect(getHouseList).toHaveBeenCalledWith(20,expect.objectContaining({status:'VACANT'}));w.unmount()})
 it('小区切换清空已选房屋',async()=>{const w=mount(ResidentHouseSelector,{props:{communityId:3,modelValue:5},global:{plugins:[ElementPlus]}});await flushPromises();await w.setProps({communityId:4});await flushPromises();expect(w.emitted('update:modelValue')).toContainEqual([undefined]);w.unmount()})
 it('旧小区的迟到请求不能回写新范围',async()=>{let resolve!: (value:any)=>void;vi.mocked(getBuildingList).mockReturnValueOnce(new Promise(done=>{resolve=done})).mockResolvedValueOnce(page([{id:40,name:'新栋'}]) as any);const w=mount(ResidentHouseSelector,{props:{communityId:3},global:{plugins:[ElementPlus]}});await w.setProps({communityId:4});await flushPromises();resolve(page([{id:10,name:'旧栋'}]));await flushPromises();expect(w.findAllComponents({name:'ElOption'}).map(o=>o.props('label'))).toContain('新栋');expect(w.findAllComponents({name:'ElOption'}).map(o=>o.props('label'))).not.toContain('旧栋');w.unmount()})
})
