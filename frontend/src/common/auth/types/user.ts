import type { Role } from '@/common/web/types/api'

/** 登录响应 data（接口设计.md 9.2.1.2 / 9.10.1.1） */
export interface AuthUser {
  id: number
  username: string
  realName: string
  role: Role
  /** 后端登录响应为小区 ID 数组（数字），小区名需另查小区列表 */
  boundCommunities?: number[]
}

export interface LoginResult {
  token: string
  expiresIn: number
  user: AuthUser
}

/** sessionStorage 持久化的会话信息 */
export interface PersistedSession {
  token: string
  user: AuthUser
  expiresAt: number
}
