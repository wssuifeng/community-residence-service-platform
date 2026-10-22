import { residentPageTitle } from '@/resident/layout/navigation'
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
    component: () => import('@/resident/layout/views/ResidentLayout.vue'),
    meta: { roles: ['RESIDENT'] },
    children: [
      { path: '', redirect: '/resident/home' },
      { path: 'home', name: 'ResidentHome', component: () => import('@/resident/workbench/views/HomeView.vue'), meta: { title: residentPageTitle("/resident/home") } },
      { path: 'profile', name: 'ResidentProfile', component: () => import('@/resident/resident/views/ProfileView.vue'), meta: { title: '个人中心' } },
      /* 租住与房源 Hub：租住契约管理 + 房源信息查看与申请租住双 Tab 容器 */
      {
        path: 'housing-lease',
        name: 'ResidentHousingLease',
        component: () => import('@/resident/lease/views/HousingLeaseHubView.vue'),
        meta: { title: residentPageTitle("/resident/housing-lease") }
      },
      { path: 'work-orders', name: 'ResidentWorkOrders', component: () => import('@/resident/workorder/views/WorkOrderListView.vue'), meta: { title: residentPageTitle("/resident/work-orders") } },
      { path: 'work-orders/create', name: 'ResidentWorkOrderCreate', component: () => import('@/resident/workorder/views/WorkOrderCreateView.vue'), meta: { title: '提交工单' } },
      { path: 'work-orders/:id', name: 'ResidentWorkOrderDetail', component: () => import('@/resident/workorder/views/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'feedbacks', name: 'ResidentFeedbacks', component: () => import('@/resident/feedback/views/FeedbackListView.vue'), meta: { title: residentPageTitle("/resident/feedbacks") } },
      { path: 'feedbacks/create', name: 'ResidentFeedbackCreate', component: () => import('@/resident/feedback/views/FeedbackCreateView.vue'), meta: { title: '提交反馈' } },
      { path: 'feedbacks/:id', name: 'ResidentFeedbackDetail', component: () => import('@/resident/feedback/views/FeedbackDetailView.vue'), meta: { title: '反馈详情' } },
      { path: 'reservations', name: 'ResidentReservations', component: () => import('@/resident/reservation/views/ReservationListView.vue'), meta: { title: residentPageTitle("/resident/reservations") } },
      { path: 'resources', name: 'ResidentResources', component: () => import('@/resident/reservation/views/ResourceListView.vue'), meta: { title: residentPageTitle("/resident/resources") } },
      { path: 'resources/:id', name: 'ResidentResourceDetail', component: () => import('@/resident/reservation/views/ResourceDetailView.vue'), meta: { title: '资源详情' } },
      { path: 'reservations/create', name: 'ResidentReservationCreate', component: () => import('@/resident/reservation/views/ReservationCreateView.vue'), meta: { title: '创建预约' } },
      { path: 'notices', name: 'ResidentNotices', component: () => import('@/resident/notice/views/NoticeListView.vue'), meta: { title: residentPageTitle("/resident/notices") } },
      { path: 'notices/:id', name: 'ResidentNoticeDetail', component: () => import('@/resident/notice/views/NoticeDetailView.vue'), meta: { title: '公告详情' } },
      { path: 'notifications', name: 'ResidentNotifications', component: () => import('@/resident/messaging/views/NotificationListView.vue'), meta: { title: residentPageTitle("/resident/notifications") } },
      /* R63 多方会话：看房群聊/租约群聊/居民-小区管理员直通的独立会话页 */
      { path: 'conversations', name: 'ResidentConversations', component: () => import('@/resident/conversation/views/ConversationDetailView.vue'), meta: { title: residentPageTitle("/resident/conversations") } },
      { path: 'conversations/:id', name: 'ResidentConversationDetail', component: () => import('@/resident/conversation/views/ConversationDetailView.vue'), meta: { title: '会话' } },
      /* 房源子路由保持原路径：详情/预约看房/申请租住行为不变，仅列表页并入 Hub */
      { path: 'housings/:id', name: 'ResidentHousingDetail', component: () => import('@/resident/housing/views/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'housings/:id/reserve', name: 'ResidentHousingReserve', component: () => import('@/resident/housing/views/HousingReservationView.vue'), meta: { title: '预约看房' } },
      { path: 'housings/:id/apply', name: 'ResidentHousingApply', component: () => import('@/resident/resident/views/ResidenceApplyView.vue'), meta: { title: '申请租住' } },
      { path: 'viewing-appointments/:id', name: 'ResidentViewingAppointmentDetail', component: () => import('@/resident/housing/views/ViewingAppointmentDetailView.vue'), meta: { title: '看房预约详情' } },
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
