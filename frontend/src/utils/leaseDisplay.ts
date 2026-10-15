import { leaseStatusLabels, type ILeaseRecord } from '@/types/modules/lease'

/** SIGNED未生效仍占用房屋；只有首期成功才能ACTIVE，界面需区分签约和支付。 */
export function leaseDisplayLabel(lease: Pick<ILeaseRecord,'status'|'agreementStatus'> & Partial<Pick<ILeaseRecord,'startDate'>>): string {
  if(lease.status==='ACTIVE'&&lease.startDate&&new Date(`${lease.startDate.slice(0,10)}T00:00:00`).getTime()>Date.now())return '已生效·待入住'
  return lease.status === 'TO_SIGN' && lease.agreementStatus === 'SIGNED'
    ? '待支付首期费用' : leaseStatusLabels[lease.status]
}
