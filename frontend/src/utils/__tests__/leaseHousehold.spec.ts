import { describe,it,expect,vi,beforeEach } from 'vitest'
import { getHouseResidentList,getResidenceDependents } from '@/api/resident'
import { leaseHouseholdNotice } from '../leaseHousehold'
vi.mock('@/api/resident',()=>({getHouseResidentList:vi.fn(),getResidenceDependents:vi.fn()}))
describe('租约直办迁出共用成员核对',()=>{
 beforeEach(()=>vi.clearAllMocks())
 it('只核对本租约主关系并展示成员，不混入另一住户',async()=>{
  vi.mocked(getHouseResidentList).mockResolvedValue({records:[{id:1,residentId:8,leaseId:7},{id:2,residentId:9,leaseId:6}],total:2} as any)
  vi.mocked(getResidenceDependents).mockResolvedValue([{residentName:'成员甲'}] as any)
  expect(await leaseHouseholdNotice({id:7,houseId:10,tenantId:8})).toEqual({confirmDependents:true,text:expect.stringContaining('成员甲')})
  expect(getResidenceDependents).toHaveBeenCalledExactlyOnceWith(1)
 })
 it('成员读取失败不能冒充无成员',async()=>{
  vi.mocked(getHouseResidentList).mockResolvedValue({records:[{id:1,residentId:8,leaseId:7}],total:1} as any)
  vi.mocked(getResidenceDependents).mockRejectedValue(new Error('读取失败'))
  await expect(leaseHouseholdNotice({id:7,houseId:10,tenantId:8})).rejects.toThrow('读取失败')
 })
})
