import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AgreementSignPanel from '../AgreementSignPanel.vue'
import type { ILeaseAgreement } from '@/types/modules/agreement'

const api = vi.hoisted(() => ({ list: vi.fn(), issue: vi.fn(), accept: vi.fn(), cancel: vi.fn(), templates: vi.fn() }))
vi.mock('@/api/agreement', () => ({
  listLeaseAgreements: api.list, createLeaseAgreement: api.issue, confirmLeaseAgreement: api.accept,
  cancelLeaseAgreement: api.cancel, listAvailableAgreementTemplates: api.templates
}))
vi.mock('@/api/lease', () => ({ getLease: vi.fn().mockResolvedValue({ communityId: 1, status: 'TO_SIGN' }) }))
const snapshot = (): ILeaseAgreement => ({ id: 10, leaseId: 1, communityId: 1, title: '租赁协议快照',
  content: '本次月租 3000 元，押金 6000 元。', status: 'PARTIAL', tenantId: 1,
  adminConfirmTime: '2026-10-11T08:00:00', adminConfirmName: '管理方甲', tenantConfirmTime: null,
  createdAt: '2026-10-11T08:00:00' })
let wrapper: VueWrapper | undefined
const button = (name: string) => Array.from(document.querySelectorAll('button')).find(b => b.textContent?.trim() === name)
async function render(role: 'ADMIN' | 'RESIDENT' = 'RESIDENT') {
  wrapper = mount(AgreementSignPanel, { props: { leaseId: 1, communityId: 1, role },
    global: { plugins: [ElementPlus] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  api.list.mockResolvedValue([snapshot()]); api.issue.mockResolvedValue(snapshot());
  api.accept.mockResolvedValue({ ...snapshot(), status: 'SIGNED' }); api.cancel.mockResolvedValue(null)
  api.templates.mockResolvedValue([{ id: 1, name: '当前模板', isDefault: 1, status: 'ACTIVE' }])
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.clearAllMocks(); document.body.innerHTML = '' })

describe('协议确认单一入口（DEF-168，真实组件挂载）', () => {
  it('居民须先打开具体正文，列表区域没有同意或承租按钮', async () => {
    await render()
    expect(button('同意协议')).toBeUndefined()
    expect(button('确认承租')).toBeUndefined()
    expect(button('查看协议并确认')).toBeTruthy()
    button('查看协议并确认')!.click(); await flushPromises()
    expect(document.body.textContent).toContain('本次月租 3000 元')
    expect(button('同意协议')).toBeTruthy()
    expect(api.accept).not.toHaveBeenCalled()
  })
  it('居民正文同意调用当前协议ID，成功后只通知父层刷新租约', async () => {
    await render(); button('查看协议并确认')!.click(); await flushPromises()
    button('同意协议')!.click(); await flushPromises()
    expect(api.accept).toHaveBeenCalledWith(10, {})
    expect(wrapper!.emitted('changed')).toHaveLength(1)
  })
  it('管理员发出后只显示等待居民与撤回，没有重复同意按钮', async () => {
    await render('ADMIN')
    expect(document.body.textContent).toContain('无需重复同意')
    expect(button('确认协议')).toBeUndefined()
    expect(button('撤回协议')).toBeTruthy()
    button('查看协议正文')!.click(); await flushPromises()
    expect(button('同意协议')).toBeUndefined()
  })
  it('管理员无承租前置即可发出，操作文案明确发出即同意', async () => {
    api.list.mockResolvedValue([]); await render('ADMIN')
    button('同意并发出协议')!.click(); await flushPromises()
    expect(document.body.textContent).toContain('发出即代表您以管理方身份同意')
    const buttons = Array.from(document.querySelectorAll('button')).filter(b => b.textContent?.trim() === '同意并发出协议')
    buttons.at(-1)!.click(); await flushPromises()
    expect(api.issue).toHaveBeenCalledWith({ leaseId: 1, templateId: 1, title: undefined, remark: undefined })
  })
  it('旧协议缺管理方同意时说明撤回重发，不提供同意按钮', async () => {
    api.list.mockResolvedValue([{ ...snapshot(), adminConfirmTime: null }]); await render()
    expect(document.body.textContent).toContain('撤回后重新发出')
    button('查看协议并确认')!.click(); await flushPromises()
    expect(button('同意协议')).toBeUndefined()
  })
  it('非待签约历史租约不应开放发出协议', async () => {
    api.list.mockResolvedValue([])
    await render('ADMIN')
    await wrapper!.setProps({ leaseStatus: 'ACTIVE' })
    expect(button('同意并发出协议')!.disabled).toBe(true)
    expect(document.body.textContent).toContain('其他状态保持历史只读')
  })
  it('已完成协议保留记录并只读，不可再次同意或撤回', async () => {
    api.list.mockResolvedValue([{ ...snapshot(), status: 'SIGNED', tenantConfirmTime: '2026-10-11T09:00:00' }])
    await render('ADMIN')
    expect(button('撤回协议')).toBeUndefined()
    button('查看协议正文')!.click(); await flushPromises()
    expect(button('同意协议')).toBeUndefined()
  })
  it('已撤回历史快照仍可查看，但不能误同意历史版本', async () => {
    api.list.mockResolvedValue([{ ...snapshot(), status: 'CANCELLED' }]); await render()
    const history = wrapper!.find('.el-collapse-item__header'); await history.trigger('click'); await flushPromises()
    button('查看协议正文')!.click(); await flushPromises()
    expect(document.body.textContent).toContain('本次月租 3000 元')
    expect(button('同意协议')).toBeUndefined()
  })
  it('确认失败保留正文供居民处理，不伪报已生效', async () => {
    api.accept.mockRejectedValue(new Error('该协议已撤回'))
    await render(); button('查看协议并确认')!.click(); await flushPromises()
    button('同意协议')!.click(); await flushPromises()
    expect(document.body.textContent).toContain('该协议已撤回')
    expect(wrapper!.emitted('changed')).toBeUndefined()
    expect(button('同意协议')).toBeTruthy()
  })
})
