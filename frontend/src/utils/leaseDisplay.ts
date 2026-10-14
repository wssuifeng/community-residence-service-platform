import { leaseStatusLabels, type ILeaseRecord } from '@/types/modules/lease'

/** SIGNED未生效仍占用房屋；只有首期成功才能ACTIVE，界面需区分签约和支付。 */
export function leaseDisplayLabel(lease: Pick<ILeaseRecord,'status'|'agreementStatus'>): string {
  return lease.status === 'TO_SIGN' && lease.agreementStatus === 'SIGNED'
    ? '待支付首期费用' : leaseStatusLabels[lease.status]
}
