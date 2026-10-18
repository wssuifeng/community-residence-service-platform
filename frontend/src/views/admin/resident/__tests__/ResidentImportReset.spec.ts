import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import ResidentImportDialog from '../ResidentImportDialog.vue'
vi.mock('@/common/resident/api/resident', () => ({ importResidents: vi.fn() }))
describe('居民CSV重新打开状态', () => {
 it('重新打开同步清空组件文件列表，允许再次选择同一文件', async () => {
  const w=mount(ResidentImportDialog,{props:{modelValue:true},global:{plugins:[ElementPlus],stubs:{ElDialog:{template:'<div><slot /><slot name="footer" /></div>'}}}})
  const upload=w.findComponent({name:'ElUpload'})
  upload.vm.handleStart(new File(['社区ID,姓名,手机号,证件号\n'],'same.csv',{type:'text/csv'}));await flushPromises()
  expect(upload.props('fileList')).toHaveLength(1)
  await w.setProps({modelValue:false});await w.setProps({modelValue:true});await flushPromises()
  expect(upload.props('fileList')).toHaveLength(0)
  upload.vm.handleStart(new File(['社区ID,姓名,手机号,证件号\n'],'same.csv',{type:'text/csv'}));await flushPromises()
  expect(upload.props('fileList')).toHaveLength(1)
  const button=w.findAll('button').find(b=>b.text()==='开始导入')!
  expect(button.attributes('disabled')).toBeUndefined();w.unmount()
 })
})
