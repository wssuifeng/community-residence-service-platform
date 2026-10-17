import type { RouteLocation, RouteRecordRaw } from 'vue-router'

/**
 * 管理端路由（UI设计.md §3.4，前缀 /admin）：
 * 侧栏项 + 隐藏下钻详情；消息中心与会话为独立入口
 * + 旧路径兼容 redirect（需承接旧 query 的用函数写法透传，其余保持对象/字符串写法）。
 *
 * 批次 g 导航合并：租住/房源/退租申请/协议模板四个一级项与「居民」内的入住申请审核
 * 收敛为「租住与房源」Hub（/admin/housing-lease，?tab= 驱动），旧一级路径全部改为
 * 函数式 redirect 落到 Hub 对应 Tab（子视图命名空间 view/stage 一并翻译），
 * 详情页 /admin/leases/:id 与 /admin/housings/:id 原样直达（静态段优先于动态段，不被吞并）。
 */

/** Hub 的 Tab 取值（与 views/admin/lease/HousingLeaseHubView.vue 保持一致） */
type HubTab = 'applications' | 'leases' | 'housings' | 'moveout' | 'agreements'

/** 旧「租住」页的页内分组（现由 LeaseListView 的 `stage` 承接），用于翻译旧浅链接 */
const LEASE_STAGES = ['active', 'toSign', 'expiring', 'expired', 'history']

/** 旧「房源」页的页内分区（现由 HousingManageView 的 `view` 承接） */
const HOUSING_VIEWS = ['houses', 'viewings', 'list']

/**
 * 旧一级路径 → Hub 的函数式 redirect 目标：旧 `tab` 由调用方翻译成子视图命名空间后剔除，
 * 其余 query（如 leaseId）原样透传，保证旧书签与站内旧链接都落在 Hub 的正确 Tab。
 */
function hubTarget(
  to: RouteLocation,
  tab: HubTab,
  extra?: Record<string, string>
): { name: string; query: Record<string, string> } {
  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries(to.query ?? {})) {
    if (key === 'tab') continue
    if (typeof value === 'string') query[key] = value
    else if (typeof value === 'number') query[key] = String(value)
  }
  return { name: 'AdminHousingLease', query: { ...query, tab, ...extra } }
}

/** 旧 query 的 `tab` 原值（非字符串视为缺省） */
function legacyTab(to: RouteLocation): string {
  const value = to.query?.tab
  return typeof value === 'string' ? value : ''
}

const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    meta: { roles: ['ADMIN', 'SUPER_ADMIN'] },
    children: [
      { path: '', redirect: '/admin/dashboard' },

      // ---- 主路由（侧栏项；/admin/system 不加 roles：操作日志对 ADMIN 开放，
      //      菜单项隐藏由 AppSidebar roles 过滤实现，页内用户/配置 Tab 由任务 12 渲染空态） ----
      { path: 'dashboard', name: 'AdminDashboard', component: () => import('@/views/admin/statistics/DashboardTabView.vue'), meta: { title: '运营看板' } },
      { path: 'community', name: 'AdminCommunity', component: () => import('@/views/admin/community/CommunityStructureView.vue'), meta: { title: '社区管理' } },
      { path: 'work-orders', name: 'AdminWorkOrders', component: () => import('@/views/admin/workorder/WorkOrderListView.vue'), meta: { title: '工单调度' } },
      { path: 'residents', name: 'AdminResidents', component: () => import('@/views/admin/resident/ResidentManageView.vue'), meta: { title: '居民管理' } },
      /* 批次 g：租住/房源/退租申请/协议模板/入住申请审核的统一 Hub（侧栏唯一入口） */
      { path: 'housing-lease', name: 'AdminHousingLease', component: () => import('@/views/admin/lease/HousingLeaseHubView.vue'), meta: { title: '租住与房源' } },
      { path: 'notices', name: 'AdminNotices', component: () => import('@/views/admin/notice/NoticeManageView.vue'), meta: { title: '公告管理' } },
      { path: 'feedbacks', name: 'AdminFeedbacks', component: () => import('@/views/admin/feedback/FeedbackManageView.vue'), meta: { title: '反馈管理' } },
      { path: 'reservations', name: 'AdminReservations', component: () => import('@/views/admin/reservation/ReservationManageView.vue'), meta: { title: '预约管理' } },
      { path: 'evaluations', name: 'AdminEvaluations', component: () => import('@/views/admin/evaluation/EvaluationManageView.vue'), meta: { title: '评价管理' } },
      { path: 'system', name: 'AdminSystem', component: () => import('@/views/admin/auth/SystemManageView.vue'), meta: { title: '系统管理' } },

      /* V19/C4 物业管理子页：排班与人员能力（侧栏项，挂在工单调度之下为独立页而非 Tab，
         因两者数据模型与操作节奏都独立于工单列表） */
      { path: 'staff-schedules', name: 'AdminStaffSchedules', component: () => import('@/views/admin/workorder/StaffScheduleView.vue'), meta: { title: '人员排班' } },
      { path: 'staff-capabilities', name: 'AdminStaffCapabilities', component: () => import('@/views/admin/workorder/StaffCapabilityView.vue'), meta: { title: '服务人员' } },

      // ---- 隐藏下钻详情 + 通知（无侧栏项） ----
      /* 租约详情与房源详情必须原样直达：静态段 leases/housings 的 redirect 只匹配无子段路径，
         动态段 :id 优先承接带 id 的 URL，故 /admin/leases/:id 不会被 Hub 重定向吞掉 */
      { path: 'communities/:id', name: 'AdminCommunityDetail', component: () => import('@/views/admin/community/CommunityDetailView.vue'), meta: { title: '社区详情' } },
      { path: 'work-orders/:id', name: 'AdminWorkOrderDetail', component: () => import('@/views/admin/workorder/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'leases/:id', name: 'AdminLeaseDetail', component: () => import('@/views/admin/lease/LeaseDetailView.vue'), meta: { title: '租约详情' } },
      { path: 'residents/:id', name: 'AdminResidentDetail', component: () => import('@/views/admin/resident/ResidentDetailView.vue'), meta: { title: '居民详情' } },
      { path: 'housings/:id', name: 'AdminHousingDetail', component: () => import('@/views/admin/housing/HousingDetailView.vue'), meta: { title: '房源详情' } },
      { path: 'notifications', name: 'AdminNotifications', component: () => import('@/views/admin/notification/NotificationListView.vue'), meta: { title: '消息中心' } },
      // 会话：原业务来源按权限聚合，与系统通知入口分开。
      { path: 'conversations', name: 'AdminConversations', component: () => import('@/views/admin/conversation/ConversationListView.vue'), meta: { title: '会话' } },
      { path: 'conversations/:id', name: 'AdminConversationDetail', component: () => import('@/views/admin/conversation/ConversationListView.vue'), meta: { title: '会话' } },

      // ---- 旧路径兼容 redirect（零断链：既有页面/登录跳转的旧路径全部落地到新容器） ----
      { path: 'statistics/dashboard', redirect: '/admin/dashboard' },
      { path: 'statistics/details', redirect: { name: 'AdminDashboard', query: { tab: 'details' } } },
      { path: 'communities', redirect: '/admin/community' },
      /* 结构四旧路径：函数写法承接旧 query（如 communityId），由结构树预选对应社区 */
      { path: 'buildings', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'tree' } }) },
      { path: 'units', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'tree' } }) },
      { path: 'houses', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'houses' } }) },
      { path: 'resources', redirect: (to) => ({ name: 'AdminCommunity', query: { ...to.query, tab: 'resources' } }) },
      /* 入住申请审核已从「居民」页迁至 Hub：旧链接与旧 `?tab=applications` 一并落到 Hub */
      { path: 'residence-applications', redirect: (to) => hubTarget(to, 'applications') },
      { path: 'residence-relations', redirect: { name: 'AdminResidents', query: { tab: 'relations' } } },
      /* 租住/房源/退租申请/协议模板四旧一级路径 → Hub 对应 Tab；旧页内分组翻译为子视图命名空间 */
      {
        path: 'leases',
        redirect: (to) => {
          const legacy = legacyTab(to)
          return hubTarget(to, 'leases', LEASE_STAGES.includes(legacy) ? { stage: legacy } : undefined)
        }
      },
      {
        path: 'housings',
        redirect: (to) => {
          const legacy = legacyTab(to)
          return hubTarget(to, 'housings', { view: HOUSING_VIEWS.includes(legacy) ? legacy : 'houses' })
        }
      },
      { path: 'move-out-applications', redirect: (to) => hubTarget(to, 'moveout') },
      { path: 'agreement-templates', redirect: (to) => hubTarget(to, 'agreements') },

      { path: 'configs', redirect: { name: 'AdminSystem', query: { tab: 'configs' } } },
      { path: 'service-categories', redirect: '/admin/work-orders' },
      { path: 'resource-reservations', redirect: '/admin/reservations' },
      { path: 'violations', redirect: { name: 'AdminReservations', query: { tab: 'violations' } } },
      /* 看房预约处置并入 Hub 的房源管理分区（?view=viewings） */
      { path: 'viewing-appointments', redirect: { name: 'AdminHousingLease', query: { tab: 'housings', view: 'viewings' } } },
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
