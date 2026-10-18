import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'
import ElementPlus, { ElDialog } from 'element-plus'
import { createPinia } from 'pinia'
import AgreementTemplateView from '@/views/admin/lease/AgreementTemplateView.vue'
import { installFakeBackend } from '@/test/fakeBackend'

/**
 * 弹窗蒙层点击不关闭（`docs/30_系统设计/组件库设计规范.md` §6.3.2）：
 * 长表单（760px + 正文 textarea）误点蒙层就关窗会丢输入。收口方式见
 * `src/common/web/utils/elementPlusDefaults.ts`（全局默认 `closeOnClickModal: false`），
 * 由 `src/main.ts` 与 `src/test/setup.ts` 共同应用。
 */
vi.mock('@/common/agreement/api/agreement', async () => (await import('@/test/fakeBackend')).fakeApi('agreement'))
vi.mock('@/common/community/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))
vi.mock('@/common/upload/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

function dialogDefault(): unknown {
  const props = ElDialog.props as unknown as Record<string, { default?: unknown }>
  return props.closeOnClickModal?.default
}

/**
 * 模拟"点击蒙层"：蒙层点击的判定链是
 * `.el-overlay-dialog` 上的 `useSameTarget(onModalClick)`——要求 mousedown / mouseup / click
 * 三次事件的 target 与 currentTarget 一致（都落在该容器本身）。
 * 故必须补齐 mousedown + mouseup 且点在 `.el-overlay-dialog` 上，否则不会触发关闭逻辑，
 * 对照组会"假通过"（单发 click 或点 `.el-overlay` 都不会关闭）。
 * 关闭判定用 DOM 可见性：jsdom 不结束 CSS 过渡，`update:modelValue` 不会回传。
 */
async function clickMask(wrapper: ReturnType<typeof mount>): Promise<void> {
  const maskArea = wrapper.find('.el-overlay-dialog')
  await maskArea.trigger('mousedown')
  await maskArea.trigger('mouseup')
  await maskArea.trigger('click')
  await flushPromises()
}

describe('弹窗蒙层点击不关闭（长表单防误关）', () => {
  it('全局默认已收口：ElDialog.closeOnClickModal 默认值为 false', () => {
    expect(dialogDefault()).toBe(false)
  })

  it('对照：显式 close-on-click-modal=true 时，点击蒙层确实会关闭（证明本用例能抓到回归）', async () => {
    const wrapper = mount(
      {
        setup() {
          return () =>
            h(
              ElDialog,
              { modelValue: true, title: '对照弹窗', closeOnClickModal: true },
              { default: () => '对照内容' }
            )
        }
      },
      { global: { plugins: [ElementPlus] }, attachTo: document.body }
    )
    await flushPromises()
    expect(wrapper.find('.el-dialog').isVisible()).toBe(true)

    await clickMask(wrapper)
    expect(wrapper.find('.el-dialog').isVisible()).toBe(false)

    wrapper.unmount()
  })

  it('重灾区 AgreementTemplateView：点蒙层后 760px 长表单弹窗保持打开且已填内容不丢', async () => {
    installFakeBackend({ role: 'ADMIN', id: 1 })
    const wrapper = mount(AgreementTemplateView, {
      global: { plugins: [createPinia(), ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()

    /* 打开「新建模板」弹窗（760px 长表单 + 正文 textarea） */
    const createButton = wrapper.findAll('button').find((item) => item.text().includes('新建模板'))
    expect(createButton, '未找到「新建模板」按钮').toBeTruthy()
    await createButton?.trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('新建协议模板')

    /* 填入长表单内容 */
    await wrapper.find('input[placeholder^="如：社区房屋租赁协议"]').setValue('社区房屋租赁协议（标准版）')
    await wrapper.find('textarea.content-input').setValue('甲方（出租方）：{{社区名称}}\n乙方（承租方）：{{居民姓名}}')
    await flushPromises()

    /* 点击蒙层：弹窗必须保持打开，输入必须还在 */
    await clickMask(wrapper)

    expect(wrapper.find('.el-dialog').isVisible()).toBe(true)
    expect((wrapper.find('input[placeholder^="如：社区房屋租赁协议"]').element as HTMLInputElement).value).toBe(
      '社区房屋租赁协议（标准版）'
    )
    expect((wrapper.find('textarea.content-input').element as HTMLTextAreaElement).value).toContain(
      '{{社区名称}}'
    )
  })
})
