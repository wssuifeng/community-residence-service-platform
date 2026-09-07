import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  FeedbackCloseDTO,
  FeedbackCreateDTO,
  FeedbackListQuery,
  FeedbackMessageCreateDTO,
  FeedbackMessageQuery,
  IFeedback,
  IFeedbackAttachment,
  IFeedbackMessage
} from '@/types/modules/feedback'

/** 提交反馈（接口设计.md 9.6.1.1） */
export function createFeedback(data: FeedbackCreateDTO) {
  return http.post<IFeedback>('/feedbacks', data)
}

/** 查询反馈详情（接口设计.md 9.6.1.2） */
export function getFeedbackDetail(id: number) {
  return http.get<IFeedback>(`/feedbacks/${id}`)
}

/** 反馈列表分页查询（接口设计.md 9.6.1.3） */
export function listFeedbacks(query: FeedbackListQuery) {
  return http.get<PageResult<IFeedback>>('/feedbacks', query)
}

/** 关闭反馈（接口设计.md 9.6.1.4） */
export function closeFeedback(id: number, data: FeedbackCloseDTO) {
  return http.patch<null>(`/feedbacks/${id}/close`, data)
}

/** 上传反馈附件（接口设计.md 9.6.2.1，multipart/form-data） */
export function uploadFeedbackAttachment(feedbackId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<IFeedbackAttachment>(`/feedbacks/${feedbackId}/attachments`, formData)
}

/** 删除反馈附件（接口设计.md 9.6.2.2） */
export function deleteFeedbackAttachment(id: number) {
  return http.delete<null>(`/feedback-attachments/${id}`)
}

/** 反馈附件列表（接口设计.md 9.6.2.3） */
export function listFeedbackAttachments(feedbackId: number) {
  return http.get<IFeedbackAttachment[]>(`/feedbacks/${feedbackId}/attachments`)
}

/** 发送反馈会话消息（接口设计.md 9.6.3.1） */
export function sendFeedbackMessage(feedbackId: number, data: FeedbackMessageCreateDTO) {
  return http.post<IFeedbackMessage>(`/feedbacks/${feedbackId}/messages`, data)
}

/** 反馈会话消息列表（接口设计.md 9.6.3.2，默认时间升序） */
export function listFeedbackMessages(feedbackId: number, query?: FeedbackMessageQuery) {
  return http.get<PageResult<IFeedbackMessage>>(`/feedbacks/${feedbackId}/messages`, query)
}
