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

/** 工单紧急程度（Flyway V1 priority 列注释：LOW/NORMAL/HIGH/URGENT） */
export type WorkOrderUrgency = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

/** 工单紧急程度中文标签 */
export const workOrderUrgencyLabels: Record<WorkOrderUrgency, string> = {
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

/** 工单（接口设计.md 9.4.2.1 / 9.4.2.3 响应字段合并） */
export interface IWorkOrder {
  id: number
  orderNumber: string
  residentId: number
  residentName: string
  residentPhone: string
  categoryId: number
  categoryName: string
  title: string
  description: string
  contactPhone: string
  /** 预约上门时间（ISO 8601，可空） */
  appointmentTime: string | null
  urgency: WorkOrderUrgency
  status: WorkOrderStatus
  assigneeId: number | null
  assigneeName: string | null
  assignedAt: string | null
  acceptedAt: string | null
  processingAt: string | null
  completedAt: string | null
  confirmedAt: string | null
  closedAt: string | null
  rejectReason: string | null
  cancelReason: string | null
  createdAt: string
  updatedAt: string
}

/** 提交工单请求（接口设计.md 9.4.2.1） */
export interface IWorkOrderCreateRequest {
  categoryId: number
  title: string
  description: string
  contactPhone: string
  appointmentTime?: string
  urgency: WorkOrderUrgency
  attachmentIds?: number[]
}

/** 更新工单请求（接口设计.md 9.4.2.2，同提交但不允许改 categoryId） */
export interface IWorkOrderUpdateRequest {
  title: string
  description: string
  contactPhone: string
  appointmentTime?: string
  urgency: WorkOrderUrgency
  attachmentIds?: number[]
}

/** 工单列表查询参数（接口设计.md 9.4.2.4） */
export interface IWorkOrderQuery extends PageQuery {
  status?: WorkOrderStatus
  urgency?: WorkOrderUrgency
  categoryId?: number
  startTime?: string
  endTime?: string
  /** 匹配标题/描述 */
  keyword?: string
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

/** 驳回/取消工单请求（接口设计.md 9.4.2.11 / 9.4.2.12） */
export interface IWorkOrderReasonRequest {
  reason: string
}

/** 工单处理记录/时间线节点（接口设计.md 9.4.2.13） */
export interface IWorkOrderProcess {
  status: WorkOrderStatus
  operatorName: string
  remark: string | null
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
