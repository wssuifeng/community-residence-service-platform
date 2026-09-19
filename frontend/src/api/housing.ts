import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  AvailableViewingTimeslotQuery,
  HousingListQuery,
  HousingSaveDTO,
  HousingStatusUpdateDTO,
  HousingTimeslotListQuery,
  HousingTimeslotSaveDTO,
  IHousing,
  IHousingTimeslot,
  IAvailableViewingTimeslot,
  IViewingAppointment,
  IViewingMessage,
  IViewingAssigneeOption,
  ViewingAppointmentAssignDTO,
  ViewingAppointmentCreateDTO,
  ViewingAppointmentListQuery,
  ViewingAppointmentReasonDTO
} from '@/types/modules/housing'

/** 创建房源（接口设计.md 9.12.1.1） */
export function createHousing(data: HousingSaveDTO) {
  return http.post<IHousing>('/housings', data)
}

/** 更新房源（接口设计.md 9.12.1.2） */
export function updateHousing(id: number, data: HousingSaveDTO) {
  return http.put<IHousing>(`/housings/${id}`, data)
}

/** 删除房源（接口设计.md 9.12.1.3，有未完成看房预约时不可删） */
export function deleteHousing(id: number) {
  return http.delete<null>(`/housings/${id}`)
}

/** 查询房源详情（接口设计.md 9.12.1.4，公开） */
export function getHousingDetail(id: number) {
  return http.get<IHousing>(`/housings/${id}`)
}

/** 房源列表分页查询（接口设计.md 9.12.1.5，公开） */
export function listHousings(query?: HousingListQuery) {
  return http.get<PageResult<IHousing>>('/housings', query)
}

/** 更新房源状态（接口设计.md 9.12.1.6） */
export function updateHousingStatus(id: number, data: HousingStatusUpdateDTO) {
  return http.patch<null>(`/housings/${id}/status`, data)
}

/** 记录房源浏览（接口设计.md 9.12.1.7，公开） */
export function recordHousingView(id: number) {
  return http.post<null>(`/housings/${id}/view`)
}

/** 创建看房预约（接口设计.md 9.12.2.1，居民/游客可提交） */
export function createViewingAppointment(data: ViewingAppointmentCreateDTO) {
  return http.post<IViewingAppointment>('/viewing-appointments', data)
}

/** 查询看房预约详情（接口设计.md 9.12.2.2） */
export function getViewingAppointmentDetail(id: number) {
  return http.get<IViewingAppointment>(`/viewing-appointments/${id}`)
}

/** 看房预约列表分页查询（接口设计.md 9.12.2.3） */
export function listViewingAppointments(query?: ViewingAppointmentListQuery) {
  return http.get<PageResult<IViewingAppointment>>('/viewing-appointments', query)
}

/** 确认看房预约（后端 ReservationActionDTO，reason 必填；TO_CONFIRM → RESERVED） */
export function confirmViewingAppointment(id: number, data: ViewingAppointmentReasonDTO) {
  return http.patch<null>(`/viewing-appointments/${id}/confirm`, data)
}

/** 完成看房预约（后端 ReservationActionDTO，reason 必填；RESERVED → COMPLETED） */
export function completeViewingAppointment(id: number, data: ViewingAppointmentReasonDTO) {
  return http.patch<null>(`/viewing-appointments/${id}/complete`, data)
}

/** 取消看房预约（接口设计.md 9.12.2.6，PENDING/CONFIRMED → CANCELLED） */
export function cancelViewingAppointment(id: number, data: ViewingAppointmentReasonDTO) {
  return http.patch<null>(`/viewing-appointments/${id}/cancel`, data)
}

/** 标记看房违约（接口设计.md 9.12.2.7，CONFIRMED → VIOLATED） */
export function violateViewingAppointment(id: number, data: ViewingAppointmentReasonDTO) {
  return http.patch<null>(`/viewing-appointments/${id}/violate`, data)
}

/** 分配带看人（R59，v1.3；ADMIN/SUPER_ADMIN，目标为启用状态的服务人员或社区管理员） */
export function assignViewingAppointment(id: number, data: ViewingAppointmentAssignDTO) {
  return http.patch<IViewingAppointment>(`/viewing-appointments/${id}/assign`, data)
}

/** 带看人候选列表（R59；ADMIN/SUPER_ADMIN，STAFF 全量 + 管辖该社区的启用 ADMIN） */
export function listAssignableAssignees(communityId: number) {
  return http.get<IViewingAssigneeOption[]>('/viewing-appointments/assignable-assignees', { communityId })
}

/** 带看会话消息列表（R59；预约居民与带看人可读，按时间正序） */
export function listViewingMessages(id: number) {
  return http.get<IViewingMessage[]>(`/viewing-appointments/${id}/messages`)
}

/** 发送带看会话消息（R59；预约居民与带看人可发） */
export function sendViewingMessage(id: number, data: { content: string }) {
  return http.post<IViewingMessage>(`/viewing-appointments/${id}/messages`, data)
}

/** 查询房源可预约看房时段（接口设计.md 9.12.2.8，公开） */
export function listViewingAvailableSlots(housingId: number, query: AvailableViewingTimeslotQuery) {
  return http.get<IAvailableViewingTimeslot[]>(`/housings/${housingId}/available-slots`, query)
}

/** 创建房源看房时段（接口设计.md 9.12.3.1） */
export function createHousingTimeslot(housingId: number, data: HousingTimeslotSaveDTO) {
  return http.post<IHousingTimeslot>(`/housings/${housingId}/timeslots`, data)
}

/** 更新房源看房时段（接口设计.md 9.12.3.2） */
export function updateHousingTimeslot(id: number, data: HousingTimeslotSaveDTO) {
  return http.put<IHousingTimeslot>(`/housing-timeslots/${id}`, data)
}

/** 删除房源看房时段（接口设计.md 9.12.3.3，有预约时不可删） */
export function deleteHousingTimeslot(id: number) {
  return http.delete<null>(`/housing-timeslots/${id}`)
}

/** 房源看房时段列表（后端返回周模板全量数组，非分页） */
export function listHousingTimeslots(housingId: number, query?: HousingTimeslotListQuery) {
  return http.get<IHousingTimeslot[]>(`/housings/${housingId}/timeslots`, query)
}
