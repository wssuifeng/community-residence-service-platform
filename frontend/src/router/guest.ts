import type { RouteRecordRaw } from 'vue-router'

/** 游客端路由（UI设计.md §3.2，公开页面，无需登录） */
const guestRoutes: RouteRecordRaw[] = [
  {
    path: '/guest',
    component: () => import('@/common/public/views/GuestLayout.vue'),
    children: [
      { path: '', redirect: '/guest/home' },
      { path: 'home', name: 'GuestHome', component: () => import('@/common/public/views/HomeView.vue'), meta: { title: '首页' } },
      { path: 'housings', name: 'GuestHousings', component: () => import('@/common/public/views/HousingListView.vue'), meta: { title: '房源浏览' } },
      { path: 'housings/:id', name: 'GuestHousingDetail', component: () => import('@/common/public/views/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'notices', name: 'GuestNotices', component: () => import('@/common/public/views/NoticeListView.vue'), meta: { title: '公开公告' } },
      { path: 'notices/:id', name: 'GuestNoticeDetail', component: () => import('@/common/public/views/NoticeDetailView.vue'), meta: { title: '公告详情' } }
    ]
  },
  {
    path: '/auth',
    children: [
      { path: 'login', name: 'Login', component: () => import('@/common/auth/views/LoginView.vue'), meta: { title: '登录' } },
      { path: 'register', name: 'Register', component: () => import('@/common/auth/views/RegisterView.vue'), meta: { title: '注册' } }
    ]
  }
]

export default guestRoutes
