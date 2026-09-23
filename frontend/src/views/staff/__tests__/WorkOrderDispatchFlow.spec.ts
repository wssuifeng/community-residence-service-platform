import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import WorkOrderListView from '../WorkOrderListView.vue'
import WorkOrderDetailView from '../WorkOrderDetailView.vue'
import { actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import { assignWorkOrder, intakeWorkOrder } from '@/api/workorder'

/**
 * 服务人员端派单链路（⑤）：管理端派单后，服务人员端必须能**在列表上直接**走完
 * 接单 → 开始处理 → 提交处理结果（修复前列表按钮文案是「接单」却只做跳转、
 * 已接单/处理中一律显示「查看」，等于点不动），并且详情页的接单/开始处理/提交结果
 * 入口按状态可见、辅助接口失败也不影响操作面板。
 */
vi.mock('@/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

const backend = () => getFakeBackend()

/** 管理端：居民提交 1 单 → 受理 → 派给 staff1（假后端 STAFF id=1） */
async function dispatchToStaff1(): Promise<void> {
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO202609220003',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水龙头持续漏水',
    description: '水龙头根部渗水',
    status: 'PENDING',
    assigneeId: null,
    assigneeName: null,
    createdAt: '2026-09-22T09:00:00'
  })
  await intakeWorkOrder(1)
  await assignWorkOrder(1, { assigneeId: 1 })
}

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/staff/work-orders', component: WorkOrderListView },
      { path: '/staff/work-orders/:id', component: { template: '<div class="detail-stub" />' } }
    ]
  })
}

async function mountList(router: Router): Promise<VueWrapper> {
  await router.push('/staff/work-orders')
  await router.isReady()
  const wrapper = mount(WorkOrderListView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

async function mountDetail(router: Router, id = 1): Promise<VueWrapper> {
  await router.push(`/staff/work-orders/${id}`)
  await router.isReady()
  const wrapper = mount(WorkOrderDetailView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().trim() === text)
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('服务人员端 · 派单链路可操作性（⑤）', () => {
  it('派单后列表出现该单，且接单 → 开始处理 → 提交结果入口随状态推进', async () => {
    await dispatchToStaff1()
    actAs('STAFF', 1)
    const router = buildRouter()
    const wrapper = await mountList(router)

    /* 待接单 Tab（默认）显示已派给本人的工单 */
    expect(wrapper.text()).toContain('厨房水龙头持续漏水')
    const accept = buttonByText(wrapper, '接单')
    expect(accept, '列表缺少「接单」操作入口').toBeTruthy()

    /* 接单：真调接口，状态推进到已接单 */
    await accept?.trigger('click')
    await flushPromises()
    expect(backend().state.workOrders[0].status).toBe('ACCEPTED')

    /* 已接单不再属于「待接单」Tab，切到「处理中」后同一位置给「开始处理」 */
    const doingTab = wrapper.findAll('button').find((item) => item.text().trim().startsWith('处理中'))
    expect(doingTab, '未找到「处理中」Tab').toBeTruthy()
    await doingTab?.trigger('click')
    await flushPromises()
    const process = buttonByText(wrapper, '开始处理')
    expect(process, '接单后未出现「开始处理」入口').toBeTruthy()
    await process?.trigger('click')
    await flushPromises()
    expect(backend().state.workOrders[0].status).toBe('IN_PROGRESS')

    /* 处理中给出「提交处理结果」，跳详情表单并带 action=complete */
    const complete = buttonByText(wrapper, '提交处理结果')
    expect(complete, '处理中未出现「提交处理结果」入口').toBeTruthy()
    await complete?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/staff/work-orders/1')
    expect(router.currentRoute.value.query.action).toBe('complete')
  })

  it('详情页按状态给出接单 / 开始处理 / 提交处理结果（三态各一次）', async () => {
    await dispatchToStaff1()
    actAs('STAFF', 1)

    /* 已派单 → 接单 */
    const router1 = buildRouter()
    const assigned = await mountDetail(router1)
    expect(buttonByText(assigned, '接单')).toBeTruthy()
    expect(buttonByText(assigned, '开始处理')).toBeFalsy()
    assigned.unmount()

    /* 已接单 → 开始处理 */
    backend().state.workOrders[0].status = 'ACCEPTED'
    const router2 = buildRouter()
    const accepted = await mountDetail(router2)
    expect(buttonByText(accepted, '开始处理')).toBeTruthy()
    expect(buttonByText(accepted, '接单')).toBeFalsy()
    accepted.unmount()

    /* 处理中 → 提交处理结果（含必填的处理结果说明） */
    backend().state.workOrders[0].status = 'IN_PROGRESS'
    const router3 = buildRouter()
    const doing = await mountDetail(router3)
    expect(buttonByText(doing, '提交处理结果')).toBeTruthy()
    expect(doing.find('#result-solution').exists()).toBe(true)
  })

  it('辅助接口（时间线/附件）失败不影响操作面板可达（修复前会整页空白）', async () => {
    await dispatchToStaff1()
    actAs('STAFF', 1)
    /* 主数据正常、辅助数据失败：走真实 fake 后再让时间线接口抛错 */
    const workorder = backend().workorder as unknown as Record<string, unknown>
    const originalTimeline = workorder.getWorkOrderTimeline
    const originalAttachments = workorder.listWorkOrderAttachments
    workorder.getWorkOrderTimeline = async () => {
      throw new Error('时间线服务不可用')
    }
    workorder.listWorkOrderAttachments = async () => {
      throw new Error('附件服务不可用')
    }
    try {
      const router = buildRouter()
      const wrapper = await mountDetail(router)
      expect(buttonByText(wrapper, '接单'), '辅助接口失败导致操作入口消失').toBeTruthy()
      expect(wrapper.text()).toContain('厨房水龙头持续漏水')
    } finally {
      workorder.getWorkOrderTimeline = originalTimeline
      workorder.listWorkOrderAttachments = originalAttachments
    }
  })

  it('主数据失败（非本人工单 403）给出显式失败态与重试，而非空白页', async () => {
    await dispatchToStaff1()
    actAs('STAFF', 1)
    /* 模拟后端数据级权限：该单未派给当前账号 → GET /work-orders/{id} 403 */
    const workorder = backend().workorder as unknown as Record<string, unknown>
    const originalGet = workorder.getWorkOrder
    workorder.getWorkOrder = async () => {
      throw new Error('无权查看他人工单')
    }
    try {
      const router = buildRouter()
      const wrapper = await mountDetail(router)

      expect(wrapper.text()).toContain('无权查看他人工单')
      expect(wrapper.text()).toContain('返回我的工单')
      expect(wrapper.text()).toContain('重新加载')
      expect(buttonByText(wrapper, '接单')).toBeFalsy()
    } finally {
      workorder.getWorkOrder = originalGet
    }
  })
})
