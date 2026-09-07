import { http } from '@/utils/request'
import type { PageQuery, PageResult } from '@/types/api'
import type {
  IEvaluation,
  IEvaluationCreateRequest,
  IEvaluationFollowup,
  IEvaluationFollowupCreateRequest,
  IEvaluationQuery,
  IUnsatisfiedEvaluationQuery
} from '@/types/modules/evaluation'

/** 提交工单评价（仅提交人可评价且不可重复，rating<4 自动生成跟进记录，接口设计.md 9.8.1.1） */
export function submitEvaluation(orderId: number, data: IEvaluationCreateRequest) {
  return http.post<IEvaluation>(`/work-orders/${orderId}/evaluation`, data)
}

/** 查询工单评价（接口设计.md 9.8.1.2） */
export function getEvaluation(orderId: number) {
  return http.get<IEvaluation>(`/work-orders/${orderId}/evaluation`)
}

/** 评价列表分页查询（接口设计.md 9.8.1.3） */
export function listEvaluations(params: IEvaluationQuery) {
  return http.get<PageResult<IEvaluation>>('/evaluations', params)
}

/** 不满意评价列表分页查询（接口设计.md 9.8.1.4） */
export function listUnsatisfiedEvaluations(params: IUnsatisfiedEvaluationQuery) {
  return http.get<PageResult<IEvaluation>>('/evaluations/unsatisfied', params)
}

/** 添加不满意跟进记录（接口设计.md 9.8.2.1） */
export function addEvaluationFollowup(evaluationId: number, data: IEvaluationFollowupCreateRequest) {
  return http.post<IEvaluationFollowup>(`/evaluations/${evaluationId}/followup`, data)
}

/** 跟进记录列表分页查询（按评价时间倒序，接口设计.md 9.8.2.2） */
export function listEvaluationFollowups(evaluationId: number, params: PageQuery) {
  return http.get<PageResult<IEvaluationFollowup>>(`/evaluations/${evaluationId}/followups`, params)
}
