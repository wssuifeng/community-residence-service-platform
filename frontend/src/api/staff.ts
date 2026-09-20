/** C4 服务人员能力绑定与排班接口（对齐后端 StaffCapabilityController / StaffScheduleController 实测契约） */
import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IBatchSaveScheduleDTO,
  IBatchSaveScheduleResult,
  IClearScheduleParams,
  ISaveStaffCapabilityDTO,
  IStaffCandidate,
  IStaffCapability,
  IStaffCapabilityQuery,
  IStaffSchedule,
  IStaffScheduleQuery
} from '@/types/modules/staff'

/* ------------------------------- 能力绑定 ------------------------------- */

/** 服务人员能力绑定分页列表（ADMIN 限本社区已绑定人员） */
export function listStaffCapabilities(params: IStaffCapabilityQuery) {
  return http.get<PageResult<IStaffCapability>>('/staff-capabilities', params)
}

/** 可绑定人员候选池（全量 STAFF 账号） */
export function listStaffCandidates() {
  return http.get<IStaffCandidate[]>('/staff-capabilities/candidates')
}

/** 服务人员能力详情 */
export function getStaffCapability(staffId: number) {
  return http.get<IStaffCapability>(`/staff-capabilities/${staffId}`)
}

/** 保存服务人员能力绑定（全量覆盖式：不传/空数组表示清空该维度） */
export function saveStaffCapability(staffId: number, data: ISaveStaffCapabilityDTO) {
  return http.put<IStaffCapability>(`/staff-capabilities/${staffId}`, data)
}

/* --------------------------------- 排班 --------------------------------- */

/** 排班区间查询（日期范围必填，跨度上限 62 天） */
export function listStaffSchedules(params: IStaffScheduleQuery) {
  return http.get<IStaffSchedule[]>('/staff-schedules', params)
}

/** 排班批量设置（同 staff+社区+日期已存在则覆盖） */
export function batchSaveStaffSchedules(data: IBatchSaveScheduleDTO) {
  return http.put<IBatchSaveScheduleResult>('/staff-schedules/batch', data)
}

/** 删除单条排班 */
export function deleteStaffSchedule(id: number) {
  return http.delete<null>(`/staff-schedules/${id}`)
}

/** 按范围清空排班（staffIds 为空表示该社区全部人员） */
export function clearStaffSchedules(params: IClearScheduleParams) {
  return http.delete<IBatchSaveScheduleResult>('/staff-schedules', {
    params: {
      communityId: params.communityId,
      staffIds: params.staffIds && params.staffIds.length > 0 ? params.staffIds.join(',') : undefined,
      startDate: params.startDate,
      endDate: params.endDate
    }
  })
}
