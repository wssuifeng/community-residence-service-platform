import { beforeEach, describe, expect, it, vi } from 'vitest'
const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/common/web/utils/request', () => ({ http: { get } }))
let pullNotifications: typeof import('../notification')['pullNotifications']
beforeEach(async () => {
  vi.resetModules()
  pullNotifications = (await import('../notification')).pullNotifications
  vi.resetAllMocks()
})
describe('通知增量真实HTTP参数', () => {
  it('非零游标使用后端sinceSeq参数，返回值取最大序号', async () => {
    get.mockResolvedValue([{ id: 4, seq: 103 }, { id: 3, seq: 102 }])
    const result = await pullNotifications(101)
    expect(get).toHaveBeenCalledWith('/notifications/pull', { sinceSeq: 101 })
    expect(result.latestSeq).toBe(103)
  })
  it('空增量不重置或回退游标', async () => {
    get.mockResolvedValue([])
    expect((await pullNotifications(101)).latestSeq).toBe(101)
  })
})
