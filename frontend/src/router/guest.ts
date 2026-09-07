import type { RouteRecordRaw } from 'vue-router'

/** 游客端路由（UI设计.md §3.2，公开页面，无需登录） */
const guestRoutes: RouteRecordRaw[] = [
  {
    path: '/guest',
    component: () => import('@/views/guest/GuestLayout.vue'),
    children: [
      { path: '', redirect: '/guest/home' },
      { path: 'home', name: 'GuestHome', component: () => import('@/views/guest/HomeView.vue'), meta: { title: '首页' } },
      { path: 'housings', name: 'GuestHousings', component: () => import('@/views/guest/HousingListView.vue'), meta: { title: '房源浏览' } },
      { path: 'housings/:id', name: 'GuestHousingDetail', component: () => import('@/views/guest/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'notices', name: 'GuestNotices', component: () => import('@/views/guest/NoticeListView.vue'), meta: { title: '公开公告' } },
      { path: 'notices/:id', name: 'GuestNoticeDetail', component: () => import('@/views/guest/NoticeDetailView.vue'), meta: { title: '公告详情' } }
    ]
  },
  {
    path: '/auth',
    children: [
      { path: 'login', name: 'Login', component: () => import('@/views/auth/LoginView.vue'), meta: { title: '登录' } },
      { path: 'register', name: 'Register', component: () => import('@/views/auth/RegisterView.vue'), meta: { title: '注册' } }
    ]
  }
]

export default guestRoutes
