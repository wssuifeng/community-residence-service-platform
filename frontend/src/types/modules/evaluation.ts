import type { PageQuery } from '@/types/api'

/** 评价统计（GET /statistics/evaluations，后端 EvaluationService.statistics 实测形状） */
export interface IEvaluationStatistics {
  total: number
  averageRating: number
  satisfiedRate: number
  /** 分档计数：键为评分字符串（'1'~'5'），仅含有记录的档位 */
  ratingDistribution: Record<string, number>
}

/** 评价分值（1-5，rating ≥ 4 判定满意） */
export type EvaluationRating = 1 | 2 | 3 | 4 | 5

/** 满意度中文标签（isSatisfied 布尔映射，供 StatusTag 使用） */
export const evaluationSatisfiedLabels = {
  true: '满意',
  false: '不满意'
} as const

/** 工单评价（后端 EvaluationVO：单一 rating + tags + isSatisfied(0/1)；接口文档的 4 维评分为漂移模型） */
export interface IEvaluation {
  id: number
  workOrderId: number
  workOrderNo: string
  residentId: number
  residentName: string
  communityId: number
  rating: EvaluationRating
  content: string | null
  tags: string | null
  isSatisfied: number
  createdAt: string
}

/** 提交评价请求（后端 CreateEvaluationDTO，仅工单提交人可评价且不可重复） */
export interface IEvaluationCreateRequest {
  rating: EvaluationRating
  content?: string
  tags?: string
  /** rating ≥ 4 为满意，前端计算 */
  isSatisfied: boolean
}

/** 评价列表查询参数（后端 GET /evaluations 实际仅收 page/size/minRating/maxRating，
 *  旧类型的 isSatisfied/assigneeId/startTime/endTime 为文档漂移、后端不识别，已删除） */
export interface IEvaluationQuery extends PageQuery {
  minRating?: number
  maxRating?: number
}

/** 不满意评价列表查询参数（后端 GET /evaluations/unsatisfied 实际仅收 page/size） */
export type IUnsatisfiedEvaluationQuery = PageQuery

/** 不满意跟进记录（后端 FollowUpVO 实测字段：跟进内容单字段 + 跟进人 + 跟进时间；
 *  旧类型的 action/result/remark/followerName/createdAt 为文档漂移，已删除） */
export interface IEvaluationFollowup {
  id: number
  evaluationId: number
  handlerId: number
  handlerName: string | null
  followupContent: string | null
  followupTime: string
}

/** 添加跟进请求（后端 CreateFollowUpDTO：仅 content，@NotBlank ≤1000 字符） */
export interface IEvaluationFollowupCreateRequest {
  content: string
}
