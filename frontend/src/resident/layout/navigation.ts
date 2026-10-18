export interface NavItem { label: string; to: string; activeMatch?: string[]; pageTitle: string }

/** 导航顺序、可见角色与路由标题在端内统一维护。 */
export const navItems: NavItem[] = [
  {
    "label": "首页",
    "to": "/resident/home",
    "pageTitle": "首页"
  },
  {
    "label": "公共资源",
    "to": "/resident/resources",
    "pageTitle": "公共资源"
  },
  {
    "label": "我的预约",
    "to": "/resident/reservations",
    "pageTitle": "我的预约"
  },
  {
    "label": "租住与房源",
    "to": "/resident/housing-lease",
    "activeMatch": [
      "/resident/housing-lease",
      "/resident/leases",
      "/resident/housings",
      "/resident/viewing-appointments"
    ],
    "pageTitle": "租住与房源"
  },
  {
    "label": "我的工单",
    "to": "/resident/work-orders",
    "pageTitle": "我的工单"
  },
  {
    "label": "我的反馈",
    "to": "/resident/feedbacks",
    "pageTitle": "我的反馈"
  },
  {
    "label": "公告",
    "to": "/resident/notices",
    "pageTitle": "公告通知"
  },
  {
    "label": "会话",
    "to": "/resident/conversations",
    "activeMatch": [
      "/resident/conversations"
    ],
    "pageTitle": "会话"
  },
  {
    "label": "消息中心",
    "to": "/resident/notifications",
    "pageTitle": "消息中心"
  }
]

export function residentPageTitle(path: string): string {
 const item = navItems.find(item => item.to === path)
 if (!item) throw new Error('未配置的导航路径: ' + path)
 return item.pageTitle
}
