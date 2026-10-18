import { describe,it,expect } from 'vitest'
import { sortHouseReference } from '@/common/resident/utils/residentImportReference'
describe('结构业务编号查看，不导出',()=>{
 it('按业务层级自然排序，不以数据库自增ID排序',()=>{
 const rows=[{houseId:1,communityId:9,communityCode:'5',buildingCode:'10',unitCode:'1',houseNumber:'101'},{houseId:99,communityId:2,communityCode:'5',buildingCode:'2',unitCode:'1',houseNumber:'101'},{houseId:100,communityId:1,communityCode:'2',buildingCode:'1',unitCode:'1',houseNumber:'101'}] as any
 expect(sortHouseReference(rows).map(r=>r.houseId)).toEqual([100,99,1])
 })
 it('保持原数据及内部主键，不重排主键',()=>{const rows=[{houseId:7,communityCode:'5',buildingCode:'1',unitCode:'1',houseNumber:'102'},{houseId:20,communityCode:'5',buildingCode:'1',unitCode:'1',houseNumber:'101'}] as any;expect(sortHouseReference(rows).map(r=>r.houseId)).toEqual([20,7]);expect(rows[0].houseId).toBe(7)})
})
