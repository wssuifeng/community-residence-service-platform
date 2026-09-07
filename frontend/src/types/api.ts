/** 统一响应格式（接口设计.md §2） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: string
}

/** 分页响应 data 结构（接口设计.md §2.2） */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

/** 分页请求参数（接口设计.md §4.1，page 从 1 开始） */
export interface PageQuery {
  page?: number
  size?: number
  sortBy?: string
  sortOrder?: 'ASC' | 'DESC'
}

/** 角色（接口设计.md §7.1 五角色） */
export type Role = 'GUEST' | 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'

/** 通用键值对 */
export type RecordData = Record<string, unknown>
