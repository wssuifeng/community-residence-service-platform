import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import AgreementTemplateView from '@/views/admin/lease/AgreementTemplateView.vue'
import { installFakeBackend } from '@/test/fakeBackend'

/**
 * 键盘焦点指示（`docs/30_系统设计/组件库设计规范.md` §6.2，WCAG AA）：
 * `:focus-visible` 的焦点环曾被紧随其后的 `:focus { outline: none }` 抹掉
 * （同特异性、后者胜出），自写交互元素完全不可见。修复见 `src/styles/index.css`。
 *
 * 说明：jsdom 不会匹配 `:focus-visible`（无浏览器的键盘启发式），因此**无法**用
 * `getComputedStyle` 断言"聚焦后 outline 存在"。这里的做法是：
 * ① 对样式源码做回归级校验（一旦有人把 `:focus{outline:none}` 写回来就失败）；
 * ② 断言真实自写交互元素可被键盘聚焦（浏览器中即命中上述全局 `:focus-visible` 规则）。
 */
vi.mock('@/api/agreement', async () => (await import('@/test/fakeBackend')).fakeApi('agreement'))
vi.mock('@/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))
vi.mock('@/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

/* 去注释后再解析：注释紧贴规则，会把选择器文本污染成「注释尾巴 + :focus-visible」 */
const css = readFileSync(resolve(process.cwd(), 'src/styles/index.css'), 'utf8').replace(
  /\/\*[\s\S]*?\*\//g,
  ''
)

/** 逐条拆出 "选择器 { 声明 }"，用于检查是否存在无条件抹掉 outline 的 :focus 规则 */
function styleRules(source: string): Array<{ selectors: string[]; body: string }> {
  const rules: Array<{ selectors: string[]; body: string }> = []
  for (const match of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    rules.push({
      selectors: match[1]
        .split(',')
        .map((part) => part.replace(/\s+/g, ' ').trim())
        .filter(Boolean),
      body: match[2]
    })
  }
  return rules
}

const rules = styleRules(css)

describe('键盘焦点指示（:focus-visible 不被抹掉）', () => {
  it('全局保留 :focus-visible 焦点环（outline 不为 none）', () => {
    const rule = rules.find((item) => item.selectors.includes(':focus-visible'))
    expect(rule, '缺少 :focus-visible 焦点环规则').toBeTruthy()
    expect(rule?.body).toMatch(/outline\s*:\s*[^;]*solid/)
  })

  it('不存在无条件抹掉 outline 的 :focus 规则（回归守卫）', () => {
    const offenders = rules.filter(
      (item) => item.selectors.includes(':focus') && /outline\s*:\s*none/.test(item.body)
    )
    expect(offenders).toEqual([])
  })

  it('需显式豁免的写法带 :not(:focus-visible)，否则键盘焦点环会被同特异性规则覆盖', () => {
    const suppressors = rules.filter((item) => /outline\s*:\s*none/.test(item.body))
    expect(suppressors.length).toBeGreaterThan(0)
    for (const rule of suppressors) {
      for (const selector of rule.selectors) {
        expect(selector, `抹掉 outline 的选择器必须排除 :focus-visible：${selector}`).toContain(
          ':focus-visible'
        )
      }
    }
  })

  it('真实自写交互元素可被键盘聚焦（浏览器中即命中上述 :focus-visible 规则）', async () => {
    installFakeBackend({ role: 'ADMIN', id: 1 })
    const wrapper = mount(AgreementTemplateView, {
      global: { plugins: [createPinia(), ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()

    const createButton = wrapper.findAll('button').find((item) => item.text().includes('新建模板'))
    await createButton?.trigger('click')
    await flushPromises()

    /* 弹窗内的「可用变量」chip 是自写 button（非 Element Plus 组件），正是此前焦点环失效的受害元素 */
    const chip = wrapper.find('button.variable-chip')
    expect(chip.exists()).toBe(true)
    ;(chip.element as HTMLButtonElement).focus()
    expect(document.activeElement).toBe(chip.element)
    expect((chip.element as HTMLButtonElement).tagName).toBe('BUTTON')
  })
})
