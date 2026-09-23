import { beforeEach, describe, expect, it, vi } from 'vitest'
import { completeWorkOrder, returnWorkOrder } from '@/api/workorder'

/**
 * 工单动作端点契约（`frontend/src/api/workorder.ts`）：
 * ① 居民「不满意退回」必须打专用端点 `PATCH /work-orders/{id}/return`（R22），
 *    而不是借用「确认完成」——借用会把工单推进到已完成，居民失去返工机会；
 * ② 完成处理的请求体按后端 `WorkOrderActionDTO` 收口为 `remark`（不再冗余携带 `solution`）。
 */
const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn()
}))

vi.mock('@/utils/request', () => ({ http: httpMock }))

beforeEach(() => {
  httpMock.patch.mockReset()
  httpMock.patch.mockResolvedValue(null)
})

describe('工单动作端点契约', () => {
  it('不满意退回打到 PATCH /work-orders/{id}/return，原因写在 remark', async () => {
    await returnWorkOrder(7, { remark: '居民反馈不满意：卫生间仍有异味' })

    expect(httpMock.patch).toHaveBeenCalledTimes(1)
    expect(httpMock.patch).toHaveBeenCalledWith('/work-orders/7/return', {
      remark: '居民反馈不满意：卫生间仍有异味'
    })
  })

  it('完成处理打到 PATCH /work-orders/{id}/complete，请求体只带 remark（无冗余 solution）', async () => {
    await completeWorkOrder(7, { remark: '已更换密封圈' })

    expect(httpMock.patch).toHaveBeenCalledTimes(1)
    const [url, body] = httpMock.patch.mock.calls[0] as [string, Record<string, unknown>]
    expect(url).toBe('/work-orders/7/complete')
    expect(body).toEqual({ remark: '已更换密封圈' })
    expect(Object.keys(body)).toEqual(['remark'])
  })
})
