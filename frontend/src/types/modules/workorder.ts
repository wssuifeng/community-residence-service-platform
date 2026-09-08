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
  createdAt: string
  updatedAt: string
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
