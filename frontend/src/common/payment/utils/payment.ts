import type { ILeasePayment } from '@/common/lease/types/lease'

/** 每次打开时重新比较真实截止，30秒系统缓冲不授予额外付款窗口。 */
export function canOpenPaymentPage(payment: Pick<ILeasePayment, 'status' | 'channelExpiresAt' | 'expiresAt' | 'settlementIssue'> | null, now = Date.now()): boolean {
  if (!payment || payment.status !== 'PENDING' || payment.settlementIssue) return false
  return [payment.channelExpiresAt, payment.expiresAt].every((deadline) => {
    if (!deadline) return true
    const expires = new Date(deadline).getTime()
    return Number.isFinite(expires) && expires > now
  })
}
