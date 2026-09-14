import type { RouteRecordRaw } from 'vue-router'

/**
 * 管理端路由（UI设计.md §3.4，前缀 /admin；frontend-beautify 重组）：
 * 11 主路由（侧栏项，页内 Tab 容器壳由任务 2~12 逐个填充实现）
 * + 4 隐藏下钻详情 + 通知列表（无侧栏项）
 * + 旧路径兼容 redirect（需承接旧 query 的用函数写法透传，其余保持对象/字符串写法）
 */
const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { roles: ['ADMIN', 'SUPER_ADMIN'] },
    children: [
      { path: '', redirect: '/admin/dashboard' },

      // ---- 11 主路由（侧栏项；/admin/system 不加 roles：操作日志对 ADMIN 开放，
      //      菜单项隐藏由 AppSidebar roles 过滤实现，页内用户/配置 Tab 由任务 12 渲染空态） ----
      { path: 'dashboard', name: 'AdminDashboard', component: () => import('@/views/admin/statistics/DashboardTabView.vue'), meta: { title: '运营看板' } },
      { path: 'community', name: 'AdminCommunity', component: () => import('@/views/admin/community/CommunityStructureView.vue'), meta: { title: '社区结构' } },
      { path: 'work-orders', name: 'AdminWorkOrders', component: () => import('@/views/admin/workorder/WorkOrderListView.vue'), meta: { title: '工单管理' } },
      { path: 'residents', name: 'AdminResidents', component: () => import('@/views/admin/resident/ResidentManageView.vue'), meta: { title: '居民管理' } },
      { path: 'leases', name: 'AdminLeases', component: () => import('@/views/admin/lease/LeaseListView.vue'), meta: { title: '租住管理' } },
      { path: 'notices', name: 'AdminNotices', component: () => import('@/views/admin/notice/NoticeManageView.vue'), meta: { title: '公告管理' } },
      { path: 'feedbacks', name: 'AdminFeedbacks', component: () => import('@/views/admin/feedback/FeedbackManageView.vue'), meta: { title: '反馈管理' } },
      { path: 'reservations', name: 'AdminReservations', component: () => import('@/views/admin/reservation/ReservationManageView.vue'), meta: { title: '预约管理' } },
      { path: 'housings', name: 'AdminHousings', component: () => import('@/views/admin/housing/HousingManageView.vue'), meta: { title: '房源管理' } },
      { path: 'evaluations', name: 'AdminEvaluations', component: () => import('@/views/admin/evaluation/EvaluationManageView.vue'), meta: { title: '评价管理' } },
      { path: 'system', name: 'AdminSystem', component: () => import('@/views/admin/auth/SystemManageView.vue'), meta: { title: '系统管理' } },

      // ---- 4 隐藏下钻详情 + 通知（无侧栏项；与下方动态段 redirect 路径段不同，无吞并） ----
      { path: 'communities/:id', name: 'AdminCommunityDetail', component: () => import('@/views/admin/community/CommunityDetailView.vue'), meta: { title: '社区详情' } },
      { path: 'work-orders/:id', name: 'AdminWorkOrderDetail', component: () => import('@/views/admin/workorder/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'residents/:id', name: 'AdminResidentDetail', component: () => import('@/views/admin/resident/ResidentDetailView.vue'), meta: { title: '居民详情' } },
      { path: 'housings/:id', name: 'AdminHousingDetail', component: () => import('@/views/admin/housing/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'notifications', name: 'AdminNotifications', component: () => import('@/views/admin/notification/NotificationListView.vue'), meta: { title: '通知列表' } },

      // ---- 旧路径兼容 redirect（零断链：既有页面/登录跳转的旧路径全部落地到新容器） ----
      { path: 'statistics/dashboard', redirect: '/admin/dashboard' },
      { path: 'statistics/details', redirect: { name: 'AdminDashboard', query: { tab: 'details' } } },
      { path: 'communities', redirect: '/admin/community' },
      /* 结构四旧路径：函数写法承接旧 query（如 communityId），由结构树预选对应社区 */
      { path: 'buildings', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'tree' } }) },
      { path: 'units', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'tree' } }) },
      { path: 'houses', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'houses' } }) },
      { path: 'resources', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'resources' } }) },
      { path: 'residence-applications', redirect: { name: 'AdminResidents', query: { tab: 'applications' } } },
      { path: 'residence-relations', redirect: { name: 'AdminResidents', query: { tab: 'relations' } } },
      { path: 'configs', redirect: { name: 'AdminSystem', query: { tab: 'configs' } } },
      { path: 'service-categories', redirect: '/admin/work-orders' },
      { path: 'resource-reservations', redirect: '/admin/reservations' },
      { path: 'violations', redirect: { name: 'AdminReservations', query: { tab: 'violations' } } },
      { path: 'viewing-appointments', redirect: { name: 'AdminHousings', query: { tab: 'viewings' } } },
      { path: 'notices/create', redirect: { name: 'AdminNotices', query: { action: 'create' } } },
      { path: 'notices/:id', redirect: '/admin/notices' },
      { path: 'feedbacks/:id', redirect: '/admin/feedbacks' },
      { path: 'evaluations/:id/followup', redirect: '/admin/evaluations' },
      { path: 'sys-users', redirect: { name: 'AdminSystem', query: { tab: 'users' } } },
      { path: 'sys-users/:id/communities', redirect: { name: 'AdminSystem', query: { tab: 'users' } } },
      { path: 'operation-logs', redirect: { name: 'AdminSystem', query: { tab: 'logs' } } }
    ]
  }
]

export default adminRoutes
