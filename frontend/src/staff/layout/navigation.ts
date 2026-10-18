export interface NavItem { label: string; to: string; activeMatch?: string[]; pageTitle: string }

/** 导航顺序、可见角色与路由标题在端内统一维护。 */
export const navItems: NavItem[] = [
  {
    "label": "工作台",
    "to": "/staff/dashboard",
    "pageTitle": "工作台首页"
  },
  {
    "label": "工单列表",
    "to": "/staff/work-orders",
    "pageTitle": "工单列表"
  },
  {
    "label": "我的带看",
    "to": "/staff/viewings",
    "pageTitle": "我的带看"
  },
  {
    "label": "会话",
    "to": "/staff/conversations",
    "activeMatch": [
      "/staff/conversations"
    ],
    "pageTitle": "会话"
  }
]

export function staffPageTitle(path: string): string {
 const item = navItems.find(item => item.to === path)
 if (!item) throw new Error('未配置的导航路径: ' + path)
 return item.pageTitle
}
