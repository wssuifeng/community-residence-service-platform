import type { RouteRecordRaw } from 'vue-router'

/** 居民端路由（UI设计.md §3.1，前缀 /resident，权限 RESIDENT） */
const residentRoutes: RouteRecordRaw[] = [
  {
    path: '/resident',
    component: () => import('@/views/resident/ResidentLayout.vue'),
    meta: { roles: ['RESIDENT'] },
    children: [
      { path: '', redirect: '/resident/home' },
      { path: 'home', name: 'ResidentHome', component: () => import('@/views/resident/HomeView.vue'), meta: { title: '首页' } },
      { path: 'profile', name: 'ResidentProfile', component: () => import('@/views/resident/ProfileView.vue'), meta: { title: '个人中心' } },
      { path: 'work-orders', name: 'ResidentWorkOrders', component: () => import('@/views/resident/WorkOrderListView.vue'), meta: { title: '我的工单' } },
      { path: 'work-orders/create', name: 'ResidentWorkOrderCreate', component: () => import('@/views/resident/WorkOrderCreateView.vue'), meta: { title: '提交工单' } },
      { path: 'work-orders/:id', name: 'ResidentWorkOrderDetail', component: () => import('@/views/resident/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'feedbacks', name: 'ResidentFeedbacks', component: () => import('@/views/resident/FeedbackListView.vue'), meta: { title: '我的反馈' } },
      { path: 'feedbacks/create', name: 'ResidentFeedbackCreate', component: () => import('@/views/resident/FeedbackCreateView.vue'), meta: { title: '提交反馈' } },
      { path: 'feedbacks/:id', name: 'ResidentFeedbackDetail', component: () => import('@/views/resident/FeedbackDetailView.vue'), meta: { title: '反馈详情' } },
      { path: 'reservations', name: 'ResidentReservations', component: () => import('@/views/resident/ReservationListView.vue'), meta: { title: '我的预约' } },
      { path: 'reservations/create', name: 'ResidentReservationCreate', component: () => import('@/views/resident/ReservationCreateView.vue'), meta: { title: '创建预约' } },
      { path: 'notices', name: 'ResidentNotices', component: () => import('@/views/resident/NoticeListView.vue'), meta: { title: '公告通知' } },
      { path: 'notices/:id', name: 'ResidentNoticeDetail', component: () => import('@/views/resident/NoticeDetailView.vue'), meta: { title: '公告详情' } },
      { path: 'notifications', name: 'ResidentNotifications', component: () => import('@/views/resident/NotificationListView.vue'), meta: { title: '消息中心' } },
      { path: 'housings', name: 'ResidentHousings', component: () => import('@/views/resident/HousingListView.vue'), meta: { title: '房源浏览' } },
      { path: 'housings/:id', name: 'ResidentHousingDetail', component: () => import('@/views/resident/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'viewing-appointments', name: 'ResidentViewingAppointments', component: () => import('@/views/resident/ViewingAppointmentListView.vue'), meta: { title: '看房预约' } }
    ]
  }
]

export default residentRoutes
