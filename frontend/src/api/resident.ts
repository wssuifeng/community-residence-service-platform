/** C2 居民与居住关系管理接口（接口设计.md §9.2） */
import { http } from '@/utils/request'
import type { PageResult, PageQuery } from '@/types/api'
import type {
  IApplicationApproveDTO,
  IApplicationApproveResult,
  IApplicationRejectDTO,
  IChangePasswordDTO,
  ICreateResidenceApplicationDTO,
  IHouseResident,
  IMoveOutDTO,
  IResidenceApplication,
  IResidenceApplicationQuery,
  IResidenceRelation,
  IResidenceRelationQuery,
  IResident,
  IResidentQuery,
  ISysConfig,
  IUpdateConfigDTO,
  IUpdateProfileDTO,
  IUpdateResidentStatusDTO
} from '@/types/modules/resident'

/* ---------------- 9.2.1 居民账号管理 ----------------
 * 注册/登录/登出（9.2.1.1 ~ 9.2.1.3）已在 src/api/auth.ts 实现
 * （residentRegister / residentLogin / residentLogout），此处不重复封装。
 */

/** 查询个人资料（接口设计.md 9.2.1.4） */
export function getMyProfile() {
  return http.get<IResident>('/residents/profile')
}

/** 更新个人资料（接口设计.md 9.2.1.5） */
export function updateMyProfile(data: IUpdateProfileDTO) {
  return http.put<IResident>('/residents/profile', data)
}

/** 修改密码（接口设计.md 9.2.1.6） */
export function changeMyPassword(data: IChangePasswordDTO) {
  return http.patch<null>('/residents/password', data)
}

/** 查询居民信息（管理员视角，接口设计.md 9.2.1.7） */
export function getResident(id: number) {
  return http.get<IResident>(`/residents/${id}`)
}

/** 居民分页列表（接口设计.md 9.2.1.8） */
export function getResidentList(params: IResidentQuery) {
  return http.get<PageResult<IResident>>('/residents', params)
}

/** 冻结/解冻居民账号（接口设计.md 9.2.1.9） */
export function updateResidentStatus(id: number, data: IUpdateResidentStatusDTO) {
  return http.patch<null>(`/residents/${id}/status`, data)
}

/* ---------------------------------- 9.2.2 入住申请管理 ---------------------------------- */

/** 提交入住申请（接口设计.md 9.2.2.1） */
export function createResidenceApplication(data: ICreateResidenceApplicationDTO) {
  return http.post<IResidenceApplication>('/residence-applications', data)
}

/** 查询入住申请详情（接口设计.md 9.2.2.2） */
export function getResidenceApplication(id: number) {
  return http.get<IResidenceApplication>(`/residence-applications/${id}`)
}

/** 入住申请分页列表（接口设计.md 9.2.2.3） */
export function getResidenceApplicationList(params: IResidenceApplicationQuery) {
  return http.get<PageResult<IResidenceApplication>>('/residence-applications', params)
}

/** 入住申请审批通过（接口设计.md 9.2.2.4） */
export function approveResidenceApplication(id: number, data: IApplicationApproveDTO) {
  return http.patch<IApplicationApproveResult>(
    `/residence-applications/${id}/approve`,
    data
  )
}

/** 入住申请审批拒绝（接口设计.md 9.2.2.5） */
export function rejectResidenceApplication(id: number, data: IApplicationRejectDTO) {
  return http.patch<null>(`/residence-applications/${id}/reject`, data)
}

/* ---------------------------------- 9.2.3 居住关系管理 ---------------------------------- */

/** 居民居住关系分页列表（接口设计.md 9.2.3.1） */
export function getResidentResidenceList(
  residentId: number,
  params?: IResidenceRelationQuery
) {
  return http.get<PageResult<IResidenceRelation>>(`/residents/${residentId}/residences`, params)
}

/** 房屋居民分页列表（接口设计.md 9.2.3.2） */
export function getHouseResidentList(houseId: number, params?: IResidenceRelationQuery) {
  return http.get<PageResult<IHouseResident>>(`/houses/${houseId}/residents`, params)
}

/** 办理迁出（接口设计.md 9.2.3.3） */
export function moveOutResidenceRelation(id: number, data: IMoveOutDTO) {
  return http.patch<null>(`/residence-relations/${id}/move-out`, data)
}

/* ---------------------------------- 9.2.4 全局配置管理 ---------------------------------- */

/** 查询配置（接口设计.md 9.2.4.1） */
export function getConfig(key: string) {
  return http.get<ISysConfig>(`/configs/${key}`)
}

/** 配置分页列表（接口设计.md 9.2.4.2） */
export function getConfigList(params?: PageQuery) {
  return http.get<PageResult<ISysConfig>>('/configs', params)
}

/** 更新配置（接口设计.md 9.2.4.3） */
export function updateConfig(key: string, data: IUpdateConfigDTO) {
  return http.put<null>(`/configs/${key}`, data)
}
