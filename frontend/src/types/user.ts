import type { Role } from './api'

/** 登录响应 data（接口设计.md 9.2.1.2 / 9.10.1.1） */
export interface AuthUser {
  id: number
  username: string
  realName: string
  role: Role
  boundCommunities?: { communityId: number; communityName: string }[]
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
