import type { PageQuery } from '@/types/api'

/** 评价分值（1-5，rating ≥ 4 判定满意） */
export type EvaluationRating = 1 | 2 | 3 | 4 | 5

/** 满意度中文标签（isSatisfied 布尔映射，供 StatusTag 使用） */
export const evaluationSatisfiedLabels = {
  true: '满意',
  false: '不满意'
} as const

/** 工单评价（接口设计.md 9.8.1.1 响应） */
export interface IEvaluation {
  id: number
  orderId: number
  orderNumber: string
  residentId: number
  residentName: string
  assigneeId: number
  assigneeName: string
  rating: EvaluationRating
  serviceAttitude: EvaluationRating
  responseSpeed: EvaluationRating
  solutionQuality: EvaluationRating
  content: string | null
  isAnonymous: boolean
  isSatisfied: boolean
  createdAt: string
}

/** 提交评价请求（接口设计.md 9.8.1.1，仅工单提交人可评价且不可重复） */
export interface IEvaluationCreateRequest {
  rating: EvaluationRating
  serviceAttitude: EvaluationRating
  responseSpeed: EvaluationRating
  solutionQuality: EvaluationRating
  content?: string
  isAnonymous: boolean
}

/** 评价列表查询参数（接口设计.md 9.8.1.3） */
export interface IEvaluationQuery extends PageQuery {
  isSatisfied?: boolean
  minRating?: EvaluationRating
  assigneeId?: number
  startTime?: string
  endTime?: string
}

/** 不满意评价列表查询参数（接口设计.md 9.8.1.4） */
export interface IUnsatisfiedEvaluationQuery extends PageQuery {
  /** 是否已跟进 */
  hasFollowup?: boolean
}

/** 不满意跟进记录（接口设计.md 9.8.2.1 响应） */
export interface IEvaluationFollowup {
  id: number
  evaluationId: number
  action: string
  result: string | null
  remark: string | null
  followerId: number
  followerName: string
  createdAt: string
}

/** 添加跟进请求（接口设计.md 9.8.2.1） */
export interface IEvaluationFollowupCreateRequest {
  action: string
  result?: string
  remark?: string
}
