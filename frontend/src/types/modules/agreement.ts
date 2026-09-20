/** C3 扩展 · 租赁协议轻量版类型定义（R64，对齐后端 AgreementService / 接口设计.md 9.3.2~9.3.3） */
import type { PageQuery } from '@/types/api'

/* ---------------------------------- 协议模板 ---------------------------------- */

/** 协议模板状态 */
export type AgreementTemplateStatus = 'ACTIVE' | 'INACTIVE'

/**
 * 协议正文可用占位符（后端 AgreementService.render 支持中英文别名）：
 * {{社区名称}} {{房屋位置}} {{房号}} {{楼层}} {{租客姓名}} {{租期开始}} {{租期结束}}
 * {{月租金}} {{押金}} {{租约编号}} {{签约日期}}
 */
export interface IAgreementTemplate {
  id: number
  /** 归属社区ID：为空表示全局通用模板（仅超管可维护，各社区均可用） */
  communityId?: number | null
  /** 归属社区名称（全局模板后端回填「全局通用模板」） */
  communityName?: string
  name: string
  /** 协议正文（支持 {{变量}} 占位；为空时正文以模板附件为准） */
  content?: string
  /** 模板附件原始文件名（上传的模板文件，供查看/下载） */
  fileName?: string
  /** 模板附件访问地址 */
  fileUrl?: string
  fileSize?: number
  /** 是否默认模板：1-是（同范围内唯一） */
  isDefault?: number
  status: AgreementTemplateStatus
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/** 协议模板创建/更新请求（POST/PUT /agreement-templates） */
export interface IAgreementTemplateDTO {
  communityId?: number | null
  name: string
  content?: string
  fileName?: string
  fileUrl?: string
  fileSize?: number
  isDefault?: number
  remark?: string
}

/** 协议模板列表查询参数 */
export interface IAgreementTemplateQuery extends PageQuery {
  communityId?: number
  status?: AgreementTemplateStatus
}

/* ---------------------------------- 租约协议 ---------------------------------- */

/**
 * 协议状态（后端四态）：
 * PENDING-待确认 / PARTIAL-单方已确认 / SIGNED-双方已确认 / CANCELLED-已撤回
 */
export type LeaseAgreementStatus = 'PENDING' | 'PARTIAL' | 'SIGNED' | 'CANCELLED'

/** 协议状态中文标签 */
export const leaseAgreementStatusLabels: Record<LeaseAgreementStatus, string> = {
  PENDING: '待确认',
  PARTIAL: '单方已确认',
  SIGNED: '双方已确认',
  CANCELLED: '已撤回'
}

/** 租约签约状态（lease_record.agreement_status：NONE-未发起，其余与协议状态同义） */
export type LeaseAgreementSignStatus = 'NONE' | LeaseAgreementStatus

/** 租约签约状态中文标签（展示为「未发起协议」而非「未发起」，避免误解为无租约） */
export const leaseAgreementSignStatusLabels: Record<LeaseAgreementSignStatus, string> = {
  NONE: '未发起协议',
  PENDING: '待签署',
  PARTIAL: '单方已确认',
  SIGNED: '已签署',
  CANCELLED: '协议已撤回'
}

/** 租约协议（后端 LeaseAgreementVO；content 为发起时的正文快照） */
export interface ILeaseAgreement {
  id: number
  leaseId: number
  communityId: number
  templateId?: number | null
  templateName?: string
  title: string
  /** 协议正文快照（占位符已渲染，模板后续修改不影响本快照） */
  content?: string
  templateFileUrl?: string
  templateFileName?: string
  status: LeaseAgreementStatus
  tenantId?: number
  /** 居民方确认人姓名 */
  tenantConfirmName?: string | null
  /** 居民方确认时间 */
  tenantConfirmTime?: string | null
  /** 管理方确认人姓名 */
  adminConfirmName?: string | null
  /** 管理方确认时间 */
  adminConfirmTime?: string | null
  cancelReason?: string
  remark?: string
  createdAt: string
}

/** 发起协议请求（POST /lease-agreements；templateId 为空时取该社区默认模板） */
export interface ICreateAgreementDTO {
  leaseId: number
  templateId?: number
  title?: string
  remark?: string
}

/** 确认协议请求（POST /lease-agreements/{id}/confirm；按当前身份落居民方或管理方） */
export interface IAgreementConfirmDTO {
  /** 确认人姓名（缺省取当前账号姓名，快照入库） */
  confirmName?: string
  remark?: string
}
