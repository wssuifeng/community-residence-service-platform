import { http } from '@/common/web/utils/request'
import type { ILeaseRecord } from '@/common/lease/types/lease'
import type { PageResult } from '@/common/web/types/api'
export interface LeaseAction { enabled: boolean; reason: string }
export interface LeaseSummary {
 displayStatus: string; firstPaymentSucceeded: boolean; firstPaymentPaidAmount: number | null; firstPaymentPaidAt: string | null
 paidRentMonths: number | null; unpaidRentMonths: number | null
 actions: Record<'firstPayment'|'rentPayment'|'extension'|'moveOut', LeaseAction>
}
export interface LeaseCard { lease: ILeaseRecord; summary: LeaseSummary }
export const getMyLeaseSummaries = (params: {page:number;size:number}) => http.get<PageResult<LeaseCard>>('/leases/my-summaries',params)
