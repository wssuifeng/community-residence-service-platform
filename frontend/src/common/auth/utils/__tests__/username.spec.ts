import { describe, it, expect } from 'vitest'
import { isValidUsername } from '@/common/auth/utils/username'
describe('中文用户名契约', () => {
  it.each(['张', '张三', '住户A_12', 'normal_user', '甲'.repeat(50)])('接受%s', value => expect(isValidUsername(value)).toBe(true))
  it.each(['', 'a b', '张@三', '甲'.repeat(51)])('拒绝非法值%s', value => expect(isValidUsername(value)).toBe(false))
})
