import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IEvaluation,
  IEvaluationCreateRequest,
  IEvaluationFollowup,
  IEvaluationFollowupCreateRequest,
  IEvaluationQuery,
  IEvaluationStatistics,
  IUnsatisfiedEvaluationQuery
} from '@/types/modules/evaluation'

/** 提交工单评价（仅提交人可评价且不可重复，rating<4 判不满意进跟进工作台） */
export function submitEvaluation(orderId: number, data: IEvaluationCreateRequest) {
  return http.post<IEvaluation>(`/work-orders/${orderId}/evaluation`, data)
}

/** 查询工单评价 */
export function getEvaluation(orderId: number) {
  return http.get<IEvaluation>(`/work-orders/${orderId}/evaluation`)
}

/** 评价列表分页查询（后端仅收 page/size/minRating/maxRating） */
export function listEvaluations(params: IEvaluationQuery) {
  return http.get<PageResult<IEvaluation>>('/evaluations', params)
}

/** 不满意评价列表分页查询（跟进工作台；后端仅收 page/size，无已跟进过滤参数） */
export function listUnsatisfiedEvaluations(params: IUnsatisfiedEvaluationQuery) {
  return http.get<PageResult<IEvaluation>>('/evaluations/unsatisfied', params)
}

/** 添加不满意跟进记录（仅不满意评价可跟进，content 必填 ≤1000 字符） */
export function addEvaluationFollowup(evaluationId: number, data: IEvaluationFollowupCreateRequest) {
  return http.post<IEvaluationFollowup>(`/evaluations/${evaluationId}/followup`, data)
}

/** 跟进记录列表（后端返回纯数组、按 id 正序，非分页结构） */
export function listEvaluationFollowups(evaluationId: number) {
  return http.get<IEvaluationFollowup[]>(`/evaluations/${evaluationId}/followups`)
}

/** 评价统计：总数/平均分/满意率/分档分布（数据权限拦截器按角色收敛社区范围） */
export function getEvaluationStatistics(communityId?: number) {
  return http.get<IEvaluationStatistics>('/statistics/evaluations', communityId ? { communityId } : undefined)
}
