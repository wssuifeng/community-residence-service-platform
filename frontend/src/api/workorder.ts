import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IServiceCategory,
  IServiceCategorySaveRequest,
  IServiceCategoryTreeNode,
  IWorkOrder,
  IWorkOrderAssignRequest,
  IWorkOrderAttachment,
  IWorkOrderCompleteRequest,
  IWorkOrderCreateRequest,
  IWorkOrderProcess,
  IWorkOrderQuery,
  IWorkOrderReasonRequest,
  IWorkOrderUpdateRequest
} from '@/types/modules/workorder'

/** 创建服务类别（接口设计.md 9.4.1.1） */
export function createServiceCategory(data: IServiceCategorySaveRequest) {
  return http.post<IServiceCategory>('/service-categories', data)
}

/** 更新服务类别（接口设计.md 9.4.1.2） */
export function updateServiceCategory(id: number, data: IServiceCategorySaveRequest) {
  return http.put<IServiceCategory>(`/service-categories/${id}`, data)
}

/** 删除服务类别（有子类别/工单关联时后端拒绝，接口设计.md 9.4.1.3） */
export function deleteServiceCategory(id: number) {
  return http.delete<null>(`/service-categories/${id}`)
}

/** 查询服务类别详情（接口设计.md 9.4.1.4） */
export function getServiceCategory(id: number) {
  return http.get<IServiceCategory>(`/service-categories/${id}`)
}

/** 查询社区服务类别树（接口设计.md 9.4.1.5） */
export function getServiceCategoryTree(communityId: number) {
  return http.get<IServiceCategoryTreeNode[]>(`/communities/${communityId}/service-categories`)
}

/** 提交工单（接口设计.md 9.4.2.1） */
export function submitWorkOrder(data: IWorkOrderCreateRequest) {
  return http.post<IWorkOrder>('/work-orders', data)
}

/** 更新工单（居民仅待受理状态可改，接口设计.md 9.4.2.2） */
export function updateWorkOrder(id: number, data: IWorkOrderUpdateRequest) {
  return http.put<IWorkOrder>(`/work-orders/${id}`, data)
}

/** 查询工单详情（接口设计.md 9.4.2.3） */
export function getWorkOrder(id: number) {
  return http.get<IWorkOrder>(`/work-orders/${id}`)
}

/** 工单列表分页查询（接口设计.md 9.4.2.4） */
export function listWorkOrders(params: IWorkOrderQuery) {
  return http.get<PageResult<IWorkOrder>>('/work-orders', params)
}

/** 派单（接口设计.md 9.4.2.5，仅待受理状态可派） */
export function assignWorkOrder(id: number, data: IWorkOrderAssignRequest) {
  return http.patch<null>(`/work-orders/${id}/assign`, data)
}

/** 接单（接口设计.md 9.4.2.6，仅派给本人的工单可接） */
export function acceptWorkOrder(id: number, data?: { remark?: string }) {
  return http.patch<null>(`/work-orders/${id}/accept`, data)
}

/** 开始处理（接口设计.md 9.4.2.7，仅已接单状态可操作） */
export function processWorkOrder(id: number, data?: { remark?: string }) {
  return http.patch<null>(`/work-orders/${id}/process`, data)
}

/** 完成工单（接口设计.md 9.4.2.8，须填写解决方案） */
export function completeWorkOrder(id: number, data: IWorkOrderCompleteRequest) {
  return http.patch<null>(`/work-orders/${id}/complete`, data)
}

/** 居民确认完成（接口设计.md 9.4.2.9，仅工单提交人可确认） */
export function confirmWorkOrder(id: number, data?: { remark?: string }) {
  return http.patch<null>(`/work-orders/${id}/confirm`, data)
}

/** 关闭工单（接口设计.md 9.4.2.10，仅居民确认后可关闭） */
export function closeWorkOrder(id: number, data?: { remark?: string }) {
  return http.patch<null>(`/work-orders/${id}/close`, data)
}

/** 驳回工单（接口设计.md 9.4.2.11，仅待受理状态可驳回） */
export function rejectWorkOrder(id: number, data: IWorkOrderReasonRequest) {
  return http.patch<null>(`/work-orders/${id}/reject`, data)
}

/** 取消工单（接口设计.md 9.4.2.12，仅提交人可取消） */
export function cancelWorkOrder(id: number, data: IWorkOrderReasonRequest) {
  return http.patch<null>(`/work-orders/${id}/cancel`, data)
}

/** 查询工单处理时间线（接口设计.md 9.4.2.13） */
export function getWorkOrderTimeline(id: number) {
  return http.get<IWorkOrderProcess[]>(`/work-orders/${id}/timeline`)
}

/** 上传工单附件（图片 ≤5MB，文档 ≤10MB，接口设计.md 9.4.3.1） */
export function uploadWorkOrderAttachment(orderId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<IWorkOrderAttachment>(`/work-orders/${orderId}/attachments`, formData)
}

/** 删除工单附件（接口设计.md 9.4.3.2） */
export function deleteWorkOrderAttachment(id: number) {
  return http.delete<null>(`/work-order-attachments/${id}`)
}

/** 查询工单附件列表（接口设计.md 9.4.3.3） */
export function listWorkOrderAttachments(orderId: number) {
  return http.get<IWorkOrderAttachment[]>(`/work-orders/${orderId}/attachments`)
}
