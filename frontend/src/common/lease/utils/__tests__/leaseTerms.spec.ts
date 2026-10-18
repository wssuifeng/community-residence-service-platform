import { describe,it,expect } from 'vitest'
import { contractEnd,calendarBoundary } from '@/common/lease/utils/leaseTerms'
describe('合同期限和缴租日历月锚点',()=>{
 it('12个月合同与3个月首付各有止期',()=>{expect(contractEnd('2026-10-15',12)).toBe('2027-10-14');expect(contractEnd('2026-10-15',3)).toBe('2027-01-14')})
 it('月底每次从原始起租日计算，不累积漂移',()=>{expect(calendarBoundary('2027-01-31',1)).toBe('2027-02-28');expect(calendarBoundary('2027-01-31',2)).toBe('2027-03-31')})
 it('六个月续租不是一次付六个月',()=>{expect(contractEnd('2027-10-15',6)).toBe('2028-04-14');expect(contractEnd('2027-10-15',1)).toBe('2027-11-14')})
 it('空起租日不编造日期',()=>expect(contractEnd('',12)).toBe(''))
})
