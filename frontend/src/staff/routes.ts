import { staffPageTitle } from '@/staff/layout/navigation'
import type { RouteRecordRaw } from 'vue-router'

/** 服务人员工作台路由（UI设计.md §3.3，前缀 /staff，权限 STAFF） */
const staffRoutes: RouteRecordRaw[] = [
  {
    path: '/staff',
    component: () => import('@/staff/layout/views/StaffLayout.vue'),
    meta: { roles: ['STAFF'] },
    children: [
      { path: '', redirect: '/staff/dashboard' },
      { path: 'dashboard', name: 'StaffDashboard', component: () => import('@/staff/workbench/views/DashboardView.vue'), meta: { title: staffPageTitle("/staff/dashboard") } },
      { path: 'work-orders', name: 'StaffWorkOrders', component: () => import('@/staff/workorder/views/WorkOrderListView.vue'), meta: { title: staffPageTitle("/staff/work-orders") } },
      { path: 'work-orders/:id', name: 'StaffWorkOrderDetail', component: () => import('@/staff/workorder/views/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'viewings', name: 'StaffViewings', component: () => import('@/staff/housing/views/ViewingsWorkbenchView.vue'), meta: { title: staffPageTitle("/staff/viewings") } },
      { path: 'conversations', name: 'StaffConversations', component: () => import('@/staff/conversation/views/ConversationListView.vue'), meta: { title: staffPageTitle("/staff/conversations") } },
      { path: 'notifications', name: 'StaffNotifications', component: () => import('@/staff/messaging/views/NotificationListView.vue'), meta: { title: '消息中心' } }
    ]
  }
]

export default staffRoutes
