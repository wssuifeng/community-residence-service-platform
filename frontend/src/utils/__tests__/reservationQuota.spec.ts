import { describe, expect, it } from 'vitest'
import { reservationDayQuotas } from '../reservationQuota'
describe('预约时段额度与预约条数分离',()=>{
  it('12小时24段容量100，90分钟一条预约占3额度而不是1',()=>{
    const slots=Array.from({length:24},(_,i)=>({ date:'2026-10-15',maxBookings:100,currentBookings:i<3?1:0,status:'AVAILABLE' as const }))
    const result=reservationDayQuotas(slots,[{reserveDate:'2026-10-15',status:'RESERVED'}]).get('2026-10-15')
    expect(result).toMatchObject({capacity:2400,active:3,reservations:1,full:false})
  })
  it('一段满额不能把仍有可约时段的整天标满',()=>{
    expect(reservationDayQuotas([{date:'2026-10-15',maxBookings:1,currentBookings:1,status:'FULL'}, {date:'2026-10-15',maxBookings:1,currentBookings:0,status:'AVAILABLE'}],[]).get('2026-10-15')?.full).toBe(false)
  })
  it('取消记录不算待办或有效预约，所有时段都满才满额',()=>{
    expect(reservationDayQuotas([{date:'2026-10-15',maxBookings:1,currentBookings:1,status:'FULL'}],[{reserveDate:'2026-10-15',status:'CANCELLED'}]).get('2026-10-15')).toMatchObject({full:true,pending:0,reservations:0})
  })
})
