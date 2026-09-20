import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  ILeasePayment,
  IPaymentChannelOption,
  IRenewPaymentCreateDTO,
  PaymentChannel
} from '@/types/modules/lease'

/**
 * R61 租约续约支付接口（v1.4）：真实双渠道（支付宝电脑网站支付 / 微信 Native）。
 * 支付状态确认走「前端轮询本单 + 后端主动查单」——本地环境无公网回调，
 * 公网部署时按官方文档补 notify 控制器即可（后端已预留）。
 */

/** 支付渠道可用性（凭据未配置的渠道 enabled=false，界面禁用明示） */
export function listPaymentChannels() {
  return http.get<IPaymentChannelOption[]>('/payment/channels')
}

/** 发起续约支付：按月数与渠道创建支付单（金额服务端计算，响应含支付凭据） */
export function createRenewPayment(leaseId: number, data: IRenewPaymentCreateDTO) {
  return http.post<ILeasePayment>(`/leases/${leaseId}/renew-payments`, data)
}

/** 查询支付单状态（前端轮询；后端在查询时向渠道侧主动查单回写状态） */
export function getPaymentStatus(paymentNo: string) {
  return http.get<ILeasePayment>(`/payment/orders/${paymentNo}`)
}

/** 关闭未支付支付单（居民放弃支付；幂等，已支付单不可关闭） */
export function closePayment(paymentNo: string) {
  return http.patch<null>(`/payment/orders/${paymentNo}/close`)
}

/** 我的支付单列表（居民端：本人租约的支付流水） */
export function listMyPayments(params?: { page?: number; size?: number; channel?: PaymentChannel }) {
  return http.get<PageResult<ILeasePayment>>('/payment/orders', params)
}
