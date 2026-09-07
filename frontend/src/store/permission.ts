import { defineStore } from 'pinia'
import { computed } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from './user'
import { hasRole } from '@/utils/permission'
import residentRoutes from '@/router/resident'
import staffRoutes from '@/router/staff'
import adminRoutes from '@/router/admin'

/**
 * 权限状态：按当前角色过滤三端路由树，输出可见菜单
 * （UI设计.md §10.1 菜单权限；按钮级用 v-permission 指令）
 */
export const usePermissionStore = defineStore('permission', () => {
  const userStore = useUserStore()

  const accessibleRoutes = computed<RouteRecordRaw[]>(() => {
    const candidates = [...residentRoutes, ...staffRoutes, ...adminRoutes]
    return candidates.filter((route) => {
      const roles = route.meta?.roles
      return !roles || hasRole(userStore.role, roles)
    })
  })

  const menus = computed(() => accessibleRoutes.value.filter((route) => !route.meta?.hidden))

  return { accessibleRoutes, menus }
})
