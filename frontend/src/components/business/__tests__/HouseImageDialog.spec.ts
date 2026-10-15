import { beforeEach,describe,it,expect,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HouseImageDialog from '../HouseImageDialog.vue'
const api=vi.hoisted(()=>({list:vi.fn(),save:vi.fn(),upload:vi.fn(),remove:vi.fn()}))
vi.mock('@/api/houseImages',()=>({listHouseImages:api.list,saveHouseImages:api.save,uploadHouseImage:api.upload,removeHouseImage:api.remove}))
describe('共用房屋相册弹窗',()=>{
 beforeEach(()=>{vi.clearAllMocks();api.list.mockResolvedValue([])})
 it('打开时加载真实房屋相册，无图不伪装实拍',async()=>{const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:false,houseId:9},global:{plugins:[ElementPlus]}});await w.setProps({modelValue:true});await flushPromises();expect(api.list).toHaveBeenCalledWith(9);expect(document.body.textContent).toContain('尚未上传房屋图片');w.unmount()})
 it('读取失败有明确错误，不显示空相册并允许覆盖保存',async()=>{api.list.mockRejectedValue(new Error('无权访问该社区'));const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:false,houseId:9},global:{plugins:[ElementPlus]}});await w.setProps({modelValue:true});await flushPromises();expect(document.body.textContent).toContain('无权访问该社区');expect(api.save).not.toHaveBeenCalled();w.unmount()})
 it('没有房屋ID不会上传或读取其他房屋',async()=>{const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:false,houseId:null},global:{plugins:[ElementPlus]}});await w.setProps({modelValue:true});await flushPromises();expect(api.list).not.toHaveBeenCalled();w.unmount()})
 it('初次挂载已打开时也读取相册',async()=>{const w=mount(HouseImageDialog,{props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}});await flushPromises();expect(api.list).toHaveBeenCalledWith(9);w.unmount()})
 it('切换房屋后旧请求不会覆盖新房屋相册',async()=>{
  let resolveOld!:(rows:unknown[])=>void
  api.list.mockImplementationOnce(()=>new Promise(r=>{resolveOld=r})).mockResolvedValueOnce([{id:2,storageKey:'/uploads/new.jpg',kind:'PHOTO',caption:'新房屋',sortOrder:0}])
  const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}})
  await w.setProps({houseId:10});await flushPromises();resolveOld([{id:1,storageKey:'/uploads/old.jpg',kind:'PHOTO',caption:'旧房屋',sortOrder:0}]);await flushPromises()
  expect(document.body.querySelector('input[placeholder="图片说明"]')?.getAttribute('value')??(document.body.querySelector('input[placeholder="图片说明"]') as HTMLInputElement)?.value).toBe('新房屋');w.unmount()
 })
})
