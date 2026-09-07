import type { RouteRecordRaw } from 'vue-router'

/** 服务人员工作台路由（UI设计.md §3.3，前缀 /staff，权限 STAFF） */
const staffRoutes: RouteRecordRaw[] = [
  {
    path: '/staff',
    component: () => import('@/views/staff/StaffLayout.vue'),
    meta: { roles: ['STAFF'] },
    children: [
      { path: '', redirect: '/staff/dashboard' },
      { path: 'dashboard', name: 'StaffDashboard', component: () => import('@/views/staff/DashboardView.vue'), meta: { title: '工作台首页' } },
      { path: 'work-orders', name: 'StaffWorkOrders', component: () => import('@/views/staff/WorkOrderListView.vue'), meta: { title: '工单列表' } },
      { path: 'work-orders/:id', name: 'StaffWorkOrderDetail', component: () => import('@/views/staff/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'notifications', name: 'StaffNotifications', component: () => import('@/views/staff/NotificationListView.vue'), meta: { title: '消息中心' } }
    ]
  }
]

export default staffRoutes
