import { beforeEach,describe,it,expect,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import HouseImageDialog from '@/common/community/components/HouseImageDialog.vue'
const api=vi.hoisted(()=>({list:vi.fn(),save:vi.fn(),upload:vi.fn(),remove:vi.fn()}))
vi.mock('@/common/community/api/houseImages',()=>({listHouseImages:api.list,saveHouseImages:api.save,uploadHouseImage:api.upload,removeHouseImage:api.remove}))
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

async function chooseFiles(files: File[]) {
 const input=document.body.querySelector('input[type="file"]') as HTMLInputElement
 Object.defineProperty(input,'files',{value:files,configurable:true})
 input.dispatchEvent(new Event('change',{bubbles:true}))
 await flushPromises()
}
const photo=(name:string)=>new File(['test'],name,{type:'image/png'})
describe('相册批量选择',()=>{
 beforeEach(()=>{vi.clearAllMocks();api.list.mockResolvedValue([]);api.upload.mockResolvedValue({})})
 it('允许一次选择多张，依次上传到同一房屋',async()=>{
  const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}});await flushPromises()
  expect(document.body.querySelector('input[type="file"]')?.hasAttribute('multiple')).toBe(true)
  await chooseFiles([photo('a.png'),photo('b.png'),photo('c.png')])
  expect(api.upload.mock.calls.map(c=>[c[0],c[1].name,c[2]])).toEqual([[9,'a.png','PHOTO'],[9,'b.png','PHOTO'],[9,'c.png','PHOTO']])
  expect(document.body.textContent).toContain('已成功3/3');w.unmount()
 })
 it('容量不足整批拒绝，不静默截断',async()=>{
  api.list.mockResolvedValue(Array.from({length:9},(_,i)=>({id:i+1,kind:'PHOTO',caption:'',storageKey:'/uploads/a.png',sortOrder:i})))
  const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}});await flushPromises()
  await chooseFiles([photo('a.png'),photo('b.png')]);expect(api.upload).not.toHaveBeenCalled();w.unmount()
 })
 it('一张失败不影响其它图片，重试只发送失败项',async()=>{
  api.upload.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('网络异常')).mockResolvedValueOnce({})
  const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}});await flushPromises()
  await chooseFiles([photo('a.png'),photo('b.png'),photo('c.png')])
  expect(api.upload).toHaveBeenCalledTimes(3)
  const retry=Array.from(document.body.querySelectorAll('button')).find(b=>b.textContent?.includes('重试此图'))!
  expect(retry).toBeTruthy();retry.click();await flushPromises()
  expect(api.upload.mock.calls.map(c=>c[1].name)).toEqual(['a.png','b.png','c.png','b.png']);w.unmount()
 })
 it('上传未结束前禁止关闭，切换房屋不会把批次后续图片挂到新房屋',async()=>{
  let complete!:()=>void
  api.upload.mockImplementationOnce(()=>new Promise<void>(r=>{complete=r}))
  const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}});await flushPromises()
  await chooseFiles([photo('a.png'),photo('b.png')])
  const close=Array.from(document.body.querySelectorAll('button')).find(b=>b.textContent?.trim()==='关闭')!
  expect(close.disabled).toBe(true)
  await w.setProps({houseId:10});complete();await flushPromises()
  expect(api.upload.mock.calls.every(c=>c[0]===9)).toBe(true);expect(api.upload).toHaveBeenCalledTimes(1);w.unmount()
 })
})

it('上传前不丢失未保存的图片说明，取消保存则不上传',async()=>{
 api.list.mockResolvedValue([{id:1,kind:'PHOTO',caption:'原说明',storageKey:'/uploads/a.png',sortOrder:0}]);api.upload.mockClear()
 const prompt=vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel')
 const w=mount(HouseImageDialog,{attachTo:document.body,props:{modelValue:true,houseId:9},global:{plugins:[ElementPlus]}});await flushPromises()
 const caption=document.body.querySelector('input[placeholder="图片说明"]') as HTMLInputElement;caption.value='尚未保存';caption.dispatchEvent(new Event('input',{bubbles:true}));await flushPromises()
 await chooseFiles([photo('b.png')]);expect(prompt).toHaveBeenCalled();expect(api.upload).not.toHaveBeenCalled();expect(caption.value).toBe('尚未保存');w.unmount();prompt.mockRestore()
})
