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

/** 居住关系状态（接口设计.md 9.2.3.1 / 9.2.3.2） */
export type ResidenceRelationStatus = 'ACTIVE' | 'MOVED_OUT'

/** 居住关系状态中文标签 */
export const residenceRelationStatusLabels: Record<ResidenceRelationStatus, string> = {
  ACTIVE: '居住中',
  MOVED_OUT: '已迁出'
}

/** 居住身份（后端 RelationVO.relationType，ResidenceRelation/ResidenceApplication 共用） */
export type RelationType = 'OWNER' | 'TENANT' | 'FAMILY'

/** 居住身份中文标签 */
export const relationTypeLabels: Record<RelationType, string> = {
  OWNER: '业主',
  TENANT: '租客',
  FAMILY: '家属'
}

/* ---------------------------------- 居民账号 ---------------------------------- */

/**
 * 居民实体（后端 ResidentVO 实际返回，2026-09-12 对齐：
 * 接口设计.md 9.2.1.7 示例的 violationCount 后端 VO 未暴露；
 * 身份证号为脱敏字段 idCardMasked，非文档示例的 idCardNumber）
 */
export interface IResident {
  id: number
  username: string
  realName: string
  phone: string
  email?: string
  /** 身份证号（后端脱敏：保留前 4 后 2 位） */
  idCardMasked?: string
  status: ResidentStatus
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

/* ---------------------------------- 代建居民 / 批量导入（R8 v1.2，后端 AdminCreateResidentDTO / ResidentImportVO 契约） ---------------------------------- */

/** 管理员代建居民请求（后端 AdminCreateResidentDTO；初始密码服务端生成：手机号后 6 位） */
export interface IAdminCreateResidentDTO {
  /** 所属社区 ID（数据级权限锚点，ADMIN 限绑定社区） */
  communityId: number
  /** 真实姓名（≤50 字符） */
  realName: string
  /** 手机号（^1[3-9]\d{9}$） */
  phone: string
  /** 身份证号（选填，^\d{17}[\dXx]$） */
  idCard?: string
  /** 用户名（选填，^[a-zA-Z0-9_]+$ 4~20 字符；留空服务端按手机号生成） */
  username?: string
}

/** 代建居民结果（初始密码明文仅本次返回，供管理员转交居民） */
export interface IAdminCreateResidentVO {
  residentId: number
  username: string
  initialPassword: string
}

/** CSV 批量导入成功行（行号从 1 计，不含表头） */
export interface IResidentImportSuccessRow {
  row: number
  username: string
  initialPassword: string
}

/** CSV 批量导入失败行 */
export interface IResidentImportFailRow {
  row: number
  reason: string
}

/** CSV 批量导入结果（部分成功语义：成功行生效，失败行带行号与原因） */
export interface IResidentImportVO {
  total: number
  success: number
  fail: number
  successRows: IResidentImportSuccessRow[]
  failRows: IResidentImportFailRow[]
}

/* ---------------------------------- 入住申请 ---------------------------------- */

/**
 * 入住申请实体（后端 ApplicationVO 实际返回，2026-09-12 对齐：
 * 文档示例的 applicationType/moveInDate/familyMembers/contactPhone/emergencyContact/
 * emergencyPhone/houseAddress 后端 VO 均未返回，真实字段为 relationType/houseLocation）
 */
export interface IResidenceApplication {
  id: number
  residentId: number
  residentName: string
  communityId: number
  houseId: number
  /** 房屋位置（楼栋-单元-房号，后端拼装） */
  houseLocation: string
  relationType?: string
  status: ResidenceApplicationStatus
  remark?: string
  reviewRemark?: string
  reviewTime?: string
  createdAt: string
}

/** 提交入住申请请求（后端 CreateApplicationDTO 实际契约） */
export interface ICreateResidenceApplicationDTO {
  houseId: number
  relationType: RelationType
  remark?: string
}

/**
 * 入住申请列表查询参数（后端 ResidenceApplicationController.page 实际仅支持
 * page/size/status；文档示例的 startTime/endTime 为漂移参数，暂删）
 */
export interface IResidenceApplicationQuery extends PageQuery {
  status?: ResidenceApplicationStatus
}

/**
 * 入住申请审批通过请求（后端 ApproveApplicationDTO 实际契约：押金字段为 deposit）
 * 审批通过响应为更新后的 IResidenceApplication（后端不返回文档示例的
 * applicationId/residenceRelationId/leaseRecordId 组装结果）
 */
export interface IApplicationApproveDTO {
  leaseStartDate: string
  leaseEndDate: string
  monthlyRent: number
  deposit?: number
  remark?: string
}

/** 入住申请审批拒绝请求（接口设计.md 9.2.2.5 请求体） */
export interface IApplicationRejectDTO {
  reason: string
}

/* ---------------------------------- 居住关系 ---------------------------------- */

/** 居住关系实体（接口设计.md 9.2.3.1 响应，居民视角） */
export interface IResidenceRelation {
  id: number
  residentId: number
  residentName: string
  residentPhone?: string
  communityId: number
  houseId: number
  houseLocation: string
  relationType?: string
  moveInDate: string
  moveOutDate: string | null
  status: ResidenceRelationStatus
  createdAt: string
}

/**
 * 房屋居民实体（后端 RelationVO 实际返回，2026-09-12 对齐：
 * 手机号字段为 residentPhone（接口设计.md 9.2.3.2 示例的 phone 为漂移定义），
 * 并携带 relationType/houseLocation/communityId/houseId/createdAt）
 */
export interface IHouseResident {
  id: number
  residentId: number
  residentName: string
  residentPhone?: string
  communityId?: number
  houseId?: number
  houseLocation?: string
  relationType?: RelationType
  moveInDate: string
  moveOutDate: string | null
  status: ResidenceRelationStatus
  createdAt?: string
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

/**
 * 全局配置实体（后端 SysConfig 实体字段，2026-09-08 联调对齐：
 * 接口设计.md 9.2.4.2 示例为分页 + key/value，后端实现为全量
 * 列表 + configKey/configValue，以后端契约为准）
 */
export interface ISysConfig {
  id?: number
  configKey: string
  configValue: string
  description?: string
  updatedAt?: string
}

/** 查询配置响应（接口设计.md 9.2.4.1，后端返回 {key, value} 包装） */
export interface IConfigValueResult {
  key: string
  value: string
}

/** 更新配置请求（接口设计.md 9.2.4.3 请求体） */
export interface IUpdateConfigDTO {
  value: string
}
