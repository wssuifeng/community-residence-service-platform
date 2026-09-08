import { http } from '@/utils/request'
import type { PageQuery, PageResult } from '@/types/api'
import type { INotice, INoticeQuery, INoticeSaveRequest, INoticeViewer } from '@/types/modules/notice'

/** 创建公告（SUPER_ADMIN 广播时 communityId 传 null，接口设计.md 9.5.1.1） */
export function createNotice(data: INoticeSaveRequest) {
  return http.post<INotice>('/notices', data)
}

/** 更新公告（仅草稿状态可改，接口设计.md 9.5.1.2） */
export function updateNotice(id: number, data: INoticeSaveRequest) {
  return http.put<INotice>(`/notices/${id}`, data)
}

/** 删除公告（仅草稿/已过期状态可删，接口设计.md 9.5.1.3） */
export function deleteNotice(id: number) {
  return http.delete<null>(`/notices/${id}`)
}

/** 查询公告详情（普通用户仅可见已发布未过期公告，接口设计.md 9.5.1.4） */
export function getNotice(id: number) {
  return http.get<INotice>(`/notices/${id}`)
}

/** 公告列表分页查询（高优先级置顶，接口设计.md 9.5.1.5） */
export function listNotices(params: INoticeQuery) {
  return http.get<PageResult<INotice>>('/notices', params)
}

/** 发布公告（publishTime 超前时为定时发布，接口设计.md 9.5.1.6） */
export function publishNotice(id: number, data: { publishTime: string }) {
  return http.patch<null>(`/notices/${id}/publish`, data)
}

/** 撤回公告（仅已发布状态可撤回，接口设计.md 9.5.1.7） */
export function withdrawNotice(id: number, data: { reason: string }) {
  return http.patch<null>(`/notices/${id}/withdraw`, data)
}

/** 记录公告查看（同一用户同一公告仅记录一次，接口设计.md 9.5.1.8） */
export function recordNoticeView(id: number) {
  return http.post<null>(`/notices/${id}/view`)
}

/** 公告查看记录分页查询（接口设计.md 9.5.1.9） */
export function listNoticeViewers(id: number, params: PageQuery) {
  return http.get<PageResult<INoticeViewer>>(`/notices/${id}/viewers`, params)
}
