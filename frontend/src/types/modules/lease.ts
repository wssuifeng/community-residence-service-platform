/** C3 租住管理类型定义（对齐后端实测契约：LeaseController/LeaseVO/CreateLeaseDTO/UpdateLeaseStatusDTO） */
import type { PageQuery } from '@/types/api'

/* ---------------------------------- 状态枚举 ---------------------------------- */

/** 租住状态（Flyway V1 lease_record.status 注释 + LeaseService 状态机：待审核→已生效→已搬出→已归档 + 已驳回） */
export type LeaseStatus = 'PENDING' | 'ACTIVE' | 'MOVED_OUT' | 'ARCHIVED' | 'REJECTED'

/** 租住状态中文标签（ACTIVE 界面文案「在租」对齐设计稿，状态机语义=已生效） */
export const leaseStatusLabels: Record<LeaseStatus, string> = {
  PENDING: '待审核',
  ACTIVE: '在租',
  MOVED_OUT: '已搬出',
  ARCHIVED: '已归档',
  REJECTED: '已驳回'
}

/** 到期标注（后端按日期自动判定，非状态值；架构设计 §6）：EXPIRING-即将到期(30天内) / EXPIRED-已到期 */
export type LeaseExpiryFlag = 'EXPIRING' | 'EXPIRED'

/* ---------------------------------- 租住记录 ---------------------------------- */

/** 租住记录（后端 LeaseVO 实测字段；无 paymentMethod/contractNumber，押金字段名为 deposit） */
export interface ILeaseRecord {
  id: number
  /** 租客ID */
  tenantId: number
  /** 社区名称（居民端租约卡片按社区分组展示，后端 LeaseVO 2026-09-20 补） */
  communityName?: string
  /** 租客姓名 */
  tenantName: string
  communityId: number
  houseId: number
  /** 房屋位置（楼栋-单元-房号） */
  houseLocation: string
  /** 租期开始日期（YYYY-MM-DD） */
  startDate: string
  /** 租期结束日期（YYYY-MM-DD） */
  endDate: string
  monthlyRent: number
  deposit?: number
  status: LeaseStatus
  /** 到期标注（仅 ACTIVE 判定，其余为空） */
  expiryFlag?: LeaseExpiryFlag | null
  remark?: string
  createdAt: string
}

/** 创建/更新租住记录请求（后端 CreateLeaseDTO；社区归属由房屋推导，更新不允许变更房屋） */
export interface ILeaseRecordDTO {
  /** 租客ID */
  residentId: number
  houseId: number
  startDate: string
  endDate: string
  monthlyRent: number
  deposit?: number
  contractUrl?: string
  remark?: string
}

/** 租住记录列表查询参数（status 为状态机值；到期标注不是状态、不可作为 status 传参） */
export interface ILeaseQuery extends PageQuery {
  status?: LeaseStatus
  residentId?: number
  houseId?: number
}

/** 更新租住状态请求（状态机流转校验，LeaseService ALLOWED_TRANSITIONS） */
export interface IUpdateLeaseStatusDTO {
  status: LeaseStatus
  remark?: string
}

/** 续租请求（需求 E3：止期顺延，仅已生效租约；后端 RenewLeaseDTO） */
export interface IRenewLeaseDTO {
  /** 新结束日期（须晚于原结束日期） */
  newEndDate: string
  monthlyRent: number
  deposit: number
  remark?: string
}

/** 即将到期租住列表查询参数（ACTIVE 且结束日期在 [今天, 今天+days] 窗口内） */
export interface IExpiringLeaseQuery extends PageQuery {
  days?: number
}

/* ---------------------------------- R61 续约支付（v1.4） ---------------------------------- */

/** 支付渠道（R61：支付宝电脑网站支付 / 微信 Native 扫码） */
export type PaymentChannel = 'ALIPAY' | 'WECHAT'

/** 支付单状态（R61：待支付→支付成功 / 已关闭；渠道侧失败保留在查询结果中） */
export type PaymentStatus = 'PENDING' | 'SUCCESS' | 'CLOSED'

/** 支付渠道可用性（GET /payment/channels 元素：凭据未配置 enabled=false 禁用明示） */
export interface IPaymentChannelOption {
  channel: PaymentChannel
  enabled: boolean
}

/** 发起续约支付请求（POST /leases/{id}/renew-payments；金额服务端按月租×月数计算，
 * 客户端不可传金额——防篡改口径） */
export interface IRenewPaymentCreateDTO {
  months: number
  channel: PaymentChannel
}

/** 支付单（后端 LeasePaymentVO；alipayForm/qrCode 按渠道二选一返回） */
export interface ILeasePayment {
  paymentNo: string
  leaseId: number
  channel: PaymentChannel
  months: number
  amount: number
  status: PaymentStatus
  /** 支付宝渠道：自动提交跳转表单 HTML（alipay.trade.page.pay 响应） */
  alipayForm?: string
  /** 微信渠道：Native 下单 code_url（前端渲染二维码） */
  qrCode?: string
  createdAt: string
  paidAt?: string | null
}
