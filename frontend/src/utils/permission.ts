import type { Role } from '@/types/api'

/**
 * 角色判定：允许的角色列表中包含当前角色即通过；
 * SUPER_ADMIN 默认覆盖 ADMIN 场景（两层权限模型，UI设计.md §10）
 */
export function hasRole(current: Role, allowed: Role[]): boolean {
  if (allowed.length === 0) return true
  if (allowed.includes(current)) return true
  return current === 'SUPER_ADMIN' && allowed.includes('ADMIN')
}
