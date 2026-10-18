import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import PublicResourceListView from '@/admin/community/views/PublicResourceListView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'

/**
 * 管理端公共资源维护 · 图片字段（2026-09-29 用户裁决 · 前端接入）：
 * - 表单选文件上传，平台上传结果按string[]提交；无新增URL输入。
 * - 编辑回填缩略图，移除后保存提交空数组；上传中禁止保存。
 * - 列表首图缩略图：有图显示首图、无图显示占位符。
 */
const batchSlots=vi.hoisted(()=>vi.fn().mockResolvedValue({createdDays:[1,2,3,4,5],skippedDays:[]}))
vi.mock('@/common/community/api/community', async () => ({...(await import('@/test/fakeBackend')).fakeApi('community'),createTimeslotsBatch:batchSlots}))

vi.mock('@/common/upload/api/upload', () => ({ uploadFile: vi.fn(async (file: File) => ({ fileUrl: '/uploads/' + file.name })) }))

const backend = () => getFakeBackend()

async function mountPage(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }]
  })
  await router.push('/admin/community')
  await router.isReady()
  const wrapper = mount(PublicResourceListView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

/** 选中「所属社区」筛选（同时触发资源列表加载，列表区才渲染） */
async function selectCommunity(wrapper: VueWrapper, communityId = 1): Promise<void> {
  const select = wrapper.findAllComponents({ name: 'ElSelect' })[0]
  select.vm.$emit('update:modelValue', communityId)
  select.vm.$emit('change', communityId)
  await flushPromises()
}

/** 列表行（按资源名称定位，避免依赖行序） */
function rowOf(wrapper: VueWrapper, name: string): DOMWrapper<Element> | undefined {
  return wrapper.findAll('tbody tr').find((row) => row.text().includes(name))
}

/** 提交弹窗（底部「保存资源」） */
async function submitDialog(wrapper: VueWrapper): Promise<void> {
  const confirm = wrapper
    .findAll('.el-dialog__footer button')
    .find((item) => item.text().trim() === '保存资源')
  expect(confirm, '未找到弹窗「保存资源」按钮').toBeTruthy()
  await confirm!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('管理端公共资源图片 · 表单（PublicResourceListView）', () => {
  it('新建：选文件上传后的平台地址数组提交，不再有URL输入框', async () => {
    const wrapper = await mountPage()
    await selectCommunity(wrapper)

    const createButton = wrapper.findAll('button').find((item) => item.text().includes('新建资源'))
    expect(createButton, '未找到「新建资源」按钮').toBeTruthy()
    await createButton!.trigger('click')
    await flushPromises()

    await wrapper.find('input[placeholder="如：篮球场"]').setValue('社区图书馆')
    expect(wrapper.find('textarea[placeholder^="图片地址"]').exists()).toBe(false)
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['image'], 'lib-1.png'), new File(['image'], 'lib-2.png')] })
    await input.trigger('change')
    await flushPromises()

    await submitDialog(wrapper)

    const created = backend().state.resources.find((item) => item.name === '社区图书馆')
    expect(created, '资源未创建').toBeTruthy()
    expect(created!.images).toEqual(['/uploads/lib-1.png', '/uploads/lib-2.png'])
  })

  it('编辑：回填缩略图，移除后保存空数组', async () => {
    backend().state.resources.push({
      id: 2,
      communityId: 1,
      name: '瑜伽室',
      type: 'GYM',
      images: ['/uploads/yoga-1.png', '/uploads/yoga-2.png'],
      status: 'AVAILABLE'
    })
    const wrapper = await mountPage()
    await selectCommunity(wrapper)

    const row = rowOf(wrapper, '瑜伽室')
    expect(row, '未渲染「瑜伽室」行').toBeTruthy()
    await row!.findAll('button').find((item) => item.text().trim() === '编辑')!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.image-item img').map(item => item.attributes('src'))).toEqual(['/uploads/yoga-1.png', '/uploads/yoga-2.png'])
    while (wrapper.find('.image-remove').exists()) {
      await wrapper.find('.image-remove').trigger('click')
      await flushPromises()
    }

    await submitDialog(wrapper)

    expect(backend().state.resources.find((item) => item.id === 2)?.images).toEqual([])
  })

  it('列表首图缩略图：有图显示首图，无图显示占位符', async () => {
    backend().state.resources.push({
      id: 2,
      communityId: 1,
      name: '瑜伽室',
      type: 'GYM',
      images: ['/uploads/yoga-1.png', '/uploads/yoga-2.png'],
      status: 'AVAILABLE'
    })
    const wrapper = await mountPage()
    await selectCommunity(wrapper)

    const yogaRow = rowOf(wrapper, '瑜伽室')
    expect(yogaRow, '未渲染「瑜伽室」行').toBeTruthy()
    const yogaCover = yogaRow!.find('img.row-cover')
    expect(yogaCover.exists()).toBe(true)
    expect(yogaCover.attributes('src')).toBe('/uploads/yoga-1.png')

    /* 种子资源「健身房」无图：显示占位符，不渲染 img */
    const gymRow = rowOf(wrapper, '健身房')
    expect(gymRow, '未渲染「健身房」行').toBeTruthy()
    expect(gymRow!.find('img.row-cover').exists()).toBe(false)
    expect(gymRow!.find('.row-cover-empty').exists()).toBe(true)
  })
  it('上传中保存与取消均禁用', async () => {
    const wrapper = await mountPage()
    await selectCommunity(wrapper)
    await wrapper.findAll('button').find(item => item.text().includes('新建资源'))!.trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'ImageUploader' }).vm.$emit('busy-change', true)
    await flushPromises()
    const buttons = wrapper.findAll('.el-dialog__footer button')
    expect(buttons.find(item => item.text() === '保存资源')?.attributes('disabled')).toBeDefined()
    expect(buttons.find(item => item.text() === '取消')?.attributes('disabled')).toBeDefined()
  })

})

it('工作日一次提交共同时间，成功保留选择且不重新开启停用模板',async()=>{
 const wrapper=await mountPage();await selectCommunity(wrapper)
 const button=wrapper.findAll('tbody button').find(b=>b.text().includes('时段'))!
 expect(button).toBeTruthy();await button.trigger('click');await flushPromises()
 const presets=wrapper.findAll('button').find(b=>b.text().trim()==='工作日')!
 await presets.trigger('click')
 const times=wrapper.findAllComponents({name:'ElTimePicker'});times[0]!.vm.$emit('update:modelValue','09:00');times[1]!.vm.$emit('update:modelValue','12:00');await flushPromises()
 const add=wrapper.findAll('button').find(b=>b.text().includes('添加到所选5个星期'))!
 expect(add).toBeTruthy();await add.trigger('click');await flushPromises()
 expect(batchSlots).toHaveBeenCalledWith(expect.any(Number),{daysOfWeek:[1,2,3,4,5],startTime:'09:00',endTime:'12:00',isAvailable:1})
 expect(wrapper.findAll('button').some(b=>b.text().includes('添加到所选5个星期'))).toBe(true);wrapper.unmount()
})
