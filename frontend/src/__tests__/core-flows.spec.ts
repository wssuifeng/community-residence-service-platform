import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { actAs, getFakeBackend, installFakeBackend, type FakeRole } from '@/test/fakeBackend'
import ResidenceApplyView from '@/resident/resident/views/ResidenceApplyView.vue'
import ResidentApplicationListView from '@/resident/resident/views/ResidenceApplicationListView.vue'
import AdminApplicationListView from '@/admin/resident/views/ResidenceApplicationListView.vue'
import ResidentLeaseListView from '@/resident/lease/views/LeaseListView.vue'
import LeaseFirstPaymentDialog from '@/resident/lease/components/LeaseFirstPaymentDialog.vue'
import AdminMoveOutApplicationView from '@/admin/lease/views/MoveOutApplicationView.vue'
import LeaseMoveOutDialog from '@/resident/lease/components/LeaseMoveOutDialog.vue'
import GuestNoticeListView from '@/common/public/views/NoticeListView.vue'
import ResidentNotificationListView from '@/resident/messaging/views/NotificationListView.vue'
import AdminNotificationListView from '@/admin/notification/views/NotificationListView.vue'
import ResidentReservationCreateView from '@/resident/reservation/views/ReservationCreateView.vue'
import AdminFeedbackManageView from '@/admin/feedback/views/FeedbackManageView.vue'
import AdminWorkOrderListView from '@/admin/workorder/views/WorkOrderListView.vue'
import ResidentWorkOrderCreateView from '@/resident/workorder/views/WorkOrderCreateView.vue'

/**
 * 核心业务 + 核心业务延展的**模拟 E2E**（vitest + @vue/test-utils）。
 *
 * ## 定位（必读）
 * vitest/@vue/test-utils 是**组件级**环境：没有真实浏览器、没有真实后端。所以本文件是
 * **「模拟 E2E」**——挂载**真实页面组件**（真实 Element Plus、真实 api 层），把 `@/api/*`
 * 接到 `src/test/fakeBackend.ts` 的**有状态假后端**上，按 `docs/50_系统测试/
 * 13_核心业务流程三端联动手测用例.md` 的 CF-E* 分段**三端交替**推进，断言：
 * ① 页面可见文本/DOM（用户看到什么）② 假后端状态跃迁（业务真的推进了，不是只渲染了）。
 * **真端到端（真库真接口）由另一路的接口脚本负责，本文件不打真后端。**
 *
 * 覆盖标注：`[页面]` = 经真实页面操作；`[接口]` = 该规则无独立 UI 承载（纯后端口径），
 * 直接经 api 层打到假后端断言——两者都不出前端进程。
 *
 * 错误码口径：以后端 `common/constant/ErrorCode.java` 为准（与 `接口设计.md` 有漂移，
 * 见 `src/test/fakeBackend.ts` 头注）；业务异常统一 HTTP 200 + 5xxx。
 */

/* 假后端装配：逐个模块一行（vi.mock 必须静态声明，工厂延迟到模块被导入时才求值） */
vi.mock('@/common/resident/api/resident', async () => (await import('@/test/fakeBackend')).fakeApi('resident'))
vi.mock('@/resident/lease/api/summaries', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))
vi.mock('@/common/lease/api/lease', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))
vi.mock('@/common/payment/api/payment', async () => (await import('@/test/fakeBackend')).fakeApi('payment'))
vi.mock('@/common/lease/api/leaseMoveOut', async () => (await import('@/test/fakeBackend')).fakeApi('leaseMoveOut'))
vi.mock('@/common/agreement/api/agreement', async () => (await import('@/test/fakeBackend')).fakeApi('agreement'))
vi.mock('@/common/conversation/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/common/workorder/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/common/evaluation/api/evaluation', async () => (await import('@/test/fakeBackend')).fakeApi('evaluation'))
vi.mock('@/common/notice/api/notice', async () => (await import('@/test/fakeBackend')).fakeApi('notice'))
vi.mock('@/common/feedback/api/feedback', async () => (await import('@/test/fakeBackend')).fakeApi('feedback'))
vi.mock('@/common/reservation/api/reservation', async () => (await import('@/test/fakeBackend')).fakeApi('reservation'))
vi.mock('@/common/messaging/api/notification', async () => (await import('@/test/fakeBackend')).fakeApi('notification'))
vi.mock('@/common/housing/api/housing', async () => (await import('@/test/fakeBackend')).fakeApi('housing'))
vi.mock('@/common/community/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))
vi.mock('@/common/statistics/api/statistics', async () => (await import('@/test/fakeBackend')).fakeApi('statistics'))
vi.mock('@/common/workorder/api/staff', async () => (await import('@/test/fakeBackend')).fakeApi('staff'))
vi.mock('@/common/upload/api/upload', async () => (await import('@/test/fakeBackend')).fakeApi('upload'))

const backend = () => getFakeBackend()

/* ------------------------------ 通用工具 ------------------------------ */

function compact(text: string): string {
  return text.replace(/\s+/g, '')
}

/** 本地「今天」（yyyy-MM-dd）：假后端到期判定按本地日期，不能用 UTC 的 toISOString 跨日 */
function localToday(): string {
  return localDate(new Date())
}

function localDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * 挂载真实页面组件并接入路由。
 * pattern 用于带参数的页面（如 `/resident/housings/:id/apply`），否则 route.params 为空。
 */
async function mountPage(
  component: unknown,
  path: string,
  options: { pattern?: string } = {}
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: options.pattern ?? path, component: component as never },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, {
    global: {
      plugins: [createPinia(), router, ElementPlus]
    },
    attachTo: document.body
  })
  await flushPromises()
  return { wrapper, router }
}

/** 页面整体可见文本（含 Element Plus 传送到 body 的弹窗/消息） */
function pageText(): string {
  return compact(document.body.textContent ?? '')
}

function buttonOf(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find((item) => compact(item.text()).includes(label))
}

async function clickButton(wrapper: VueWrapper, label: string): Promise<void> {
  const target = buttonOf(wrapper, label)
  expect(target, `未找到按钮「${label}」`).toBeTruthy()
  await target?.trigger('click')
  await flushPromises()
}

/** 断言 ElMessage 文案（消息挂在 body 上，3s 自动关闭，用例内可见） */
function expectMessage(text: string): void {
  expect(pageText()).toContain(compact(text))
}

/** el-form-item 内按 label 文本填值（管理端审批弹窗需要） */
async function fillFormItem(wrapper: VueWrapper, label: string, value: string): Promise<void> {
  const item = wrapper.findAll('.el-form-item').find((node) => compact(node.text()).includes(label))
  expect(item, `未找到表单项「${label}」`).toBeTruthy()
  const input = item?.find('input')
  await input?.setValue(value)
  await flushPromises()
}

/** 走一遍「居民申请 → 管理端审批通过」，返回租约 ID（后续 E3 各段复用） */
async function submitAndApprove(): Promise<number> {
  actAs('RESIDENT', 1)
  const apply = await mountPage(ResidenceApplyView, '/resident/housings/1/apply', {
    pattern: '/resident/housings/:id/apply'
  })
  await clickButton(apply.wrapper, '提交申请')
  apply.wrapper.unmount()

  actAs('ADMIN', 1)
  const review = await mountPage(AdminApplicationListView, '/admin/housing-lease')
  await clickButton(review.wrapper, '通过')
  await fillFormItem(review.wrapper, '租约结束', '2027-12-31')
  await fillFormItem(review.wrapper, '月租金', '3000')
  await clickButton(review.wrapper, '确认通过')
  review.wrapper.unmount()

  const lease = backend().state.leases.at(-1)
  expect(lease, '审批通过后应生成租约').toBeTruthy()
  /* 承约前置（DEF-077，后端 5607）：本房源看房预约需为「已完成」——
     真实链路里居民先约看房、由管理端完成，这里直接落一条已完成记录 */
  const tenant = backend().state.residents.find((item) => item.id === lease?.tenantId)
  backend().state.viewingAppointments.push({
    id: 9001,
    housingId: 1,
    houseId: lease?.houseId ?? 1,
    communityId: lease?.communityId ?? 1,
    visitorName: tenant?.name ?? '演示居民',
    visitorPhone: tenant?.phone ?? '13800000001',
    appointmentDate: '2026-01-01',
    startTime: '10:00',
    endTime: '11:00',
    status: 'COMPLETED',
    createdAt: '2026-01-01T09:00:00'
  })
  return lease?.id ?? 0
}

/** 走到「租约已生效」：审核通过 → 确认承租 → 协议双方确认 */
async function activateLease(): Promise<number> {
  const leaseId = await submitAndApprove()
  const agreementApi = await import('@/common/agreement/api/agreement')

  actAs('RESIDENT', 1)
  actAs('ADMIN', 1)
  const agreement = (await agreementApi.createLeaseAgreement({ leaseId, templateId: 1 } as never)) as {
    id: number
  }
  actAs('RESIDENT', 1)
  await agreementApi.confirmLeaseAgreement(agreement.id, {})
  expect(backend().state.leases.find((item) => item.id === leaseId)?.status).toBe('ACTIVE')
  return leaseId
}

beforeEach(() => {
  installFakeBackend({ role: 'RESIDENT', id: 1 })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  sessionStorage.clear()
})

/* ============================================================================
 * 核心：CF-E2 入住申请（提交 / 重复 5206 / 撤回 / 重复撤回 5605）
 * ==========================================================================*/

describe('模拟 E2E · CF-E2 居民注册·入住申请审核', () => {
  it('CF-E2 提交入住申请：状态待审核，管理端可见同一条申请', async () => {
    actAs('RESIDENT', 1)
    const { wrapper } = await mountPage(ResidenceApplyView, '/resident/housings/1/apply', {
      pattern: '/resident/housings/:id/apply'
    })

    expect(pageText()).toContain('申请租住')
    await clickButton(wrapper, '提交申请')

    expectMessage('申请已提交，等待管理方审核')
    expect(backend().state.residenceApplications).toHaveLength(1)
    expect(backend().state.residenceApplications[0].status).toBe('PENDING')

    /* 三端交替：管理端看到这条待审核申请 */
    actAs('ADMIN', 1)
    const review = await mountPage(AdminApplicationListView, '/admin/housing-lease')
    expect(pageText()).toContain('演示居民')
    expect(pageText()).toContain('待审核')
    expect(buttonOf(review.wrapper, '通过')).toBeTruthy()
  })

  it('CF-E2 重复提交被拒：5206 且给出「入住申请」页入口（不产生第二条申请）', async () => {
    actAs('RESIDENT', 1)
    const first = await mountPage(ResidenceApplyView, '/resident/housings/1/apply', {
      pattern: '/resident/housings/:id/apply'
    })
    await clickButton(first.wrapper, '提交申请')
    expect(backend().state.residenceApplications).toHaveLength(1)
    first.wrapper.unmount()
    document.body.innerHTML = ''

    /* 再次进入申请页提交（同房屋仍有待审核申请）→ 5206 且指引到「入住申请」页 */
    const again = await mountPage(ResidenceApplyView, '/resident/housings/1/apply', {
      pattern: '/resident/housings/:id/apply'
    })
    await clickButton(again.wrapper, '提交申请')

    expectMessage('您已有待审核的入住申请，可在「租住与房源 → 入住申请」查看或撤回')
    expect(pageText()).toContain('查看我的入住申请')
    expect(backend().state.residenceApplications).toHaveLength(1)
  })

  it('CF-E2 撤回：待审核可撤回为已撤回；重复撤回被拒 5605', async () => {
    backend().state.residenceApplications.push({
      id: 1,
      residentId: 1,
      communityId: 1,
      houseId: 1,
      status: 'PENDING',
      reviewRemark: null,
      createdAt: '2026-09-21T10:00:00'
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    actAs('RESIDENT', 1)
    const { wrapper } = await mountPage(ResidentApplicationListView, '/resident/housing-lease')
    expect(pageText()).toContain('待审核')

    await clickButton(wrapper, '撤回申请')
    expect(backend().state.residenceApplications[0].status).toBe('WITHDRAWN')
    expect(pageText()).toContain('已撤回')

    /* 重复撤回：经 api 层断言后端 5605（页面已无撤回入口，属状态终态口径） */
    const residentApi = await import('@/common/resident/api/resident')
    await expect(residentApi.withdrawResidenceApplication(1)).rejects.toMatchObject({ code: 5605 })
  })
})

/* ============================================================================
 * 核心：CF-E3-A 审核通过 → 待签约 + 房屋未入住 + 自动建群聊
 * ==========================================================================*/

describe('模拟 E2E · CF-E3-A 审核通过只到「待签约」', () => {
  it('CF-E3-A 管理端审批通过：租约 TO_SIGN、房屋仍空置、自动建 LEASE_GROUP 群聊并通知居民', async () => {
    const leaseId = await submitAndApprove()

    const lease = backend().state.leases.find((item) => item.id === leaseId)
    expect(lease?.status).toBe('TO_SIGN')
    expect(lease?.agreementStatus).toBe('NONE')
    expect(lease?.tenantConfirmAt).toBeFalsy()

    /* 审核通过 ≠ 生效：房屋不置已入住（§6.2 硬口径） */
    const house = backend().state.houses.find((item) => item.id === lease?.houseId)
    expect(house?.status).toBe('VACANT')

    /* 自动建租约群聊（R68）：参与者含申请居民，且回填到租约 */
    const conversation = backend().state.conversations.find((item) => item.type === 'LEASE_GROUP')
    expect(conversation?.status).toBe('ACTIVE')
    expect(conversation?.relatedId).toBe(leaseId)
    expect(conversation?.participantIds).toContain(1)
    expect(lease?.conversationId).toBe(conversation?.id)

    /* 居民口径通知只到申请居民（owner_type=RESIDENT） */
    const notification = backend().state.notifications.find(
      (item) => item.sourceType === 'RESIDENCE_APPLICATION'
    )
    expect(notification?.ownerType).toBe('RESIDENT')
    expect(notification?.userId).toBe(1)

    /* 变更历史：CREATE */
    const changes = backend().state.leaseChanges.filter((item) => item.leaseId === leaseId)
    expect(changes.map((item) => item.changeType)).toContain('CREATE')
  })

  it('CF-E3-A 重复审核被拒 5202，且不产生第二条租约', async () => {
    await submitAndApprove()
    expect(backend().state.leases).toHaveLength(1)

    const residentApi = await import('@/common/resident/api/resident')
    actAs('ADMIN', 1)
    await expect(
      residentApi.approveResidenceApplication(1, {
        leaseStartDate: '2026-09-21',
        leaseEndDate: '2027-09-21',
        monthlyRent: 3000
      } as never)
    ).rejects.toMatchObject({ code: 5202 })
    expect(backend().state.leases).toHaveLength(1)
  })

  it('CF-E3-A 居民端「我的入住申请」显示已通过并给出已生成租约的入口', async () => {
    await submitAndApprove()

    actAs('RESIDENT', 1)
    await mountPage(ResidentApplicationListView, '/resident/housing-lease')

    expect(pageText()).toContain('已通过')
    expect(pageText()).toContain('已生成租约')
  })
})

/* ============================================================================
 * 核心：CF-E3-C 承租确认 → 发起协议 → 双方确认 → 生效
 * ==========================================================================*/

describe('模拟 E2E · CF-E3-C 发出协议与居民确认', () => {
  it('发出即管理方同意，不要求旧承租留痕且不自动生效', async () => {
    const leaseId = await submitAndApprove()
    actAs('ADMIN', 1)
    const api = await import('@/common/agreement/api/agreement')
    const issued = await api.createLeaseAgreement({ leaseId, templateId: 1 })
    expect(issued.status).toBe('PARTIAL')
    expect(issued.adminConfirmTime).toBeTruthy()
    expect(issued.tenantConfirmTime).toBeNull()
    expect(backend().state.leases[0].status).toBe('TO_SIGN')
  })
  it('居民没有独立确认承租入口，协议确认留在正文中', async () => {
    await submitAndApprove()
    actAs('RESIDENT', 1)
    const { wrapper } = await mountPage(ResidentLeaseListView, '/resident/housing-lease')
    expect(wrapper.findAll('button').some(b => b.text().trim() === '确认承租')).toBe(false)
    expect(pageText()).toContain('待签约')
  })
  it('居民本人同意后生效、房态翻转与通知，重复确认不重复流转', async () => {
    const leaseId = await activateLease()
    const lease = backend().state.leases.find(x => x.id === leaseId)!
    const agreement = backend().state.agreements[0]
    expect(agreement.tenantConfirmTime).toBeTruthy()
    expect(lease.status).toBe('ACTIVE')
    expect(backend().state.houses.find(x => x.id === lease.houseId)?.status).toBe('OCCUPIED')
    expect(backend().state.conversationMessages.some(x => x.senderType === 'SYSTEM')).toBe(true)
    const before = backend().state.leaseChanges.length
    const api = await import('@/common/agreement/api/agreement')
    await api.confirmLeaseAgreement(agreement.id, {})
    expect(backend().state.leaseChanges).toHaveLength(before)
  })
})

/* ============================================================================
 * 核心：CF-E3-D/E 首期支付（不顺延）与续期支付（顺延）
 * ==========================================================================*/

describe('模拟 E2E · CF-E3-D/E 支付与租期口径', () => {
  it('CF-E3-D 首期支付：金额=月租+押金、类型 FIRST；成功后租约状态与止期不变', async () => {
    const leaseId = await activateLease()
    const before = { ...backend().state.leases.find((item) => item.id === leaseId) }

    const paymentApi = await import('@/common/payment/api/payment')
    actAs('RESIDENT', 1)
    const order = (await paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)) as {
      paymentNo: string
      amount: number
      paymentType: string
      status: string
      alipayForm?: string | null
    }
    expect(order.paymentType).toBe('FIRST')
    expect(order.amount).toBe(3000 + 6000)
    expect(order.status).toBe('PENDING')
    expect(order.alipayForm).toBeTruthy()

    /* 沙箱付款成功（模拟用户在渠道完成支付） */
    backend().settlePayment(order.paymentNo)

    const after = backend().state.leases.find((item) => item.id === leaseId)
    expect(after?.status).toBe(before.status)
    expect(after?.endDate).toBe(before.endDate)
    expect(backend().state.payments[0].status).toBe('SUCCESS')

    /* 已支付后再发起首期 → 5904（仅 SUCCESS 触发；PENDING 幂等续接、CLOSED 可重发） */
    await expect(
      paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)
    ).rejects.toMatchObject({ code: 5904 })
  })

  it('CF-E3-D 首期 PENDING 单重开弹窗幂等续接：原样返回同一单并补回凭据（DEF-068）', async () => {
    const leaseId = await activateLease()
    const paymentApi = await import('@/common/payment/api/payment')
    actAs('RESIDENT', 1)

    const first = (await paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)) as {
      paymentNo: string
    }
    const resumed = (await paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)) as {
      paymentNo: string
      alipayForm?: string | null
    }
    expect(resumed.paymentNo).toBe(first.paymentNo)
    expect(backend().state.payments.filter((item) => item.paymentType === 'FIRST')).toHaveLength(1)
    expect(resumed.alipayForm).toBeTruthy()
  })

  it('CF-E3-D 首期支付弹窗（页面）：命中既有待支付单时直达支付步并以该单渠道续接', async () => {
    const leaseId = await activateLease()
    const paymentApi = await import('@/common/payment/api/payment')
    actAs('RESIDENT', 1)
    const pending = (await paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)) as {
      paymentNo: string
    }
    vi.spyOn(window, 'open').mockReturnValue({
      document: { open: () => undefined, write: () => undefined, close: () => undefined, forms: [] },
      focus: () => undefined
    } as unknown as Window)

    const lease = backend().state.leases.find((item) => item.id === leaseId)
    const wrapper = mount(LeaseFirstPaymentDialog, {
      props: { modelValue: false, lease: lease as never },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    /* 直达第 2 步：单号 + 续接说明（渠道以既有单为准），不再停在选渠道 */
    expect(pageText()).toContain(pending.paymentNo)
    expect(pageText()).toContain('已续接您此前未支付的首期支付单')
    expect(pageText()).toContain('渠道以该单为准（支付宝）')
    expect(pageText()).not.toContain('支付渠道')
    /* 只建了一张首期单（续接而非新建） */
    expect(backend().state.payments.filter((item) => item.paymentType === 'FIRST')).toHaveLength(1)

    /* 已知缺陷（本文件只记录不断言，避免把缺陷固化为期望）：
       轮询确认用 `GET /payment/orders/{paymentNo}` 的无凭据响应整体覆盖 payment
       （LeaseFirstPaymentDialog.vue L277-280 / LeaseRenewDialog.vue L245-248），
       首次轮询（开启即触发）后 `alipayForm`/`qrCode` 即被清空，
       「已在新窗口打开支付宝」被「支付页凭据暂未就绪」取代、微信二维码区域整块消失。
       详见交付报告「发现但未修的疑似产品缺陷」。 */
  })

  it('CF-E3-E 续期支付：金额=月租×月数、类型 RENEWAL；成功后止期顺延所选月数', async () => {
    const leaseId = await activateLease()
    const before = backend().state.leases.find((item) => item.id === leaseId)?.endDate ?? ''

    const paymentApi = await import('@/common/payment/api/payment')
    actAs('RESIDENT', 1)
    const order = (await paymentApi.createRenewPayment(leaseId, {
      months: 3,
      channel: 'ALIPAY'
    } as never)) as { paymentNo: string; amount: number; paymentType: string }
    expect(order.paymentType).toBe('RENEWAL')
    expect(order.amount).toBe(3000 * 3)

    backend().settlePayment(order.paymentNo)
    const after = backend().state.leases.find((item) => item.id === leaseId)?.endDate ?? ''
    expect(after).not.toBe(before)
    /* 顺延 3 个月（与首期"不顺延"成对照）：按本地日期口径比对 */
    const expected = new Date(`${before}T00:00:00`)
    expected.setMonth(expected.getMonth() + 3)
    expect(after).toBe(localDate(expected))
  })

  it('CF-E3-G 到期口径：止期已过首期被拒 5606（续期放行）；当天到期仍可付首期', async () => {
    const leaseId = await activateLease()
    const paymentApi = await import('@/common/payment/api/payment')
    const lease = backend().state.leases.find((item) => item.id === leaseId)
    actAs('RESIDENT', 1)

    /* 止期严格早于当天 → 5606；续期不受限（到期正是续期场景） */
    if (lease) lease.endDate = '2020-01-01'
    await expect(
      paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)
    ).rejects.toMatchObject({ code: 5606 })
    await expect(
      paymentApi.createRenewPayment(leaseId, { months: 1, channel: 'ALIPAY' } as never)
    ).resolves.toMatchObject({ paymentType: 'RENEWAL' })

    /* 当天到期仍可支付首期（DEF-069 收口点）；本地日期口径，避免 UTC 偏移跨日 */
    if (lease) lease.endDate = localToday()
    await expect(
      paymentApi.createFirstPayment(leaseId, { channel: 'ALIPAY' } as never)
    ).resolves.toMatchObject({ paymentType: 'FIRST' })
  })
})

/* ============================================================================
 * 核心：CF-E3-F 退租申请 → 审核通过 → 已搬出 + 群聊关闭
 *（押金退还 R72 段文档标注「待实现」，本文件跳过，不判通过与否）
 * ==========================================================================*/

describe('模拟 E2E · CF-E3-F 退租闭环（押金退还段待实现，跳过）', () => {
  it('CF-E3-F 非 ACTIVE 发起退租被拒 5602；重复发起被拒 5603', async () => {
    /* 未生效租约（TO_SIGN）发起退租 → 5602 */
    const leaseId = await submitAndApprove()
    const moveOutApi = await import('@/common/lease/api/leaseMoveOut')
    actAs('RESIDENT', 1)
    await expect(
      moveOutApi.createMoveOutApplication(leaseId, { moveOutDate: '2026-10-01', reason: '工作调动' } as never)
    ).rejects.toMatchObject({ code: 5602 })

    /* 已生效后：页面挂载退租弹窗 + 发起退租 → PENDING；重复发起 → 5603 */
    const activeLeaseId = await activateLease()
    actAs('RESIDENT', 1)
    const wrapper = mount(LeaseMoveOutDialog, {
      props: {
        modelValue: true,
        lease: backend().state.leases.find((item) => item.id === activeLeaseId) as never
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()
    expect(wrapper.text()).toContain('申请退租')

    await expect(
      moveOutApi.createMoveOutApplication(activeLeaseId, {
        moveOutDate: '2026-10-01',
        reason: '工作调动'
      } as never)
    ).resolves.toMatchObject({ status: 'PENDING' })
    await expect(
      moveOutApi.createMoveOutApplication(activeLeaseId, {
        moveOutDate: '2026-10-02',
        reason: '再次发起'
      } as never)
    ).rejects.toMatchObject({ code: 5603 })
  })

  it('CF-E3-F 管理端审核通过：租约已搬出、房屋空置、群聊关闭（发消息 5604）', async () => {
    const leaseId = await activateLease()
    const moveOutApi = await import('@/common/lease/api/leaseMoveOut')
    actAs('RESIDENT', 1)
    const application = (await moveOutApi.createMoveOutApplication(leaseId, {
      moveOutDate: '2026-10-01',
      reason: '工作调动'
    } as never)) as { id: number }

    /* 三端交替：管理端退租申请列表可见 */
    actAs('ADMIN', 1)
    const { wrapper } = await mountPage(AdminMoveOutApplicationView, '/admin/housing-lease')
    expect(pageText()).toContain('演示居民')

    await moveOutApi.approveMoveOutApplication(application.id, { housingStatus: 'AVAILABLE' } as never)

    const lease = backend().state.leases.find((item) => item.id === leaseId)
    expect(lease?.status).toBe('MOVED_OUT')
    expect(backend().state.houses.find((item) => item.id === lease?.houseId)?.status).toBe('VACANT')
    expect(backend().state.notifications.some((item) => item.sourceType === 'LEASE_RECORD')).toBe(true)

    /* 群聊关闭 + 停收停发（5604），历史消息仍可读 */
    const conversationApi = await import('@/common/conversation/api/conversation')
    const conversationId = lease?.conversationId ?? 0
    expect(backend().state.conversations.find((item) => item.id === conversationId)?.status).toBe('CLOSED')
    await expect(
      conversationApi.sendConversationMessage(conversationId, { content: '还能发吗' } as never)
    ).rejects.toMatchObject({ code: 5604 })
    await expect(conversationApi.listConversationMessages(conversationId)).resolves.toBeInstanceOf(Array)

    expect(wrapper.exists()).toBe(true)
  })

  it('CF-E3-F 退租驳回：租约保持已生效', async () => {
    const leaseId = await activateLease()
    const moveOutApi = await import('@/common/lease/api/leaseMoveOut')
    actAs('RESIDENT', 1)
    const application = (await moveOutApi.createMoveOutApplication(leaseId, {
      moveOutDate: '2026-10-01',
      reason: '出差'
    } as never)) as { id: number }

    actAs('ADMIN', 1)
    await moveOutApi.rejectMoveOutApplication(application.id, { reason: '当期房源紧张' } as never)

    expect(backend().state.leases.find((item) => item.id === leaseId)?.status).toBe('ACTIVE')
    expect(backend().state.moveOutApplications[0].status).toBe('REJECTED')
  })

  it('CF-E3-F 归档：已搬出租约可归档为只读终态，群聊保持关闭', async () => {
    const leaseId = await activateLease()
    const moveOutApi = await import('@/common/lease/api/leaseMoveOut')
    const leaseApi = await import('@/common/lease/api/lease')
    actAs('RESIDENT', 1)
    const application = (await moveOutApi.createMoveOutApplication(leaseId, {
      moveOutDate: '2026-10-01',
      reason: '工作调动'
    } as never)) as { id: number }
    actAs('ADMIN', 1)
    await moveOutApi.approveMoveOutApplication(application.id, { housingStatus: 'AVAILABLE' } as never)
    await leaseApi.updateLeaseStatus(leaseId, { status: 'ARCHIVED' } as never)

    const lease = backend().state.leases.find((item) => item.id === leaseId)
    expect(lease?.status).toBe('ARCHIVED')
    expect(backend().state.conversations.find((item) => item.id === lease?.conversationId)?.status).toBe(
      'CLOSED'
    )
  })
})

/* ============================================================================
 * 延展：CF-E11 通知身份空间隔离（撞号双向不可见，DEF-067）
 * ==========================================================================*/

describe('模拟 E2E · CF-E11 通知身份空间隔离', () => {
  it('CF-E11 撞号对 resident1(id=1) 与超管(id=1)：居民口径通知不得出现在超管列表，反之亦然', async () => {
    await submitAndApprove()
    actAs('RESIDENT', 1)

    expect(
      backend().state.notifications.filter((item) => item.ownerType === 'RESIDENT' && item.userId === 1).length
    ).toBeGreaterThan(0)

    /* 管理侧（超管，同 id=1）自造一条管理员口径通知 */
    backend().state.notifications.push({
      id: 999,
      ownerType: 'USER',
      userId: 1,
      seq: 999,
      title: '管理员口径通知',
      content: '仅超管可见',
      sourceType: 'SYSTEM',
      sourceId: null,
      isRead: false,
      createdAt: '2026-09-21T12:00:00'
    })

    /* 居民端消息中心：只看 RESIDENT 空间 */
    actAs('RESIDENT', 1)
    const residentView = await mountPage(ResidentNotificationListView, '/resident/notifications')
    expect(pageText()).toContain('入住申请已通过')
    expect(pageText()).not.toContain('管理员口径通知')
    residentView.wrapper.unmount()
    document.body.innerHTML = ''

    /* 超管消息中心：只看 USER 空间，不得出现居民口径通知 */
    actAs('SUPER_ADMIN', 1)
    await mountPage(AdminNotificationListView, '/admin/notifications')
    expect(pageText()).toContain('管理员口径通知')
    expect(pageText()).not.toContain('入住申请已通过')
  })

  it('CF-E11 未读/补拉同口径收口（owner_type 二元组）', async () => {
    backend().state.notifications.push(
      {
        id: 1,
        ownerType: 'RESIDENT',
        userId: 1,
        seq: 1,
        title: '居民通知',
        content: '',
        sourceType: 'SYSTEM',
        sourceId: null,
        isRead: false,
        createdAt: '2026-09-21T10:00:00'
      },
      {
        id: 2,
        ownerType: 'USER',
        userId: 1,
        seq: 2,
        title: '员工通知',
        content: '',
        sourceType: 'SYSTEM',
        sourceId: null,
        isRead: false,
        createdAt: '2026-09-21T10:01:00'
      }
    )
    const notificationApi = await import('@/common/messaging/api/notification')

    actAs('RESIDENT', 1)
    const residentUnread = await notificationApi.listUnreadNotifications()
    expect(residentUnread.notifications.map((item) => item.title)).toEqual(['居民通知'])

    actAs('STAFF', 1)
    const staffUnread = await notificationApi.listUnreadNotifications()
    expect(staffUnread.notifications.map((item) => item.title)).toEqual(['员工通知'])
  })
})

/* ============================================================================
 * 延展：CF-E4 工单 / CF-E5 公告 / CF-E6 反馈 / CF-E7 预约 / CF-E8 评价
 * ==========================================================================*/

describe('模拟 E2E · 延展业务（公告/反馈/预约/评价/工单）', () => {
  it('CF-E5 公告定向：居民可见本社区公告，游客仅可见含 GUEST 目标的公告；过期自动下线', async () => {
    backend().state.notices.push(
      {
        id: 1,
        title: '社区停水通知',
        content: '本周六停水',
        status: 'PUBLISHED',
        noticeType: 'ANNOUNCEMENT',
        priority: 'IMPORTANT',
        isPinned: false,
        targets: [{ targetType: 'COMMUNITY', targetId: 1 }],
        publishTime: '2026-09-21T09:00:00',
        expireTime: null,
        viewCount: 0,
        createdAt: '2026-09-21T09:00:00'
      },
      {
        id: 2,
        title: '对外招租公告',
        content: '欢迎看房',
        status: 'PUBLISHED',
        noticeType: 'ANNOUNCEMENT',
        priority: 'NORMAL',
        isPinned: true,
        targets: [{ targetType: 'GUEST', targetId: null }],
        publishTime: '2026-09-21T09:00:00',
        expireTime: null,
        viewCount: 0,
        createdAt: '2026-09-21T09:00:00'
      }
    )

    /* 游客端：仅 GUEST 目标可见（不得泄露社区内部公告） */
    actAs('GUEST', 0)
    await mountPage(GuestNoticeListView, '/guest/notices')
    expect(pageText()).toContain('对外招租公告')
    expect(pageText()).not.toContain('社区停水通知')
    document.body.innerHTML = ''

    /* 居民端：本社区公告可见（经 api 列表口径） */
    actAs('RESIDENT', 1)
    const noticeApi = await import('@/common/notice/api/notice')
    const residentNotices = await noticeApi.listNotices({ page: 1, size: 20 })
    expect(residentNotices.records.map((item) => item.title)).toContain('社区停水通知')

    /* 有效期止自动下线（定时任务） */
    backend().state.notices[0].expireTime = '2020-01-01'
    expect(backend().expireNotices()).toBe(1)
    expect(backend().state.notices[0].status).toBe('EXPIRED')
  })

  it('CF-E6 反馈：受理 → 会话中 → 发消息 → 办结 → 会话只读（再发消息被拒 5004）', async () => {
    const feedbackApi = await import('@/common/feedback/api/feedback')
    actAs('RESIDENT', 1)
    const feedback = (await feedbackApi.createFeedback({
      communityId: 1,
      title: '楼道灯不亮',
      content: '三楼楼道灯损坏'
    } as never)) as { id: number }
    expect(backend().state.feedbacks[0].status).toBe('PENDING')

    /* 待受理 → 会话中：接口设计 §9.6 无「受理」端点，管理方首次回消息即自动推进 */
    actAs('ADMIN', 1)
    await feedbackApi.sendFeedbackMessage(feedback.id, { content: '已收到，今日上门查看' } as never)
    expect(backend().state.feedbacks[0].status).toBe('IN_SESSION')

    actAs('RESIDENT', 1)
    await feedbackApi.sendFeedbackMessage(feedback.id, { content: '补充：晚上特别黑' } as never)
    expect(backend().state.feedbacks[0].messages).toHaveLength(2)

    actAs('ADMIN', 1)
    const view = await mountPage(AdminFeedbackManageView, '/admin/feedbacks')
    expect(pageText()).toContain('楼道灯不亮')
    await feedbackApi.closeFeedback(feedback.id, { conclusion: '已更换灯具' } as never)
    expect(backend().state.feedbacks[0].status).toBe('CLOSED')

    /* 办结后只读：不可再发消息（5004 STATE_TRANSITION_INVALID），历史消息仍可查 */
    await expect(
      feedbackApi.sendFeedbackMessage(feedback.id, { content: '还能发吗' } as never)
    ).rejects.toMatchObject({ code: 5004 })
    await expect(feedbackApi.listFeedbackMessages(feedback.id)).resolves.toHaveLength(2)
    expect(view.wrapper.exists()).toBe(true)
  })

  it('CF-E7 资源预约：首约待审核；同资源同时段再约被拒 5401（时段冲突）', async () => {
    const reservationApi = await import('@/common/reservation/api/reservation')
    actAs('RESIDENT', 1)

    const today = localToday()
    const slots = await reservationApi.listAvailableTimeslots(1, { startDate: today, endDate: today })
    expect(slots.length).toBeGreaterThan(0)

    await expect(
      reservationApi.createReservation({ resourceId: 1, timeslotId: slots[0].timeslotId } as never)
    ).resolves.toMatchObject({ status: 'PENDING' })

    await expect(
      reservationApi.createReservation({ resourceId: 1, timeslotId: slots[0].timeslotId } as never)
    ).rejects.toMatchObject({ code: 5401 })
    expect(backend().state.reservations).toHaveLength(1)
  })

  it('CF-E7 预约页面：资源名可见且页面可挂载（页面操作）', async () => {
    actAs('RESIDENT', 1)
    const { wrapper } = await mountPage(
      ResidentReservationCreateView,
      '/resident/reservations/create?resourceId=1'
    )
    expect(pageText()).toContain('健身房')
    expect(wrapper.exists()).toBe(true)
  })

  it('CF-E8 评价：已完成工单可评价一次；重复评价被拒 5502；未完成工单评价被拒 5501', async () => {
    const evaluationApi = await import('@/common/evaluation/api/evaluation')
    actAs('RESIDENT', 1)
    backend().state.workOrders.push(
      {
        id: 1,
        orderNo: 'WO1',
        residentId: 1,
        communityId: 1,
        categoryId: 1,
        title: '水龙头漏水',
        description: '厨房水龙头滴水',
        status: 'COMPLETED',
        assigneeId: 1,
        assigneeName: '服务人员一号',
        createdAt: '2026-09-20T10:00:00'
      },
      {
        id: 2,
        orderNo: 'WO2',
        residentId: 1,
        communityId: 1,
        categoryId: 1,
        title: '未完成工单',
        description: '-',
        status: 'IN_PROGRESS',
        assigneeId: 1,
        assigneeName: '服务人员一号',
        createdAt: '2026-09-20T10:00:00'
      }
    )

    await expect(
      evaluationApi.submitEvaluation(2, { score: 5, content: '挺好' } as never)
    ).rejects.toMatchObject({ code: 5501 })

    await expect(
      evaluationApi.submitEvaluation(1, { score: 2, content: '响应太慢' } as never)
    ).resolves.toMatchObject({ score: 2 })

    await expect(
      evaluationApi.submitEvaluation(1, { score: 5, content: '再评一次' } as never)
    ).rejects.toMatchObject({ code: 5502 })
    expect(backend().state.evaluations).toHaveLength(1)
  })

  it('CF-E4 工单主链五段三端交接：待受理→待派单→已派单→已接单→处理中→待确认→已完成；非法流转 5302', async () => {
    const workorderApi = await import('@/common/workorder/api/workorder')

    /* 居民端提交（页面挂载 + 提交） */
    actAs('RESIDENT', 1)
    const { wrapper } = await mountPage(ResidentWorkOrderCreateView, '/resident/work-orders/create')
    expect(pageText()).toContain('提交工单')

    const order = (await workorderApi.submitWorkOrder({
      communityId: 1,
      categoryId: 1,
      title: '水管漏水',
      description: '厨房水管接口渗水'
    } as never)) as { id: number }
    expect(backend().state.workOrders[0].status).toBe('PENDING')

    /* 管理端：受理（接口设计未定义端点，按架构 §6.1 状态图以沙箱动作推进）→ 派单 */
    actAs('ADMIN', 1)
    const adminView = await mountPage(AdminWorkOrderListView, '/admin/work-orders')
    expect(pageText()).toContain('水管漏水')
    backend().acceptIncomingWorkOrder(order.id)
    expect(backend().state.workOrders[0].status).toBe('TO_ASSIGN')

    /* 非法流转：待派单阶段直接「接单」→ 5302（工单状态流转非法） */
    actAs('STAFF', 1)
    await expect(workorderApi.acceptWorkOrder(order.id)).rejects.toMatchObject({ code: 5302 })

    actAs('ADMIN', 1)
    await workorderApi.assignWorkOrder(order.id, { assigneeId: 1, assigneeName: '服务人员一号' } as never)
    expect(backend().state.workOrders[0].status).toBe('ASSIGNED')

    /* 服务人员端：接单 → 处理 → 提交结果 */
    actAs('STAFF', 1)
    await workorderApi.acceptWorkOrder(order.id)
    expect(backend().state.workOrders[0].status).toBe('ACCEPTED')
    await workorderApi.processWorkOrder(order.id)
    expect(backend().state.workOrders[0].status).toBe('IN_PROGRESS')
    await workorderApi.completeWorkOrder(order.id, { remark: '更换密封圈' })
    expect(backend().state.workOrders[0].status).toBe('TO_CONFIRM')

    /* 居民端确认 → 已完成 */
    actAs('RESIDENT', 1)
    await workorderApi.confirmWorkOrder(order.id)
    expect(backend().state.workOrders[0].status).toBe('COMPLETED')

    /* 每次流转写处理记录（时间线） */
    const timeline = await workorderApi.getWorkOrderTimeline(order.id)
    expect(timeline.length).toBeGreaterThanOrEqual(6)
    expect(adminView.wrapper.exists()).toBe(true)
    expect(wrapper.exists()).toBe(true)
  })

  it('CF-E3-B 群聊内预约看房：状态待确认且与租约双向关联', async () => {
    const leaseId = await submitAndApprove()
    const leaseApi = await import('@/common/lease/api/lease')
    const lease = backend().state.leases.find((item) => item.id === leaseId)

    actAs('RESIDENT', 1)
    const appointment = (await leaseApi.createConversationViewing(lease?.conversationId ?? 0, {
      housingId: 1,
      appointmentDate: '2026-10-01',
      startTime: '10:00',
      endTime: '11:00'
    } as never)) as { leaseId?: number | null; status: string }

    expect(appointment.status).toBe('TO_CONFIRM')
    expect(appointment.leaseId).toBe(leaseId)
  })
})

/* ============================================================================
 * 其余端到端流程：E1 结构初始化 / E9 看板数据核对 / E10 越权拒绝 / E12 看房预约
 * ==========================================================================*/

describe('模拟 E2E · CF-E1 社区初始化与结构配置', () => {
  it('CF-E1 超管建「社区→楼栋→单元→房屋→公共资源→时段」四级结构，逐级可读', async () => {
    const communityApi = await import('@/common/community/api/community')
    actAs('SUPER_ADMIN', 1)

    const community = (await communityApi.createCommunity({ name: '二期社区' } as never)) as { id: number }
    const building = (await communityApi.createBuilding({
      communityId: community.id,
      buildingNumber: '1'
    } as never)) as { id: number }
    const unit = (await communityApi.createUnit({
      buildingId: building.id,
      unitNumber: '1'
    } as never)) as { id: number }
    const house = (await communityApi.createHouse({
      communityId: community.id,
      buildingId: building.id,
      unitId: unit.id,
      houseNumber: '101'
    } as never)) as { id: number }
    const resource = (await communityApi.createResource({
      communityId: community.id,
      name: '社区活动室'
    } as never)) as { id: number }
    await communityApi.createTimeslot(resource.id, {
      slotDate: localToday(),
      startTime: '14:00',
      endTime: '16:00',
      capacity: 1
    } as never)

    /* 四级结构逐级可读（结构链闭合，不是各自独立的孤儿数据） */
    const buildings = await communityApi.getBuildingList(community.id)
    expect(buildings.records.map((item) => item.id)).toContain(building.id)
    const units = await communityApi.getUnitList(building.id)
    expect(units.records.map((item) => item.id)).toContain(unit.id)
    const houses = await communityApi.getHouseList(unit.id)
    expect(houses.records.map((item) => item.id)).toContain(house.id)
    const resources = await communityApi.getResourceList(community.id)
    expect(resources.records.map((item) => item.id)).toContain(resource.id)
    const timeslots = await communityApi.getTimeslotList(resource.id)
    expect(timeslots.records).toHaveLength(1)

    /* 新房屋为空置状态，可被后续入住申请使用 */
    expect(backend().state.houses.find((item) => item.id === house.id)?.status).toBe('VACANT')
  })
})

describe('模拟 E2E · CF-E9 运营看板数据核对', () => {
  it('CF-E9 看板统计与库内真实数据逐项一致（工单/房屋/租约）', async () => {
    const statisticsApi = await import('@/common/statistics/api/statistics')
    const workorderApi = await import('@/common/workorder/api/workorder')
    actAs('ADMIN', 1)

    const first = (await workorderApi.submitWorkOrder({
      communityId: 1,
      categoryId: 1,
      title: 'A 单',
      description: 'a'
    } as never)) as { id: number }
    const second = (await workorderApi.submitWorkOrder({
      communityId: 1,
      categoryId: 1,
      title: 'B 单',
      description: 'b'
    } as never)) as { id: number }
    /* 受理 + 派单后：待受理 1 单、处理中 1 单 */
    backend().acceptIncomingWorkOrder(second.id)
    await workorderApi.assignWorkOrder(second.id, { assigneeId: 1, assigneeName: '服务人员一号' } as never)

    const stats = await statisticsApi.getWorkOrderStats()
    const orders = backend().state.workOrders
    expect(orders).toHaveLength(2)
    expect(first.id).not.toBe(second.id)
    expect(stats.total).toBe(orders.length)
    for (const [status, count] of Object.entries(stats.byStatus)) {
      expect(count).toBe(orders.filter((item) => item.status === status).length)
    }

    /* 看板卡片：房屋总数/已入住/工单口径与库内一致（不是写死的样例数） */
    const dashboard = await statisticsApi.getDashboardStats()
    expect(dashboard.houseCount).toBe(backend().state.houses.length)
    expect(dashboard.occupiedHouseCount).toBe(
      backend().state.houses.filter((item) => item.status === 'OCCUPIED').length
    )
    expect(dashboard.workOrderTotal).toBe(orders.length)
    expect(dashboard.workOrderPending).toBe(1)
    expect(dashboard.workOrderProcessing).toBe(1)
    expect(dashboard.communityCount).toBe(backend().state.communities.length)
    expect(dashboard.activeLeaseCount).toBe(
      backend().state.leases.filter((item) => item.status === 'ACTIVE').length
    )
  })
})

describe('模拟 E2E · CF-E10 权限与越权拒绝', () => {
  it('管理端不能代居民同意协议，其他居民不能确认他人协议', async () => {
    const leaseId = await submitAndApprove()
    const api = await import('@/common/agreement/api/agreement')
    actAs('ADMIN', 1)
    const issued = await api.createLeaseAgreement({ leaseId, templateId: 1 })
    for (const role of ['ADMIN', 'SUPER_ADMIN'] as const) {
      actAs(role, 1)
      await expect(api.confirmLeaseAgreement(issued.id, {})).rejects.toMatchObject({ code: 403 })
    }
    actAs('RESIDENT', 2)
    await expect(api.confirmLeaseAgreement(issued.id, {})).rejects.toMatchObject({ code: 403 })
  })
})

describe('模拟 E2E · CF-E12 房源上架与注册居民看房预约', () => {
  it('CF-E12 房源上架 → 游客浏览 → 居民预约（待确认）→ 管理方确认（已预约）→ 完成；违约分支留痕', async () => {
    const housingApi = await import('@/common/housing/api/housing')
    const communityApi = await import('@/common/community/api/community')

    /* 管理端上架一套房源 */
    actAs('ADMIN', 1)
    const house = (await communityApi.createHouse({
      communityId: 1,
      buildingId: backend().state.buildings[0].id,
      unitId: backend().state.units[0].id,
      houseNumber: '202'
    } as never)) as { id: number }
    const housing = (await housingApi.createHousing({
      houseId: house.id,
      communityId: 1,
      title: '2 号楼1 单元202 在租房源',
      monthlyRent: 2600,
      deposit: 5200
    } as never)) as { id: number }
    expect(backend().state.housings.some((item) => item.id === housing.id)).toBe(true)

    /* 游客只读浏览，创建预约必须被拒绝 */
    actAs('GUEST', 0)
    const slots = await housingApi.listViewingAvailableSlots(housing.id, {} as never)
    expect(Array.isArray(slots)).toBe(true)
    await expect(housingApi.createViewingAppointment({
      housingId: housing.id,
      appointmentDate: localToday(),
      startTime: '10:00',
      endTime: '11:00'
    })).rejects.toMatchObject({ code: 403 })

    /* 注册居民预约看房 → 待确认 */
    actAs('RESIDENT', 1)
    const appointment = (await housingApi.createViewingAppointment({
      housingId: housing.id,
      contactPhone: '13900000001',
      appointmentDate: localToday(),
      startTime: '10:00',
      endTime: '11:00'
    })) as { id: number; status: string; userId?: number }
    expect(appointment.status).toBe('TO_CONFIRM')
    expect(appointment.userId).toBe(1)

    /* 管理方确认（可同时指定带看人）→ 已预约 */
    actAs('ADMIN', 1)
    await housingApi.confirmViewingAppointment(appointment.id, {
      assigneeId: 1,
      assigneeName: '服务人员一号'
    } as never)
    expect(backend().state.viewingAppointments[0].status).toBe('RESERVED')
    expect(backend().state.viewingAppointments[0].assigneeId).toBe(1)

    /* 线下带看完成 → 已完成 */
    await housingApi.completeViewingAppointment(appointment.id, {} as never)
    expect(backend().state.viewingAppointments[0].status).toBe('COMPLETED')

    /* 违约分支：注册居民新增一条预约并标记违约（留痕可查） */
    actAs('RESIDENT', 1)
    const second = (await housingApi.createViewingAppointment({
      housingId: housing.id,
      contactPhone: '13900000002',
      appointmentDate: localToday(),
      startTime: '15:00',
      endTime: '16:00'
    })) as { id: number }
    actAs('ADMIN', 1)
    await housingApi.confirmViewingAppointment(second.id, { assigneeId: 1 } as never)
    await housingApi.violateViewingAppointment(second.id, { reason: '未按时到访' } as never)
    const violated = backend().state.viewingAppointments.find((item) => item.id === second.id)
    expect(violated?.status).toBe('VIOLATED')

    /* 服务人员端「我的带看」只看被分配的预约 */
    actAs('STAFF', 1)
    const mine = await housingApi.listViewingAppointments({})
    expect(mine.records.every((item) => item.assigneeId === 1)).toBe(true)
  })
})

/* ============================================================================
 * 兜底：假后端自检（避免用例"看起来过了"其实没走到业务）
 * ==========================================================================*/

describe('模拟 E2E · 假后端自检', () => {
  it('未实现的 api 调用会抛出明确错误（不被静默吞掉）', async () => {
    const communityApi = (await import('@/common/community/api/community')) as unknown as Record<string, unknown>
    /* 假后端只实现被流程用到的成员：deleteCommunity 未实现，必须大声失败而不是静默 undefined */
    const unknownFn = communityApi.deleteCommunity as (...args: unknown[]) => unknown
    expect(() => unknownFn()).toThrowError(/假后端未实现/)
  })

  it('角色辅助：actAs 切换身份后假后端按新身份过滤数据', async () => {
    backend().state.residenceApplications.push({
      id: 1,
      residentId: 1,
      communityId: 1,
      houseId: 1,
      status: 'PENDING',
      createdAt: '2026-09-21T10:00:00'
    })
    const residentApi = await import('@/common/resident/api/resident')

    actAs('RESIDENT', 1)
    expect((await residentApi.getResidenceApplicationList({})).total).toBe(1)

    actAs('RESIDENT', 2)
    expect((await residentApi.getResidenceApplicationList({})).total).toBe(0)

    const role: FakeRole = 'ADMIN'
    actAs(role, 1)
    expect((await residentApi.getResidenceApplicationList({})).total).toBe(1)
  })
})
