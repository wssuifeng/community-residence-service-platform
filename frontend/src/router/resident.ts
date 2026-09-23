import type { RouteRecordRaw } from 'vue-router'

/**
 * 居民端路由（UI设计.md §3.1 居民端导航，前缀 /resident，权限 RESIDENT）。
 *
 * 租住签约闭环（批次 f）导航合并：原「我的租约」与「房源」两项合并为
 * 「租住与房源」Hub 容器（/resident/housing-lease，?tab=lease|housing 深链）。
 * 旧路径保持可用——/resident/leases 与 /resident/housings 以函数式 redirect
 * 透传 query 落到 Hub 对应 tab，房源详情/预约/申请等子路由行为不变。
 */
const residentRoutes: RouteRecordRaw[] = [
  {
    path: '/resident',
    component: () => import('@/views/resident/ResidentLayout.vue'),
    meta: { roles: ['RESIDENT'] },
    children: [
      { path: '', redirect: '/resident/home' },
      { path: 'home', name: 'ResidentHome', component: () => import('@/views/resident/HomeView.vue'), meta: { title: '首页' } },
      { path: 'profile', name: 'ResidentProfile', component: () => import('@/views/resident/ProfileView.vue'), meta: { title: '个人中心' } },
      /* 租住与房源 Hub：租住契约管理 + 房源信息查看与申请租住双 Tab 容器 */
      {
        path: 'housing-lease',
        name: 'ResidentHousingLease',
        component: () => import('@/views/resident/HousingLeaseHubView.vue'),
        meta: { title: '租住与房源' }
      },
      { path: 'work-orders', name: 'ResidentWorkOrders', component: () => import('@/views/resident/WorkOrderListView.vue'), meta: { title: '我的工单' } },
      { path: 'work-orders/create', name: 'ResidentWorkOrderCreate', component: () => import('@/views/resident/WorkOrderCreateView.vue'), meta: { title: '提交工单' } },
      { path: 'work-orders/:id', name: 'ResidentWorkOrderDetail', component: () => import('@/views/resident/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'feedbacks', name: 'ResidentFeedbacks', component: () => import('@/views/resident/FeedbackListView.vue'), meta: { title: '我的反馈' } },
      { path: 'feedbacks/create', name: 'ResidentFeedbackCreate', component: () => import('@/views/resident/FeedbackCreateView.vue'), meta: { title: '提交反馈' } },
      { path: 'feedbacks/:id', name: 'ResidentFeedbackDetail', component: () => import('@/views/resident/FeedbackDetailView.vue'), meta: { title: '反馈详情' } },
      { path: 'reservations', name: 'ResidentReservations', component: () => import('@/views/resident/ReservationListView.vue'), meta: { title: '我的预约' } },
      { path: 'resources', name: 'ResidentResources', component: () => import('@/views/resident/ResourceListView.vue'), meta: { title: '公共资源' } },
      { path: 'resources/:id', name: 'ResidentResourceDetail', component: () => import('@/views/resident/ResourceDetailView.vue'), meta: { title: '资源详情' } },
      { path: 'reservations/create', name: 'ResidentReservationCreate', component: () => import('@/views/resident/ReservationCreateView.vue'), meta: { title: '创建预约' } },
      { path: 'notices', name: 'ResidentNotices', component: () => import('@/views/resident/NoticeListView.vue'), meta: { title: '公告通知' } },
      { path: 'notices/:id', name: 'ResidentNoticeDetail', component: () => import('@/views/resident/NoticeDetailView.vue'), meta: { title: '公告详情' } },
      { path: 'notifications', name: 'ResidentNotifications', component: () => import('@/views/resident/NotificationListView.vue'), meta: { title: '消息中心' } },
      /* R63 多方会话：看房群聊/租约群聊/居民-社区管理员直通的独立会话页 */
      { path: 'conversations/:id', name: 'ResidentConversationDetail', component: () => import('@/views/resident/ConversationDetailView.vue'), meta: { title: '沟通会话' } },
      /* 房源子路由保持原路径：详情/预约看房/申请租住行为不变，仅列表页并入 Hub */
      { path: 'housings/:id', name: 'ResidentHousingDetail', component: () => import('@/views/resident/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'housings/:id/reserve', name: 'ResidentHousingReserve', component: () => import('@/views/resident/HousingReservationView.vue'), meta: { title: '预约看房' } },
      { path: 'housings/:id/apply', name: 'ResidentHousingApply', component: () => import('@/views/resident/ResidenceApplyView.vue'), meta: { title: '申请租住' } },
      { path: 'viewing-appointments/:id', name: 'ResidentViewingAppointmentDetail', component: () => import('@/views/resident/ViewingAppointmentDetailView.vue'), meta: { title: '看房预约详情' } },
      /* 独立看房预约列表页退役（R59，v1.3）：并入「我的预约」看房 tab */
      { path: 'viewing-appointments', redirect: { path: '/resident/reservations', query: { tab: 'viewing' } } },

      /* ---- 旧路径兼容：导航合并前的两个菜单路径（外部链接/旧书签/通知落地仍在用） ----
         函数式 redirect 透传 query（含 leaseId 深链），保证旧链接落地到 Hub 对应 Tab；
         租约页旧「tab=payments」（合并前的支付记录 tab，现为租约明细）一并透传——
         Hub 视其为租约分区、LeaseListView 回显明细子 tab，不透传会被强改回 lease（OCR DEF-111） */
      {
        path: 'leases',
        redirect: (to) => ({
          path: '/resident/housing-lease',
          query: { ...to.query, tab: to.query.tab === 'payments' ? 'payments' : 'lease' }
        })
      },
      {
        path: 'housings',
        redirect: (to) => ({ path: '/resident/housing-lease', query: { ...to.query, tab: 'housing' } })
      }
    ]
  }
]

export default residentRoutes
