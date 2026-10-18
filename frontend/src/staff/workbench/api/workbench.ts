import { http } from '@/common/web/utils/request'
import type { IWorkOrder } from '@/common/workorder/types/workorder'
export interface StaffWorkbenchSummary { todoCount: number; doingCount: number; completedCount: number; urgentCount: number; todoOrders: IWorkOrder[] }
export const getStaffWorkbenchSummary = () => http.get<StaffWorkbenchSummary>('/staff/workbench/summary')
