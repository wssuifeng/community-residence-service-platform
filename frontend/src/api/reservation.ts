import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  AvailableTimeslotQuery,
  IAvailableTimeslot,
  IResourceReservation,
  IViolationRecord,
  ReservationActionDTO,
  ReservationCreateDTO,
  ReservationListQuery,
  ReservationReasonDTO,
  ViolationListQuery
} from '@/types/modules/reservation'

/** 创建资源预约（接口设计.md 9.7.1.1） */
export function createReservation(data: ReservationCreateDTO) {
  return http.post<IResourceReservation>('/resource-reservations', data)
}

/** 查询预约详情（接口设计.md 9.7.1.2） */
export function getReservationDetail(id: number) {
  return http.get<IResourceReservation>(`/resource-reservations/${id}`)
}

/** 预约列表分页查询（接口设计.md 9.7.1.3） */
export function listReservations(query: ReservationListQuery) {
  return http.get<PageResult<IResourceReservation>>('/resource-reservations', query)
}

/** 确认预约（接口设计.md 9.7.1.4，PENDING → CONFIRMED） */
export function confirmReservation(id: number, data?: ReservationActionDTO) {
  return http.patch<null>(`/resource-reservations/${id}/confirm`, data)
}

/** 完成预约（接口设计.md 9.7.1.5，CONFIRMED → COMPLETED） */
export function completeReservation(id: number, data?: ReservationActionDTO) {
  return http.patch<null>(`/resource-reservations/${id}/complete`, data)
}

/** 拒绝预约（接口设计.md 9.7.1.6，PENDING → REJECTED） */
export function rejectReservation(id: number, data: ReservationReasonDTO) {
  return http.patch<null>(`/resource-reservations/${id}/reject`, data)
}

/** 取消预约（接口设计.md 9.7.1.7，PENDING/CONFIRMED → CANCELLED，仅预约人） */
export function cancelReservation(id: number, data: ReservationReasonDTO) {
  return http.patch<null>(`/resource-reservations/${id}/cancel`, data)
}

/** 标记违约（接口设计.md 9.7.1.8，CONFIRMED → VIOLATED） */
export function violateReservation(id: number, data: ReservationReasonDTO) {
  return http.patch<null>(`/resource-reservations/${id}/violate`, data)
}

/** 查询资源可预约时段（接口设计.md 9.7.1.9，公开） */
export function listAvailableTimeslots(resourceId: number, query: AvailableTimeslotQuery) {
  return http.get<IAvailableTimeslot[]>(`/resources/${resourceId}/available-slots`, query)
}

/** 居民违约记录列表（接口设计.md 9.7.2.1） */
export function listResidentViolations(residentId: number, query?: ViolationListQuery) {
  return http.get<PageResult<IViolationRecord>>(`/residents/${residentId}/violations`, query)
}
