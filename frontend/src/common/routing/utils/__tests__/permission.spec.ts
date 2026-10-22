import { describe, expect, it } from 'vitest'
import { hasRole } from '@/common/routing/utils/permission'

describe('账号角色权限', () => {
  it('未登录公开访问不匹配任何账号角色', () => {
    expect(hasRole(null, ['RESIDENT'])).toBe(false)
    expect(hasRole(null, ['ADMIN', 'SUPER_ADMIN'])).toBe(false)
  })

  it('超级管理员继承管理员可访问权限', () => {
    expect(hasRole('SUPER_ADMIN', ['ADMIN'])).toBe(true)
  })
})
