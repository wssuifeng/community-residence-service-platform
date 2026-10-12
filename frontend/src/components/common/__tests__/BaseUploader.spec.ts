import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BaseUploader from '../BaseUploader.vue'
const { upload } = vi.hoisted(() => ({ upload: vi.fn() }))
vi.mock('@/api/upload', () => ({ uploadFile: upload }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), warning: vi.fn() } }))
const global = { stubs: { 'el-button': { template: '<button><slot /></button>' }, 'el-dialog': { template: '<div><slot /></div>' } } }
async function choose(wrapper: ReturnType<typeof mount>, names: string[]) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: names.map(name => new File(['test'], name, { type: 'image/png' })) })
  await input.trigger('change'); await flushPromises()
}
beforeEach(() => { upload.mockReset() })
describe('图片上传逐张结果', () => {
 it('部分失败保留成功项，并继续其他文件', async () => {
  upload.mockResolvedValueOnce({ fileUrl: '/uploads/one.png' }).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({ fileUrl: '/uploads/three.png' })
  const wrapper = mount(BaseUploader, { props: { modelValue: [] }, global })
  await choose(wrapper, ['one.png', 'two.png', 'three.png'])
  expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([['/uploads/one.png', '/uploads/three.png']])
  expect(wrapper.text()).toContain('two.png')
 })
 it('整个上传期间明确busy状态', async () => {
  upload.mockResolvedValue({ fileUrl: '/uploads/one.png' })
  const wrapper = mount(BaseUploader, { props: { modelValue: [] }, global })
  await choose(wrapper, ['one.png'])
  expect(wrapper.emitted('busy-change')).toEqual([[true], [false]])
 })
 it('超过数量上限不请求上传', async () => {
  const wrapper = mount(BaseUploader, { props: { modelValue: ['/uploads/old.png'], limit: 1 }, global })
  await choose(wrapper, ['two.png']); expect(upload).not.toHaveBeenCalled()
 })
 it('禁用状态不请求上传', async () => {
  const wrapper = mount(BaseUploader, { props: { modelValue: [], disabled: true }, global })
  await choose(wrapper, ['one.png']); expect(upload).not.toHaveBeenCalled()
 })
 it('上传中重复选择不会发第二次请求', async () => {
  let resolve!: (value: { fileUrl: string }) => void
  upload.mockImplementation(() => new Promise(done => { resolve = done }))
  const wrapper = mount(BaseUploader, { props: { modelValue: [] }, global })
  await choose(wrapper, ['one.png']); await choose(wrapper, ['two.png'])
  expect(upload).toHaveBeenCalledTimes(1)
  resolve({ fileUrl: '/uploads/one.png' }); await flushPromises()
 })
 it('组件卸载后迟到结果不回写', async () => {
  let resolve!: (value: { fileUrl: string }) => void
  upload.mockImplementation(() => new Promise(done => { resolve = done }))
  const wrapper = mount(BaseUploader, { props: { modelValue: [] }, global })
  await choose(wrapper, ['one.png']); wrapper.unmount()
  resolve({ fileUrl: '/uploads/one.png' }); await flushPromises()
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
 })
 it('已有图片预览和移除都有明确按钮说明', async () => {
  const wrapper = mount(BaseUploader, { props: { modelValue: ['/uploads/old.png'] }, global })
  expect(wrapper.find('button[aria-label="预览图片 old.png"]').exists()).toBe(true)
  await wrapper.find('button[aria-label="移除图片 old.png"]').trigger('click')
  expect(wrapper.emitted('update:modelValue')).toEqual([[[]]])
 })

})
