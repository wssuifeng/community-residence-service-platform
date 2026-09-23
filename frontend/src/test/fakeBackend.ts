import { ApiError } from '@/utils/request'

/**
 * 内存版「假后端」——**模拟 E2E** 的测试基建（不连真后端、不发网络请求）。
 *
 * ## 定位（必读）
 * vitest + @vue/test-utils 是**组件级**运行环境：没有真实浏览器、没有真实后端。
 * 因此这里做的是**模拟 E2E**：挂载**真实页面组件**（含真实 Element Plus、真实
 * api 层调用），把 `@/api/*` 换成**有状态的假后端**，按「三端交替」推进业务并断言
 * 关键跃迁（页面可见文本/DOM + 假后端状态变化）。**真端到端（真库真接口）由另一路的
 * 接口脚本负责，本文件绝不打真后端。**
 *
 * ## 契约来源（不凭记忆编）
 * - 错误码语义：`docs/30_系统设计/接口设计.md` §3.1/§3.2、§9.19.15（5606/5904 校验顺序）
 * - 状态机：`docs/30_系统设计/架构设计.md` §6.1~§6.7
 * - 流程分段：`docs/50_系统测试/13_核心业务流程三端联动手测用例.md`（CF-E1~CF-E12）
 * - 业务错误一律**HTTP 200 + 业务码 5xxx**（§3.1 L147），故这里统一抛 ApiError(code)，
 *   由 `@/utils/request` 的同名类承载（页面里 `error instanceof ApiError` 分支才走得通）。
 *
 * ## 核心口径（照抄文档，测试断言依据）
 * - 审核通过只到 `TO_SIGN`：**房屋不置已入住**、自动建 `LEASE_GROUP` 群聊；协议双方确认
 *   齐才 `TO_SIGN → ACTIVE` + `house.status = OCCUPIED`（§6.2）。
 * - 承租确认是发起协议的前置（未确认发起 → **5601**），且**幂等不覆盖首次留痕**（§6.7b）。
 * - **首期支付不顺延 `end_date`、不改租约状态**；**续期支付按所选月数顺延 `end_date`**（§6.7d）。
 * - 首期校验顺序（§9.19.15）：租约存在 → 本人 → `ACTIVE`(5602) → 已有 FIRST 且 SUCCESS(5904)
 *   → 止期**严格早于**当天(5606，当天到期仍可支付) → FIRST 的 PENDING 幂等续接 → 新建单。
 * - 退租审核通过：租约 `MOVED_OUT`、居住关系终止、房屋置空置、**租约群聊 CLOSED（发消息 5604）**。
 * - 通知身份空间分离（DEF-067）：读取按 `(owner_type, user_id)` 收口；`resident.id` 与
 *   `sys_user.id` **会撞号**，故本假后端默认让超管 id 与居民 id 都是 1（撞号对）用于双向验证。
 */

/* ------------------------------ 业务错误码（以后端 ErrorCode 枚举为准） ------------------------------
 * 权威来源：`backend/src/main/java/com/community/residence/common/constant/ErrorCode.java`
 * （96 行完整枚举）。**注意与 `docs/30_系统设计/接口设计.md` 的三处漂移**（已在报告中标出）：
 * - 重复入住申请：实现 = `APPLICATION_PENDING_EXISTS(5206)`，文档 §9.2.2.1 写作 5205；
 *   （实现里 5205 = `HOUSE_NOT_VACANT` 房屋不可申请），前端 ResidenceApplyView 也是 5206 ✓；
 * - 工单非法流转：实现 = `WORK_ORDER_INVALID_TRANSITION(5302)`；文档 §3.2 写 5302/5303 且有冲突；
 * - 押金段（R72）：**后端定稿取 5703~5706**，`5701/5702` 仍属 C10 权限管理码
 *   （`SELF_FREEZE_FORBIDDEN` / `BIND_ROLE_INVALID`）——设计文档原把 57xx 整段划给押金系疏漏，
 *   本假后端按定稿映射实现，见 CODES 的 `DEPOSIT_*`。
 */

export const CODES = {
  /** 业务异常统一 HTTP 200 + 5xxx（接口设计 §3.1） */
  OK: 200,
  PARAM_INVALID: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  /** 状态流转非法（反馈办结非法流转、公告非法流转——公告原 5501~5504 已裁决作废） */
  STATE_TRANSITION_INVALID: 5004,
  /** 入住申请已审核（重复审核） */
  APPLICATION_ALREADY_REVIEWED: 5202,
  /** 房屋当前不可申请入住 */
  HOUSE_NOT_VACANT: 5205,
  /** 已有待审核的入住申请（重复申请） */
  APPLICATION_PENDING_EXISTS: 5206,
  /** 工单不存在 / 工单状态流转非法 */
  WORK_ORDER_NOT_FOUND: 5301,
  WORK_ORDER_INVALID_TRANSITION: 5302,
  /** 预约时段冲突（同资源同时段重叠） */
  RESERVATION_CONFLICT: 5401,
  /** 工单未完成不可评价 / 已评价不可重复 */
  EVALUATION_NOT_ALLOWED: 5501,
  EVALUATION_DUPLICATE: 5502,
  /** 未确认承租即发起协议（R67 前置条件） */
  LEASE_TENANCY_NOT_CONFIRMED: 5601,
  /** 租约状态不允许该操作 */
  LEASE_STATUS_INVALID: 5602,
  /** 已有进行中的退租申请 */
  MOVE_OUT_APPLICATION_PENDING: 5603,
  /** 会话已关闭（停收停发） */
  CONVERSATION_CLOSED: 5604,
  /** 入住申请不可撤回（非待审核） */
  APPLICATION_NOT_WITHDRAWABLE: 5605,
  /** 租约已到期（首期支付时止期严格早于当天；续期不受限，DEF-069） */
  LEASE_EXPIRED: 5606,
  /** 承租前置未满足：本房源看房预约未完成（DEF-077） */
  LEASE_TENANCY_VIEWING_REQUIRED: 5607,
  /** 承租前置未满足：租约沟通会话未建立（DEF-077） */
  LEASE_TENANCY_CONVERSATION_REQUIRED: 5608,
  /** 支付渠道未配置（凭据门控） */
  PAYMENT_CHANNEL_DISABLED: 5901,
  /** 支付单创建失败 */
  PAYMENT_CREATE_FAILED: 5902,
  /** 同类型支付单已完成（PENDING 走幂等续接、CLOSED 允许重新发起，DEF-068） */
  PAYMENT_ALREADY_EXISTS: 5904,
  /* ---- R72 押金退还（后端定稿映射 2026-09-22：押金段取 5703~5706） ----
     注意 5701/5702 **保持为 C10 权限管理码**（SELF_FREEZE_FORBIDDEN / BIND_ROLE_INVALID），
     不是押金码；设计文档原把 57xx 整段划给押金系疏漏，以本映射为准。 */
  /** 押金状态不允许退还（非 PAID，5703） */
  DEPOSIT_STATUS_NOT_REFUNDABLE: 5703,
  /** 退款金额超过实缴押金（报文含「可退 X 元」，5704） */
  DEPOSIT_AMOUNT_EXCEEDED: 5704,
  /** 退款渠道与实缴渠道不一致（不允许跨渠道退款，5705） */
  DEPOSIT_CHANNEL_MISMATCH: 5705,
  /** 退款请求号冲突（同号但内容不一致，5706） */
  DEPOSIT_OUT_REQUEST_CONFLICT: 5706
} as const

function fail(code: number, message: string): never {
  throw new ApiError(code, message, CODES.OK)
}

/* ------------------------------ 角色与身份 ------------------------------ */

export type FakeRole = 'GUEST' | 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'

export interface FakeActor {
  role: FakeRole
  /** 身份空间内的主体 ID：RESIDENT → resident.id；ADMIN/STAFF/SUPER_ADMIN → sys_user.id */
  id: number
  communityIds: number[]
}

const SESSION_KEY = 'crsp_session'

/**
 * 模拟登录：写 sessionStorage（与真实登录同源，Pinia user store 会读到），
 * 页面组件与假后端都由此判定当前身份。
 */
export function fakeLogin(role: FakeRole, options: { id?: number; username?: string; communityIds?: number[] } = {}): void {
  const user = {
    id: options.id ?? 1,
    username: options.username ?? `${role.toLowerCase()}1`,
    realName: options.username ?? `${role.toLowerCase()}1`,
    role,
    boundCommunities: options.communityIds ?? [1]
  }
  sessionStorage.setItem(
    SESSION_KEY,
    JSON.stringify({ token: 'fake-token', user, expiresAt: Date.now() + 3_600_000 })
  )
}

/** 当前身份：读 sessionStorage（无会话按游客） */
export function currentActor(): FakeActor {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return { role: 'GUEST', id: 0, communityIds: [] }
  try {
    const session = JSON.parse(raw) as {
      user?: { id: number; role: FakeRole; boundCommunities?: number[] }
    }
    const user = session.user
    if (!user) return { role: 'GUEST', id: 0, communityIds: [] }
    return { role: user.role, id: user.id, communityIds: user.boundCommunities ?? [] }
  } catch {
    return { role: 'GUEST', id: 0, communityIds: [] }
  }
}

/* ------------------------------ 实体 ------------------------------ */

export interface FakeHouse {
  id: number
  communityId: number
  buildingId: number
  unitId: number
  houseNumber: string
  /** 楼层（IHouse.floor） */
  floor: number
  area: number
  /** 房屋占用状态：VACANT 空置 / OCCUPIED 已入住（§6.2：租赁生效才置 OCCUPIED） */
  status: 'VACANT' | 'OCCUPIED'
}

export interface FakeHousing {
  id: number
  houseId: number
  communityId: number
  title: string
  monthlyRent: number
  deposit: number
  status: 'AVAILABLE' | 'RESERVED' | 'RENTED' | 'OFFLINE'
  rentType: 'RENT' | 'SALE'
  layout: string
  /** 图片列表（页面按 images[0] 取首图，缺字段会直接渲染报错） */
  images: string[]
  coverUrl?: string | null
}

export interface FakeResidenceApplication {
  id: number
  residentId: number
  communityId: number
  houseId: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
  reviewRemark?: string | null
  /** V26 强关联：审批通过（含社区自动审批）时写死的租约 id；为空表示无关联 */
  leaseId?: number | null
  createdAt: string
}

export interface FakeLease {
  id: number
  tenantId: number
  tenantName: string
  communityId: number
  houseId: number
  houseLocation: string
  startDate: string
  endDate: string
  monthlyRent: number
  deposit: number
  status: 'PENDING' | 'TO_SIGN' | 'ACTIVE' | 'MOVED_OUT' | 'ARCHIVED' | 'REJECTED'
  agreementStatus: 'NONE' | 'PENDING' | 'PARTIAL' | 'SIGNED' | 'CANCELLED'
  tenantConfirmAt?: string | null
  tenantConfirmBy?: number | null
  conversationId?: number | null
  /** 押金状态（R72：UNPAID/PAID/REFUNDING/REFUNDED/OFFSET；缺省按 UNPAID 兜底） */
  depositStatus?: 'UNPAID' | 'PAID' | 'REFUNDING' | 'REFUNDED' | 'OFFSET'
  /** 押金抵扣说明（仅 OFFSET 有值） */
  depositOffsetRemark?: string | null
  createdAt: string
}

/** 押金退还单（R72 §9.20.1 响应 DepositRefundVO 的等价最小集） */
export interface FakeDepositRefund {
  id: number
  leaseId: number
  moveOutApplicationId?: number | null
  amount: number
  channel: 'ALIPAY' | 'WECHAT'
  /** 幂等键（商户退款请求号）：库级全局唯一（uk_deposit_out_request_no），同号重放返回既有 */
  outRequestNo: string
  channelRefundNo?: string | null
  status: 'PENDING' | 'SUCCESS' | 'FAILED'
  failReason?: string | null
  operatorId?: number | null
  operatorName?: string | null
  createdAt: string
  refundedAt?: string | null
}

export interface FakeLeaseChange {
  id: number
  leaseId: number
  changeType: 'CREATE' | 'ATTRIBUTE' | 'RENEW' | 'STATUS' | 'AGREEMENT' | 'DEPOSIT_REFUND'
  fieldName?: string
  /** 字段中文名（后端 LeaseChangeVO 提供，时间线按此渲染字段名） */
  fieldLabel?: string
  oldValue?: string | null
  newValue?: string | null
  operatorType: 'ADMIN' | 'RESIDENT' | 'SYSTEM'
  createdAt: string
}

export interface FakeAgreement {
  id: number
  leaseId: number
  templateId: number
  status: 'PENDING' | 'PARTIAL' | 'SIGNED' | 'CANCELLED'
  content: string
  tenantConfirmedAt?: string | null
  adminConfirmedAt?: string | null
  /** 管理方撤回原因（§9.17.9：cancel 的 reason 落 cancel_reason） */
  cancelReason?: string | null
  createdAt: string
}

export interface FakePayment {
  paymentNo: string
  leaseId: number
  paymentType: 'FIRST' | 'RENEWAL'
  channel: 'ALIPAY' | 'WECHAT'
  months: number
  amount: number
  status: 'PENDING' | 'SUCCESS' | 'CLOSED'
  createdAt: string
  paidAt?: string | null
  alipayForm?: string | null
  qrCode?: string | null
  /* ---- R73 字段扩展（§9.19.13）：金额构成与覆盖租期区间 + 渠道流水号 ---- */
  /** 租金部分（首期=首月租金，续期=月租×月数） */
  rentAmount?: number | null
  /** 押金部分（仅首期有值；决定「实缴押金」口径，§9.20.1） */
  depositAmount?: number | null
  periodStart?: string | null
  periodEnd?: string | null
  channelTradeNo?: string | null
}

export interface FakeMoveOutApplication {
  id: number
  leaseId: number
  residentId: number
  communityId: number
  /** 契约字段名（后端 LeaseMoveOutApplicationVO.moveOutDate；旧名 expectedMoveOutDate 已废弃） */
  moveOutDate: string
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  reviewRemark?: string | null
  createdAt: string
}

export interface FakeConversation {
  id: number
  type: 'VIEWING_GROUP' | 'LEASE_GROUP' | 'DIRECT' | 'WORK_ORDER_GROUP'
  relatedId: number | null
  /** 所属社区（真实 conversation 表有该列；DIRECT 幂等键之一） */
  communityId?: number | null
  title: string
  status: 'ACTIVE' | 'CLOSED'
  participantIds: number[]
  createdAt: string
  /** 关闭时间（§6.7c：CLOSED 只读留档） */
  closedAt?: string | null
  /**
   * 参与者身份空间明细（R74 新增，选填）：
   * 会话成员按 `(owner_type, user_id)` 二元组收口（§9.21.2 / DEF-067）——
   * 假后端的 id 空间不区分角色（居民 1 / 管理员 1 / 服务人员 1 会撞号），
   * 故这里按 **(space, id, role)** 三元组落成员，才能表达「派单前服务人员不在群」。
   * 缺省（存量看房/租约群聊）仍按 `participantIds` 判定，行为不变。
   */
  participantRefs?: Array<{
    id: number
    space: 'RESIDENT' | 'USER'
    name?: string
    role?: 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'
  }>
}

export interface FakeConversationMessage {
  id: number
  conversationId: number
  senderType: 'RESIDENT' | 'ADMIN' | 'SYSTEM' | 'STAFF'
  senderId: number
  senderName: string
  content: string
  createdAt: string
}

export interface FakeWorkOrder {
  id: number
  orderNo: string
  residentId: number
  communityId: number
  categoryId: number
  title: string
  description: string
  status:
    | 'PENDING'
    | 'TO_ASSIGN'
    | 'ASSIGNED'
    | 'ACCEPTED'
    | 'IN_PROGRESS'
    | 'TO_CONFIRM'
    | 'COMPLETED'
    | 'CLOSED'
    | 'REJECTED'
    | 'CANCELLED'
  assigneeId?: number | null
  assigneeName?: string | null
  /** 工单临时群聊会话 ID（R74/V23；受理成功后回填，受理前为空） */
  conversationId?: number | null
  createdAt: string
}

export interface FakeWorkOrderProcess {
  id: number
  orderId: number
  action: string
  operatorName: string
  remark?: string | null
  createdAt: string
}

export interface FakeEvaluation {
  id: number
  orderId: number
  score: number
  content: string
  satisfied: boolean
  followups: Array<{ id: number; content: string; operatorName: string; createdAt: string }>
  createdAt: string
}

export interface FakeNotice {
  id: number
  title: string
  content: string
  status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN' | 'EXPIRED'
  noticeType: 'ANNOUNCEMENT'
  priority: 'NORMAL' | 'IMPORTANT' | 'URGENT'
  isPinned: boolean
  targets: Array<{ targetType: 'COMMUNITY' | 'BUILDING' | 'GUEST'; targetId: number | null }>
  publishTime?: string | null
  expireTime?: string | null
  /** 撤回原因（§9.5.1.7：withdraw 的 reason 必填） */
  withdrawReason?: string | null
  viewCount: number
  createdAt: string
}

export interface FakeFeedback {
  id: number
  residentId: number
  communityId: number
  title: string
  content: string
  status: 'PENDING' | 'IN_SESSION' | 'CLOSED'
  closeConclusion?: string | null
  messages: FakeConversationMessage[]
  createdAt: string
}

export interface FakeResource {
  id: number
  communityId: number
  name: string
  status: 'AVAILABLE' | 'DISABLED'
}

export interface FakeTimeslot {
  id: number
  resourceId: number
  slotDate: string
  startTime: string
  endTime: string
  capacity: number
  status: 'AVAILABLE' | 'FULL'
}

export interface FakeReservation {
  id: number
  residentId: number
  residentName: string
  resourceId: number
  timeslotId: number
  status: 'PENDING' | 'RESERVED' | 'CONFIRMED' | 'COMPLETED' | 'REJECTED' | 'CANCELLED' | 'VIOLATED'
  reason?: string | null
  createdAt: string
}

export interface FakeNotification {
  id: number
  /** 身份空间（DEF-067）：USER=sys_user 主体 / RESIDENT=resident 主体 */
  ownerType: 'USER' | 'RESIDENT'
  userId: number
  seq: number
  title: string
  content: string
  sourceType: string
  sourceId: number | null
  isRead: boolean
  createdAt: string
}

export interface FakeViewingAppointment {
  id: number
  housingId: number
  houseId: number
  communityId: number
  visitorName: string
  visitorPhone: string
  appointmentDate: string
  startTime: string
  endTime: string
  status: 'TO_CONFIRM' | 'RESERVED' | 'COMPLETED' | 'CANCELLED' | 'VIOLATED'
  assigneeId?: number | null
  assigneeName?: string | null
  leaseId?: number | null
  createdAt: string
}

export interface FakeBackendState {
  communities: Array<{ id: number; name: string; autoApproveResidence: number; defaultLeaseMonths: number }>
  buildings: Array<{ id: number; communityId: number; name: string }>
  units: Array<{ id: number; buildingId: number; name: string }>
  houses: FakeHouse[]
  housings: FakeHousing[]
  residents: Array<{ id: number; name: string; phone: string; communityId: number; status: 'ACTIVE' | 'FROZEN' }>
  residenceApplications: FakeResidenceApplication[]
  residenceRelations: Array<{ id: number; residentId: number; houseId: number; status: 'ACTIVE' | 'MOVED_OUT'; moveOutAt?: string | null }>
  leases: FakeLease[]
  leaseChanges: FakeLeaseChange[]
  /** R72 押金退还流水（按租约收敛，单租约记录量小故不分页） */
  depositRefunds: FakeDepositRefund[]
  agreements: FakeAgreement[]
  agreementTemplates: Array<{ id: number; communityId: number | null; name: string; content: string; status: 'ACTIVE' | 'INACTIVE' }>
  payments: FakePayment[]
  moveOutApplications: FakeMoveOutApplication[]
  conversations: FakeConversation[]
  conversationMessages: FakeConversationMessage[]
  workOrders: FakeWorkOrder[]
  workOrderProcesses: FakeWorkOrderProcess[]
  serviceCategories: Array<{ id: number; communityId: number; name: string; status: 'ACTIVE' | 'INACTIVE' }>
  evaluations: FakeEvaluation[]
  notices: FakeNotice[]
  noticeViews: number[]
  feedbacks: FakeFeedback[]
  resources: FakeResource[]
  timeslots: FakeTimeslot[]
  reservations: FakeReservation[]
  notifications: FakeNotification[]
  viewingAppointments: FakeViewingAppointment[]
  channelLevels: Record<string, string[]>
  /** 渠道启用状态（凭据门控：未配置的渠道 enabled=false） */
  channels: Array<{ channel: 'ALIPAY' | 'WECHAT'; enabled: boolean }>
}

/* ------------------------------ 工具 ------------------------------ */

function nowIso(): string {
  return new Date().toISOString().slice(0, 19)
}

/** 本地日期（yyyy-MM-dd）：与下方 daysFromToday 的本地口径一致，避免 UTC 跨日错位 */
function dateOnly(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function addMonths(dateStr: string, months: number): string {
  const date = new Date(`${dateStr}T00:00:00`)
  date.setMonth(date.getMonth() + months)
  return dateOnly(date)
}

function daysFromToday(dateStr: string): number {
  const target = new Date(`${dateStr}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((target.getTime() - today.getTime()) / 86_400_000)
}

interface PageParams {
  page?: number
  size?: number
}

function pageOf<T>(rows: T[], params?: PageParams): { records: T[]; total: number; page: number; size: number; pages: number } {
  const page = params?.page ?? 1
  const size = params?.size ?? 10
  const start = (page - 1) * size
  return {
    records: rows.slice(start, start + size),
    total: rows.length,
    page,
    size,
    pages: Math.max(1, Math.ceil(rows.length / size))
  }
}

/* ------------------------------ 假后端主体 ------------------------------ */

export class FakeBackend {
  state!: FakeBackendState
  /** 调用流水：断言「真的推进了业务」时可查（如确认承租只写了一次留痕） */
  calls: Array<{ module: string; fn: string; args: unknown[] }> = []
  private seq = 0
  private ids: Record<string, number> = {}

  constructor() {
    this.reset()
  }

  /** 复位为演示种子（联调种子社区的等价最小集） */
  reset(overrides: Partial<FakeBackendState> = {}): void {
    this.seq = 0
    this.ids = {}
    this.calls = []
    this.state = {
      /* autoApproveResidence 按 ICommunity 口径为 0/1（0=人工审核，1=提交即通过） */
      communities: [{ id: 1, name: '联调测试社区', autoApproveResidence: 0, defaultLeaseMonths: 12 }],
      buildings: [{ id: 1, communityId: 1, name: '1 号楼' }],
      units: [{ id: 1, buildingId: 1, name: '1 单元' }],
      houses: [
        { id: 1, communityId: 1, buildingId: 1, unitId: 1, houseNumber: '101', floor: 1, area: 88, status: 'VACANT' },
        { id: 2, communityId: 1, buildingId: 1, unitId: 1, houseNumber: '102', floor: 1, area: 76, status: 'VACANT' }
      ],
      housings: [
        {
          id: 1,
          houseId: 1,
          communityId: 1,
          title: '1 号楼1 单元101 在租房源',
          monthlyRent: 3000,
          deposit: 6000,
          status: 'AVAILABLE',
          rentType: 'RENT',
          layout: '2室1厅',
          images: ['/housing-placeholder.png'],
          coverUrl: null
        }
      ],
      residents: [{ id: 1, name: '演示居民', phone: '13800000001', communityId: 1, status: 'ACTIVE' }],
      residenceApplications: [],
      residenceRelations: [],
      leases: [],
      leaseChanges: [],
      depositRefunds: [],
      agreements: [],
      agreementTemplates: [
        {
          id: 1,
          communityId: 1,
          name: '社区标准租赁协议',
          content: '甲方：社区管理方；乙方：{{tenantName}}；房屋：{{houseLocation}}；月租：{{monthlyRent}}',
          status: 'ACTIVE'
        }
      ],
      payments: [],
      moveOutApplications: [],
      conversations: [],
      conversationMessages: [],
      workOrders: [],
      workOrderProcesses: [],
      serviceCategories: [
        { id: 1, communityId: 1, name: '水电维修', status: 'ACTIVE' },
        { id: 2, communityId: 1, name: '公共区域保洁', status: 'ACTIVE' }
      ],
      evaluations: [],
      notices: [],
      noticeViews: [],
      feedbacks: [],
      resources: [{ id: 1, communityId: 1, name: '健身房', status: 'AVAILABLE' }],
      timeslots: [
        { id: 1, resourceId: 1, slotDate: dateOnly(new Date()), startTime: '09:00', endTime: '10:00', capacity: 1, status: 'AVAILABLE' }
      ],
      reservations: [],
      notifications: [],
      viewingAppointments: [],
      channelLevels: { NORMAL: ['WEBSOCKET'], IMPORTANT: ['WEBSOCKET', 'SMS'] },
      channels: [
        { channel: 'ALIPAY', enabled: true },
        { channel: 'WECHAT', enabled: false }
      ],
      ...overrides
    }
    /* 计数器对齐种子最大 ID：否则 nextId 会从 1 重新发号，与种子数据撞号
       （曾导致新建房屋/时段与种子同 ID，按 ID 反查时命中两条） */
    const idKeys: Array<[string, Array<{ id: number }>]> = [
      ['community', this.state.communities],
      ['building', this.state.buildings],
      ['unit', this.state.units],
      ['house', this.state.houses],
      ['housing', this.state.housings],
      ['residenceApplication', this.state.residenceApplications],
      ['residenceRelation', this.state.residenceRelations],
      ['lease', this.state.leases],
      ['leaseChange', this.state.leaseChanges],
      ['depositRefund', this.state.depositRefunds],
      ['agreement', this.state.agreements],
      ['moveOutApplication', this.state.moveOutApplications],
      ['conversation', this.state.conversations],
      ['message', this.state.conversationMessages],
      ['workOrder', this.state.workOrders],
      ['workOrderProcess', this.state.workOrderProcesses],
      ['evaluation', this.state.evaluations],
      ['notice', this.state.notices],
      ['feedback', this.state.feedbacks],
      ['resource', this.state.resources],
      ['timeslot', this.state.timeslots],
      ['reservation', this.state.reservations],
      ['notification', this.state.notifications],
      ['viewingAppointment', this.state.viewingAppointments]
    ]
    for (const [key, rows] of idKeys) {
      this.ids[key] = rows.reduce((max, row) => Math.max(max, row.id ?? 0), 0)
    }
  }

  private nextId(key: string): number {
    this.ids[key] = (this.ids[key] ?? 0) + 1
    return this.ids[key]
  }

  /** 通知投递（DEF-067：身份空间 + 主体 ID 双键） */
  private notify(
    ownerType: 'USER' | 'RESIDENT',
    userId: number,
    payload: { title: string; content: string; sourceType: string; sourceId?: number | null }
  ): void {
    this.seq += 1
    this.state.notifications.push({
      id: this.nextId('notification'),
      ownerType,
      userId,
      seq: this.seq,
      title: payload.title,
      content: payload.content,
      sourceType: payload.sourceType,
      sourceId: payload.sourceId ?? null,
      isRead: false,
      createdAt: nowIso()
    })
  }

  private recordCall(module: string, fn: string, args: unknown[]): void {
    this.calls.push({ module, fn, args })
  }

  /** 指定模块被调用的次数（断言"只推进了一次"用） */
  callCount(module: string, fn: string): number {
    return this.calls.filter((item) => item.module === module && item.fn === fn).length
  }

  private houseLocation(houseId: number): string {
    const house = this.state.houses.find((item) => item.id === houseId)
    if (!house) return `房屋#${houseId}`
    const building = this.state.buildings.find((item) => item.id === house.buildingId)
    const unit = this.state.units.find((item) => item.id === house.unitId)
    return `${building?.name ?? ''}${unit?.name ?? ''}${house.houseNumber}`
  }

  private communityName(communityId: number): string {
    return this.state.communities.find((item) => item.id === communityId)?.name ?? ''
  }

  private leaseView(lease: FakeLease): Record<string, unknown> {
    return {
      ...lease,
      communityName: this.communityName(lease.communityId),
      houseLocation: this.houseLocation(lease.houseId),
      expiryFlag: lease.status === 'ACTIVE' ? this.expiryFlagOf(lease) : null
    }
  }

  /** 到期标注（§6.2：仅 ACTIVE 判定，30 天内 EXPIRING / 已过止期 EXPIRED，日期派生非状态流转） */
  private expiryFlagOf(lease: FakeLease): string | null {
    const days = daysFromToday(lease.endDate)
    if (days < 0) return 'EXPIRED'
    if (days <= 30) return 'EXPIRING'
    return null
  }

  private paymentView(payment: FakePayment, withCredential: boolean): Record<string, unknown> {
    return {
      paymentNo: payment.paymentNo,
      leaseId: payment.leaseId,
      paymentType: payment.paymentType,
      channel: payment.channel,
      months: payment.months,
      amount: payment.amount,
      status: payment.status,
      createdAt: payment.createdAt,
      paidAt: payment.paidAt ?? null,
      /* R73 字段扩展：金额构成与覆盖租期区间 + 渠道流水号（时间线条目展示所需） */
      rentAmount: payment.rentAmount ?? null,
      depositAmount: payment.depositAmount ?? null,
      periodStart: payment.periodStart ?? null,
      periodEnd: payment.periodEnd ?? null,
      channelTradeNo: payment.channelTradeNo ?? null,
      /* 列表/查单接口不返回渠道凭据（仅发起接口返回），页面据此走"重新获取支付页"分支 */
      alipayForm: withCredential ? (payment.alipayForm ?? null) : null,
      qrCode: withCredential ? (payment.qrCode ?? null) : null
    }
  }

  private credentialFor(payment: FakePayment): string | null {
    return payment.channel === 'ALIPAY' ? `<form id="${payment.paymentNo}">alipay</form>` : null
  }

  private assertLeaseOwned(lease: FakeLease, actor: FakeActor): void {
    if (actor.role === 'RESIDENT' && lease.tenantId !== actor.id) {
      fail(CODES.FORBIDDEN, '无权访问他人租约')
    }
  }

  /** 管理端数据级权限：ADMIN/SUPER_ADMIN 限绑定社区（R72 押金端点沿用该口径） */
  private assertCommunity(communityId: number, actor: FakeActor): void {
    if (actor.role === 'ADMIN' && !actor.communityIds.includes(communityId)) {
      fail(CODES.FORBIDDEN, '无权访问非绑定社区的数据')
    }
  }

  /**
   * 租约状态流转 + 写 STATUS 变更留痕。
   * 合法流转由调用方先行校验（§9.19.4：PENDING→TO_SIGN/REJECTED、TO_SIGN→ACTIVE/REJECTED、
   * ACTIVE→MOVED_OUT、MOVED_OUT→ARCHIVED；ARCHIVED 只读终态）。
   */
  private changeStatus(
    lease: FakeLease,
    status: FakeLease['status'],
    operatorType: 'ADMIN' | 'RESIDENT' | 'SYSTEM'
  ): void {
    const before = lease.status
    if (before === status) return
    lease.status = status
    this.state.leaseChanges.push({
      id: this.nextId('leaseChange'),
      leaseId: lease.id,
      changeType: 'STATUS',
      oldValue: before,
      newValue: status,
      operatorType,
      createdAt: nowIso()
    })
  }

  /** 关闭租约群聊（§9.19.11/§9.19.14：CLOSED 后停收停发，历史消息只读留档） */
  private closeLeaseGroup(lease: FakeLease): void {
    const conversation = this.state.conversations.find((item) => item.id === lease.conversationId)
    if (!conversation) return
    if (conversation.status === 'CLOSED') return
    conversation.status = 'CLOSED'
    conversation.closedAt = nowIso()
  }

  /* --------------------- R74 工单临时群聊（§9.21.2） --------------------- */

  /** 按工单定位已建群（type + relatedId 判定，同一工单建群唯一） */
  private findWorkOrderGroup(order: FakeWorkOrder): FakeConversation | undefined {
    return this.state.conversations.find(
      (item) => item.type === 'WORK_ORDER_GROUP' && item.relatedId === order.id
    )
  }

  /**
   * 受理即建群（幂等复用）：标题「工单沟通·<工单号>」，成员＝**居民提交人 + 受理管理员**。
   * 参与者写 `participantRefs`（身份空间二元组），避免居民 1 与管理员 1 撞号后误判可见性。
   */
  private ensureWorkOrderGroup(order: FakeWorkOrder, adminId: number): FakeConversation {
    const existing = this.findWorkOrderGroup(order)
    if (existing) {
      order.conversationId = existing.id
      return existing
    }
    const residentName = this.state.residents.find((item) => item.id === order.residentId)?.name ?? '居民'
    const conversation: FakeConversation = {
      id: this.nextId('conversation'),
      type: 'WORK_ORDER_GROUP',
      relatedId: order.id,
      title: `工单沟通·${order.orderNo}`,
      status: 'ACTIVE',
      participantIds: [order.residentId, adminId],
      participantRefs: [
        { id: order.residentId, space: 'RESIDENT', name: residentName, role: 'RESIDENT' },
        { id: adminId, space: 'USER', name: '社区管理员', role: 'ADMIN' }
      ],
      createdAt: nowIso()
    }
    this.state.conversations.push(conversation)
    order.conversationId = conversation.id
    return conversation
  }

  /** 派单后服务人员自动入群（幂等：同一身份已在群内不重复追加） */
  private joinWorkOrderGroup(
    order: FakeWorkOrder,
    userId: number,
    space: 'RESIDENT' | 'USER',
    name: string,
    role: 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'
  ): void {
    const conversation = this.findWorkOrderGroup(order)
    if (!conversation) return
    const refs = (conversation.participantRefs ??= [])
    if (refs.some((item) => item.id === userId && item.space === space && item.role === role)) return
    refs.push({ id: userId, space, name, role })
    if (!conversation.participantIds.includes(userId)) conversation.participantIds.push(userId)
  }

  /** 终审关闭 → 会话资源回收（`CLOSED` + `closed_at`，只读留痕不物理删除；重复关闭不报错） */
  private closeWorkOrderGroup(order: FakeWorkOrder): void {
    const conversation = order.conversationId
      ? this.state.conversations.find((item) => item.id === order.conversationId)
      : this.findWorkOrderGroup(order)
    if (!conversation || conversation.status === 'CLOSED') return
    conversation.status = 'CLOSED'
    conversation.closedAt = nowIso()
  }

  /* --------------------- V26 申请 → 租约强关联 --------------------- */

  /**
   * 申请 → 租约：**只读申请表上写死的 `lease_id`**（审批通过时写入），
   * 再取该租约的会话 id。为空即返回空——**不回退按（居民/社区/房屋）猜"最新租约"**，
   * 避免把"同屋多租约指错"的 bug 引回读路径。
   */
  private applicationLeaseLink(application: FakeResidenceApplication): {
    leaseId: number | null
    conversationId: number | null
  } {
    const leaseId = application.leaseId ?? null
    const lease = leaseId === null ? undefined : this.state.leases.find((item) => item.id === leaseId)
    return { leaseId, conversationId: lease?.conversationId ?? null }
  }

  /** 参与者可见性判定（§9.21.2：仅参与者可见；有身份空间明细时按三元组比对） */
  private isConversationParticipant(conversation: FakeConversation, actor: FakeActor): boolean {
    const refs = conversation.participantRefs
    if (refs && refs.length > 0) {
      const space = actor.role === 'RESIDENT' ? 'RESIDENT' : 'USER'
      return refs.some((item) => item.id === actor.id && item.space === space && item.role === actor.role)
    }
    return conversation.participantIds.includes(actor.id)
  }

  /* ============================ 模拟沙箱动作（测试专用，非 api） ============================ */

  /**
   * R73 时间线字段（服务端派生口径，后端定稿）：
   * - FIRST：`rentAmount = min(单额, 月租)`、`depositAmount = 单额 − 租金部分`、
   *   `period = 租期开始 ~ +1 月`；押金为 0 的首期成功**不置 `PAID`**（不出现「可退 0 元」）；
   * - RENEWAL：`rentAmount = 单额`、`depositAmount = 0`、`period = 止期前推 months 个月 ~ 止期`；
   * - 恒有 `amount = rentAmount + depositAmount`。
   * 在支付成功时结算（`paidAt` 落定后才是有意义的覆盖区间）。
   */
  private derivePaymentPeriod(payment: FakePayment): void {
    const lease = this.state.leases.find((item) => item.id === payment.leaseId)
    if (!lease) return
    if (payment.paymentType === 'FIRST') {
      const rent = Math.min(payment.amount, lease.monthlyRent)
      payment.rentAmount = rent
      payment.depositAmount = Number((payment.amount - rent).toFixed(2))
      payment.periodStart = lease.startDate
      payment.periodEnd = addMonths(lease.startDate, 1)
      return
    }
    payment.rentAmount = payment.amount
    payment.depositAmount = 0
    payment.periodStart = addMonths(lease.endDate, -payment.months)
    payment.periodEnd = lease.endDate
  }

  /** 模拟用户在沙箱渠道完成付款：把支付单置 SUCCESS 并按类型联动（首期不顺延 / 续期顺延） */
  settlePayment(paymentNo: string): void {
    const payment = this.state.payments.find((item) => item.paymentNo === paymentNo)
    if (!payment) fail(CODES.NOT_FOUND, '支付单不存在')
    if (payment.status !== 'PENDING') return
    payment.status = 'SUCCESS'
    payment.paidAt = nowIso()
    const lease = this.state.leases.find((item) => item.id === payment.leaseId)
    if (!lease) return
    if (payment.paymentType === 'RENEWAL') {
      const before = lease.endDate
      lease.endDate = addMonths(lease.endDate, payment.months)
      this.state.leaseChanges.push({
        id: this.nextId('leaseChange'),
        leaseId: lease.id,
        changeType: 'RENEW',
        fieldName: 'endDate',
        oldValue: before,
        newValue: lease.endDate,
        operatorType: 'RESIDENT',
        createdAt: nowIso()
      })
      return
    }
    /* 首期支付：不改租约状态、不顺延 end_date（§6.7d），只落支付单与押金状态；
       押金为 0 的首期成功不置 PAID（避免「可退 0 元」的假可退状态） */
    const paidDeposit = payment.depositAmount ?? 0
    if (paidDeposit > 0) lease.depositStatus = 'PAID'
  }

  /** 模拟定时任务判定公告过期下线（§5 NoticeAutoOfflineTask） */
  expireNotices(asOf = dateOnly(new Date())): number {
    let count = 0
    for (const notice of this.state.notices) {
      if (notice.status === 'PUBLISHED' && notice.expireTime && notice.expireTime < asOf) {
        notice.status = 'EXPIRED'
        count += 1
      }
    }
    return count
  }

  /* ============================ 各模块 api 实现 ============================ */

  resident = {
    getMyProfile: async () => {
      const actor = currentActor()
      /* 无匹配档案必须 404（DEF-126，对齐真后端 profile→requireResident）：
         不得静默回退 residents[0]——那会用别人的档案掩盖越权/脏数据回归 */
      const found = this.state.residents.find((item) => item.id === actor.id)
      if (!found) fail(CODES.NOT_FOUND, '当前身份无对应居民档案')
      return found
    },
    getResidentList: async (params?: PageParams) => pageOf(this.state.residents, params),
    createResidenceApplication: async (data: { houseId: number; relationType?: string; remark?: string }) => {
      const actor = currentActor()
      this.recordCall('resident', 'createResidenceApplication', [data])
      const house = this.state.houses.find((item) => item.id === data.houseId)
      if (!house) fail(CODES.NOT_FOUND, '房屋不存在')
      /* 房屋状态校验（ErrorCode.HOUSE_NOT_VACANT 5205） */
      if (house.status !== 'VACANT') fail(CODES.HOUSE_NOT_VACANT, '房屋当前不可申请入住')
      const duplicate = this.state.residenceApplications.find(
        (item) => item.residentId === actor.id && item.houseId === data.houseId && item.status === 'PENDING'
      )
      if (duplicate) {
        fail(CODES.APPLICATION_PENDING_EXISTS, '已有待审核的入住申请')
      }
      const application: FakeResidenceApplication = {
        id: this.nextId('residenceApplication'),
        residentId: actor.id,
        /* 社区由房屋推导（前端不传 communityId，与真实后端同口径） */
        communityId: house.communityId,
        houseId: data.houseId,
        status: 'PENDING',
        reviewRemark: null,
        /* V26：租约关联在审批通过时才写入 */
        leaseId: null,
        createdAt: nowIso()
      }
      this.state.residenceApplications.push(application)
      return application
    },
    getResidenceApplication: async (id: number) => {
      const found = this.state.residenceApplications.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '入住申请不存在')
      return found
    },
    getResidenceApplicationList: async (params?: { status?: string } & PageParams) => {
      const actor = currentActor()
      /* 居民只看本人；管理端只看绑定社区（数据级权限口径） */
      let rows = this.state.residenceApplications.filter((item) =>
        actor.role === 'RESIDENT' ? item.residentId === actor.id : actor.communityIds.includes(item.communityId)
      )
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      const view = rows.map((item) => {
        const house = this.state.houses.find((house) => house.id === item.houseId)
        const resident = this.state.residents.find((person) => person.id === item.residentId)
        return {
          ...item,
          houseLocation: house ? this.houseLocation(house.id) : '',
          communityName: this.communityName(item.communityId),
          residentName: resident?.name ?? '',
          residentPhone: resident?.phone ?? '',
          /* V26 强关联：只读申请表写死的 lease_id（为空即无关联，不猜测） */
          ...this.applicationLeaseLink(item)
        }
      })
      return pageOf(view, params)
    },
    approveResidenceApplication: async (id: number, data?: { remark?: string }) => {
      const actor = currentActor()
      const application = this.state.residenceApplications.find((item) => item.id === id)
      if (!application) fail(CODES.NOT_FOUND, '入住申请不存在')
      /* 重复审核：申请已终态 → `APPLICATION_ALREADY_REVIEWED(5202)`，
         且不再生成第二条租约（R66：仅 PENDING 可审批） */
      if (application.status !== 'PENDING') {
        fail(CODES.APPLICATION_ALREADY_REVIEWED, '入住申请已审核')
      }
      application.status = 'APPROVED'
      application.reviewRemark = data?.remark ?? null
      /* 审核通过只到「待签约」：房屋不置已入住（§6.2） */
      const community = this.state.communities.find((item) => item.id === application.communityId)
      const months = community?.defaultLeaseMonths ?? 12
      const start = dateOnly(new Date())
      const lease: FakeLease = {
        id: this.nextId('lease'),
        tenantId: application.residentId,
        tenantName: this.state.residents.find((item) => item.id === application.residentId)?.name ?? '',
        communityId: application.communityId,
        houseId: application.houseId,
        houseLocation: this.houseLocation(application.houseId),
        startDate: start,
        endDate: addMonths(start, months),
        monthlyRent: 3000,
        deposit: 6000,
        status: 'TO_SIGN',
        agreementStatus: 'NONE',
        tenantConfirmAt: null,
        tenantConfirmBy: null,
        conversationId: null,
        createdAt: nowIso()
      }
      this.state.leases.push(lease)
      /* V26 强关联：审批通过时把生成的租约写死在申请表上（`residence_application.lease_id`），
         VO 直接取该列——**为空即"无关联"，不做任何按房屋回退猜测** */
      application.leaseId = lease.id
      this.state.leaseChanges.push({
        id: this.nextId('leaseChange'),
        leaseId: lease.id,
        changeType: 'CREATE',
        operatorType: 'ADMIN',
        newValue: 'TO_SIGN',
        createdAt: nowIso()
      })
      /* 自动建租约群聊（R68：参与者=申请居民 + 本社区启用管理员）；同租约建群幂等 */
      let conversation = this.state.conversations.find(
        (item) => item.type === 'LEASE_GROUP' && item.relatedId === lease.id
      )
      if (!conversation) {
        conversation = {
          id: this.nextId('conversation'),
          type: 'LEASE_GROUP',
          relatedId: lease.id,
          title: `租约群聊 · ${lease.houseLocation}`,
          status: 'ACTIVE',
          participantIds: [lease.tenantId, actor.id],
          createdAt: nowIso()
        }
        this.state.conversations.push(conversation)
      }
      lease.conversationId = conversation.id
      /* v1.8 现行口径：仍建 residence_relation(ACTIVE)（房屋保持 VACANT） */
      if (
        !this.state.residenceRelations.some(
          (item) => item.residentId === application.residentId && item.houseId === application.houseId
        )
      ) {
        this.state.residenceRelations.push({
          id: this.nextId('residenceRelation'),
          residentId: application.residentId,
          houseId: application.houseId,
          status: 'ACTIVE',
          moveOutAt: null
        })
      }
      this.notify('RESIDENT', application.residentId, {
        title: '入住申请已通过',
        content: `您对 ${lease.houseLocation} 的入住申请已通过，租约待签约`,
        sourceType: 'RESIDENCE_APPLICATION',
        sourceId: application.id
      })
      return application
    },
    rejectResidenceApplication: async (id: number, data?: { remark?: string; reason?: string }) => {
      const application = this.state.residenceApplications.find((item) => item.id === id)
      if (!application) fail(CODES.NOT_FOUND, '入住申请不存在')
      if (application.status !== 'PENDING') {
        fail(CODES.APPLICATION_ALREADY_REVIEWED, '入住申请已审核')
      }
      application.status = 'REJECTED'
      application.reviewRemark = data?.remark ?? data?.reason ?? null
      /* 驳回不生成租约（§6.5） */
      this.notify('RESIDENT', application.residentId, {
        title: '入住申请已驳回',
        content: `驳回理由：${application.reviewRemark ?? '无'}`,
        sourceType: 'RESIDENCE_APPLICATION',
        sourceId: application.id
      })
      return null
    },
    withdrawResidenceApplication: async (id: number) => {
      const actor = currentActor()
      const application = this.state.residenceApplications.find((item) => item.id === id)
      if (!application) fail(CODES.NOT_FOUND, '入住申请不存在')
      if (application.residentId !== actor.id) fail(CODES.FORBIDDEN, '无权操作他人申请')
      /* 仅待审核可撤回，终态再撤 → 5605 */
      if (application.status !== 'PENDING') {
        fail(CODES.APPLICATION_NOT_WITHDRAWABLE, '当前状态不可撤回')
      }
      application.status = 'WITHDRAWN'
      application.reviewRemark = '申请人主动撤回'
      return application
    },
    getMyResidenceRelations: async () => this.state.residenceRelations,
    getResidentResidenceList: async () => pageOf(this.state.residenceRelations),
    getHouseResidentList: async () => pageOf([])
  }

  lease = {
    getLeaseList: async (params?: { status?: string; keyword?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.leases.filter((item) =>
        actor.role === 'RESIDENT' ? item.tenantId === actor.id : actor.communityIds.includes(item.communityId)
      )
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(rows.map((item) => this.leaseView(item)), params)
    },
    getExpiringLeaseList: async (params?: PageParams) => {
      const rows = this.state.leases.filter((item) => item.status === 'ACTIVE')
      return pageOf(rows.map((item) => this.leaseView(item)), params)
    },
    getLease: async (id: number) => {
      const lease = this.state.leases.find((item) => item.id === id)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      this.assertLeaseOwned(lease, currentActor())
      return this.leaseView(lease)
    },
    getLeaseChanges: async (id: number) =>
      this.state.leaseChanges.filter((item) => item.leaseId === id).map((item) => ({ ...item })),
    confirmTenancy: async (id: number) => {
      const actor = currentActor()
      const lease = this.state.leases.find((item) => item.id === id)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      if (actor.role === 'ADMIN' || actor.role === 'SUPER_ADMIN') {
        /* 管理端不可代确认（R67 越权拒绝） */
        fail(CODES.FORBIDDEN, '管理端不可代居民确认承租')
      }
      /* 仅本人租约可确认（§9.19.3） */
      this.assertLeaseOwned(lease, actor)
      /* 承约不设硬前置（用户裁决 2026-09-22）：看房与租约沟通群都只是**自愿的交通手段**，
         不构成承约门槛——后端已撤掉 DEF-077 硬前置（5607/5608 仅保留错误码定义）。
         承约后由管理方直接推进协议确认（见 confirmLeaseAgreement 的 DEF-078 去重）。 */
      if (!lease.tenantConfirmAt) {
        lease.tenantConfirmAt = nowIso()
        lease.tenantConfirmBy = actor.id
      }
      /* 幂等：重复确认不覆盖首次留痕 */
      return this.leaseView(lease)
    },
    listLeasePayments: async (id: number) =>
      this.state.payments
        .filter((item) => item.leaseId === id)
        .map((item) => this.paymentView(item, false)),
    /* R72 押金退还挂在同一 `@/api/lease` 模块下（前端真实模块划分如此），
       故此处按名转发到 depositRefund 实现，避免同一逻辑两份实现漂移 */
    createDepositRefund: (id: number, data: { amount?: number; remark?: string; channel?: 'ALIPAY' | 'WECHAT'; outRequestNo: string }) =>
      this.depositRefund.createDepositRefund(id, data),
    listDepositRefunds: (id: number) => this.depositRefund.listDepositRefunds(id),
    listLeaseViewings: async () => this.state.viewingAppointments,
    createConversationViewing: async (
      conversationId: number,
      data: { housingId: number; appointmentDate: string; startTime: string; endTime: string }
    ) => {
      const conversation = this.state.conversations.find((item) => item.id === conversationId)
      if (!conversation) fail(CODES.NOT_FOUND, '会话不存在')
      const housing = this.state.housings.find((item) => item.id === data.housingId)
      if (!housing) fail(CODES.NOT_FOUND, '房源不存在')
      const appointment: FakeViewingAppointment = {
        id: this.nextId('viewingAppointment'),
        housingId: housing.id,
        houseId: housing.houseId,
        communityId: housing.communityId,
        visitorName: '演示居民',
        visitorPhone: '13800000001',
        appointmentDate: data.appointmentDate,
        startTime: data.startTime,
        endTime: data.endTime,
        status: 'TO_CONFIRM',
        assigneeId: null,
        assigneeName: null,
        leaseId: conversation.type === 'LEASE_GROUP' ? conversation.relatedId : null,
        createdAt: nowIso()
      }
      this.state.viewingAppointments.push(appointment)
      return appointment
    },
    createLease: async (data: { communityId: number; houseId: number; tenantId: number; monthlyRent: number; deposit: number; startDate: string; endDate: string }) => {
      const lease: FakeLease = {
        id: this.nextId('lease'),
        tenantId: data.tenantId,
        tenantName: this.state.residents.find((item) => item.id === data.tenantId)?.name ?? '',
        communityId: data.communityId,
        houseId: data.houseId,
        houseLocation: this.houseLocation(data.houseId),
        startDate: data.startDate,
        endDate: data.endDate,
        monthlyRent: data.monthlyRent,
        deposit: data.deposit,
        status: 'PENDING',
        agreementStatus: 'NONE',
        tenantConfirmAt: null,
        tenantConfirmBy: null,
        conversationId: null,
        createdAt: nowIso()
      }
      this.state.leases.push(lease)
      this.state.leaseChanges.push({
        id: this.nextId('leaseChange'),
        leaseId: lease.id,
        changeType: 'CREATE',
        operatorType: 'ADMIN',
        createdAt: nowIso()
      })
      return this.leaseView(lease)
    },
    updateLease: async (id: number, data: Partial<FakeLease>) => {
      const lease = this.state.leases.find((item) => item.id === id)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      /* 属性变更逐字段留痕（V18） */
      for (const [key, value] of Object.entries(data)) {
        const before = (lease as unknown as Record<string, unknown>)[key]
        if (before !== value && typeof value !== 'object') {
          this.state.leaseChanges.push({
            id: this.nextId('leaseChange'),
            leaseId: id,
            changeType: 'ATTRIBUTE',
            fieldName: key,
            oldValue: before == null ? null : String(before),
            newValue: value == null ? null : String(value),
            operatorType: 'ADMIN',
            createdAt: nowIso()
          })
        }
      }
      Object.assign(lease, data)
      return this.leaseView(lease)
    },
    updateLeaseStatus: async (id: number, data: { status: FakeLease['status']; remark?: string }) => {
      const lease = this.state.leases.find((item) => item.id === id)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      const allowed: Record<string, string[]> = {
        TO_SIGN: ['ACTIVE', 'REJECTED'],
        ACTIVE: ['MOVED_OUT'],
        MOVED_OUT: ['ARCHIVED'],
        PENDING: ['TO_SIGN', 'REJECTED']
      }
      if (!(allowed[lease.status] ?? []).includes(data.status)) {
        fail(CODES.LEASE_STATUS_INVALID, `租约状态 ${lease.status} 不允许流转为 ${data.status}`)
      }
      this.changeStatus(lease, data.status, 'ADMIN')
      if (data.status === 'ARCHIVED') this.closeLeaseGroup(lease)
      return null
    },
    renewLease: async (id: number, data: { months: number }) => {
      const lease = this.state.leases.find((item) => item.id === id)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      if (lease.status !== 'ACTIVE') fail(CODES.LEASE_STATUS_INVALID, '仅已生效租约可续租')
      const before = lease.endDate
      lease.endDate = addMonths(lease.endDate, data.months)
      this.state.leaseChanges.push({
        id: this.nextId('leaseChange'),
        leaseId: id,
        changeType: 'RENEW',
        fieldName: 'endDate',
        oldValue: before,
        newValue: lease.endDate,
        operatorType: 'ADMIN',
        createdAt: nowIso()
      })
      return this.leaseView(lease)
    }
  }

  payment = {
    listPaymentChannels: async () => this.state.channels.map((item) => ({ ...item })),
    createFirstPayment: async (leaseId: number, data: { channel: 'ALIPAY' | 'WECHAT' }) => {
      const actor = currentActor()
      const lease = this.state.leases.find((item) => item.id === leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      this.assertLeaseOwned(lease, actor)
      /* 校验顺序照抄接口设计 §9.19.15 */
      if (lease.status !== 'ACTIVE') fail(CODES.LEASE_STATUS_INVALID, '租约状态不允许支付首期费用')
      const success = this.state.payments.find(
        (item) => item.leaseId === leaseId && item.paymentType === 'FIRST' && item.status === 'SUCCESS'
      )
      if (success) fail(CODES.PAYMENT_ALREADY_EXISTS, '该类型支付单已完成')
      /* 止期严格早于当天才拒（当天到期仍可支付），续期不受限（DEF-069） */
      if (daysFromToday(lease.endDate) < 0) {
        fail(CODES.LEASE_EXPIRED, `租约已到期（止期 ${lease.endDate}），请续租或办理退租`)
      }
      const pending = this.state.payments.find(
        (item) => item.leaseId === leaseId && item.paymentType === 'FIRST' && item.status === 'PENDING'
      )
      if (pending) {
        /* 幂等续接：原样返回该单并补回渠道凭据（DEF-068） */
        pending.alipayForm = this.credentialFor(pending)
        return this.paymentView(pending, true)
      }
      if (!this.state.channels.find((item) => item.channel === data.channel)?.enabled) {
        fail(CODES.PAYMENT_CHANNEL_DISABLED, '该支付渠道未配置')
      }
      const payment: FakePayment = {
        paymentNo: `FIRST${Date.now()}`,
        leaseId,
        paymentType: 'FIRST',
        channel: data.channel,
        months: 1,
        amount: lease.monthlyRent + lease.deposit,
        status: 'PENDING',
        createdAt: nowIso(),
        paidAt: null,
        alipayForm: null,
        qrCode: null
      }
      /* R73 派生字段在创建时即算好（覆盖区间以租约当前租期为准） */
      this.derivePaymentPeriod(payment)
      payment.alipayForm = this.credentialFor(payment)
      this.state.payments.push(payment)
      return this.paymentView(payment, true)
    },
    createRenewPayment: async (leaseId: number, data: { months: number; channel: 'ALIPAY' | 'WECHAT' }) => {
      const actor = currentActor()
      const lease = this.state.leases.find((item) => item.id === leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      this.assertLeaseOwned(lease, actor)
      if (lease.status !== 'ACTIVE') fail(CODES.LEASE_STATUS_INVALID, '租约状态不允许续期支付')
      const pending = this.state.payments.find(
        (item) => item.leaseId === leaseId && item.paymentType === 'RENEWAL' && item.status === 'PENDING'
      )
      if (pending && pending.months === data.months) {
        pending.alipayForm = this.credentialFor(pending)
        return this.paymentView(pending, true)
      }
      if (!this.state.channels.find((item) => item.channel === data.channel)?.enabled) {
        fail(CODES.PAYMENT_CHANNEL_DISABLED, '该支付渠道未配置')
      }
      const payment: FakePayment = {
        paymentNo: `RENEW${Date.now()}`,
        leaseId,
        paymentType: 'RENEWAL',
        channel: data.channel,
        months: data.months,
        amount: lease.monthlyRent * data.months,
        status: 'PENDING',
        createdAt: nowIso(),
        paidAt: null,
        alipayForm: null,
        qrCode: null
      }
      this.derivePaymentPeriod(payment)
      payment.alipayForm = this.credentialFor(payment)
      this.state.payments.push(payment)
      return this.paymentView(payment, true)
    },
    getPaymentStatus: async (paymentNo: string) => {
      const payment = this.state.payments.find((item) => item.paymentNo === paymentNo)
      if (!payment) fail(CODES.NOT_FOUND, '支付单不存在')
      return this.paymentView(payment, false)
    },
    closePayment: async (paymentNo: string) => {
      const payment = this.state.payments.find((item) => item.paymentNo === paymentNo)
      if (!payment) fail(CODES.NOT_FOUND, '支付单不存在')
      if (payment.status === 'PENDING') payment.status = 'CLOSED'
      return null
    },
    listMyPayments: async (params?: PageParams) => {
      const actor = currentActor()
      const rows = this.state.payments
        .filter((item) => this.state.leases.find((lease) => lease.id === item.leaseId)?.tenantId === actor.id)
        .map((item) => this.paymentView(item, false))
      return pageOf(rows, params)
    }
  }

  /**
   * R72 押金退还（接口设计 §9.20.1/§9.20.2，错误码按后端定稿 5703~5706）。
   *
   * 契约口径（照后端实现，不自行放宽）：
   * - 幂等**先于一切业务门禁**（DEF-126，对齐真后端校验段顺序）：同号重复提交
   *   **返回既有记录**（金额未显式传视为同额）；同租约同号但金额不一致 **5706**；
   * - 前置：租约 `MOVED_OUT`（否则 5602）且 `deposit_status='PAID'`（否则 5703）；
   * - 渠道：取该租约**成功**首期单的渠道原路退回；请求若带 channel 只做一致性校验（不一致 5705）；
   * - 金额：`amount` 空 = 全额（实缴押金 − 已成功退还）；超限 **5704**（报文含「可退 X 元」）；
   * - 失败回落 `PAID` + `fail_reason`，**重试须换新号**；全额退清置 `REFUNDED`；
   * - `remark` 不落 refund 表，写进 `lease_change_log`（`changeType=DEPOSIT_REFUND`）。
   */
  depositRefund = {
    createDepositRefund: async (
      leaseId: number,
      data: { amount?: number; remark?: string; channel?: 'ALIPAY' | 'WECHAT'; outRequestNo: string }
    ) => {
      const actor = currentActor()
      const lease = this.state.leases.find((item) => item.id === leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      this.assertCommunity(lease.communityId, actor)

      /* 幂等优先（DEF-126）：同号查找前移到 refundable 计算与前置门禁之前——
         重复提交必须先命中幂等回放，不得被后续门禁（全额已退后 5703/5704）或
         「amount 空 → 按可退余额折算 ≠ 既有金额」的旧比较误拒。一致性判据照真后端
         existingOrConflict：同租约 + 金额一致（未显式传金额视为同额）返回既有，
         请求号被复用到不同内容 → 5706 */
      const existing = this.state.depositRefunds.find(
        (item) => item.outRequestNo === data.outRequestNo
      )
      if (existing) {
        const sameLease = existing.leaseId === leaseId
        const sameAmount = data.amount == null || Math.abs(existing.amount - data.amount) <= 0.001
        if (!sameLease || !sameAmount) {
          fail(CODES.DEPOSIT_OUT_REQUEST_CONFLICT, '同一退款请求号的内容不一致')
        }
        return { ...existing }
      }

      if (lease.status !== 'MOVED_OUT') {
        fail(CODES.LEASE_STATUS_INVALID, '仅已搬出租约可发起押金退还')
      }
      if ((lease.depositStatus ?? 'UNPAID') !== 'PAID') {
        fail(
          CODES.DEPOSIT_STATUS_NOT_REFUNDABLE,
          `当前押金状态不允许退还：${lease.depositStatus ?? 'UNPAID'}`
        )
      }

      /* 实缴押金 = 成功首期单的押金部分；无成功首期单则渠道未知，不可退 */
      const firstPaid = this.state.payments.find(
        (item) => item.leaseId === leaseId && item.paymentType === 'FIRST' && item.status === 'SUCCESS'
      )
      if (!firstPaid) {
        fail(CODES.DEPOSIT_CHANNEL_MISMATCH, '未找到成功首期支付单，无法确定实缴渠道')
      }
      /* 渠道一致性校验：客户端传了渠道就必须与实缴渠道一致（不允许跨渠道退款） */
      if (data.channel && data.channel !== firstPaid.channel) {
        fail(CODES.DEPOSIT_CHANNEL_MISMATCH, '退款渠道与实缴渠道不一致，不允许跨渠道退款')
      }

      const paidDeposit = firstPaid.depositAmount ?? lease.deposit
      const refunded = this.state.depositRefunds
        .filter((item) => item.leaseId === leaseId && item.status === 'SUCCESS')
        .reduce((sum, item) => sum + item.amount, 0)
      const refundable = Math.max(0, paidDeposit - refunded)
      const amount = data.amount ?? refundable
      if (amount <= 0) fail(CODES.PARAM_INVALID, '退还金额须大于 0')
      if (amount > refundable) {
        fail(CODES.DEPOSIT_AMOUNT_EXCEEDED, `退款金额超过实缴押金，可退 ${refundable} 元`)
      }

      const refund: FakeDepositRefund = {
        id: this.nextId('depositRefund'),
        leaseId,
        amount,
        /* 原路退回：渠道取实缴支付单渠道，客户端不可指定 */
        channel: firstPaid.channel,
        outRequestNo: data.outRequestNo,
        status: 'SUCCESS',
        failReason: null,
        operatorId: actor.id,
        operatorName: '社区管理员',
        createdAt: nowIso(),
        refundedAt: nowIso()
      }
      this.state.depositRefunds.push(refund)
      /* 全额退清才置 REFUNDED；部分退还后仍为 PAID（余额可继续退） */
      lease.depositStatus = refunded + amount >= paidDeposit - 0.001 ? 'REFUNDED' : 'PAID'
      /* 备注不落 refund 表：写进变更历史（前端时间线按该类型渲染备注） */
      this.state.leaseChanges.push({
        id: this.nextId('leaseChange'),
        leaseId,
        changeType: 'DEPOSIT_REFUND',
        fieldName: 'depositStatus',
        oldValue: 'PAID',
        newValue: lease.depositStatus,
        operatorType: 'ADMIN',
        createdAt: nowIso()
      })
      if (data.remark) {
        this.state.leaseChanges.push({
          id: this.nextId('leaseChange'),
          leaseId,
          changeType: 'DEPOSIT_REFUND',
          fieldName: 'remark',
          fieldLabel: '押金退还备注',
          newValue: data.remark,
          operatorType: 'ADMIN',
          createdAt: nowIso()
        })
      }
      this.notify('RESIDENT', lease.tenantId, {
        title: '押金已退还',
        content: `租约（${lease.houseLocation}）押金 ¥${amount} 已原路退还至${refund.channel === 'ALIPAY' ? '支付宝' : '微信支付'}`,
        sourceType: 'LEASE_PAYMENT',
        sourceId: refund.id
      })
      return { ...refund }
    },
    listDepositRefunds: async (leaseId: number) => {
      const actor = currentActor()
      const lease = this.state.leases.find((item) => item.id === leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      this.assertLeaseOwned(lease, actor)
      this.assertCommunity(lease.communityId, actor)
      return this.state.depositRefunds
        .filter((item) => item.leaseId === leaseId)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .map((item) => ({ ...item }))
    }
  }

  leaseMoveOut = {
    createMoveOutApplication: async (
      leaseId: number,
      data: { expectedMoveOutDate?: string; moveOutDate?: string; reason: string }
    ) => {
      const actor = currentActor()
      const lease = this.state.leases.find((item) => item.id === leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      this.assertLeaseOwned(lease, actor)
      if (lease.status !== 'ACTIVE') fail(CODES.LEASE_STATUS_INVALID, '仅已生效租约可申请退租')
      const existing = this.state.moveOutApplications.find(
        (item) => item.leaseId === leaseId && item.status === 'PENDING'
      )
      if (existing) fail(CODES.MOVE_OUT_APPLICATION_PENDING, '已有待审核的退租申请')
      const application: FakeMoveOutApplication = {
        id: this.nextId('moveOutApplication'),
        leaseId,
        residentId: lease.tenantId,
        communityId: lease.communityId,
        moveOutDate: data.moveOutDate ?? data.expectedMoveOutDate ?? dateOnly(new Date()),
        reason: data.reason,
        status: 'PENDING',
        reviewRemark: null,
        createdAt: nowIso()
      }
      this.state.moveOutApplications.push(application)
      return { ...application, houseLocation: this.houseLocation(lease.houseId) }
    },
    listMoveOutApplications: async (params?: { status?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.moveOutApplications.filter((item) =>
        actor.communityIds.includes(item.communityId)
      )
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(
        rows.map((item) => ({
          ...item,
          houseLocation: this.houseLocation(
            this.state.leases.find((lease) => lease.id === item.leaseId)?.houseId ?? 0
          ),
          residentName: this.state.residents.find((person) => person.id === item.residentId)?.name ?? ''
        })),
        params
      )
    },
    approveMoveOutApplication: async (id: number, data?: { remark?: string }) => {
      const application = this.state.moveOutApplications.find((item) => item.id === id)
      if (!application) fail(CODES.NOT_FOUND, '退租申请不存在')
      if (application.status !== 'PENDING') fail(CODES.LEASE_STATUS_INVALID, '该退租申请已处理')
      application.status = 'APPROVED'
      application.reviewRemark = data?.remark ?? null
      const lease = this.state.leases.find((item) => item.id === application.leaseId)
      if (lease) {
        this.changeStatus(lease, 'MOVED_OUT', 'ADMIN')
        /* 房屋置空置（R70） */
        const house = this.state.houses.find((item) => item.id === lease.houseId)
        if (house) house.status = 'VACANT'
        /* 居住关系终止并记搬出时间 */
        const relation = this.state.residenceRelations.find(
          (item) => item.residentId === lease.tenantId && item.houseId === lease.houseId && item.status === 'ACTIVE'
        )
        if (relation) {
          relation.status = 'MOVED_OUT'
          relation.moveOutAt = nowIso()
        }
        /* 租约群聊关闭（停收停发，历史只读） */
        this.closeLeaseGroup(lease)
        this.notify('RESIDENT', lease.tenantId, {
          title: '退租申请已通过',
          content: `租约（${lease.houseLocation}）已办理退租，群聊关闭只读`,
          sourceType: 'LEASE_RECORD',
          sourceId: lease.id
        })
      }
      return application
    },
    rejectMoveOutApplication: async (id: number, data?: { remark?: string; reason?: string }) => {
      const application = this.state.moveOutApplications.find((item) => item.id === id)
      if (!application) fail(CODES.NOT_FOUND, '退租申请不存在')
      if (application.status !== 'PENDING') fail(CODES.LEASE_STATUS_INVALID, '该退租申请已处理')
      application.status = 'REJECTED'
      application.reviewRemark = data?.remark ?? data?.reason ?? null
      /* 驳回后租约保持已生效（CF-E3-F） */
      return application
    }
  }

  agreement = {
    listAgreementTemplates: async (params?: PageParams) => pageOf(this.state.agreementTemplates, params),
    listAvailableAgreementTemplates: async (communityId: number) =>
      this.state.agreementTemplates.filter(
        (item) => item.status === 'ACTIVE' && (item.communityId === null || item.communityId === communityId)
      ),
    getAgreementTemplate: async (id: number) => {
      const found = this.state.agreementTemplates.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '协议模板不存在')
      return found
    },
    createAgreementTemplate: async (data: { communityId: number | null; name: string; content: string }) => {
      const template = { id: this.nextId('agreementTemplate'), status: 'ACTIVE' as const, ...data }
      this.state.agreementTemplates.push(template)
      return template
    },
    updateAgreementTemplate: async (id: number, data: { name?: string; content?: string }) => {
      const found = this.state.agreementTemplates.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '协议模板不存在')
      Object.assign(found, data)
      return found
    },
    updateAgreementTemplateStatus: async (id: number, status: 'ACTIVE' | 'INACTIVE') => {
      const found = this.state.agreementTemplates.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '协议模板不存在')
      found.status = status
      return found
    },
    deleteAgreementTemplate: async (id: number) => {
      this.state.agreementTemplates = this.state.agreementTemplates.filter((item) => item.id !== id)
      return null
    },
    listLeaseAgreements: async (leaseId: number) =>
      this.state.agreements.filter((item) => item.leaseId === leaseId),
    createLeaseAgreement: async (data: { leaseId: number; templateId: number }) => {
      const lease = this.state.leases.find((item) => item.id === data.leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租约不存在')
      /* 前置：未确认承租 → 5601（R67） */
      if (!lease.tenantConfirmAt) {
        fail(CODES.LEASE_TENANCY_NOT_CONFIRMED, '居民尚未确认承租，不可发起协议')
      }
      const unfinished = this.state.agreements.find(
        (item) => item.leaseId === data.leaseId && item.status !== 'SIGNED' && item.status !== 'CANCELLED'
      )
      if (unfinished) fail(CODES.LEASE_STATUS_INVALID, '该租约已有未完成的租赁协议')
      const template = this.state.agreementTemplates.find((item) => item.id === data.templateId)
      const agreement: FakeAgreement = {
        id: this.nextId('agreement'),
        leaseId: data.leaseId,
        templateId: data.templateId,
        status: 'PENDING',
        /* 正文按发起时租约数据渲染为快照（R64） */
        content: (template?.content ?? '')
          .replace('{{tenantName}}', lease.tenantName)
          .replace('{{houseLocation}}', lease.houseLocation)
          .replace('{{monthlyRent}}', String(lease.monthlyRent)),
        tenantConfirmedAt: null,
        adminConfirmedAt: null,
        createdAt: nowIso()
      }
      this.state.agreements.push(agreement)
      lease.agreementStatus = 'PENDING'
      this.state.leaseChanges.push({
        id: this.nextId('leaseChange'),
        leaseId: lease.id,
        changeType: 'AGREEMENT',
        newValue: 'PENDING',
        operatorType: 'ADMIN',
        createdAt: nowIso()
      })
      return agreement
    },
    getLeaseAgreement: async (id: number) => {
      const found = this.state.agreements.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '租赁协议不存在')
      return found
    },
    confirmLeaseAgreement: async (id: number, data: { party: 'TENANT' | 'ADMIN'; remark?: string }) => {
      const agreement = this.state.agreements.find((item) => item.id === id)
      if (!agreement) fail(CODES.NOT_FOUND, '租赁协议不存在')
      if (agreement.status === 'CANCELLED') fail(CODES.LEASE_STATUS_INVALID, '协议已撤回')
      const lease = this.state.leases.find((item) => item.id === agreement.leaseId)
      if (data.party === 'TENANT') {
        if (!agreement.tenantConfirmedAt) agreement.tenantConfirmedAt = nowIso()
      } else {
        if (!agreement.adminConfirmedAt) agreement.adminConfirmedAt = nowIso()
        /* DEF-078 去重：居民「同意承租」只在承约采集一次——管理方确认时若协议上还没有
           居民侧留痕，直接引用租约的承约留痕（与后端 AgreementService.confirm 同口径） */
        if (!agreement.tenantConfirmedAt && lease?.tenantConfirmAt) {
          agreement.tenantConfirmedAt = lease.tenantConfirmAt
        }
      }
      /* 双方齐 → SIGNED；联动：TO_SIGN → ACTIVE + 房屋置已入住（§6.2/§6.7a） */
      if (agreement.tenantConfirmedAt && agreement.adminConfirmedAt) {
        agreement.status = 'SIGNED'
        if (lease && lease.status === 'TO_SIGN') {
          this.changeStatus(lease, 'ACTIVE', 'SYSTEM')
          const house = this.state.houses.find((item) => item.id === lease.houseId)
          if (house) house.status = 'OCCUPIED'
          const conversation = this.state.conversations.find((item) => item.id === lease.conversationId)
          if (conversation) {
            this.state.conversationMessages.push({
              id: this.nextId('message'),
              conversationId: conversation.id,
              senderType: 'SYSTEM',
              senderId: 0,
              senderName: '系统',
              content: '租赁协议双方已确认，租约已生效',
              createdAt: nowIso()
            })
          }
          this.notify('RESIDENT', lease.tenantId, {
            title: '租约已生效',
            content: `${lease.houseLocation} 的租赁协议双方已确认，租约正式生效`,
            sourceType: 'LEASE_SIGN',
            sourceId: agreement.id
          })
        }
      } else {
        agreement.status = 'PARTIAL'
      }
      if (lease) lease.agreementStatus = agreement.status === 'SIGNED' ? 'SIGNED' : 'PARTIAL'
      return agreement
    },
    cancelLeaseAgreement: async (id: number, reason: string) => {
      const agreement = this.state.agreements.find((item) => item.id === id)
      if (!agreement) fail(CODES.NOT_FOUND, '租赁协议不存在')
      if (agreement.status === 'SIGNED') fail(CODES.LEASE_STATUS_INVALID, '已双方确认的协议不可撤回')
      agreement.status = 'CANCELLED'
      agreement.cancelReason = reason
      const lease = this.state.leases.find((item) => item.id === agreement.leaseId)
      if (lease) lease.agreementStatus = 'NONE'
      return null
    }
  }

  conversation = {
    listConversations: async (params?: PageParams) => {
      const actor = currentActor()
      /* 参与者过滤与 getConversation/消息端点同口径（DEF-126）：居民与系统用户 id
         会撞号，平面 participantIds 过滤会把「同号另一身份空间」的会话串见进来，
         必须走 (space, id, role) 三元组判定 */
      const rows = this.state.conversations.filter((item) => this.isConversationParticipant(item, actor))
      return pageOf(
        rows.map((item) => ({
          id: item.id,
          type: item.type,
          relatedId: item.relatedId,
          title: item.title,
          status: item.status,
          lastMessage: this.state.conversationMessages
            .filter((message) => message.conversationId === item.id)
            .at(-1)?.content ?? '',
          updatedAt: item.createdAt
        })),
        params
      )
    },
    getConversation: async (id: number) => {
      const found = this.state.conversations.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '会话不存在')
      /* 仅参与者可见（§9.21.2）：非参与者 403；看房/租约群聊无身份空间明细时沿用旧判定 */
      const actor = currentActor()
      if (!this.isConversationParticipant(found, actor)) {
        fail(CODES.FORBIDDEN, '非会话参与者不可查看该会话')
      }
      return {
        ...found,
        participantCount: found.participantRefs?.length ?? found.participantIds.length,
        participants: found.participantRefs?.map((item) => ({
          userId: item.id,
          name: item.name ?? '',
          role: item.role ?? (item.space === 'RESIDENT' ? 'RESIDENT' : 'ADMIN'),
          ownerType: item.space
        }))
      }
    },
    listConversationMessages: async (conversationId: number) => {
      const found = this.state.conversations.find((item) => item.id === conversationId)
      if (!found) fail(CODES.NOT_FOUND, '会话不存在')
      const actor = currentActor()
      if (!this.isConversationParticipant(found, actor)) {
        fail(CODES.FORBIDDEN, '非会话参与者不可查看该会话消息')
      }
      return this.state.conversationMessages.filter((item) => item.conversationId === conversationId)
    },
    sendConversationMessage: async (conversationId: number, data: { content: string }) => {
      const conversation = this.state.conversations.find((item) => item.id === conversationId)
      if (!conversation) fail(CODES.NOT_FOUND, '会话不存在')
      const actor = currentActor()
      if (!this.isConversationParticipant(conversation, actor)) {
        fail(CODES.FORBIDDEN, '非会话参与者不可发送消息')
      }
      /* CLOSED 后停收停发（§6.7c / §9.21.2：工单终审结束后再发言 → 5604） */
      if (conversation.status === 'CLOSED') {
        fail(CODES.CONVERSATION_CLOSED, '会话已关闭，无法继续发送消息')
      }
      const message: FakeConversationMessage = {
        id: this.nextId('message'),
        conversationId,
        senderType: actor.role === 'RESIDENT' ? 'RESIDENT' : actor.role === 'STAFF' ? 'STAFF' : 'ADMIN',
        senderId: actor.id,
        senderName:
          conversation.participantRefs?.find(
            (item) => item.id === actor.id && item.space === (actor.role === 'RESIDENT' ? 'RESIDENT' : 'USER') && item.role === actor.role
          )?.name ?? (actor.role === 'RESIDENT' ? '演示居民' : '社区管理员'),
        content: data.content,
        createdAt: nowIso()
      }
      this.state.conversationMessages.push(message)
      return message
    },
    /**
     * 创建/获取直通会话（DEF-076）：
     * - 居民端：本人（传他人 403）；
     * - **管理端/超管代居民发起**：须指定 `residentId`（缺省 → 400），未绑定该社区 → 403；
     * - 幂等键 =（DIRECT，社区，居民）——两个入口谁先发起都命中同一条会话。
     */
    openDirectConversation: async (data: { communityId: number; residentId?: number }) => {
      const actor = currentActor()
      const adminInitiated = actor.role === 'ADMIN' || actor.role === 'SUPER_ADMIN'
      let residentId = data.residentId ?? null
      if (adminInitiated) {
        if (!actor.communityIds.includes(data.communityId)) {
          fail(CODES.FORBIDDEN, '无权操作未绑定社区的数据')
        }
        if (residentId === null) {
          fail(CODES.PARAM_INVALID, '请指定直通会话的居民')
        }
        if (!this.state.residents.some((item) => item.id === residentId)) {
          fail(CODES.NOT_FOUND, '居民不存在')
        }
      } else {
        if (residentId !== null && residentId !== actor.id) {
          fail(CODES.FORBIDDEN, '居民仅可为本人发起直通会话')
        }
        residentId = actor.id
      }
      const residentName = this.state.residents.find((item) => item.id === residentId)?.name ?? '居民'
      /* 幂等命中：该居民（居民身份空间）在该社区的 DIRECT 会话 */
      let conversation = this.state.conversations.find(
        (item) =>
          item.type === 'DIRECT' &&
          item.communityId === data.communityId &&
          (item.participantRefs ?? []).some((ref) => ref.space === 'RESIDENT' && ref.id === residentId)
      )
      if (!conversation) {
        /* 参与者（真实后端同一事务内拉入）：该居民 + 该社区启用管理员——
           管理端代发起时为发起人本人，居民端发起时取演示管理员 id=1 */
        const adminId = adminInitiated ? actor.id : 1
        conversation = {
          id: this.nextId('conversation'),
          type: 'DIRECT',
          relatedId: null,
          communityId: data.communityId,
          title: `${residentName} 与社区管理员`,
          status: 'ACTIVE',
          participantIds: Array.from(new Set([residentId, adminId])),
          participantRefs: [
            { id: residentId, space: 'RESIDENT', name: residentName, role: 'RESIDENT' },
            { id: adminId, space: 'USER', name: '社区管理员', role: 'ADMIN' }
          ],
          createdAt: nowIso()
        }
        this.state.conversations.push(conversation)
      }
      return conversation
    },
    /**
     * 建/取租约沟通会话（DEF-076 补建入口）：ADMIN/SUPER_ADMIN，幂等，
     * 命中或新建后回写 `lease_record.conversation_id`。
     */
    ensureLeaseGroupConversation: async (leaseId: number) => {
      const actor = currentActor()
      if (actor.role !== 'ADMIN' && actor.role !== 'SUPER_ADMIN') {
        fail(CODES.FORBIDDEN, '仅社区管理员可建立租约沟通会话')
      }
      const lease = this.state.leases.find((item) => item.id === leaseId)
      if (!lease) fail(CODES.NOT_FOUND, '租住记录不存在')
      if (!actor.communityIds.includes(lease.communityId)) {
        fail(CODES.FORBIDDEN, '无权操作未绑定社区的数据')
      }
      let conversation = this.state.conversations.find(
        (item) => item.type === 'LEASE_GROUP' && item.relatedId === leaseId
      )
      if (!conversation) {
        conversation = {
          id: this.nextId('conversation'),
          type: 'LEASE_GROUP',
          relatedId: leaseId,
          communityId: lease.communityId,
          title: `租约沟通 · ${lease.houseLocation}`,
          status: 'ACTIVE',
          participantIds: [lease.tenantId, actor.id],
          participantRefs: [
            {
              id: lease.tenantId,
              space: 'RESIDENT',
              name: this.state.residents.find((item) => item.id === lease.tenantId)?.name ?? '居民',
              role: 'RESIDENT'
            },
            { id: actor.id, space: 'USER', name: '社区管理员', role: 'ADMIN' }
          ],
          createdAt: nowIso()
        }
        this.state.conversations.push(conversation)
      }
      /* 回写租约的会话 id（真实后端同一事务内回写） */
      lease.conversationId = conversation.id
      return conversation
    },
  }

  workorder = {
    getServiceCategoryTree: async (communityId: number) =>
      this.state.serviceCategories
        .filter((item) => item.status === 'ACTIVE' && item.communityId === communityId)
        .map((item) => ({ id: item.id, name: item.name, children: [] })),
    submitWorkOrder: async (data: { communityId: number; categoryId: number; title: string; description: string }) => {
      const actor = currentActor()
      const order: FakeWorkOrder = {
        id: this.nextId('workOrder'),
        orderNo: `WO${Date.now()}`,
        residentId: actor.id,
        communityId: data.communityId,
        categoryId: data.categoryId,
        title: data.title,
        description: data.description,
        status: 'PENDING',
        assigneeId: null,
        assigneeName: null,
        createdAt: nowIso()
      }
      this.state.workOrders.push(order)
      return order
    },
    listWorkOrders: async (params?: { status?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.workOrders.filter((item) => {
        if (actor.role === 'RESIDENT') return item.residentId === actor.id
        if (actor.role === 'STAFF') return item.assigneeId === actor.id
        return actor.communityIds.includes(item.communityId)
      })
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(rows, params)
    },
    getWorkOrder: async (id: number) => {
      const found = this.state.workOrders.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '工单不存在')
      return found
    },
    getWorkOrderTimeline: async (id: number) =>
      this.state.workOrderProcesses.filter((item) => item.orderId === id),
    listAssignableStaff: async () => [
      { id: 1, name: '服务人员一号', communityId: 1, categoryIds: [1], onDuty: true }
    ],
    listWorkOrderAttachments: async () => [],
    /** 工单状态流转：只允许状态机内的合法流转，非法 → 5004（§6.1） */
    /* 派单：接口设计 §9.4.2.5 写「仅 SUBMITTED 可派单」，实现口径 SUBMITTED = 待受理/待派单
       （架构 §6.1：PENDING/TO_ASSIGN → ASSIGNED） */
    /* 派单 / 改派：接口设计 §9.4.2.5「已派单=改派」——与 dispatch.ts 的 canAssignOrder 同口径 */
    assignWorkOrder: async (id: number, data: { assigneeId: number; assigneeName?: string; remark?: string }) => {
      const order = this.transitionOrder(id, ['PENDING', 'TO_ASSIGN', 'ASSIGNED'], 'ASSIGNED', '派单', {
        assigneeId: data.assigneeId,
        assigneeName: data.assigneeName ?? '服务人员一号',
        remark: data.remark
      })
      /* 派单后被派服务人员自动入群（§9.21.2「派单成功后内部接线」，幂等不重复建群） */
      this.joinWorkOrderGroup(order, data.assigneeId, 'USER', data.assigneeName ?? '服务人员一号', 'STAFF')
      return order
    },
    acceptWorkOrder: async (id: number, data?: { remark?: string }) =>
      this.transitionOrder(id, ['ASSIGNED'], 'ACCEPTED', '接单', { remark: data?.remark }),
    processWorkOrder: async (id: number, data?: { remark?: string }) =>
      this.transitionOrder(id, ['ACCEPTED'], 'IN_PROGRESS', '开始处理', { remark: data?.remark }),
    completeWorkOrder: async (id: number, data: { remark?: string }) =>
      this.transitionOrder(id, ['IN_PROGRESS'], 'TO_CONFIRM', '提交结果', { remark: data?.remark }),
    confirmWorkOrder: async (id: number, data?: { remark?: string }) =>
      this.transitionOrder(id, ['TO_CONFIRM'], 'COMPLETED', '居民确认', { remark: data?.remark }),
    /**
     * 管理端受理（接口设计 §9.21.1，R74）：仅 `PENDING` 可受理（否则 5302，硬口径非幂等），
     * `PENDING → TO_ASSIGN`，**同一事务自动建立工单临时群聊**（成员＝居民提交人 + 受理管理员）
     * 并回填 `conversationId`（响应即回带）。
     */
    intakeWorkOrder: async (id: number, data?: { remark?: string }) => {
      const actor = currentActor()
      if (actor.role !== 'ADMIN' && actor.role !== 'SUPER_ADMIN') {
        fail(CODES.FORBIDDEN, '仅社区管理员可受理工单')
      }
      const order = this.transitionOrder(id, ['PENDING'], 'TO_ASSIGN', '受理', { remark: data?.remark })
      const conversation = this.ensureWorkOrderGroup(order, actor.id)
      order.conversationId = conversation.id
      this.notify('RESIDENT', order.residentId, {
        title: '工单已受理',
        content: `您的工单 ${order.orderNo} 已受理，工单沟通群已建立`,
        sourceType: 'WORK_ORDER',
        sourceId: order.id
      })
      return order
    },
    /**
     * 终审结束（复用 §9.4.2.10 关闭端点，§9.21.3）：前置收窄为**仅 `COMPLETED`**（否则 5302），
     * 关闭同时把工单临时群聊回收为 `CLOSED`（只读留痕，不物理删除）。
     */
    closeWorkOrder: async (id: number, data?: { remark?: string }) => {
      const order = this.transitionOrder(id, ['COMPLETED'], 'CLOSED', '终审关闭', { remark: data?.remark })
      this.closeWorkOrderGroup(order)
      this.notify('RESIDENT', order.residentId, {
        title: '工单已结束',
        content: `您的工单 ${order.orderNo} 已终审结束（已关闭），工单沟通群回收为只读`,
        sourceType: 'WORK_ORDER',
        sourceId: order.id
      })
      return order
    },
    rejectWorkOrder: async (id: number, data: { reason: string }) =>
      this.transitionOrder(id, ['PENDING'], 'REJECTED', '驳回', { remark: data.reason }),
    cancelWorkOrder: async (id: number, data: { reason: string }) =>
      this.transitionOrder(id, ['PENDING', 'TO_ASSIGN'], 'CANCELLED', '取消', { remark: data.reason }),
    /* 居民不满意退回（R22，后端 PATCH /work-orders/{id}/return）：待确认 → 处理中，
       须填原因（WorkOrderActionDTO.remark @NotBlank → 400），退回后通知当前服务人员 */
    returnWorkOrder: async (id: number, data: { remark: string }) => {
      if (!data?.remark || !String(data.remark).trim()) {
        fail(CODES.PARAM_INVALID, '退回原因不能为空')
      }
      const order = this.transitionOrder(id, ['TO_CONFIRM'], 'IN_PROGRESS', '居民不满意退回', {
        remark: data.remark
      })
      if (order.assigneeId) {
        this.notify('USER', order.assigneeId, {
          title: '工单被退回处理中',
          content: `工单 ${order.orderNo} 被居民退回：${data.remark}`,
          sourceType: 'WORK_ORDER',
          sourceId: order.id
        })
      }
      return null
    }
    /* R74 起「受理」已有正式端点（接口设计 §9.21.1 `PATCH /work-orders/{id}/intake`），
       即上方 workorder.intakeWorkOrder；沙箱动作 acceptIncomingWorkOrder 保留为
       直调封装（core-flows 中间态构造用），语义与端点一致。 */
  }

  /**
   * 沙箱直调「受理」（PENDING → TO_ASSIGN）——正式端点见 `workorder.intakeWorkOrder`
   * （§9.21.1，含自动建群与受理管理员入群）。本方法只做状态流转，供 core-flows
   * 构造已达受理态的中途数据使用。
   */
  acceptIncomingWorkOrder(id: number, data?: { remark?: string }): FakeWorkOrder {
    return this.transitionOrder(id, ['PENDING'], 'TO_ASSIGN', '受理', { remark: data?.remark })
  }

  private transitionOrder(
    id: number,
    from: FakeWorkOrder['status'][],
    to: FakeWorkOrder['status'],
    action: string,
    extra: { assigneeId?: number; assigneeName?: string; remark?: string } = {}
  ): FakeWorkOrder {
    const order = this.state.workOrders.find((item) => item.id === id)
    if (!order) fail(CODES.WORK_ORDER_NOT_FOUND, '工单不存在')
    if (!from.includes(order.status)) {
      /* 工单非法流转 → WORK_ORDER_INVALID_TRANSITION(5302)（ErrorCode.java L50） */
      fail(CODES.WORK_ORDER_INVALID_TRANSITION, `工单当前状态（${order.status}）不允许「${action}」`)
    }
    const actor = currentActor()
    order.status = to
    if (extra.assigneeId) {
      order.assigneeId = extra.assigneeId
      order.assigneeName = extra.assigneeName ?? null
    }
    this.state.workOrderProcesses.push({
      id: this.nextId('workOrderProcess'),
      orderId: id,
      action,
      operatorName: actor.role === 'RESIDENT' ? '演示居民' : actor.role === 'STAFF' ? '服务人员一号' : '社区管理员',
      remark: extra.remark ?? null,
      createdAt: nowIso()
    })
    return order
  }

  evaluation = {
    submitEvaluation: async (orderId: number, data: { score: number; content: string }) => {
      const order = this.state.workOrders.find((item) => item.id === orderId)
      if (!order) fail(CODES.WORK_ORDER_NOT_FOUND, '工单不存在')
      if (order.status !== 'COMPLETED') {
        fail(CODES.EVALUATION_NOT_ALLOWED, '工单未完成，不可评价')
      }
      const exists = this.state.evaluations.find((item) => item.orderId === orderId)
      if (exists) fail(CODES.EVALUATION_DUPLICATE, '已评价，不可重复评价')
      const evaluation: FakeEvaluation = {
        id: this.nextId('evaluation'),
        orderId,
        score: data.score,
        content: data.content,
        satisfied: data.score > 2,
        followups: [],
        createdAt: nowIso()
      }
      this.state.evaluations.push(evaluation)
      return evaluation
    },
    getEvaluation: async (orderId: number) =>
      this.state.evaluations.find((item) => item.orderId === orderId) ?? null,
    listEvaluations: async (params?: PageParams) => pageOf(this.state.evaluations, params),
    listUnsatisfiedEvaluations: async (params?: PageParams) =>
      pageOf(
        this.state.evaluations.filter((item) => !item.satisfied),
        params
      ),
    addEvaluationFollowup: async (evaluationId: number, data: { content: string }) => {
      const evaluation = this.state.evaluations.find((item) => item.id === evaluationId)
      if (!evaluation) fail(CODES.NOT_FOUND, '评价不存在')
      const followup = {
        id: this.nextId('followup'),
        content: data.content,
        operatorName: '社区管理员',
        createdAt: nowIso()
      }
      evaluation.followups.push(followup)
      return followup
    },
    listEvaluationFollowups: async (evaluationId: number) =>
      this.state.evaluations.find((item) => item.id === evaluationId)?.followups ?? [],
    getEvaluationStatistics: async () => ({ total: this.state.evaluations.length, satisfiedRate: 100 })
  }

  notice = {
    createNotice: async (data: {
      title: string
      content: string
      priority?: FakeNotice['priority']
      isPinned?: boolean
      targets?: FakeNotice['targets']
      expireTime?: string
    }) => {
      const notice: FakeNotice = {
        id: this.nextId('notice'),
        title: data.title,
        content: data.content,
        status: 'DRAFT',
        noticeType: 'ANNOUNCEMENT',
        priority: data.priority ?? 'NORMAL',
        isPinned: data.isPinned ?? false,
        targets: data.targets ?? [],
        publishTime: null,
        expireTime: data.expireTime ?? null,
        viewCount: 0,
        createdAt: nowIso()
      }
      this.state.notices.push(notice)
      return notice
    },
    updateNotice: async (id: number, data: Partial<FakeNotice>) => {
      const notice = this.state.notices.find((item) => item.id === id)
      if (!notice) fail(CODES.NOT_FOUND, '公告不存在')
      Object.assign(notice, data)
      return notice
    },
    publishNotice: async (id: number, data: { publishTime: string }) => {
      const notice = this.state.notices.find((item) => item.id === id)
      if (!notice) fail(CODES.NOT_FOUND, '公告不存在')
      notice.status = 'PUBLISHED'
      notice.publishTime = data.publishTime
      return null
    },
    withdrawNotice: async (id: number, data: { reason: string }) => {
      const notice = this.state.notices.find((item) => item.id === id)
      if (!notice) fail(CODES.NOT_FOUND, '公告不存在')
      notice.status = 'WITHDRAWN'
      notice.withdrawReason = data.reason
      return null
    },
    deleteNotice: async (id: number) => {
      this.state.notices = this.state.notices.filter((item) => item.id !== id)
      return null
    },
    getNotice: async (id: number) => {
      const notice = this.state.notices.find((item) => item.id === id)
      if (!notice) fail(CODES.NOT_FOUND, '公告不存在')
      return notice
    },
    /** 可见性口径：游客仅可见含 GUEST 目标的 PUBLISHED；居民可见本社区/全社区目标；管理端看本社区 */
    listNotices: async (params?: { status?: string; targetType?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.notices.filter((item) => {
        if (actor.role === 'GUEST') {
          return (
            item.status === 'PUBLISHED' &&
            item.targets.some((target) => target.targetType === 'GUEST')
          )
        }
        if (item.status !== 'PUBLISHED') return false
        return item.targets.some(
          (target) =>
            target.targetType === 'GUEST' ||
            (target.targetType === 'COMMUNITY' && actor.communityIds.includes(Number(target.targetId)))
        )
      })
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      if (params?.targetType === 'GUEST') {
        rows = rows.filter((item) => item.targets.some((target) => target.targetType === 'GUEST'))
      }
      rows = [...rows].sort((a, b) => Number(b.isPinned) - Number(a.isPinned))
      return pageOf(rows, params)
    },
    recordNoticeView: async (id: number) => {
      if (!this.state.noticeViews.includes(id)) this.state.noticeViews.push(id)
      const notice = this.state.notices.find((item) => item.id === id)
      if (notice) notice.viewCount += 1
      return null
    },
    listNoticeViewers: async (id: number, params?: PageParams) => {
      const rows = this.state.noticeViews.includes(id)
        ? [{ id: 1, residentName: '演示居民', viewedAt: nowIso() }]
        : []
      return pageOf(rows, params)
    }
  }

  feedback = {
    createFeedback: async (data: { communityId: number; title: string; content: string }) => {
      const actor = currentActor()
      const feedback: FakeFeedback = {
        id: this.nextId('feedback'),
        residentId: actor.id,
        communityId: data.communityId,
        title: data.title,
        content: data.content,
        status: 'PENDING',
        closeConclusion: null,
        messages: [],
        createdAt: nowIso()
      }
      this.state.feedbacks.push(feedback)
      return feedback
    },
    getFeedbackDetail: async (id: number) => {
      const feedback = this.state.feedbacks.find((item) => item.id === id)
      if (!feedback) fail(CODES.NOT_FOUND, '反馈不存在')
      return feedback
    },
    listFeedbacks: async (params?: { status?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.feedbacks.filter((item) =>
        actor.role === 'RESIDENT' ? item.residentId === actor.id : actor.communityIds.includes(item.communityId)
      )
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(
        rows.map((item) => ({
          ...item,
          residentName: this.state.residents.find((person) => person.id === item.residentId)?.name ?? '',
          messageCount: item.messages.length
        })),
        params
      )
    },
    closeFeedback: async (id: number, data: { conclusion: string }) => {
      const feedback = this.state.feedbacks.find((item) => item.id === id)
      if (!feedback) fail(CODES.NOT_FOUND, '反馈不存在')
      /* 非法流转 → 5004（接口设计 §3.2 已勘误：原 5601 系笔误） */
      if (feedback.status !== 'PENDING' && feedback.status !== 'IN_SESSION') {
        fail(CODES.STATE_TRANSITION_INVALID, '当前状态不可办结')
      }
      feedback.status = 'CLOSED'
      feedback.closeConclusion = data.conclusion
      return null
    },
    sendFeedbackMessage: async (feedbackId: number, data: { content: string }) => {
      const feedback = this.state.feedbacks.find((item) => item.id === feedbackId)
      if (!feedback) fail(CODES.NOT_FOUND, '反馈不存在')
      /* 办结后会话归档只读（R30）：不可再发消息 */
      if (feedback.status === 'CLOSED') {
        fail(CODES.STATE_TRANSITION_INVALID, '反馈已办结，会话只读，不可再发送消息')
      }
      const actor = currentActor()
      const message: FakeConversationMessage = {
        id: this.nextId('message'),
        conversationId: feedbackId,
        senderType: actor.role === 'RESIDENT' ? 'RESIDENT' : 'ADMIN',
        senderId: actor.id,
        senderName: actor.role === 'RESIDENT' ? '演示居民' : '社区管理员',
        content: data.content,
        createdAt: nowIso()
      }
      feedback.messages.push(message)
      /* 首次会话消息即把「待受理」推进为「会话中」（§9.6.3.1：反馈状态自动更新为会话中） */
      if (feedback.status === 'PENDING') feedback.status = 'IN_SESSION'
      return message
    },
    listFeedbackMessages: async (feedbackId: number) =>
      this.state.feedbacks.find((item) => item.id === feedbackId)?.messages ?? [],
    listFeedbackAttachments: async () => [],
    uploadFeedbackAttachment: async () => ({ id: 1, fileName: 'a.png', fileUrl: '/uploads/a.png' }),
    deleteFeedbackAttachment: async () => null
  }

  reservation = {
    /* 可约时段：响应字段名为 timeslotId（IAvailableTimeslot），查询参数为 startDate/endDate */
    listAvailableTimeslots: async (
      resourceId: number,
      query?: { startDate?: string; endDate?: string; slotDate?: string }
    ) => {
      const inUse = new Set(
        this.state.reservations
          .filter((item) => ['PENDING', 'RESERVED', 'COMPLETED'].includes(item.status))
          .map((item) => item.timeslotId)
      )
      const from = query?.startDate ?? query?.slotDate ?? dateOnly(new Date())
      const to = query?.endDate ?? from
      return this.state.timeslots
        .filter(
          (item) => item.resourceId === resourceId && item.slotDate >= from && item.slotDate <= to
        )
        .map((item) => ({
          timeslotId: item.id,
          date: item.slotDate,
          startTime: item.startTime,
          endTime: item.endTime,
          maxBookings: item.capacity,
          currentBookings: inUse.has(item.id) ? item.capacity : 0,
          status: inUse.has(item.id) ? 'FULL' : item.status
        }))
    },
    createReservation: async (data: { resourceId: number; timeslotId: number }) => {
      const actor = currentActor()
      const timeslot = this.state.timeslots.find((item) => item.id === data.timeslotId)
      if (!timeslot) fail(CODES.NOT_FOUND, '时段不存在')
      /* 同一资源同一时段重叠 → 5401（R33：并发/重叠 100% 拦截） */
      const conflict = this.state.reservations.find(
        (item) =>
          item.timeslotId === data.timeslotId && ['PENDING', 'RESERVED', 'COMPLETED'].includes(item.status)
      )
      if (conflict) fail(CODES.RESERVATION_CONFLICT, '该时段已被预约，请选择其它时段')
      const reservation: FakeReservation = {
        id: this.nextId('reservation'),
        residentId: actor.id,
        residentName: this.state.residents.find((item) => item.id === actor.id)?.name ?? '演示居民',
        resourceId: data.resourceId,
        timeslotId: data.timeslotId,
        status: 'PENDING',
        reason: null,
        createdAt: nowIso()
      }
      this.state.reservations.push(reservation)
      return reservation
    },
    listReservations: async (params?: { status?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.reservations.filter((item) =>
        actor.role === 'RESIDENT' ? item.residentId === actor.id : true
      )
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(
        rows.map((item) => ({
          ...item,
          resourceName: this.state.resources.find((resource) => resource.id === item.resourceId)?.name ?? '',
          slotDate: this.state.timeslots.find((slot) => slot.id === item.timeslotId)?.slotDate ?? '',
          startTime: this.state.timeslots.find((slot) => slot.id === item.timeslotId)?.startTime ?? '',
          endTime: this.state.timeslots.find((slot) => slot.id === item.timeslotId)?.endTime ?? ''
        })),
        params
      )
    },
    getReservationDetail: async (id: number) => {
      const found = this.state.reservations.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '预约不存在')
      return found
    },
    confirmReservation: async (id: number) =>
      this.changeReservation(id, ['PENDING'], 'RESERVED'),
    rejectReservation: async (id: number, data: { reason: string }) =>
      this.changeReservation(id, ['PENDING'], 'REJECTED', data.reason),
    completeReservation: async (id: number) => this.changeReservation(id, ['RESERVED'], 'COMPLETED'),
    cancelReservation: async (id: number, data: { reason?: string }) =>
      this.changeReservation(id, ['PENDING', 'RESERVED'], 'CANCELLED', data?.reason),
    violateReservation: async (id: number, data: { reason: string }) =>
      this.changeReservation(id, ['RESERVED'], 'VIOLATED', data.reason),
    listResidentViolations: async () => pageOf([])
  }

  private changeReservation(
    id: number,
    from: FakeReservation['status'][],
    to: FakeReservation['status'],
    reason?: string
  ): FakeReservation {
    const reservation = this.state.reservations.find((item) => item.id === id)
    if (!reservation) fail(CODES.NOT_FOUND, '预约不存在')
    if (!from.includes(reservation.status)) {
      fail(CODES.STATE_TRANSITION_INVALID, `预约状态 ${reservation.status} 不允许该流转`)
    }
    reservation.status = to
    if (reason) reservation.reason = reason
    return reservation
  }

  notification = {
    /** DEF-067：读取按 (owner_type, user_id) 收口，撞号不串 */
    listNotifications: async (params?: PageParams) => {
      const actor = currentActor()
      const ownerType = actor.role === 'RESIDENT' ? 'RESIDENT' : 'USER'
      const rows = this.state.notifications
        .filter((item) => item.ownerType === ownerType && item.userId === actor.id)
        .sort((a, b) => b.seq - a.seq)
      return pageOf(
        rows.map((item) => ({
          id: item.id,
          seq: item.seq,
          title: item.title,
          content: item.content,
          sourceType: item.sourceType,
          sourceId: item.sourceId,
          recipientId: item.userId,
          isRead: item.isRead,
          createdAt: item.createdAt
        })),
        params
      )
    },
    /* 假后端装配在 **api 层**：后端返回纯数组、api 层包成 {count, notifications}（§9.11.1） */
    listUnreadNotifications: async () => {
      const actor = currentActor()
      const ownerType = actor.role === 'RESIDENT' ? 'RESIDENT' : 'USER'
      const notifications = this.state.notifications
        .filter((item) => item.ownerType === ownerType && item.userId === actor.id && !item.isRead)
        .map((item) => ({
          id: item.id,
          seq: item.seq,
          title: item.title,
          content: item.content,
          sourceType: item.sourceType,
          sourceId: item.sourceId,
          createdAt: item.createdAt
        }))
      return { count: notifications.length, notifications }
    },
    pullNotifications: async (lastSeq: number) => {
      const actor = currentActor()
      const ownerType = actor.role === 'RESIDENT' ? 'RESIDENT' : 'USER'
      const notifications = this.state.notifications
        .filter((item) => item.ownerType === ownerType && item.userId === actor.id && item.seq > lastSeq)
        .map((item) => ({
          id: item.id,
          seq: item.seq,
          title: item.title,
          content: item.content,
          sourceType: item.sourceType,
          sourceId: item.sourceId,
          isRead: item.isRead,
          createdAt: item.createdAt
        }))
      const latestSeq = notifications.reduce((max, item) => Math.max(max, item.seq), lastSeq)
      /* 同为 api 层包裹结构（后端返回 {notifications, latestSeq} 由 api 层适配） */
      return { notifications, latestSeq }
    },
    markNotificationRead: async (id: number) => {
      const found = this.state.notifications.find((item) => item.id === id)
      if (found) found.isRead = true
      return null
    },
    markAllNotificationsRead: async () => {
      const actor = currentActor()
      const ownerType = actor.role === 'RESIDENT' ? 'RESIDENT' : 'USER'
      let count = 0
      for (const item of this.state.notifications) {
        if (item.ownerType === ownerType && item.userId === actor.id && !item.isRead) {
          item.isRead = true
          count += 1
        }
      }
      return { count }
    },
    getChannelLevels: async () => this.state.channelLevels,
    updateChannelLevels: async (levels: Record<string, string[]>) => {
      this.state.channelLevels = levels
      return null
    }
  }

  housing = {
    listHousings: async (params?: { status?: string } & PageParams) => {
      let rows = this.state.housings
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(
        rows.map((item) => ({
          ...item,
          images: item.images ?? [],
          communityName: this.communityName(item.communityId),
          houseLocation: this.houseLocation(item.houseId)
        })),
        params
      )
    },
    getHousingDetail: async (id: number) => {
      const housing = this.state.housings.find((item) => item.id === id)
      if (!housing) fail(CODES.NOT_FOUND, '房源不存在')
      return {
        ...housing,
        images: housing.images ?? [],
        communityName: this.communityName(housing.communityId),
        houseLocation: this.houseLocation(housing.houseId)
      }
    },
    createHousing: async (data: Partial<FakeHousing> & { houseId: number; communityId: number }) => {
      const housing: FakeHousing = {
        id: this.nextId('housing'),
        houseId: data.houseId,
        communityId: data.communityId,
        title: data.title ?? '新房源',
        monthlyRent: data.monthlyRent ?? 1000,
        deposit: data.deposit ?? 2000,
        status: 'AVAILABLE',
        rentType: data.rentType ?? 'RENT',
        layout: data.layout ?? '1室1厅',
        images: data.images ?? [],
        coverUrl: null
      }
      this.state.housings.push(housing)
      return housing
    },
    updateHousingStatus: async (id: number, data: { status: FakeHousing['status'] }) => {
      const housing = this.state.housings.find((item) => item.id === id)
      if (!housing) fail(CODES.NOT_FOUND, '房源不存在')
      housing.status = data.status
      return null
    },
    recordHousingView: async () => 1,
    listViewingAvailableSlots: async () => [
      { id: 1, slotDate: dateOnly(new Date()), startTime: '10:00', endTime: '11:00', remaining: 1 }
    ],
    createViewingAppointment: async (data: {
      housingId: number
      appointmentDate: string
      startTime: string
      endTime: string
      visitorName?: string
      visitorPhone?: string
    }) => {
      const housing = this.state.housings.find((item) => item.id === data.housingId)
      if (!housing) fail(CODES.NOT_FOUND, '房源不存在')
      const conflict = this.state.viewingAppointments.find(
        (item) =>
          item.housingId === data.housingId &&
          item.appointmentDate === data.appointmentDate &&
          item.startTime === data.startTime &&
          ['TO_CONFIRM', 'RESERVED'].includes(item.status)
      )
      if (conflict) fail(CODES.RESERVATION_CONFLICT, '该看房时段已被预约')
      const appointment: FakeViewingAppointment = {
        id: this.nextId('viewingAppointment'),
        housingId: housing.id,
        houseId: housing.houseId,
        communityId: housing.communityId,
        visitorName: data.visitorName ?? '游客',
        visitorPhone: data.visitorPhone ?? '13900000000',
        appointmentDate: data.appointmentDate,
        startTime: data.startTime,
        endTime: data.endTime,
        status: 'TO_CONFIRM',
        assigneeId: null,
        assigneeName: null,
        leaseId: null,
        createdAt: nowIso()
      }
      this.state.viewingAppointments.push(appointment)
      return appointment
    },
    listViewingAppointments: async (params?: { status?: string } & PageParams) => {
      const actor = currentActor()
      let rows = this.state.viewingAppointments.filter((item) =>
        actor.role === 'STAFF' ? item.assigneeId === actor.id : true
      )
      if (params?.status) rows = rows.filter((item) => item.status === params.status)
      return pageOf(rows, params)
    },
    getViewingAppointmentDetail: async (id: number) => {
      const found = this.state.viewingAppointments.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '看房预约不存在')
      return found
    },
    confirmViewingAppointment: async (id: number, data: { assigneeId?: number; assigneeName?: string }) => {
      const found = this.state.viewingAppointments.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '看房预约不存在')
      if (found.status !== 'TO_CONFIRM') fail(CODES.STATE_TRANSITION_INVALID, '当前状态不可确认')
      found.status = 'RESERVED'
      if (data?.assigneeId) {
        found.assigneeId = data.assigneeId
        found.assigneeName = data.assigneeName ?? '服务人员一号'
      }
      return null
    },
    assignViewingAppointment: async (id: number, data: { assigneeId: number; assigneeName?: string }) => {
      const found = this.state.viewingAppointments.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '看房预约不存在')
      found.assigneeId = data.assigneeId
      found.assigneeName = data.assigneeName ?? '服务人员一号'
      return found
    },
    completeViewingAppointment: async (id: number) => {
      const found = this.state.viewingAppointments.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '看房预约不存在')
      if (found.status !== 'RESERVED') fail(CODES.STATE_TRANSITION_INVALID, '当前状态不可完成')
      found.status = 'COMPLETED'
      return null
    },
    cancelViewingAppointment: async (id: number) => {
      const found = this.state.viewingAppointments.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '看房预约不存在')
      found.status = 'CANCELLED'
      return null
    },
    violateViewingAppointment: async (id: number) => {
      const found = this.state.viewingAppointments.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '看房预约不存在')
      found.status = 'VIOLATED'
      return null
    },
    listViewingMessages: async () => [],
    sendViewingMessage: async () => ({ id: 1, content: '', createdAt: nowIso() }),
    listHousingTimeslots: async () => [],
    createHousingTimeslot: async () => ({ id: 1 }),
    updateHousingTimeslot: async () => null,
    deleteHousingTimeslot: async () => null,
    listAssignableAssignees: async () => [{ id: 1, name: '服务人员一号', communityId: 1 }],
    deleteHousing: async () => null,
    updateHousing: async () => null
  }

  community = {
    getCommunityList: async () => this.state.communities.map((item) => ({ ...item })),
    getCommunity: async (id: number) => {
      const found = this.state.communities.find((item) => item.id === id)
      if (!found) fail(CODES.NOT_FOUND, '社区不存在')
      return found
    },
    getBuildingList: async (communityId: number, params?: PageParams) =>
      pageOf(
        this.state.buildings.filter((item) => item.communityId === communityId),
        params
      ),
    getBuilding: async (id: number) => this.state.buildings.find((item) => item.id === id) ?? null,
    getUnitList: async (buildingId: number, params?: PageParams) =>
      pageOf(
        this.state.units.filter((item) => item.buildingId === buildingId),
        params
      ),
    getUnit: async (id: number) => this.state.units.find((item) => item.id === id) ?? null,
    /* 房屋列表按单元维度（GET /units/{unitId}/houses，接口设计 §9.1.4.2） */
    getHouseList: async (unitId: number, params?: PageParams) =>
      pageOf(
        this.state.houses
          .filter((item) => item.unitId === unitId)
          .map((item) => ({ ...item, houseLocation: this.houseLocation(item.id) })),
        params
      ),
    listHouses: async (params?: { communityId?: number } & PageParams) => {
      const rows = this.state.houses
        .filter((item) => (params?.communityId ? item.communityId === params.communityId : true))
        .map((item) => ({ ...item, houseLocation: this.houseLocation(item.id) }))
      return pageOf(rows, params)
    },
    getHouse: async (id: number) => {
      const house = this.state.houses.find((item) => item.id === id)
      if (!house) fail(CODES.NOT_FOUND, '房屋不存在')
      return { ...house, houseLocation: this.houseLocation(id) }
    },
    getResourceList: async (communityId: number, params?: PageParams) =>
      pageOf(
        this.state.resources.filter((item) => item.communityId === communityId),
        params
      ),
    getTimeslotList: async (resourceId: number, params?: PageParams) =>
      pageOf(
        this.state.timeslots.filter((item) => item.resourceId === resourceId),
        params
      ),
    getResource: async (id: number) => {
      const resource = this.state.resources.find((item) => item.id === id)
      if (!resource) fail(CODES.NOT_FOUND, '资源不存在')
      return resource
    },
    /* ---- 结构初始化写接口（E1：社区→楼栋→单元→房屋→公共资源→时段） ---- */
    createCommunity: async (data: { name?: string } ) => {
      const community = {
        id: this.nextId('community'),
        name: data?.name ?? '新建社区',
        autoApproveResidence: 0,
        defaultLeaseMonths: 12
      }
      this.state.communities.push(community)
      return community
    },
    createBuilding: async (data: { communityId: number; name?: string; buildingNumber?: string }) => {
      const building = {
        id: this.nextId('building'),
        communityId: data.communityId,
        name: data.name ?? `${data.buildingNumber ?? this.state.buildings.length + 1} 号楼`,
        buildingNumber: data.buildingNumber ?? String(this.state.buildings.length + 1)
      }
      this.state.buildings.push(building)
      return building
    },
    createUnit: async (data: { buildingId: number; name?: string; unitNumber?: string }) => {
      const unit = {
        id: this.nextId('unit'),
        buildingId: data.buildingId,
        name: data.name ?? `${data.unitNumber ?? '1'} 单元`
      }
      this.state.units.push(unit)
      return unit
    },
    createHouse: async (data: {
      communityId: number
      buildingId: number
      unitId: number
      houseNumber?: string
      floor?: number
      area?: number
    }) => {
      const house: FakeHouse = {
        id: this.nextId('house'),
        communityId: data.communityId,
        buildingId: data.buildingId,
        unitId: data.unitId,
        houseNumber: data.houseNumber ?? '101',
        floor: data.floor ?? 1,
        area: data.area ?? 80,
        status: 'VACANT'
      }
      this.state.houses.push(house)
      return house
    },
    createResource: async (data: { communityId: number; name?: string; capacity?: number }) => {
      const resource: FakeResource = {
        id: this.nextId('resource'),
        communityId: data.communityId,
        name: data.name ?? '公共资源',
        status: 'AVAILABLE'
      }
      this.state.resources.push(resource)
      return resource
    },
    createTimeslot: async (
      resourceId: number,
      data: { slotDate: string; startTime: string; endTime: string; capacity?: number }
    ) => {
      const timeslot: FakeTimeslot = {
        id: this.nextId('timeslot'),
        resourceId,
        slotDate: data.slotDate,
        startTime: data.startTime,
        endTime: data.endTime,
        capacity: data.capacity ?? 1,
        status: 'AVAILABLE'
      }
      this.state.timeslots.push(timeslot)
      return timeslot
    }
  }

  statistics = {
    /* 工单统计（IWorkOrderStats：total + byStatus 映射） */
    getWorkOrderStats: async () => {
      const byStatus: Record<string, number> = {}
      for (const order of this.state.workOrders) {
        byStatus[order.status] = (byStatus[order.status] ?? 0) + 1
      }
      return { total: this.state.workOrders.length, byStatus }
    },
    /* 运营看板（IDashboardStats：扁平计数字段，与库内数据同源计算） */
    getDashboardStats: async () => {
      const houses = this.state.houses
      const byHouseStatus: Record<string, number> = {}
      for (const house of houses) {
        byHouseStatus[house.status] = (byHouseStatus[house.status] ?? 0) + 1
      }
      const byWorkOrderStatus: Record<string, number> = {}
      for (const order of this.state.workOrders) {
        byWorkOrderStatus[order.status] = (byWorkOrderStatus[order.status] ?? 0) + 1
      }
      const byRating: Record<string, number> = {}
      for (const evaluation of this.state.evaluations) {
        byRating[String(evaluation.score)] = (byRating[String(evaluation.score)] ?? 0) + 1
      }
      const expiring = this.state.leases.filter(
        (item) => item.status === 'ACTIVE' && this.expiryFlagOf(item) === 'EXPIRING'
      ).length
      return {
        communityCount: this.state.communities.length,
        buildingCount: this.state.buildings.length,
        houseCount: houses.length,
        occupiedHouseCount: houses.filter((item) => item.status === 'OCCUPIED').length,
        residentCount: this.state.residents.length,
        activeLeaseCount: this.state.leases.filter((item) => item.status === 'ACTIVE').length,
        expiringLeaseCount: expiring,
        workOrderTotal: this.state.workOrders.length,
        workOrderPending: (byWorkOrderStatus.PENDING ?? 0) + (byWorkOrderStatus.TO_ASSIGN ?? 0),
        workOrderProcessing:
          (byWorkOrderStatus.ASSIGNED ?? 0) +
          (byWorkOrderStatus.ACCEPTED ?? 0) +
          (byWorkOrderStatus.IN_PROGRESS ?? 0),
        workOrderCompleted: byWorkOrderStatus.COMPLETED ?? 0,
        reservationTotal: this.state.reservations.length,
        reservationPending: this.state.reservations.filter((item) => item.status === 'PENDING').length,
        violationCount: this.state.viewingAppointments.filter((item) => item.status === 'VIOLATED').length,
        evaluationTotal: this.state.evaluations.length,
        workOrderTrend7d: {},
        workOrderStatusDistribution: byWorkOrderStatus,
        houseStatusDistribution: byHouseStatus,
        ratingDistribution: byRating
      }
    },
    getCommunityOptions: async () =>
      this.state.communities.map((item) => ({ id: item.id, name: item.name }))
  }

  staff = {
    listStaffOptions: async () => [{ id: 1, name: '服务人员一号', communityId: 1 }],
    getMyViewingAppointments: async (params?: PageParams) => pageOf(this.state.viewingAppointments, params)
  }

  upload = {
    uploadFile: async () => ({ url: '/uploads/fake.png', fileName: 'fake.png' })
  }
}

/* ------------------------------ 装配入口 ------------------------------ */

let singleton: FakeBackend | null = null

/** 取得（必要时创建）单例：页面组件经 vi.mock 走的都是同一个实例 */
export function getFakeBackend(): FakeBackend {
  singleton ??= new FakeBackend()
  return singleton
}

/** 复位假后端并模拟登录为指定角色（每个用例开头调用） */
export function installFakeBackend(options: { role?: FakeRole; id?: number } = {}): FakeBackend {
  const backend = getFakeBackend()
  backend.reset()
  sessionStorage.clear()
  fakeLogin(options.role ?? 'RESIDENT', { id: options.id ?? 1 })
  return backend
}

/** 切换当前身份（三端交替推进时用；不动假后端状态） */
export function actAs(role: FakeRole, id = 1): void {
  fakeLogin(role, { id })
}

const MODULE_KEYS = [
  'resident',
  'lease',
  'payment',
  'leaseMoveOut',
  'agreement',
  'conversation',
  'workorder',
  'evaluation',
  'notice',
  'feedback',
  'reservation',
  'notification',
  'housing',
  'community',
  'statistics',
  'staff',
  'upload'
] as const

/**
 * 供 `vi.mock('@/api/xxx', () => fakeApi('xxx'))` 使用：
 * 已实现的函数转发到假后端；未实现的函数**大声失败**（而不是静默返回 undefined），
 * 便于第一时间发现"流程走过界"。
 */
export function fakeApi(moduleName: string): Record<string, unknown> {
  const backend = getFakeBackend()
  const impl = (backend as unknown as Record<string, Record<string, unknown>>)[moduleName] ?? {}
  return new Proxy(impl, {
    get(target, prop) {
      if (prop === 'then') return undefined
      if (prop === '__esModule') return true
      if (typeof prop === 'string' && prop in target) {
        const value = target[prop]
        return typeof value === 'function' ? (value as (...args: unknown[]) => unknown).bind(target) : value
      }
      return () => {
        throw new Error(`假后端未实现 @/api/${moduleName}.${String(prop)}()——请补实现或收敛用例范围`)
      }
    },
    has: () => true
  })
}

/** 便于 spec 一次装配全部模块（需在 spec 里逐个 vi.mock，见 core-flows.spec.ts 头注） */
export const FAKE_API_MODULES = MODULE_KEYS
