import type { RouteRecordRaw } from 'vue-router'

/** 管理端路由（UI设计.md §3.4，前缀 /admin，按 C1~C12 模块组织） */
const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { roles: ['ADMIN', 'SUPER_ADMIN'] },
    children: [
      { path: '', redirect: '/admin/statistics/dashboard' },

      // C1 社区基础信息管理
      { path: 'communities', name: 'AdminCommunities', component: () => import('@/views/admin/community/CommunityListView.vue'), meta: { title: '社区列表' } },
      { path: 'communities/:id', name: 'AdminCommunityDetail', component: () => import('@/views/admin/community/CommunityDetailView.vue'), meta: { title: '社区详情' } },
      { path: 'buildings', name: 'AdminBuildings', component: () => import('@/views/admin/community/BuildingListView.vue'), meta: { title: '楼栋管理' } },
      { path: 'units', name: 'AdminUnits', component: () => import('@/views/admin/community/UnitListView.vue'), meta: { title: '单元管理' } },
      { path: 'houses', name: 'AdminHouses', component: () => import('@/views/admin/community/HouseListView.vue'), meta: { title: '房屋管理' } },
      { path: 'resources', name: 'AdminResources', component: () => import('@/views/admin/community/PublicResourceListView.vue'), meta: { title: '公共资源' } },

      // C2 居民管理
      { path: 'residents', name: 'AdminResidents', component: () => import('@/views/admin/resident/ResidentListView.vue'), meta: { title: '居民列表' } },
      { path: 'residents/:id', name: 'AdminResidentDetail', component: () => import('@/views/admin/resident/ResidentDetailView.vue'), meta: { title: '居民详情' } },
      { path: 'residence-applications', name: 'AdminResidenceApplications', component: () => import('@/views/admin/resident/ResidenceApplicationListView.vue'), meta: { title: '入住申请' } },
      { path: 'residence-relations', name: 'AdminResidenceRelations', component: () => import('@/views/admin/resident/ResidenceRelationListView.vue'), meta: { title: '居住关系' } },
      { path: 'configs', name: 'AdminConfigs', component: () => import('@/views/admin/resident/GlobalConfigView.vue'), meta: { title: '全局配置', roles: ['SUPER_ADMIN'] } },

      // C3 租住管理
      { path: 'leases', name: 'AdminLeases', component: () => import('@/views/admin/lease/LeaseListView.vue'), meta: { title: '租住记录' } },
      { path: 'leases/expiring', name: 'AdminExpiringLeases', component: () => import('@/views/admin/lease/ExpiringLeaseListView.vue'), meta: { title: '即将到期' } },

      // C4 工单管理
      { path: 'service-categories', name: 'AdminServiceCategories', component: () => import('@/views/admin/workorder/ServiceCategoryListView.vue'), meta: { title: '服务类别' } },
      { path: 'work-orders', name: 'AdminWorkOrders', component: () => import('@/views/admin/workorder/WorkOrderListView.vue'), meta: { title: '工单列表' } },
      { path: 'work-orders/:id', name: 'AdminWorkOrderDetail', component: () => import('@/views/admin/workorder/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },

      // C5 公告管理
      { path: 'notices', name: 'AdminNotices', component: () => import('@/views/admin/notice/NoticeListView.vue'), meta: { title: '公告列表' } },
      { path: 'notices/create', name: 'AdminNoticeCreate', component: () => import('@/views/admin/notice/NoticeCreateView.vue'), meta: { title: '创建公告' } },
      { path: 'notices/:id', name: 'AdminNoticeDetail', component: () => import('@/views/admin/notice/NoticeDetailView.vue'), meta: { title: '公告详情' } },

      // C6 反馈管理
      { path: 'feedbacks', name: 'AdminFeedbacks', component: () => import('@/views/admin/feedback/FeedbackListView.vue'), meta: { title: '反馈列表' } },
      { path: 'feedbacks/:id', name: 'AdminFeedbackDetail', component: () => import('@/views/admin/feedback/FeedbackDetailView.vue'), meta: { title: '反馈详情' } },

      // C7 资源预约
      { path: 'resource-reservations', name: 'AdminResourceReservations', component: () => import('@/views/admin/reservation/ResourceReservationListView.vue'), meta: { title: '预约列表' } },
      { path: 'violations', name: 'AdminViolations', component: () => import('@/views/admin/reservation/ViolationListView.vue'), meta: { title: '违约记录' } },

      // C8 服务评价
      { path: 'evaluations', name: 'AdminEvaluations', component: () => import('@/views/admin/evaluation/EvaluationListView.vue'), meta: { title: '评价列表' } },
      { path: 'evaluations/:id/followup', name: 'AdminEvaluationFollowup', component: () => import('@/views/admin/evaluation/EvaluationFollowupView.vue'), meta: { title: '跟进记录' } },

      // C9 运营统计
      { path: 'statistics/dashboard', name: 'AdminStatisticsDashboard', component: () => import('@/views/admin/statistics/DashboardView.vue'), meta: { title: '运营看板' } },
      { path: 'statistics/work-orders', name: 'AdminWorkOrderStatistics', component: () => import('@/views/admin/statistics/WorkOrderStatisticsView.vue'), meta: { title: '工单统计' } },
      { path: 'statistics/residents', name: 'AdminResidentStatistics', component: () => import('@/views/admin/statistics/ResidentStatisticsView.vue'), meta: { title: '居民统计' } },
      { path: 'statistics/resources', name: 'AdminResourceStatistics', component: () => import('@/views/admin/statistics/ResourceStatisticsView.vue'), meta: { title: '资源统计' } },
      { path: 'statistics/evaluations', name: 'AdminEvaluationStatistics', component: () => import('@/views/admin/statistics/EvaluationStatisticsView.vue'), meta: { title: '评价统计' } },

      // C10 权限管理
      { path: 'sys-users', name: 'AdminSysUsers', component: () => import('@/views/admin/auth/SysUserListView.vue'), meta: { title: '系统用户', roles: ['SUPER_ADMIN'] } },
      { path: 'sys-users/:id/communities', name: 'AdminSysUserCommunities', component: () => import('@/views/admin/auth/SysUserCommunityView.vue'), meta: { title: '社区绑定', roles: ['SUPER_ADMIN'] } },
      { path: 'operation-logs', name: 'AdminOperationLogs', component: () => import('@/views/admin/auth/OperationLogListView.vue'), meta: { title: '操作日志' } },

      // C11 消息中心
      { path: 'notifications', name: 'AdminNotifications', component: () => import('@/views/admin/notification/NotificationListView.vue'), meta: { title: '通知列表' } },

      // C12 房源管理
      { path: 'housings', name: 'AdminHousings', component: () => import('@/views/admin/housing/HousingListView.vue'), meta: { title: '房源列表' } },
      { path: 'housings/:id', name: 'AdminHousingDetail', component: () => import('@/views/admin/housing/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'viewing-appointments', name: 'AdminViewingAppointments', component: () => import('@/views/admin/housing/ViewingAppointmentListView.vue'), meta: { title: '看房预约' } }
    ]
  }
]

export default adminRoutes
