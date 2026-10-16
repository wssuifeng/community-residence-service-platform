import { describe, expect, it } from 'vitest'
import { canOpenPaymentPage } from '../payment'

const now = new Date('2026-10-16T12:00:00').getTime()
const pending = {
  status: 'PENDING' as const,
  channelExpiresAt: '2026-10-16T12:15:00',
  expiresAt: '2026-10-16T12:15:30'
}

describe('支付页打开前的订单截止保护', () => {
  it('截止前可打开，不自行把旧订单改为新15分钟', () => {
    expect(canOpenPaymentPage(pending, now)).toBe(true)
    expect(canOpenPaymentPage({ ...pending, channelExpiresAt: '2026-10-17T12:00:00', expiresAt: '2026-10-17T12:05:00' }, now)).toBe(true)
  })
  it('渠道截止的那一秒就不得打开，系统缓冲不延长付款时间', () => {
    expect(canOpenPaymentPage(pending, new Date(pending.channelExpiresAt).getTime())).toBe(false)
    expect(canOpenPaymentPage(pending, new Date(pending.expiresAt).getTime() - 1)).toBe(false)
  })
  it('历史只有系统截止时同样阻止过期打开，不凭空推算渠道期限', () => {
    expect(canOpenPaymentPage({ status: 'PENDING', expiresAt: '2026-10-16T11:59:59' }, now)).toBe(false)
    expect(canOpenPaymentPage({ status: 'PENDING' }, now)).toBe(true)
  })
  it('成功、关闭、异常待核对、未知或损坏的截止都不打开', () => {
    expect(canOpenPaymentPage({ ...pending, status: 'SUCCESS' }, now)).toBe(false)
    expect(canOpenPaymentPage({ ...pending, status: 'CLOSED' }, now)).toBe(false)
    expect(canOpenPaymentPage({ ...pending, settlementIssue: '待核对' }, now)).toBe(false)
    expect(canOpenPaymentPage({ ...pending, channelExpiresAt: 'bad' }, now)).toBe(false)
    expect(canOpenPaymentPage(null, now)).toBe(false)
  })
})
