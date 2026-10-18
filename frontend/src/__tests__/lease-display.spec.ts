import { describe,it,expect } from 'vitest'
import { leaseDisplayLabel } from '@/common/lease/utils/leaseDisplay'
describe('租约派生展示，不改变业务状态',()=>{
  it('居民同意协议但未生效显示待支付',()=>expect(leaseDisplayLabel({status:'TO_SIGN',agreementStatus:'SIGNED'})).toBe('待支付首期费用'))
  it('未同意仍待签约',()=>expect(leaseDisplayLabel({status:'TO_SIGN',agreementStatus:'PENDING'})).toBe('待签约'))
  it('支付生效后在租，历史不显示待支付',()=>{
    expect(leaseDisplayLabel({status:'ACTIVE',agreementStatus:'SIGNED'})).toBe('在租')
    expect(leaseDisplayLabel({status:'MOVED_OUT',agreementStatus:'SIGNED'})).toBe('已搬出')
  })
})
