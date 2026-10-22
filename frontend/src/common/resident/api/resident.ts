/** C2 居民与居住关系管理接口（接口设计.md §9.2） */
import { http } from '@/common/web/utils/request'
import { loadSession } from '@/common/auth/utils/auth'
import type { PageResult } from '@/common/web/types/api'
import type {
  IApplicationApproveDTO,
  IApplicationRejectDTO,
  IAdminCreateResidentDTO,
  IAdminCreateResidentVO,
  IChangePasswordDTO,
  IConfigValueResult,
  ICreateResidenceApplicationDTO,
  IHouseResident,
  IMoveOutDTO,
  IResidenceApplication,
  IResidenceApplicationQuery,
  IResidenceRelation,
  IResidenceRelationQuery,
  IResident,
  IResidentImportVO,
  IResidentQuery,
  ISysConfig,
  IUpdateConfigDTO,
  IUpdateProfileDTO,
  IUpdateResidentStatusDTO
} from '@/common/resident/types/resident'

/* ---------------- 9.2.1 居民账号管理 ----------------
 * 注册/登录/登出（9.2.1.1 ~ 9.2.1.3）已在 src/common/auth/api/auth.ts 实现
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

/** 按完整用户名或手机号找到同住登记账号，不扩大居民台账范围。 */
export function findCoResidentCandidates(houseId: number, keyword: string) {
  return http.get<Array<Pick<IResident, 'id' | 'username' | 'realName'>>>('/residents/co-resident-candidates', { houseId, keyword })
}

/** 冻结/解冻居民账号（接口设计.md 9.2.1.9） */
export function updateResidentStatus(id: number, data: IUpdateResidentStatusDTO) {
  return http.patch<null>(`/residents/${id}/status`, data)
}

/** 管理员代建居民账号（R8 v1.2；初始密码服务端生成，明文仅本次返回供转交居民） */
export function adminCreateResident(data: IAdminCreateResidentDTO): Promise<IAdminCreateResidentVO> {
  return http.post<IAdminCreateResidentVO>('/residents/admin-create', data)
}

/**
 * CSV 批量导入居民（R8 v1.2；UTF-8 CSV，表头：小区ID,姓名,手机号,证件号；
 * 部分成功语义。FormData 传法与 api/upload.ts 一致，axios 自动带
 * multipart/form-data 边界，勿手动设置 Content-Type）
 */
export function importResidents(file: File, communityId?: number): Promise<IResidentImportVO> {
  const formData = new FormData()
  formData.append('file', file)
  if (communityId) formData.append('communityId', String(communityId))
  return http.post<IResidentImportVO>('/residents/import', formData)
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

/** 入住申请审批通过（接口设计.md 9.2.2.4；后端实际返回更新后的申请 VO，非文档示例组装结果） */
export function approveResidenceApplication(id: number, data: IApplicationApproveDTO) {
  return http.patch<IResidenceApplication>(
    `/residence-applications/${id}/approve`,
    data
  )
}

/** 入住申请审批拒绝（接口设计.md 9.2.2.5） */
export function rejectResidenceApplication(id: number, data: IApplicationRejectDTO) {
  return http.patch<null>(`/residence-applications/${id}/reject`, data)
}

/**
 * 撤回入住申请（R66 批次 f 端点；仅申请人本人、仅「待审核」可撤回，
 * 撤回后落「已撤回」终态并关闭租约群聊）
 */
export function withdrawResidenceApplication(id: number) {
  return http.patch<IResidenceApplication>(`/residence-applications/${id}/withdraw`)
}

/* ---------------------------------- 9.2.3 居住关系管理 ---------------------------------- */

/** 居民居住关系分页列表（接口设计.md 9.2.3.1） */
export function getResidentResidenceList(
  residentId: number,
  params?: IResidenceRelationQuery
) {
  return http.get<PageResult<IResidenceRelation>>(`/residents/${residentId}/residences`, params)
}

/**
 * 当前登录居民的居住关系列表（居民端自助查询，居民端三段预约流的小区来源）
 * residentId 取当前会话用户 ID（后端 ResidenceRelationService 对 RESIDENT 强校验限本人），
 * 默认只取生效中关系（status=ACTIVE，后端按 move_out_date IS NULL 过滤）
 */
export async function getMyResidenceRelations(
  params?: Omit<IResidenceRelationQuery, 'page' | 'size'>
): Promise<PageResult<IResidenceRelation>> {
  const residentId = loadSession()?.user.id
  if (!residentId) {
    throw new Error('未登录或会话已过期')
  }
  return getResidentResidenceList(residentId, { page: 1, size: 50, ...params })
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

/** 查询配置（接口设计.md 9.2.4.1，后端返回 {key, value} 包装） */
export function getConfig(key: string) {
  return http.get<IConfigValueResult>(`/configs/${key}`)
}

/** 配置列表（接口设计.md 9.2.4.2；后端实现为全量列表，非分页） */
export function getConfigList() {
  return http.get<ISysConfig[]>('/configs')
}

/** 更新配置（接口设计.md 9.2.4.3） */
export function updateConfig(key: string, data: IUpdateConfigDTO) {
  return http.put<null>(`/configs/${key}`, data)
}

/** 管理员登记同住关系，不建立租约或交易义务。 */
export function createResidenceRelation(data: { residentId: number; houseId: number; anchorRelationId: number; relationType: 'FAMILY' | 'CO_RESIDENT'; moveInDate: string; remark?: string }) {
  return http.post<IResidenceRelation>('/residence-relations', data)
}
/** 管理员确认主关系结束前查看准确依赖成员。 */
export function getResidenceDependents(id: number) {
  return http.get<IResidenceRelation[]>(`/residence-relations/${id}/dependents`)
}
