import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import DashboardView from '@/staff/workbench/views/DashboardView.vue'

const mocks = vi.hoisted(() => ({ workload: vi.fn(), orders: vi.fn(), notices: vi.fn() }))
vi.mock('@/common/statistics/api/statistics', () => ({ getStaffWorkloadStats: mocks.workload }))
vi.mock('@/staff/workbench/api/workbench', () => ({ getStaffWorkbenchSummary: mocks.orders }))
vi.mock('@/common/messaging/api/notification', () => ({ listNotifications: mocks.notices }))
vi.mock('@/common/statistics/components/EChart.vue', () => ({ default: { name: 'EChartStub', props: ['option', 'height'], template: '<div data-testid="chart" />' } }))

const stats = { days: 7, startDate: '2026-10-06', endDate: '2026-10-12', assignedTrend: { '2026-10-12': 4 }, submittedTrend: { '2026-10-12': 3 }, todayAssigned: 4, todaySubmitted: 3, totalSubmitted: 3 }
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/'); await router.isReady()
  const wrapper = mount(DashboardView, { global: { plugins: [createPinia(), router, ElementPlus] } })
  await flushPromises(); return wrapper
}
beforeEach(() => {
  mocks.workload.mockResolvedValue(stats)
  mocks.orders.mockResolvedValue({ todoCount: 0, doingCount: 0, completedCount: 0, urgentCount: 0, todoOrders: [] })
  mocks.notices.mockResolvedValue({ records: [], total: 0 })
})
afterEach(() => vi.resetAllMocks())
describe('人员当前负荷与事件统计分开', () => {
  it('真实事件用次数标签，不伪装当前负荷增长或今日完成', async () => {
    const wrapper = await open()
    expect(wrapper.get('[data-testid="today-submitted"]').text()).toBe('3')
    expect(wrapper.text()).toContain('当前工单')
    expect(wrapper.text()).toContain('近7日派入')
    expect(wrapper.text()).not.toContain('趋势待明确')
    expect(wrapper.text()).not.toContain('今日完成')
    expect(mocks.workload).toHaveBeenCalledWith(7)
    const chart = wrapper.findAllComponents({ name: 'EChartStub' }).find(component => component.props('option').legend)
    expect(chart?.props('option').legend.top).toBe(0)
    wrapper.unmount()
  })
  it('统计失败不显示0事件，原地重试后显示真实事件', async () => {
    mocks.workload.mockRejectedValueOnce(new Error('暂时不可用'))
    const wrapper = await open()
    expect(wrapper.find('[role="alert"]').text()).toContain('事件统计加载失败')
    expect(wrapper.find('[data-testid="today-submitted"]').exists()).toBe(false)
    const button = wrapper.findAll('button').find(button => button.text() === '重新加载事件统计')!
    await button.trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="today-submitted"]').text()).toBe('3')
    wrapper.unmount()
  })
})

describe('工作台摘要边界',()=>{
 it('一份摘要替代五次列表，摘要失败不伪装为零负荷并可原地重试',async()=>{
  mocks.orders.mockRejectedValueOnce(new Error('合成摘要失败'))
  const wrapper=await open()
  expect(wrapper.text()).toContain('工作台加载失败')
  expect(wrapper.find('.overview-panel').exists()).toBe(false)
  expect(wrapper.get('[data-testid="today-submitted"]').text()).toBe('3')
  expect(mocks.orders).toHaveBeenCalledTimes(1)
  await wrapper.findAll('button').find(button=>button.text()==='重新加载工作台')!.trigger('click');await flushPromises()
  expect(wrapper.find('.overview-panel').exists()).toBe(true)
  expect(mocks.orders).toHaveBeenCalledTimes(2)
  wrapper.unmount()
 })
})
