import { describe, it, expect } from 'vitest'
import { sortHouseReference, houseReferenceCsv } from '../residentImportReference'
const row = (houseId: number, buildingName: string, floor: number, houseNumber: string) => ({ houseId, communityId: 2, communityName:'合成社区', buildingName, unitName: '一单元', floor, houseNumber, houseStatus:'VACANT' }) as any
describe('导入对照真实ID',()=>{
 it('按位置排序而不是自增ID',()=>expect(sortHouseReference([row(1,'10栋',1,'101'),row(77,'2栋',2,'201'),row(99,'2栋',1,'101')]).map(r=>r.houseId)).toEqual([99,77,1]))
 it('保留真实ID，文本转义逗号/引号与公式',()=>{const csv=houseReferenceCsv([row(77,'=危险',1,'101,"A"')]);expect(csv).toContain('"77"');expect(csv).toContain('"101,""A"""');expect(csv).toContain("'=危险")})
})
