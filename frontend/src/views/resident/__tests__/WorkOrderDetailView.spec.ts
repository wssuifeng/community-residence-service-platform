import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkOrderDetailView from '@/views/resident/WorkOrderDetailView.vue'
import { installFakeBackend, getFakeBackend } from '@/test/fakeBackend'

/**
 * 居民端「不满意退回」（R22）：必须走专用退回端点 → 工单回到**处理中**，
 * 而不是借「确认完成」把工单推进到**已完成**（改前行为）。原因必填（后端 @NotBlank）。
 */
vi.mock('@/common/workorder/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/common/evaluation/api/evaluation', async () => (await import('@/test/fakeBackend')).fakeApi('evaluation'))

const backend = () => getFakeBackend()

async function mountPage(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/resident/work-orders/:id', component: WorkOrderDetailView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push('/resident/work-orders/1')
  await router.isReady()
  const wrapper = mount(WorkOrderDetailView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

function seedToConfirmOrder(): void {
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO20260922001',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水管漏水',
    description: '水槽下方渗水',
    status: 'TO_CONFIRM',
    assigneeId: 1,
    assigneeName: '服务人员一号',
    createdAt: '2026-09-22T09:00:00'
  })
}

function seedClosedOrder(): void {
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO20260922002',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水管漏水',
    description: '水槽下方渗水',
    status: 'CLOSED',
    assigneeId: 1,
    assigneeName: '服务人员一号',
    conversationId: 1,
    createdAt: '2026-09-22T09:00:00'
  })
  backend().state.conversations.push({
    id: 1,
    type: 'WORK_ORDER_GROUP',
    relatedId: 1,
    title: '工单沟通 WO20260922002',
    status: 'CLOSED',
    participantIds: [1],
    createdAt: '2026-09-22T09:00:00',
    closedAt: '2026-09-22T10:00:00'
  })
}

beforeEach(() => {
  installFakeBackend({ role: 'RESIDENT', id: 1 })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('居民工单详情 · 不满意退回（R22）', () => {
  it('点击「不满意」并填写原因：工单回到处理中、原因留痕、服务人员收到通知', async () => {
    seedToConfirmOrder()
    const promptSpy = vi
      .spyOn(ElMessageBox, 'prompt')
      .mockResolvedValue({ value: '  卫生间仍有异味  ' } as never)

    const wrapper = await mountPage()
    const button = wrapper.findAll('button').find((item) => item.text().trim() === '不满意')
    expect(button, '未找到「不满意」按钮').toBeTruthy()

    await button?.trigger('click')
    await flushPromises()

    /* 走的是退回端点：状态 TO_CONFIRM → IN_PROGRESS（若误调确认完成会是 COMPLETED） */
    const order = backend().state.workOrders[0]
    expect(order.status).toBe('IN_PROGRESS')
    expect(order.status).not.toBe('COMPLETED')

    /* 原因写入 remark 并留痕（trim 后提交） */
    const process = backend().state.workOrderProcesses.filter((item) => item.orderId === 1)
    expect(process.some((item) => String(item.remark).includes('居民反馈不满意：卫生间仍有异味'))).toBe(true)

    /* 退回后通知当前服务人员（USER 身份空间） */
    expect(
      backend().state.notifications.some(
        (item) => item.ownerType === 'USER' && item.userId === 1 && item.title.includes('退回')
      )
    ).toBe(true)

    /* 原因必填：弹窗校验器拒绝空白输入（保留原有固定文案与交互） */
    const options = promptSpy.mock.calls[0][2] as unknown as {
      inputValidator: (value: string) => boolean | string
    }
    expect(options.inputValidator('   ')).toBe('请填写不满意原因')
    expect(options.inputValidator('服务未解决')).toBe(true)
  })

  it('未填原因（校验不通过）时不会调用退回：工单保持待确认', async () => {
    seedToConfirmOrder()
    vi.spyOn(ElMessageBox, 'prompt').mockRejectedValue('cancel' as never)

    const wrapper = await mountPage()
    const button = wrapper.findAll('button').find((item) => item.text().trim() === '不满意')
    await button?.trigger('click')
    await flushPromises()

    expect(backend().state.workOrders[0].status).toBe('TO_CONFIRM')
    expect(backend().state.workOrderProcesses).toHaveLength(0)
  })

  it('终审关闭后仍保留居民补交评价入口', async () => {
    seedClosedOrder()

    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('服务评价')
    expect(wrapper.find('.evaluation-card').exists()).toBe(true)
    expect(wrapper.find('.evaluation-form').exists()).toBe(true)

    await wrapper.findAll('.evaluation-form .el-rate__item')[4].trigger('click')
    await wrapper.find('.evaluation-form textarea').setValue('维修及时，处理结果符合预期')
    await wrapper.find('.evaluation-form button').trigger('click')
    await flushPromises()

    expect(backend().state.evaluations[0]).toMatchObject({ orderId: 1, score: 5, satisfied: true })
    expect(wrapper.find('.evaluation-form').exists()).toBe(false)
    expect(wrapper.find('.evaluation-summary').exists()).toBe(true)
  })

  it('退回成功提示指向「重新处理后再确认」，不再指向退回后不可用的评价表单', async () => {
    /* OCR DEF-124：退回后工单为处理中，评价表单仅已完成可用——
       提示文案不得再指引居民去「评价中打分」 */
    seedToConfirmOrder()
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '卫生间仍有异味' } as never)
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)

    const wrapper = await mountPage()
    const button = wrapper.findAll('button').find((item) => item.text().trim() === '不满意')
    await button?.trigger('click')
    await flushPromises()

    const message = String(success.mock.calls[0]?.[0])
    expect(message).toBe('反馈已记录，已退回服务人员重新处理，完成后可再次确认并评价')
    expect(message).not.toContain('请在下方评价中打分')
    /* 佐证：退回后为处理中，评价区不渲染（原提示指向的表单此刻不存在） */
    expect(backend().state.workOrders[0].status).toBe('IN_PROGRESS')
    expect(wrapper.text()).not.toContain('服务评价')
  })
})
