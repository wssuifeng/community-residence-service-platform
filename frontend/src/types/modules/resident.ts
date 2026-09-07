/** C2 居民与居住关系管理类型定义（接口设计.md §9.2） */
import type { PageQuery } from '@/types/api'

/* ---------------------------------- 状态枚举 ---------------------------------- */

/** 居民账号状态（接口设计.md 9.2.1.8 / 9.2.1.9） */
export type ResidentStatus = 'ACTIVE' | 'FROZEN'

/** 居民账号状态中文标签 */
export const residentStatusLabels: Record<ResidentStatus, string> = {
  ACTIVE: '正常',
  FROZEN: '已冻结'
}

/** 入住申请状态（接口设计.md 9.2.2.3） */
export type ResidenceApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 入住申请状态中文标签 */
export const residenceApplicationStatusLabels: Record<ResidenceApplicationStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/** 入住申请类型（接口设计.md 9.2.2.1，文档示例值 RENT） */
export type ApplicationType = 'RENT'

/** 入住申请类型中文标签 */
export const applicationTypeLabels: Record<ApplicationType, string> = {
  RENT: '租住'
}

/** 居住关系状态（接口设计.md 9.2.3.1 / 9.2.3.2） */
export type ResidenceRelationStatus = 'ACTIVE' | 'MOVED_OUT'

/** 居住关系状态中文标签 */
export const residenceRelationStatusLabels: Record<ResidenceRelationStatus, string> = {
  ACTIVE: '居住中',
  MOVED_OUT: '已迁出'
}

/* ---------------------------------- 居民账号 ---------------------------------- */

/** 居民实体（接口设计.md 9.2.1.4 / 9.2.1.7 响应） */
export interface IResident {
  id: number
  username: string
  realName: string
  phone: string
  email?: string
  idCardNumber?: string
  status: ResidentStatus
  violationCount?: number
  createdAt: string
}

/** 居民注册请求（接口设计.md 9.2.1.1 请求体，登录/登出见 api/auth.ts） */
export interface IResidentRegisterDTO {
  username: string
  password: string
  realName: string
  phone: string
  email?: string
  idCardNumber?: string
}

/** 更新个人资料请求（接口设计.md 9.2.1.5 请求体） */
export interface IUpdateProfileDTO {
  realName: string
  phone: string
  email?: string
}

/** 修改密码请求（接口设计.md 9.2.1.6 请求体） */
export interface IChangePasswordDTO {
  oldPassword: string
  newPassword: string
}

/** 居民列表查询参数（接口设计.md 9.2.1.8） */
export interface IResidentQuery extends PageQuery {
  status?: ResidentStatus
  keyword?: string
}

/** 冻结/解冻居民账号请求（接口设计.md 9.2.1.9 请求体） */
export interface IUpdateResidentStatusDTO {
  status: ResidentStatus
  reason?: string
}

/* ---------------------------------- 入住申请 ---------------------------------- */

/** 入住申请实体（接口设计.md 9.2.2.1 响应） */
export interface IResidenceApplication {
  id: number
  residentId: number
  residentName: string
  houseId: number
  houseAddress: string
  applicationType: ApplicationType
  moveInDate: string
  familyMembers: number
  contactPhone?: string
  emergencyContact?: string
  emergencyPhone?: string
  status: ResidenceApplicationStatus
  remark?: string
  createdAt: string
}

/** 提交入住申请请求（接口设计.md 9.2.2.1 请求体） */
export interface ICreateResidenceApplicationDTO {
  houseId: number
  applicationType: ApplicationType
  moveInDate: string
  familyMembers?: number
  contactPhone?: string
  emergencyContact?: string
  emergencyPhone?: string
  remark?: string
}

/** 入住申请列表查询参数（接口设计.md 9.2.2.3） */
export interface IResidenceApplicationQuery extends PageQuery {
  status?: ResidenceApplicationStatus
  startTime?: string
  endTime?: string
}

/** 入住申请审批通过请求（接口设计.md 9.2.2.4 请求体） */
export interface IApplicationApproveDTO {
  leaseStartDate: string
  leaseEndDate: string
  monthlyRent: number
  depositAmount?: number
  remark?: string
}

/** 入住申请审批拒绝请求（接口设计.md 9.2.2.5 请求体） */
export interface IApplicationRejectDTO {
  reason: string
}

/** 入住申请审批通过响应（接口设计.md 9.2.2.4 响应，自动创建居住关系与租住记录） */
export interface IApplicationApproveResult {
  applicationId: number
  residenceRelationId: number
  leaseRecordId: number
}

/* ---------------------------------- 居住关系 ---------------------------------- */

/** 居住关系实体（接口设计.md 9.2.3.1 响应，居民视角） */
export interface IResidenceRelation {
  id: number
  residentId: number
  residentName: string
  houseId: number
  houseAddress: string
  moveInDate: string
  moveOutDate: string | null
  status: ResidenceRelationStatus
  createdAt: string
}

/** 房屋居民实体（接口设计.md 9.2.3.2 响应，房屋视角） */
export interface IHouseResident {
  id: number
  residentId: number
  residentName: string
  phone: string
  moveInDate: string
  moveOutDate: string | null
  status: ResidenceRelationStatus
}

/** 居住关系列表查询参数（接口设计.md 9.2.3.1 / 9.2.3.2） */
export interface IResidenceRelationQuery extends PageQuery {
  status?: ResidenceRelationStatus
}

/** 办理迁出请求（接口设计.md 9.2.3.3 请求体） */
export interface IMoveOutDTO {
  moveOutDate: string
  reason?: string
}

/* ---------------------------------- 全局配置 ---------------------------------- */

/** 全局配置实体（接口设计.md 9.2.4.1 响应） */
export interface ISysConfig {
  key: string
  value: string
  description?: string
}

/** 更新配置请求（接口设计.md 9.2.4.3 请求体） */
export interface IUpdateConfigDTO {
  value: string
}
