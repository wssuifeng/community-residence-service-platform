import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { actAs, getFakeBackend, installFakeBackend } from '@/test/fakeBackend'
import { openDirectConversation, ensureLeaseGroupConversation, getConversation } from '@/common/conversation/api/conversation'

/**
 * 后端新交付契约的前端接入（第一层：接口级）——
 * ①`POST /conversations/direct` 放开 ADMIN/SUPER_ADMIN + `residentId`（幂等 / 缺居民 400 / 跨小区 403）
 * ②`POST /conversations/lease-groups`（幂等建/取 LEASE_GROUP 并回写 lease_record.conversation_id）
 */
vi.mock('@/common/conversation/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/common/lease/api/lease', async () => (await import('@/test/fakeBackend')).fakeApi('lease'))

const backend = () => getFakeBackend()

function seedResident(id: number, name: string): void {
  backend().state.residents.push({
    id,
    name,
    phone: `1380000000${id}`,
    communityId: 1,
    status: 'ACTIVE'
  } as never)
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
  seedResident(1, '演示居民')
  seedResident(2, '李四')
})

afterEach(() => {
  sessionStorage.clear()
})

describe('① 管理端代居民发起直通会话（DEF-076）', () => {
  it('管理端指定 residentId 可发起，重复调用命中同一条会话（幂等）', async () => {
    const first = await openDirectConversation({ communityId: 1, residentId: 2 })
    expect(first.type).toBe('DIRECT')
    expect(first.communityId).toBe(1)

    const second = await openDirectConversation({ communityId: 1, residentId: 2 })
    expect(second.id).toBe(first.id)
    expect(
      backend().state.conversations.filter((item) => item.type === 'DIRECT' && item.communityId === 1)
    ).toHaveLength(1)

    /* 参与者：该居民（居民身份空间）+ 小区管理员 */
    const detail = await getConversation(first.id)
    expect(detail.participants?.some((item) => item.ownerType === 'RESIDENT' && item.userId === 2)).toBe(
      true
    )
  })

  it('管理端未指定居民 → 400「请指定直通会话的居民」', async () => {
    await expect(openDirectConversation({ communityId: 1 })).rejects.toMatchObject({
      code: 400,
      message: '请指定直通会话的居民'
    })
  })

  it('管理端发起未绑定小区 → 403', async () => {
    await expect(openDirectConversation({ communityId: 99, residentId: 2 })).rejects.toMatchObject({
      code: 403
    })
  })

  it('居民端仍仅可为本人发起（传他人 403），本人发起与管理员发起命中同一条', async () => {
    actAs('RESIDENT', 2)
    await expect(openDirectConversation({ communityId: 1, residentId: 1 })).rejects.toMatchObject({
      code: 403
    })
    const mine = await openDirectConversation({ communityId: 1 })

    actAs('ADMIN', 1)
    const byAdmin = await openDirectConversation({ communityId: 1, residentId: 2 })
    expect(byAdmin.id).toBe(mine.id)
  })
})

describe('② 建/取租约沟通会话（DEF-076 补建入口）', () => {
  function seedLease(conversationId: number | null, communityId = 1): void {
    backend().state.leases.push({
      id: 1,
      tenantId: 1,
      communityId,
      houseId: 1,
      houseLocation: '1号楼-1单元-101',
      startDate: '2026-01-01',
      endDate: '2027-12-31',
      monthlyRent: 3000,
      deposit: 6000,
      status: 'TO_SIGN',
      conversationId,
      createdAt: '2026-01-01T10:00:00'
    } as never)
  }

  it('无群租约可补建：返回 LEASE_GROUP 并回写 lease_record.conversation_id，重复调用幂等', async () => {
    seedLease(null)
    const conversation = await ensureLeaseGroupConversation(1)

    expect(conversation.type).toBe('LEASE_GROUP')
    expect(conversation.relatedId).toBe(1)
    expect(backend().state.leases[0].conversationId).toBe(conversation.id)

    const again = await ensureLeaseGroupConversation(1)
    expect(again.id).toBe(conversation.id)
    expect(backend().state.conversations.filter((item) => item.type === 'LEASE_GROUP')).toHaveLength(1)
  })

  it('租约不存在 → 404；租约属未绑定小区 → 403；居民不可调用 → 403', async () => {
    await expect(ensureLeaseGroupConversation(404)).rejects.toMatchObject({ code: 404 })

    /* 跨小区：租约属小区 2，管理端仅绑小区 1 → 403（checkCommunityAccess） */
    seedLease(null, 2)
    await expect(ensureLeaseGroupConversation(1)).rejects.toMatchObject({ code: 403 })
    expect(backend().state.conversations.filter((item) => item.type === 'LEASE_GROUP')).toHaveLength(0)

    /* 居民无权限调用补建端点 */
    actAs('RESIDENT', 1)
    await expect(ensureLeaseGroupConversation(1)).rejects.toMatchObject({ code: 403 })
  })
})
