/** C3 扩展 · 租赁协议接口（对齐后端 AgreementTemplateController / LeaseAgreementController 实测契约） */
import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IAgreementConfirmDTO,
  IAgreementTemplate,
  IAgreementTemplateDTO,
  IAgreementTemplateQuery,
  ICreateAgreementDTO,
  ILeaseAgreement
} from '@/types/modules/agreement'

/* ---------------------------------- 协议模板 ---------------------------------- */

/** 协议模板分页列表（社区管理员可见全局模板与本社区模板） */
export function listAgreementTemplates(params: IAgreementTemplateQuery) {
  return http.get<PageResult<IAgreementTemplate>>('/agreement-templates', params)
}

/** 某社区可用的协议模板清单（本社区模板 + 全局模板，默认模板排最前） */
export function listAvailableAgreementTemplates(communityId: number) {
  return http.get<IAgreementTemplate[]>('/agreement-templates/available', { communityId })
}

/** 协议模板详情 */
export function getAgreementTemplate(id: number) {
  return http.get<IAgreementTemplate>(`/agreement-templates/${id}`)
}

/** 创建协议模板（正文与模板附件至少填写一项；communityId 为空则为全局模板，仅超管） */
export function createAgreementTemplate(data: IAgreementTemplateDTO) {
  return http.post<IAgreementTemplate>('/agreement-templates', data)
}

/** 更新协议模板 */
export function updateAgreementTemplate(id: number, data: IAgreementTemplateDTO) {
  return http.put<IAgreementTemplate>(`/agreement-templates/${id}`, data)
}

/** 启用/停用协议模板 */
export function updateAgreementTemplateStatus(id: number, status: 'ACTIVE' | 'INACTIVE') {
  return http.patch<IAgreementTemplate>(`/agreement-templates/${id}/status`, null, { params: { status } })
}

/** 删除协议模板（已生成协议保存正文快照，删除模板不影响历史协议） */
export function deleteAgreementTemplate(id: number) {
  return http.delete<null>(`/agreement-templates/${id}`)
}

/* ---------------------------------- 租约协议 ---------------------------------- */

/** 发起租约协议（管理方按模板生成正文快照并送居民确认） */
export function createLeaseAgreement(data: ICreateAgreementDTO) {
  return http.post<ILeaseAgreement>('/lease-agreements', data)
}

/** 某租约的协议流水（含已撤回历史，倒序） */
export function listLeaseAgreements(leaseId: number) {
  return http.get<ILeaseAgreement[]>('/lease-agreements', { leaseId })
}

/** 协议详情 */
export function getLeaseAgreement(id: number) {
  return http.get<ILeaseAgreement>(`/lease-agreements/${id}`)
}

/** 确认协议（按当前身份落居民方或管理方；双方确认后协议生效并同步租约签约状态） */
export function confirmLeaseAgreement(id: number, data: IAgreementConfirmDTO) {
  return http.post<ILeaseAgreement>(`/lease-agreements/${id}/confirm`, data)
}

/** 撤回协议（仅管理方；仅待确认/单方已确认可撤回） */
export function cancelLeaseAgreement(id: number, reason: string) {
  return http.post<null>(`/lease-agreements/${id}/cancel`, null, { params: { reason } })
}
