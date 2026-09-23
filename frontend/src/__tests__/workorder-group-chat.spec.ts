import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import type { FakeConversation, FakeWorkOrder } from '@/test/fakeBackend'
import {
  acceptWorkOrder,
  assignWorkOrder,
  closeWorkOrder,
  completeWorkOrder,
  confirmWorkOrder,
  getWorkOrder,
  intakeWorkOrder,
  processWorkOrder
} from '@/api/workorder'
import { getConversation, listConversationMessages, sendConversationMessage } from '@/api/conversation'

/**
 * CF-E4 / R74 工单临时群聊会话（接口设计 §9.21.1~§9.21.4）接口级断言：
 * 受理即建群（成员＝居民提交人 + 受理管理员）→ 派单自动入群（三方齐、幂等）→
 * 仅参与者可见（非参与者 403）→ 终审结束回收为只读（CLOSED + closedAt，
 * 历史可读、再发言 5604）。
 */

const backend = () => getFakeBackend()

/* 工单与会话两条链路都走假后端（前端只调既有 api 函数，契约不变） */
vi.mock('@/api/workorder', async () => (await import('@/test/fakeBackend')).fakeApi('workorder'))
vi.mock('@/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))

function seedOrder(status: FakeWorkOrder['status'] = 'PENDING'): void {
  backend().state.workOrders.push({
    id: 1,
    orderNo: 'WO202609220003',
    residentId: 1,
    communityId: 1,
    categoryId: 1,
    title: '厨房水龙头持续漏水',
    description: '水龙头根部渗水，柜内有积水',
    status,
    assigneeId: null,
    assigneeName: null,
    createdAt: '2026-09-22T09:00:00'
  })
}

function groupOf(): FakeConversation | undefined {
  return backend().state.conversations.find((item) => item.type === 'WORK_ORDER_GROUP')
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  sessionStorage.clear()
})

describe('工单临时群聊（R74 · §9.21.2）', () => {
  it('受理前无群与 conversationId；受理即建群，成员＝居民提交人 + 受理管理员', async () => {
    seedOrder()

    /* 受理前：无群、工单不带 conversationId（前端据此不渲染面板） */
    expect(backend().state.conversations).toHaveLength(0)
    expect((await getWorkOrder(1)).conversationId ?? null).toBeNull()

    const updated = await intakeWorkOrder(1, { remark: '已安排核实' })

    expect(updated.status).toBe('TO_ASSIGN')
    const group = groupOf()
    expect(group, '受理未建群').toBeTruthy()
    expect(updated.conversationId).toBe(group?.id)
    expect(group?.title).toBe('工单沟通·WO202609220003')
    expect(group?.relatedId).toBe(1)
    expect(group?.status).toBe('ACTIVE')

    const detail = await getConversation(group!.id)
    expect(detail.participants?.map((item) => `${item.role}:${item.userId}`).sort()).toEqual([
      'ADMIN:1',
      'RESIDENT:1'
    ])
    expect(detail.participantCount).toBe(2)

    /* 受理通知落到居民提交人（owner_type=RESIDENT 身份空间） */
    expect(
      backend().state.notifications.some(
        (item) => item.ownerType === 'RESIDENT' && item.userId === 1 && item.title === '工单已受理'
      )
    ).toBe(true)
  })

  it('非 PENDING 再次受理 → 5302，且不重复建群', async () => {
    seedOrder()
    await intakeWorkOrder(1)

    await expect(intakeWorkOrder(1)).rejects.toMatchObject({ code: 5302 })
    expect(backend().state.conversations.filter((item) => item.type === 'WORK_ORDER_GROUP')).toHaveLength(1)
  })

  it('STAFF/RESIDENT 不可受理（403）', async () => {
    seedOrder()
    actAs('STAFF', 1)
    await expect(intakeWorkOrder(1)).rejects.toMatchObject({ code: 403 })
    actAs('RESIDENT', 1)
    await expect(intakeWorkOrder(1)).rejects.toMatchObject({ code: 403 })
    expect(backend().state.workOrders[0].status).toBe('PENDING')
  })

  it('派单后被派服务人员自动入群（三方齐），重复派单不重复入群', async () => {
    seedOrder()
    await intakeWorkOrder(1)

    await assignWorkOrder(1, { assigneeId: 1 })
    const afterAssign = await getConversation(groupOf()!.id)
    expect(afterAssign.participants?.map((item) => `${item.role}:${item.userId}`).sort()).toEqual([
      'ADMIN:1',
      'RESIDENT:1',
      'STAFF:1'
    ])

    /* 改派同一人：幂等，不重复追加 */
    await assignWorkOrder(1, { assigneeId: 1 })
    const afterReassign = await getConversation(groupOf()!.id)
    expect(afterReassign.participantCount).toBe(3)
  })

  it('非参与者不可见：未入群的服务人员与未参与的管理员取会话/读消息/发言均 403', async () => {
    seedOrder()
    await intakeWorkOrder(1)
    const groupId = groupOf()!.id
    await sendConversationMessage(groupId, { content: '已受理，正在安排服务人员' })

    /* 服务人员：派单前不在群 */
    actAs('STAFF', 1)
    await expect(getConversation(groupId)).rejects.toMatchObject({ code: 403 })
    await expect(listConversationMessages(groupId)).rejects.toMatchObject({ code: 403 })
    await expect(sendConversationMessage(groupId, { content: '我还没被派单' })).rejects.toMatchObject({
      code: 403
    })

    /* 另一管理员（未受理该单、未入群）同样不可见 */
    actAs('ADMIN', 2)
    await expect(getConversation(groupId)).rejects.toMatchObject({ code: 403 })

    /* 参与者（居民提交人）正常可见——身份空间二元组判定，不与管理员 1 撞号 */
    actAs('RESIDENT', 1)
    const messages = await listConversationMessages(groupId)
    expect(messages).toHaveLength(1)
    const mine = await sendConversationMessage(groupId, { content: '麻烦尽快安排' })
    expect(mine.senderId).toBe(1)
    actAs('ADMIN', 1)
    expect(await listConversationMessages(groupId)).toHaveLength(2)
  })

  it('终审结束仅 COMPLETED 可关闭；关闭后会话回收为只读，历史可读、再发言 5604、重复关闭不报错', async () => {
    seedOrder()
    await intakeWorkOrder(1)
    await assignWorkOrder(1, { assigneeId: 1 })
    /* 群内先留一条历史消息，供回收后「历史仍可读」断言 */
    await sendConversationMessage(groupOf()!.id, { content: '已受理，正在安排服务人员' })

    /* 前置收窄：非 COMPLETED 关闭 → 5302 */
    await expect(closeWorkOrder(1)).rejects.toMatchObject({ code: 5302 })

    actAs('STAFF', 1)
    await acceptWorkOrder(1)
    await processWorkOrder(1)
    await completeWorkOrder(1, { remark: '已更换密封圈' })
    actAs('RESIDENT', 1)
    await confirmWorkOrder(1)

    actAs('ADMIN', 1)
    await closeWorkOrder(1)
    expect(backend().state.workOrders[0].status).toBe('CLOSED')

    const group = groupOf()!
    expect(group.status).toBe('CLOSED')
    expect(group.closedAt).toBeTruthy()
    /* 回收 ≠ 物理删除：会话与历史消息仍在 */
    const detail = await getConversation(group.id)
    expect(detail.status).toBe('CLOSED')
    expect(await listConversationMessages(group.id)).toHaveLength(1)
    /* 再发言被拒 5604 */
    await expect(sendConversationMessage(group.id, { content: '还能说话吗' })).rejects.toMatchObject({
      code: 5604
    })
    /* 前置收窄的另一面：已关闭工单再次关闭仍属非法流转 5302（会话保持 CLOSED 不被反复改写） */
    await expect(closeWorkOrder(1)).rejects.toMatchObject({ code: 5302 })
    expect(group.status).toBe('CLOSED')
  })
})
