import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { IOperationLog, IOperationLogDetail, IOperationLogQuery } from '@/types/modules/auth'

/** C10 操作日志查询接口（接口设计.md §9.10.3） */

/** 9.10.3.1 操作日志列表（分页） */
export function getOperationLogList(params: IOperationLogQuery) {
  return http.get<PageResult<IOperationLog>>('/operation-logs', params)
}

/** 9.10.3.2 操作日志详情 */
export function getOperationLogDetail(id: number) {
  return http.get<IOperationLogDetail>(`/operation-logs/${id}`)
}
