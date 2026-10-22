export interface MenuItem { path: string; title: string; icon: string; roles?: string[]; activeMatch?: string[]; pageTitle: string }

/** 导航顺序、可见角色与路由标题在端内统一维护。 */
export const menuItems: MenuItem[] = [
  {
    "path": "/admin/dashboard",
    "title": "看板",
    "icon": "dashboard",
    "pageTitle": "运营看板"
  },
  {
    "path": "/admin/community",
    "title": "小区管理",
    "icon": "community",
    "pageTitle": "小区管理"
  },
  {
    "path": "/admin/work-orders",
    "title": "工单调度",
    "icon": "workorder",
    "pageTitle": "工单调度"
  },
  {
    "path": "/admin/staff-schedules",
    "title": "人员排班",
    "icon": "schedule",
    "pageTitle": "人员排班"
  },
  {
    "path": "/admin/staff-capabilities",
    "title": "服务人员",
    "icon": "staff",
    "pageTitle": "服务人员"
  },
  {
    "path": "/admin/residents",
    "title": "居民",
    "icon": "residents",
    "pageTitle": "居民管理"
  },
  {
    "path": "/admin/housing-lease",
    "title": "租住与房源",
    "icon": "lease",
    "activeMatch": [
      "/admin/leases",
      "/admin/housings",
      "/admin/move-out-applications",
      "/admin/agreement-templates",
      "/admin/residence-applications",
      "/admin/viewing-appointments"
    ],
    "pageTitle": "租住与房源"
  },
  {
    "path": "/admin/notices",
    "title": "公告",
    "icon": "notice",
    "pageTitle": "公告管理"
  },
  {
    "path": "/admin/feedbacks",
    "title": "反馈",
    "icon": "feedback",
    "pageTitle": "反馈管理"
  },
  {
    "path": "/admin/reservations",
    "title": "预约",
    "icon": "reservation",
    "pageTitle": "预约管理"
  },
  {
    "path": "/admin/evaluations",
    "title": "评价",
    "icon": "evaluation",
    "pageTitle": "评价管理"
  },
  {
    "path": "/admin/notifications",
    "title": "消息中心",
    "icon": "notification",
    "pageTitle": "消息中心"
  },
  {
    "path": "/admin/conversations",
    "title": "会话",
    "icon": "chat",
    "pageTitle": "会话"
  },
  {
    "path": "/admin/system",
    "title": "系统",
    "icon": "system",
    "roles": [
      "SUPER_ADMIN"
    ],
    "pageTitle": "系统管理"
  }
]

export function adminPageTitle(path: string): string {
 const item = menuItems.find(item => item.path === path)
 if (!item) throw new Error('未配置的导航路径: ' + path)
 return item.pageTitle
}
