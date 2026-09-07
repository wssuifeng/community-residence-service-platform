/** C3 租住管理接口（接口设计.md §9.3） */
import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IExpiringLeaseQuery,
  ILeaseQuery,
  ILeaseRecord,
  ILeaseRecordDTO,
  IUpdateLeaseStatusDTO
} from '@/types/modules/lease'

/** 创建租住记录（接口设计.md 9.3.1.1） */
export function createLease(data: ILeaseRecordDTO) {
  return http.post<ILeaseRecord>('/leases', data)
}

/** 更新租住记录（接口设计.md 9.3.1.2） */
export function updateLease(id: number, data: ILeaseRecordDTO) {
  return http.put<ILeaseRecord>(`/leases/${id}`, data)
}

/** 查询租住详情（接口设计.md 9.3.1.3） */
export function getLease(id: number) {
  return http.get<ILeaseRecord>(`/leases/${id}`)
}

/** 租住记录分页列表（接口设计.md 9.3.1.4） */
export function getLeaseList(params: ILeaseQuery) {
  return http.get<PageResult<ILeaseRecord>>('/leases', params)
}

/** 更新租住状态（接口设计.md 9.3.1.5） */
export function updateLeaseStatus(id: number, data: IUpdateLeaseStatusDTO) {
  return http.patch<null>(`/leases/${id}/status`, data)
}

/** 即将到期租住分页列表（接口设计.md 9.3.1.6） */
export function getExpiringLeaseList(params?: IExpiringLeaseQuery) {
  return http.get<PageResult<ILeaseRecord>>('/leases/expiring', params)
}
