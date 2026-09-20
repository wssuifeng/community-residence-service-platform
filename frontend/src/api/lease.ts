/** C3 租住管理接口（对齐后端 LeaseController 实测契约） */
import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IExpiringLeaseQuery,
  ILeaseChange,
  ILeaseQuery,
  ILeaseRecord,
  ILeaseRecordDTO,
  IRenewLeaseDTO,
  IUpdateLeaseStatusDTO
} from '@/types/modules/lease'

/** 创建租住记录（管理员登记，初始待审核） */
export function createLease(data: ILeaseRecordDTO) {
  return http.post<ILeaseRecord>('/leases', data)
}

/** 更新租住记录（不允许变更房屋） */
export function updateLease(id: number, data: ILeaseRecordDTO) {
  return http.put<ILeaseRecord>(`/leases/${id}`, data)
}

/** 续租（需求 E3：仅已生效租约，止期顺延、租金/押金更新） */
export function renewLease(id: number, data: IRenewLeaseDTO) {
  return http.post<ILeaseRecord>(`/leases/${id}/renew`, data)
}

/** 查询租住详情 */
export function getLease(id: number) {
  return http.get<ILeaseRecord>(`/leases/${id}`)
}

/** 租住记录分页列表 */
export function getLeaseList(params: ILeaseQuery) {
  return http.get<PageResult<ILeaseRecord>>('/leases', params)
}

/** 更新租住状态（状态机流转校验） */
export function updateLeaseStatus(id: number, data: IUpdateLeaseStatusDTO) {
  return http.patch<null>(`/leases/${id}/status`, data)
}

/** 即将到期租住分页列表（按到期日期升序） */
export function getExpiringLeaseList(params?: IExpiringLeaseQuery) {
  return http.get<PageResult<ILeaseRecord>>('/leases/expiring', params)
}

/** 租约变更历史（V18 字段级前后值留痕，倒序；居民限本人租约） */
export function getLeaseChanges(id: number) {
  return http.get<ILeaseChange[]>(`/leases/${id}/changes`)
}
