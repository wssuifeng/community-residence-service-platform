import { describe, expect, it } from 'vitest'
import { residentDisplayName, residentInitial, residentRelationLabel } from '../staffResidentPresentation'

describe('服务人员居民信息只读真实字段', () => {
  it('不同工单ID不影响真实姓名与身份', () => {
    for (const id of [1, 2, 3, 9]) {
      expect(residentDisplayName({ id, residentName: '王小明' })).toBe('王小明')
      expect(residentRelationLabel({ id })).toBe('居民')
    }
  })
  it('空姓名采用中性回退，不推断性别', () => {
    expect(residentDisplayName({ id: 2, residentName: '  ' })).toBe('居民')
    expect(residentInitial('  ')).toBe('居')
  })
})
