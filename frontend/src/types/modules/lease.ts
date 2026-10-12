/** C3 租住管理类型定义（对齐后端实测契约：LeaseController/LeaseVO/CreateLeaseDTO/UpdateLeaseStatusDTO） */
import type { PageQuery } from '@/types/api'
import type { LeaseAgreementSignStatus } from '@/types/modules/agreement'

/* ---------------------------------- 状态枚举 ---------------------------------- */

/**
 * 租住状态（Flyway V20 lease_record.status 注释 + LeaseService 状态机 6 态，R66）：
 * 待审核 → 待签约 → 已生效 → 已搬出 → 已归档，另有终态已驳回。
 * 审核通过只到「待签约」且房屋不置已入住；协议双方确认才流转到已生效。
 */
export type LeaseStatus = 'PENDING' | 'TO_SIGN' | 'ACTIVE' | 'MOVED_OUT' | 'ARCHIVED' | 'REJECTED'

/** 租住状态中文标签（ACTIVE 界面文案「在租」对齐设计稿，状态机语义=已生效） */
export const leaseStatusLabels: Record<LeaseStatus, string> = {
  PENDING: '待审核',
  TO_SIGN: '待签约',
  ACTIVE: '在租',
  MOVED_OUT: '已搬出',
  ARCHIVED: '已归档',
  REJECTED: '已驳回'
}

/** 到期标注（后端按日期自动判定，非状态值；架构设计 §6）：EXPIRING-即将到期(30天内) / EXPIRED-已到期 */
export type LeaseExpiryFlag = 'EXPIRING' | 'EXPIRED'

/* --------------------- R72 押金状态（数据库设计 v1.4 §3.3.1 lease_record.deposit_status） --------------------- */

/**
 * 押金状态五态（R72）：
 * UNPAID 未缴 / PAID 已缴 / REFUNDING 退还中 / REFUNDED 已退还 / OFFSET 已抵扣。
 *
 * 边界（系统定义 v1.6 §3）：平台**不托管押金资金、不计息、不垫付**——本状态只表达
 * 「实缴与退还的进度」，不代表平台持有余额；前端同样不得据此做余额/计息类展示。
 */
export type DepositStatus = 'UNPAID' | 'PAID' | 'REFUNDING' | 'REFUNDED' | 'OFFSET'

/** 押金状态中文标签（UI设计 v1.4 §3.1.1 固定文案：未缴 / 已缴 / 退还中 / 已退还 / 已抵扣） */
export const depositStatusLabels: Record<DepositStatus, string> = {
  UNPAID: '未缴',
  PAID: '已缴',
  REFUNDING: '退还中',
  REFUNDED: '已退还',
  OFFSET: '已抵扣'
}

/** 押金退还单状态（接口设计 §9.20.1：发起 PENDING → 渠道成功 SUCCESS / 渠道失败 FAILED 可重试） */
export type DepositRefundStatus = 'PENDING' | 'SUCCESS' | 'FAILED'

/** 押金退还单状态中文标签 */
export const depositRefundStatusLabels: Record<DepositRefundStatus, string> = {
  PENDING: '退还中',
  SUCCESS: '已退还',
  FAILED: '退还失败'
}

/**
 * 押金退还记录（后端 DepositRefundVO，接口设计 §9.20.1 响应字段逐一对应）。
 * `outRequestNo` 为商户退款请求号（幂等键）；`channelRefundNo` 为渠道退款单号；
 * `failReason` 为渠道失败原因（仅 FAILED 有值，UI 必须可见，不可吞掉）。
 */
export interface IDepositRefund {
  id: number
  leaseId: number
  /** 关联的退租申请（管理端从退租申请页发起时带上） */
  moveOutApplicationId?: number | null
  amount: number
  channel: PaymentChannel
  /** 幂等键：同一租约内唯一，重复提交返回既有记录 */
  outRequestNo: string
  /** 渠道退款单号（仅渠道受理后有值） */
  channelRefundNo?: string | null
  status: DepositRefundStatus
  /** 渠道失败原因（FAILED 时必填语义，前端原样展示） */
  failReason?: string | null
  operatorId?: number | null
  operatorName?: string | null
  createdAt: string
  /** 退还完成时间（仅 SUCCESS 有值） */
  refundedAt?: string | null
}

/**
 * 发起押金退还请求（POST /leases/{id}/deposit-refunds）。
 * `amount` 为空 = 全额退还（实缴押金 − 已成功退还金额），由服务端计算；
 * 渠道**不接受客户端指定**（服务端取实缴支付单渠道原路退回），故请求体无 channel。
 */
export interface ICreateDepositRefundDTO {
  amount?: number
  remark?: string
  /** 幂等键（商户退款请求号，≤64），由前端生成；失败重试须换新号 */
  outRequestNo: string
}

/* ------------------------- R73 租约明细时间线（不新增端点，三源合并） ------------------------- */

/** 时间线条目类型：状态流转 / 支付 / 押金退还 */
export type LeaseTimelineEntryType = 'STATUS' | 'PAYMENT' | 'DEPOSIT_REFUND'

/**
 * 租约明细时间线条目（接口设计 §9.20.3 字段要求，前端三源合并后的统一视图模型）。
 * 保留各源原始字段以便渲染差异（支付看金额构成与覆盖租期，退款看失败原因）。
 */
export interface ILeaseTimelineEntry {
  /** 合成键：`${type}-${源记录主键}`，跨源同号不冲突 */
  key: string
  type: LeaseTimelineEntryType
  /** 发生时间（排序依据）：支付用 paidAt ?? createdAt，退款用 refundedAt ?? createdAt，流转用变更时间 */
  occurredAt: string
  /** 完成时间（仅已完成条目有值） */
  completedAt?: string | null
  /** 支付 / 退款条目：业务单号 */
  bizNo?: string | null
  /** 支付条目：渠道流水号（R73 扩展字段 channelTradeNo） */
  channelTradeNo?: string | null
  /** 退款条目：渠道退款单号 */
  channelRefundNo?: string | null
  /** 渠道（支付 / 退款条目） */
  channel?: PaymentChannel | null
  /** 总金额（支付 / 退款条目） */
  amount?: number | null
  /** 金额构成拆分：租金部分（R73 扩展字段 rentAmount，旧数据可能为空） */
  rentAmount?: number | null
  /** 金额构成拆分：押金部分（R73 扩展字段 depositAmount） */
  depositAmount?: number | null
  /** 覆盖租期区间（R73 扩展字段 periodStart / periodEnd） */
  periodStart?: string | null
  periodEnd?: string | null
  /** 支付项目文案（首月租金 / 押金 / 首期费用 / 续期租金） */
  paymentItem?: string | null
  /** 状态文案（流转条目为变更描述，支付 / 退款条目为状态标签） */
  statusText?: string | null
  /** 状态语义色档（前端标签着色用） */
  statusTone?: 'pending' | 'completed' | 'failed' | 'neutral'
  /** 变更类型（仅流转条目，用于标签与说明） */
  changeType?: LeaseChangeType
  /** 字段中文名 / 描述（仅流转条目） */
  fieldLabel?: string | null
  /** 前后值（仅流转条目） */
  oldValue?: string | null
  newValue?: string | null
  /** 操作人（仅流转条目） */
  operatorText?: string | null
  /** 备注 / 原因（流转 reason、退款备注） */
  remark?: string | null
  /** 失败原因（仅退款失败条目，UI 必展示） */
  failReason?: string | null
}

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
  /** 协议签约状态（V18：NONE-未发起协议，其余与协议状态同义；展示用，不参与状态机） */
  agreementStatus?: LeaseAgreementSignStatus
  /** 租约群聊 ID（V20/R68，conversation.id；会话页与「我的租约」互跳用） */
  conversationId?: number | null
  /**
   * 押金状态（V22/R72，数据库设计 v1.4 §3.3.1）：五态见 `DepositStatus`。
   * 后端未返回时按 `UNPAID` 兜底展示（不猜「已缴」，避免误导退还入口）。
   */
  depositStatus?: DepositStatus | null
  /** 押金抵扣说明（仅 `OFFSET` 有值，UI 需随徽标一并展示） */
  depositOffsetRemark?: string | null
  /** 合同附件URL（线下签署的合同可挂此处） */
  contractUrl?: string | null
  remark?: string
  createdAt: string
}

/* ------------------------- 租约属性变更历史（V18，字段级留痕） ------------------------- */

/**
 * 变更类型：CREATE-登记, ATTRIBUTE-属性变更, RENEW-续租, STATUS-状态流转, AGREEMENT-协议签约,
 * DEPOSIT_REFUND-押金退还（V22/R72 §9.20.1：发起押金退还写 changeType=DEPOSIT_REFUND 留痕）
 */
export type LeaseChangeType =
  | 'CREATE'
  | 'ATTRIBUTE'
  | 'RENEW'
  | 'STATUS'
  | 'AGREEMENT'
  | 'DEPOSIT_REFUND'

/** 变更类型中文标签 */
export const leaseChangeTypeLabels: Record<LeaseChangeType, string> = {
  CREATE: '登记租约',
  ATTRIBUTE: '属性变更',
  RENEW: '续租',
  STATUS: '状态流转',
  AGREEMENT: '协议签约',
  DEPOSIT_REFUND: '押金退还'
}

/** 变更操作人身份 */
export type LeaseChangeOperatorType = 'ADMIN' | 'RESIDENT' | 'SYSTEM'

/** 租约变更记录（GET /leases/{id}/changes；属性变更逐字段一条，oldValue/newValue 为字符串快照） */
export interface ILeaseChange {
  id: number
  changeType: LeaseChangeType
  /** 字段名（登记/状态/协议等非单字段变更时为 null） */
  fieldName?: string | null
  /** 字段中文名 */
  fieldLabel?: string | null
  oldValue?: string | null
  newValue?: string | null
  /** 变更原因/备注 */
  reason?: string | null
  operatorId?: number | null
  operatorName?: string | null
  operatorType: LeaseChangeOperatorType
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

/** 支付单类型（V20/R71：FIRST-首期（首月租金+押金）/ RENEWAL-续期（月租×月数）） */
export type LeasePaymentType = 'FIRST' | 'RENEWAL'

/** 支付单类型中文标签 */
export const leasePaymentTypeLabels: Record<LeasePaymentType, string> = {
  FIRST: '首期',
  RENEWAL: '续期'
}

/** 发起首期费用支付请求（POST /leases/{id}/first-payments；金额=月租+押金由服务端计算，
 *  客户端不传金额与月数——防篡改口径） */
export interface IFirstPaymentCreateDTO {
  channel: PaymentChannel
}

/** 支付单（后端 LeasePaymentVO；alipayForm/qrCode 按渠道二选一返回） */
export interface ILeasePayment {
  paymentNo: string
  leaseId: number
  channel: PaymentChannel
  /** 支付单类型（V20 起后端返回；旧数据缺省按续期展示） */
  paymentType?: LeasePaymentType
  /** 计费月数（首期固定 1） */
  months: number
  amount: number
  status: PaymentStatus
  /** 支付宝渠道：自动提交跳转表单 HTML（alipay.trade.page.pay 响应）；
   *  列表 / 查单接口**不返回凭据**，后端可能回 null，判空按 falsy 处理 */
  alipayForm?: string | null
  /** 微信渠道：Native 下单 code_url（前端渲染二维码） */
  qrCode?: string
  createdAt: string
  paidAt?: string | null
  /* ---- R73 字段扩展（§9.19.13）：金额构成与覆盖租期区间 + 渠道流水号 ---- */
  /** 租金部分金额（首期=首月租金，续期=月租×月数；旧数据可能为空，为空时不做拆分展示） */
  rentAmount?: number | null
  /** 押金部分金额（仅首期有值；决定「实缴押金」口径，见 §9.20.1） */
  depositAmount?: number | null
  /** 本次支付覆盖的租期起始日（R73 时间线「覆盖租期区间」） */
  periodStart?: string | null
  /** 本次支付覆盖的租期结束日 */
  periodEnd?: string | null
  /** 渠道交易号 / 流水号（R73 时间线条目展示） */
  channelTradeNo?: string | null
}

/* ------------------------- R70 居民自助退租申请（V20） ------------------------- */

/** 退租申请状态（PENDING→APPROVED / REJECTED；同一租约同时仅允许一条 PENDING） */
export type MoveOutApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 退租申请状态中文标签 */
export const moveOutApplicationStatusLabels: Record<MoveOutApplicationStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/** 退租申请（后端 LeaseMoveOutApplicationVO；居民端提交、管理端审核） */
export interface IMoveOutApplication {
  id: number
  leaseId: number
  residentId: number
  /** 申请人姓名 */
  residentName?: string | null
  communityId: number
  houseId: number
  /** 房屋位置（楼栋-单元-房号） */
  houseLocation?: string | null
  /** 租约号（后端可能返回，居民端缺省按 id 兜底展示） */
  leaseNo?: string | null
  /** 计划搬出日期（YYYY-MM-DD） */
  moveOutDate: string
  reason?: string | null
  status: MoveOutApplicationStatus
  reviewerId?: number | null
  reviewerName?: string | null
  reviewTime?: string | null
  rejectReason?: string | null
  createdAt: string
  updatedAt?: string | null
}

/** 提交退租申请（POST /leases/{id}/move-out-applications；居民本人、仅 ACTIVE 租约） */
export interface ICreateMoveOutApplicationDTO {
  /** 计划搬出日期（YYYY-MM-DD） */
  moveOutDate: string
  reason?: string
}

/** 退租申请分页查询参数（GET /lease-move-out-applications，管理端按社区数据权限） */
export interface IMoveOutApplicationQuery extends PageQuery {
  status?: MoveOutApplicationStatus
  communityId?: number
}

/** 退租审核通过请求（PATCH /lease-move-out-applications/{id}/approve；
 *  housingStatus 为房源回收方式，缺省由服务端按在架处理） */
export interface IMoveOutApproveDTO {
  housingStatus?: 'AVAILABLE' | 'OFFLINE'
}

/** 退租驳回请求（PATCH /lease-move-out-applications/{id}/reject，理由必填） */
export interface IMoveOutRejectDTO {
  reason: string
}
