import type { RouteRecordRaw } from 'vue-router'
const authRoutes: RouteRecordRaw[] = [{
    path: '/auth',
    children: [
      { path: 'login', name: 'Login', component: () => import('@/common/auth/views/LoginView.vue'), meta: { title: '登录' } },
      { path: 'register', name: 'Register', component: () => import('@/common/auth/views/RegisterView.vue'), meta: { title: '注册' } }
    ]
  }]
export default authRoutes
