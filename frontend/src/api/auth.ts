import { http } from '@/utils/request'
import type { LoginResult } from '@/types/user'

/** 居民登录（接口设计.md 9.2.1.2） */
export function residentLogin(data: { username: string; password: string }) {
  return http.post<LoginResult>('/auth/resident/login', data)
}

/** 居民注册（接口设计.md 9.2.1.1） */
export function residentRegister(data: {
  username: string
  password: string
  realName: string
  phone: string
  email?: string
  idCardNumber?: string
}) {
  return http.post<null>('/auth/resident/register', data)
}

/** 居民登出（接口设计.md 9.2.1.3） */
export function residentLogout() {
  return http.post<null>('/auth/resident/logout')
}

/** 管理员/服务人员登录（接口设计.md 9.10.1.1） */
export function adminLogin(data: { username: string; password: string }) {
  return http.post<LoginResult>('/auth/admin/login', data)
}

/** 管理员登出（接口设计.md 9.10.1.2） */
export function adminLogout() {
  return http.post<null>('/auth/admin/logout')
}
