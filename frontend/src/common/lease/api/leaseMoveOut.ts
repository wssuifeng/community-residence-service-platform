import { http } from '@/common/web/utils/request'
import type { PageResult } from '@/common/web/types/api'
import type {
  ICreateMoveOutApplicationDTO,
  IMoveOutApplication,
  IMoveOutApplicationQuery,
  IMoveOutApproveDTO,
  IMoveOutRejectDTO
} from '@/common/lease/types/lease'

/**
 * R70 居民自助退租申请接口（V20 租住签约闭环）。
 *
 * 契约要点：居民侧仅可对**本人已生效（ACTIVE）**租约发起，同一租约同时仅允许一条
 * 待审核申请（重复发起后端拒绝 MOVE_OUT_APPLICATION_PENDING）；管理端审核通过后
 * 由后端联动租约→已搬出、居住关系终止、房屋置空置、房源按 housingStatus 回收。
 * 因此本模块只负责发起与审核，不做任何本地状态预判。
 */

/** 提交退租申请（居民本人、仅 ACTIVE 租约；搬出日期 + 原因） */
export function createMoveOutApplication(leaseId: number, data: ICreateMoveOutApplicationDTO) {
  return http.post<IMoveOutApplication>(`/leases/${leaseId}/move-out-applications`, data)
}

/** 退租申请分页列表（管理端，按社区数据权限；居民侧经租约详情查看） */
export function listMoveOutApplications(params: IMoveOutApplicationQuery) {
  return http.get<PageResult<IMoveOutApplication>>('/lease-move-out-applications', params)
}

/** 审核通过退租申请（租约转已搬出并联动居住关系/房屋/房源） */
export function approveMoveOutApplication(id: number, data: IMoveOutApproveDTO) {
  return http.patch<IMoveOutApplication>(`/lease-move-out-applications/${id}/approve`, data)
}

/** 驳回退租申请（理由必填，租约保持已生效） */
export function rejectMoveOutApplication(id: number, data: IMoveOutRejectDTO) {
  return http.patch<IMoveOutApplication>(`/lease-move-out-applications/${id}/reject`, data)
}
