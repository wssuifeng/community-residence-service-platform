import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LeaseTermsPanel from '../LeaseTermsPanel.vue'
import type { ILeaseRecord } from '@/types/modules/lease'
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/utils/request', () => ({ http: api }))
const lease = { id: 1, status: 'ACTIVE', startDate: '2026-10-15', endDate: '2027-10-14', monthlyRent: 3000 } as ILeaseRecord
beforeEach(() => { vi.clearAllMocks(); api.get.mockResolvedValue([]) })
describe('合同段及缴租安排共用面板', () => {
 it('展示后端逐段已缴区间，不把未来最大止期当成全期已缴', async () => {
  api.get.mockResolvedValue([{ id: 5, termType: 'INITIAL', status: 'ACTIVE', contractMonths: 12, firstPaymentMonths: 3, monthlyRent: 3000, paidMonths: 3, remainingMonths: 9, nextDueDate: '2027-01-15', overdue: false, paidPeriods: [{paymentNo: 'MOCK-1', startDate: '2026-10-15', endDate: '2027-01-14'}] }])
  const w = mount(LeaseTermsPanel, { props: { lease }, global: { plugins: [ElementPlus] } })
  await flushPromises(); expect(w.text()).toContain('已缴3个月 / 待缴9个月'); expect(w.text()).toContain('MOCK-1：2026-10-15 至 2027-01-14'); expect(w.text()).toContain('普通交租不延长合同'); w.unmount()
 })
 it('没有合同快照的历史数据明确待核对，不伪造付款安排', async () => {
  const w = mount(LeaseTermsPanel, { props: { lease }, global: { plugins: [ElementPlus] } })
  await flushPromises(); expect(w.text()).toContain('历史合同付款安排未补录'); expect(api.post).not.toHaveBeenCalled(); w.unmount()
 })
})

it('居民申请延长，不出现旧管理员拟定首笔续租表单',async()=>{
 api.get.mockImplementation((url:string)=>Promise.resolve(url.endsWith('/terms')?[{id:5,termType:'INITIAL',status:'ACTIVE',startDate:lease.startDate,endDate:lease.endDate,contractMonths:12,monthlyRent:3000,paidMonths:1,remainingMonths:11,paidPeriods:[]}]:[]))
 const w=mount(LeaseTermsPanel,{props:{lease},global:{plugins:[ElementPlus]}});await flushPromises()
 expect(w.text()).toContain('2027-10-14 → 2028-01-14');expect(w.text()).not.toContain('新增段首付')
 await w.findAll('button').find(b=>b.text()==='同意此方案并提交延长申请')!.trigger('click');await flushPromises()
 expect(api.post).toHaveBeenCalledWith('/leases/1/extensions',{months:3,reason:''});w.unmount()
})
it('管理员只有审核入口，不允许修改居民的申请月数/价格',async()=>{
 api.get.mockImplementation((url:string)=>Promise.resolve(url.endsWith('/extensions')?[{id:8,status:'PENDING',months:3,residentId:4,originalEndDate:'2027-10-14',proposedEndDate:'2028-01-14',monthlyRent:3000}]:[]))
 const w=mount(LeaseTermsPanel,{props:{lease,admin:true},global:{plugins:[ElementPlus]}});await flushPromises()
 expect(w.text()).toContain('批准延长');expect(w.text()).toContain('驳回');expect(w.findAllComponents({name:'ElInputNumber'})).toHaveLength(0);w.unmount()
})
