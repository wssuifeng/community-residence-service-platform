import type { Role, PageQuery } from '@/types/api'

/** 系统用户角色（接口设计.md 9.10.1.3：ADMIN/STAFF/SUPER_ADMIN） */
export type SystemRole = Extract<Role, 'ADMIN' | 'STAFF' | 'SUPER_ADMIN'>

export const systemRoleLabels: Record<SystemRole, string> = {
  ADMIN: '社区管理员',
  STAFF: '服务人员',
  SUPER_ADMIN: '超级管理员'
}

/** 账号状态（接口设计.md 9.10.1.7） */
export type UserStatus = 'ACTIVE' | 'FROZEN'

export const userStatusLabels: Record<UserStatus, string> = {
  ACTIVE: '正常',
  FROZEN: '已冻结'
}

/** 绑定社区摘要（接口设计.md 9.10.1.5） */
export interface IBoundCommunity {
  communityId: number
  communityName: string
}

/** 系统用户（接口设计.md 9.10.1.3 响应） */
export interface ISysUser {
  id: number
  username: string
  realName: string
  phone?: string
  email?: string
  role: SystemRole
  status: UserStatus
  boundCommunities?: IBoundCommunity[]
  createdAt: string
}

/** 创建系统用户请求（接口设计.md 9.10.1.3） */
export interface ICreateSysUserDTO {
  username: string
  password: string
  realName: string
  phone?: string
  email?: string
  role: SystemRole
}

/** 更新系统用户请求（接口设计.md 9.10.1.4，不含 username/password） */
export interface IUpdateSysUserDTO {
  realName: string
  phone?: string
  email?: string
  role: SystemRole
}

/** 冻结/解冻请求（接口设计.md 9.10.1.7） */
export interface IUpdateSysUserStatusDTO {
  status: UserStatus
  reason?: string
}

/** 修改密码请求（接口设计.md 9.10.1.8） */
export interface IChangePasswordDTO {
  oldPassword: string
  newPassword: string
}

/** 系统用户列表查询参数（接口设计.md 9.10.1.6） */
export interface ISysUserQuery extends PageQuery {
  role?: SystemRole
  status?: UserStatus
  keyword?: string
}

/** 管理员绑定社区项（接口设计.md 9.10.2.3） */
export interface IUserCommunityBinding {
  communityId: number
  communityName: string
  communityAddress?: string
  boundAt: string
}

/** 绑定社区响应（接口设计.md 9.10.2.1） */
export interface IUserCommunityBindingResult {
  userId: number
  userName: string
  communityId: number
  communityName: string
  createdAt: string
}

/** 操作日志（接口设计.md 9.10.3.1） */
export interface IOperationLog {
  id: number
  operatorId: number
  operatorName: string
  operatorType: 'ADMIN' | 'STAFF' | 'RESIDENT'
  module: string
  action: string
  targetType?: string
  targetId?: number
  description: string
  ipAddress?: string
  userAgent?: string
  createdAt: string
}

/** 操作日志详情（接口设计.md 9.10.3.2） */
export interface IOperationLogDetail extends IOperationLog {
  requestParams?: string
}

/** 操作日志查询参数（接口设计.md 9.10.3.1） */
export interface IOperationLogQuery extends PageQuery {
  operatorId?: number
  operatorType?: 'ADMIN' | 'STAFF' | 'RESIDENT'
  module?: string
  action?: string
  startTime?: string
  endTime?: string
  keyword?: string
}
