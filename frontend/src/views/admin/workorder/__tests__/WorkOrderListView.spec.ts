import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkOrderListView from '../WorkOrderListView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeWorkOrder } from '@/test/fakeBackend'

/**
 * 管理端工单调度（R74 增量）：**受理**入口仅 `PENDING` 行可见、**终审结束**入口仅
 * `COMPLETED` 行可见，且两个动作都按 ADMIN / SUPER_ADMIN 角色收口（v-permission）；
 * 受理失败（状态已变）按 ApiError 文案提示，不静默。
 */
vi.mock('@/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))
vi.mock('@/api/statistics', async () => (await import('@/test/fakeBackend')).fakeApi('statistics'))

const backend = () => getFakeBackend()

function seedOrder(partial: Partial<FakeWorkOrder> & { id: number; status: FakeWorkOrder['status'] }): void {
  backend().state.workOrders.push({
    orderNo: `WO20260922000${partial.id}`,
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: `工单 ${partial.id}`,
    description: '描述',
    assigneeId: null,
    assigneeName: null,
    createdAt: '2026-09-22T09:00:00',
    ...partial
  })
}

async function mountList(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/work-orders', component: WorkOrderListView },
      { path: '/admin/work-orders/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push('/admin/work-orders')
  await router.isReady()
  const wrapper = mount(WorkOrderListView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  /* 默认是阶段看板视图，切到列表视图才能逐行断言操作列 */
  const listToggle = wrapper.findAll('button').find((item) => item.text().trim() === '列表')
  if (listToggle && wrapper.findAll('tbody tr').length === 0) {
    await listToggle.trigger('click')
    await flushPromises()
  }
  return wrapper
}

/** 列表行「状态 → 行文本」：按工单号定位行，避免依赖行序 */
function rowTextOf(wrapper: VueWrapper, orderNo: string): string {
  const row = wrapper.findAll('tbody tr').find((item) => item.text().includes(orderNo))
  return row?.text() ?? ''
}

/** 行内操作按钮文案（按状态文案断言会被「待受理」状态标签误伤，故只看按钮） */
function rowButtonsOf(wrapper: VueWrapper, orderNo: string): string[] {
  const row = wrapper.findAll('tbody tr').find((item) => item.text().includes(orderNo))
  return row ? row.findAll('button').map((item) => item.text().trim()) : []
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
  /* 默认列表视图即可（看板为默认视图，这里切到列表以便逐行断言操作列） */
  seedOrder({ id: 1, status: 'PENDING' })
  seedOrder({ id: 2, status: 'TO_ASSIGN' })
  seedOrder({ id: 3, status: 'COMPLETED', assigneeId: 1, assigneeName: '服务人员一号' })
  seedOrder({ id: 4, status: 'TO_CONFIRM', assigneeId: 1, assigneeName: '服务人员一号' })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('管理端工单调度 · R74 受理与终审入口', () => {
  it('受理入口仅待受理行可见；终审结束入口仅已完成行可见', async () => {
    const wrapper = await mountList()

    const pending = rowButtonsOf(wrapper, 'WO202609220001')
    const toAssign = rowButtonsOf(wrapper, 'WO202609220002')
    const completed = rowButtonsOf(wrapper, 'WO202609220003')
    const toConfirm = rowButtonsOf(wrapper, 'WO202609220004')

    expect(pending).toContain('受理')
    expect(toAssign).not.toContain('受理')
    expect(completed).toContain('终审结束')
    expect(toConfirm).not.toContain('终审结束')
    /* 已完成行不再显示受理（受理前置仅 PENDING，终审前置仅 COMPLETED） */
    expect(completed).not.toContain('受理')
  })

  it('受理入口按角色收口：STAFF 视角即使行可见也不渲染该按钮', async () => {
    /* 管理员视角：待受理行有受理按钮 */
    const adminWrapper = await mountList()
    expect(rowButtonsOf(adminWrapper, 'WO202609220001')).toContain('受理')
    adminWrapper.unmount()

    /* 服务人员视角：列表按其被派单过滤，故造一条「已指派给自己且仍为待受理」的行，
       使行可见；此时按钮消失的唯一原因只能是 v-permission 角色判定 */
    installFakeBackend({ role: 'STAFF', id: 1 })
    backend().state.workOrders.push({
      id: 5,
      orderNo: 'WO202609220005',
      residentId: 1,
      communityId: 1,
      categoryId: 1,
      title: '待受理但已指派',
      description: '描述',
      status: 'PENDING',
      assigneeId: 1,
      assigneeName: '服务人员一号',
      createdAt: '2026-09-22T09:00:00'
    })
    const staffWrapper = await mountList()
    expect(rowTextOf(staffWrapper, 'WO202609220005'), '服务人员视角未渲染该行，用例前提不成立').not.toBe(
      ''
    )
    expect(rowButtonsOf(staffWrapper, 'WO202609220005')).not.toContain('受理')
  })

  it('受理成功：状态转待派单、回带 conversationId 并提示已建群', async () => {
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    const wrapper = await mountList()

    const button = wrapper
      .findAll('button')
      .find((item) => item.text().trim() === '受理')
    expect(button, '未找到受理按钮').toBeTruthy()
    await button?.trigger('click')
    await flushPromises()

    expect(backend().state.workOrders[0].status).toBe('TO_ASSIGN')
    const group = backend().state.conversations.find((item) => item.type === 'WORK_ORDER_GROUP')
    expect(group?.title).toBe('工单沟通·WO202609220001')
    expect(backend().state.workOrders[0].conversationId).toBe(group?.id)
    expect(success.mock.calls[0]?.[0]).toBe('工单已受理，工单沟通群已建立')
    /* 受理后自动刷新：列表行出现「已建群」标记 */
    await flushPromises()
    expect(rowTextOf(wrapper, 'WO202609220001')).toContain('已建群')
  })

  it('受理失败（行已过期、状态已变）：按 ApiError 文案提示错误', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    const wrapper = await mountList()

    /* 模拟并发：页面渲染后该单已被他人受理（状态非 PENDING） */
    backend().state.workOrders[0].status = 'TO_ASSIGN'

    const button = wrapper
      .findAll('button')
      .find((item) => item.text().trim() === '受理')
    await button?.trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledTimes(1)
    expect(String(error.mock.calls[0]?.[0])).toContain('不允许「受理」')
    /* 失败不建群 */
    expect(backend().state.conversations).toHaveLength(0)
  })

  it('终审确认框打开期间即防重入：finalizingId 先于确认置位，二次点击不再弹框', async () => {
    /* OCR DEF-124：防重入标记原在确认框 resolve 后才置位——确认框滞留期间
       再点他行终审会弹出第二个确认框，确认后并发两次关闭请求 */
    let resolveConfirm!: (value: unknown) => void
    const confirmSpy = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveConfirm = resolve
          }) as never
      )
    seedOrder({ id: 5, status: 'COMPLETED', assigneeId: 1, assigneeName: '服务人员一号' })
    const wrapper = await mountList()

    const finalizeOf = (orderNo: string) => {
      const row = wrapper.findAll('tbody tr').find((item) => item.text().includes(orderNo))
      return row?.findAll('button').find((item) => item.text().trim() === '终审结束')
    }

    /* A 行点终审：确认框打开且未决 */
    await finalizeOf('WO202609220003')?.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledTimes(1)

    /* 确认框等待期间 A 行按钮已带 loading（finalizingId 已置位，时序断言） */
    const loadingRow = wrapper.findAll('tbody tr').find((item) => item.text().includes('WO202609220003'))
    const loadingButton = loadingRow?.findAll('button').find((item) => item.text().includes('终审结束'))
    expect(loadingButton?.classes()).toContain('is-loading')

    /* 确认框未决期间点 B 行终审：被全局防重入挡下，不再弹第二个确认框 */
    await finalizeOf('WO202609220005')?.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(backend().state.workOrders.find((item) => item.id === 5)?.status).toBe('COMPLETED')

    /* 确认后 A 行正常关闭；标记复位后 B 行可再次发起终审 */
    resolveConfirm('confirm')
    await flushPromises()
    expect(backend().state.workOrders.find((item) => item.id === 3)?.status).toBe('CLOSED')
    await finalizeOf('WO202609220005')?.trigger('click')
    await flushPromises()
    expect(confirmSpy).toHaveBeenCalledTimes(2)
  })
})
