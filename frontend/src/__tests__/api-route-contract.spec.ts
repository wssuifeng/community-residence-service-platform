import { beforeEach, describe, expect, it, vi } from 'vitest'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
  put: vi.fn(),
  delete: vi.fn()
}))

vi.mock('@/utils/request', () => ({ http: httpMock }))

import { getPaymentStatus, listPaymentChannels } from '@/api/payment'
import { listViewingAvailableSlots } from '@/api/housing'

describe('前端 API 路由契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue([])
  })

  it('支付查单使用后端实际的 orders 子路径', async () => {
    await getPaymentStatus('RENEW20261010000001')
    expect(httpMock.get).toHaveBeenCalledWith('/payment/orders/RENEW20261010000001')
  })

  it('支付渠道查询使用 payment/channels', async () => {
    await listPaymentChannels()
    expect(httpMock.get).toHaveBeenCalledWith('/payment/channels')
  })

  it('看房可约时段使用 startDate/endDate 契约，不猜测 date 参数', async () => {
    await listViewingAvailableSlots(1, { startDate: '2026-10-12', endDate: '2026-10-12' })
    expect(httpMock.get).toHaveBeenCalledWith('/housings/1/available-slots', {
      startDate: '2026-10-12',
      endDate: '2026-10-12'
    })
  })
})
