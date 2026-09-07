import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw, Router } from 'vue-router'
import { useUserStore } from '@/store/user'
import { hasRole } from '@/utils/permission'
import type { Role } from '@/types/api'
import residentRoutes from './resident'
import guestRoutes from './guest'
import staffRoutes from './staff'
import adminRoutes from './admin'

declare module 'vue-router' {
  interface RouteMeta {
    /** 允许访问的角色；空/缺省为公开页面（UI设计.md §10.1） */
    roles?: Role[]
    title?: string
    hidden?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  ...guestRoutes,
  ...residentRoutes,
  ...staffRoutes,
  ...adminRoutes,
  { path: '/', redirect: '/guest/home' },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/NotFoundView.vue'), meta: { title: '页面不存在' } },
  { path: '/403', name: 'Forbidden', component: () => import('@/views/ForbiddenView.vue'), meta: { title: '无权访问' } }
]

const router: Router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 路由守卫：未登录访问受限页 → 登录页（携带回跳地址）；
 * 角色不匹配 → 403；登录后访问登录页 → 按角色跳转各自首页
 */
router.beforeEach((to) => {
  const userStore = useUserStore()
  const roles: Role[] = to.meta.roles ?? []

  document.title = to.meta.title ? `${to.meta.title} · 社区居住服务管理系统` : '社区居住服务管理系统'

  if (to.name === 'Login' && userStore.isLoggedIn) {
    return roleHomePath(userStore.role)
  }

  if (roles.length === 0) return true

  if (!userStore.isLoggedIn) {
    return {
      name: 'Login',
      query: { redirect: to.fullPath }
    }
  }

  if (!hasRole(userStore.role, roles)) {
    return { name: 'Forbidden' }
  }
  return true
})

function roleHomePath(role: Role): string {
  switch (role) {
    case 'RESIDENT':
      return '/resident/home'
    case 'STAFF':
      return '/staff/dashboard'
    case 'ADMIN':
    case 'SUPER_ADMIN':
      return '/admin/statistics/dashboard'
    default:
      return '/guest/home'
  }
}

export default router
