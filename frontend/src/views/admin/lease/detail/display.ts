import type { ILeaseChange, LeaseChangeType, LeaseChangeOperatorType } from '@/common/lease/types/lease'
import { leaseChangeTypeLabels } from '@/common/lease/types/lease'
/** 变更类型 → 标签配色：属性变更中性、续租蓝、状态流转紫、协议签约绿、押金退还青、登记蓝灰 */
export const CHANGE_TONE: Record<LeaseChangeType, string> = {
  CREATE: 'is-neutral',
  ATTRIBUTE: 'is-neutral',
  RENEW: 'is-blue',
  STATUS: 'is-purple',
  AGREEMENT: 'is-green',
  DEPOSIT_REFUND: 'is-teal'
}

const OPERATOR_LABELS: Record<LeaseChangeOperatorType, string> = {
  ADMIN: '管理方',
  RESIDENT: '居民',
  SYSTEM: '系统'
}

/** 金额类字段补 ¥（字段名或中文名可辨）；空值统一显示「—」 */
function isMoneyField(change: ILeaseChange): boolean {
  const key = `${change.fieldName ?? ''}${change.fieldLabel ?? ''}`
  return /rent|deposit|租金|押金/i.test(key)
}

export function valueText(change: ILeaseChange, value?: string | null): string {
  if (value == null || value === '') return '—'
  if (isMoneyField(change) && !value.startsWith('¥')) return `¥ ${value}`
  return value
}

/** 变更描述：字段级变更给「字段中文名」，整体变更给类型说明 */
export function changeDescription(change: ILeaseChange): string {
  if (change.fieldLabel) return change.fieldLabel
  return leaseChangeTypeLabels[change.changeType]
}

export const operatorText = (change: ILeaseChange): string => {
  const name = change.operatorName || OPERATOR_LABELS[change.operatorType]
  return `${name}（${OPERATOR_LABELS[change.operatorType]}）`
}

export function moneyText(value?: number | null): string { return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}` }
export function leaseNo(id: number): string { return `ZL${String(id).padStart(4, '0')}` }
