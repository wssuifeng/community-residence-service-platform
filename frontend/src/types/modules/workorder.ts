import type { PageQuery } from '@/types/api'

/**
 * 工单状态（10 状态，接口设计.md 9.4 / Flyway V1 work_order 表状态注释，
 * 状态机：待受理→待派单→已派单→已接单→处理中→待确认→已完成 + 已关闭/已驳回/已取消）
 */
export type WorkOrderStatus =
  | 'PENDING' // 待受理
  | 'TO_ASSIGN' // 待派单
  | 'ASSIGNED' // 已派单
  | 'ACCEPTED' // 已接单
  | 'IN_PROGRESS' // 处理中
  | 'TO_CONFIRM' // 待确认
  | 'COMPLETED' // 已完成
  | 'CLOSED' // 已关闭
  | 'REJECTED' // 已驳回
  | 'CANCELLED' // 已取消

/** 工单状态中文标签（供 StatusTag 使用） */
export const workOrderStatusLabels: Record<WorkOrderStatus, string> = {
  PENDING: '待受理',
  TO_ASSIGN: '待派单',
  ASSIGNED: '已派单',
  ACCEPTED: '已接单',
  IN_PROGRESS: '处理中',
  TO_CONFIRM: '待确认',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  REJECTED: '已驳回',
  CANCELLED: '已取消'
}

/** 工单优先级（后端 CreateWorkOrderDTO.priority：LOW/NORMAL/HIGH/URGENT，接口文档的 urgency 为漂移命名） */
export type WorkOrderPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

/** 工单优先级中文标签 */
export const workOrderPriorityLabels: Record<WorkOrderPriority, string> = {
  LOW: '低',
  NORMAL: '普通',
  HIGH: '高',
  URGENT: '紧急'
}

/** 工单附件文件类型（图片 ≤5MB，文档 ≤10MB） */
export type WorkOrderFileType = 'IMAGE' | 'DOCUMENT'

/** 工单附件文件类型中文标签 */
export const workOrderFileTypeLabels: Record<WorkOrderFileType, string> = {
  IMAGE: '图片',
  DOCUMENT: '文档'
}

/** 服务类别（接口设计.md 9.4.1） */
export interface IServiceCategory {
  id: number
  communityId: number
  /** 顶级类别为 null */
  parentId: number | null
  name: string
  description: string | null
  sortOrder: number
  createdAt: string
}

/** 创建/更新服务类别请求（接口设计.md 9.4.1.1 / 9.4.1.2） */
export interface IServiceCategorySaveRequest {
  communityId: number
  parentId?: number | null
  name: string
  description?: string
  sortOrder?: number
}

/** 服务类别树节点（接口设计.md 9.4.1.5，不含 parentId/communityId） */
export interface IServiceCategoryTreeNode {
  id: number
  name: string
  description: string | null
  sortOrder: number
  children: IServiceCategoryTreeNode[]
}

/** 工单（后端 WorkOrderVO：content/priority/orderNo，2026-09-08 验收对齐） */
export interface IWorkOrder {
  id: number
  orderNo: string
  residentId: number
  residentName: string
  communityId: number
  categoryId: number
  categoryName: string
  title: string
  content: string
  contactPhone: string | null
  address: string | null
  priority: WorkOrderPriority
  status: WorkOrderStatus
  assigneeId: number | null
  assigneeName: string | null
  /**
   * 调度标记（V19，列表/详情均回填，供调度视图一眼抓重点）：
   * OVERDUE-超时（待受理/待派单>30min、已派单>120min 未接单、处理中>1440min）、
   * URGENT-紧急且未完结、NEW-近 2 小时新建、NORMAL-常规
   */
  dispatchFlag?: DispatchFlag
  /** 进入当前状态以来的分钟数（无时间线记录时以创建时间兜底） */
  waitedMinutes?: number
  /** 当前处理人今日班次标签（为空=当日未排班） */
  assigneeShiftLabel?: string | null
  /** 当前处理人在手工单数（未完结工单数） */
  assigneeActiveOrders?: number
  createdAt: string
  updatedAt: string
}

/* ------------------------ 调度视图（V19）：标记与派单候选 ------------------------ */

/** 调度标记（后端口径见 WorkOrderService 阈值常量） */
export type DispatchFlag = 'OVERDUE' | 'URGENT' | 'NEW' | 'NORMAL'

/** 调度标记中文标签 */
export const dispatchFlagLabels: Record<DispatchFlag, string> = {
  OVERDUE: '超时',
  URGENT: '紧急',
  NEW: '新单',
  NORMAL: '常规'
}

/** 调度标记配色（列表强调色，OVERDUE 红色最重） */
export const dispatchFlagColors: Record<DispatchFlag, { bg: string; fg: string }> = {
  OVERDUE: { bg: 'rgba(239, 68, 68, 0.12)', fg: '#b91c1c' },
  URGENT: { bg: 'rgba(249, 115, 22, 0.14)', fg: '#c2410c' },
  NEW: { bg: 'rgba(59, 130, 246, 0.12)', fg: '#1d4ed8' },
  NORMAL: { bg: 'rgba(148, 163, 184, 0.16)', fg: '#475569' }
}

/** 工单列表排序口径（V19；不传=DEFAULT 保持原倒序） */
export type WorkOrderSort = 'DEFAULT' | 'WAIT_DESC' | 'PRIORITY'

/**
 * 派单候选（V19 档位推荐，GET /work-orders/assignable-staff）。
 * recommendLevel 越小越推荐：1=常驻本社区且擅长该类别，2=常驻本社区，3=擅长该类别，4=其他
 */
export interface IStaffOption {
  id: number
  realName: string
  recommendLevel?: number
  matchedCommunity?: boolean
  matchedCategory?: boolean
  /** 常驻社区名称（顿号/逗号拼接） */
  communityNames?: string
  /** 今日班次标签（为空=未排班） */
  todayShiftLabel?: string | null
  /** 在手工单数 */
  activeOrderCount?: number
}

/** 派单候选推荐档位标签 */
export const staffRecommendLevelLabels: Record<number, string> = {
  1: '常驻本社区 · 擅长该类别',
  2: '常驻本社区',
  3: '擅长该类别',
  4: '其他人员'
}

/** 提交工单请求（后端 CreateWorkOrderDTO：content/priority/address） */
export interface IWorkOrderCreateRequest {
  categoryId: number
  title: string
  content: string
  contactPhone: string
  address?: string
  priority: WorkOrderPriority
}

/** 更新工单请求（后端无独立更新端点，P1 仅创建） */
export interface IWorkOrderUpdateRequest {
  title: string
  content: string
  contactPhone: string
  priority: WorkOrderPriority
}

/** 工单列表查询参数（接口设计.md 9.4.2.4） */
export interface IWorkOrderQuery extends PageQuery {
  status?: WorkOrderStatus
  priority?: WorkOrderPriority
  categoryId?: number
  keyword?: string
  /** 按当前处理人过滤（V19） */
  assigneeId?: number
  /** 排序口径（V19）：DEFAULT-按ID倒序（默认）, WAIT_DESC-等待最久在前, PRIORITY-紧急优先 */
  sort?: WorkOrderSort
}

/** 派单请求（接口设计.md 9.4.2.5） */
export interface IWorkOrderAssignRequest {
  assigneeId: number
  remark?: string
}

/** 完成工单请求（接口设计.md 9.4.2.8） */
export interface IWorkOrderCompleteRequest {
  solution: string
  remark?: string
}

/** 驳回/取消工单请求（接口设计.md 9.4.2.11 / 9.4.2.12；后端 WorkOrderActionDTO 实际字段为 remark，reason 为文档漂移命名） */
export interface IWorkOrderReasonRequest {
  remark: string
}

/** 工单处理记录/时间线节点（接口设计.md 9.4.2.13；后端实际响应为 newStatus/content，status/remark 为文档漂移命名） */
export interface IWorkOrderProcess {
  action: string
  oldStatus: WorkOrderStatus | null
  newStatus: WorkOrderStatus
  content: string | null
  operatorId: number
  operatorName: string
  operatorType: string
  createdAt: string
}

/** 工单附件（接口设计.md 9.4.3.1 / 9.4.3.3） */
export interface IWorkOrderAttachment {
  id: number
  orderId: number
  fileName: string
  fileUrl: string
  fileSize: number
  fileType: WorkOrderFileType
  createdAt: string
}
