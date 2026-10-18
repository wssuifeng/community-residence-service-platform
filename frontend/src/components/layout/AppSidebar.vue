<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/common/auth/store/user'
import { useAdminTodoStore } from '@/store/adminTodo'

/**
 * 管理端左侧边栏（frontend-beautify 重构，样式对齐 design-mockups/admin/01、02）：
 * 深蓝底单组 + 线性图标；「系统」为超管独占，按角色过滤（UI设计.md §10.1）。
 *
 * 批次 g 导航合并：原「租住」「房源」「退租申请」「协议模板」四个一级项收敛为单一
 * 「租住与房源」项（/admin/housing-lease 五 Tab Hub，入住申请审核也迁入其中），
 * 同一模块不再拆到多个页面；旧一级路径仍由路由重定向落到 Hub，靠 activeMatch 点亮本项。
 */
const route = useRoute()
const userStore = useUserStore()
const todos=useAdminTodoStore()
let timer:ReturnType<typeof setInterval>|undefined
onMounted(()=>{void todos.refresh();timer=setInterval(()=>{if(document.visibilityState==='visible')void todos.refresh()},30000)})
onUnmounted(()=>{if(timer)clearInterval(timer)})
watch(()=>route.fullPath,()=>{void todos.refresh()})
watch(()=>userStore.user?.id,()=>{void todos.refresh()})
function pending(path:string):number{
 const key:Record<string,string>={'/admin/work-orders':'workOrders','/admin/feedbacks':'feedbacks','/admin/reservations':'reservations'}
 if(path==='/admin/housing-lease')return ['applications','moveout','housings','leases'].reduce((n,k)=>n+(todos.counts[k]??0),0)
 return todos.counts[key[path]??'']??0
}

interface MenuItem {
  path: string
  title: string
  icon: string
  roles?: string[]
  /** 额外点亮前缀：菜单项合并后，未挂在 Hub 路径下的旧路径/下钻详情仍归入本项 */
  activeMatch?: string[]
}

const menuItems: MenuItem[] = [
  { path: '/admin/dashboard', title: '看板', icon: 'dashboard' },
  { path: '/admin/community', title: '社区管理', icon: 'community' },
  { path: '/admin/work-orders', title: '工单调度', icon: 'workorder' },
  // V19 物业管理：排班与人员能力（长期对接人员是日常工单的调度基础）
  { path: '/admin/staff-schedules', title: '人员排班', icon: 'schedule' },
  { path: '/admin/staff-capabilities', title: '服务人员', icon: 'staff' },
  { path: '/admin/residents', title: '居民', icon: 'residents' },
  // 批次 g：租住/房源/退租申请/协议模板/入住申请审核的唯一入口
  {
    path: '/admin/housing-lease',
    title: '租住与房源',
    icon: 'lease',
    activeMatch: [
      '/admin/leases',
      '/admin/housings',
      '/admin/move-out-applications',
      '/admin/agreement-templates',
      '/admin/residence-applications',
      '/admin/viewing-appointments'
    ]
  },
  { path: '/admin/notices', title: '公告', icon: 'notice' },
  { path: '/admin/feedbacks', title: '反馈', icon: 'feedback' },
  { path: '/admin/reservations', title: '预约', icon: 'reservation' },
  { path: '/admin/evaluations', title: '评价', icon: 'evaluation' },
  // 消息中心：ADMIN/SUPER_ADMIN 均可见（复用隐藏路由 /admin/notifications，验收第三轮 C2）
  { path: '/admin/notifications', title: '消息中心', icon: 'notification' },
  // 会话：原业务沟通来源聚合；与消息中心的系统通知分开。
  { path: '/admin/conversations', title: '会话', icon: 'chat' },
  { path: '/admin/system', title: '系统', icon: 'system', roles: ['SUPER_ADMIN'] }
]

/** 角色过滤后的菜单（超管独占项对 ADMIN 隐藏，UI设计.md §10.1） */
const visibleItems = computed(() =>
  menuItems.filter((item) => !item.roles || item.roles.includes(userStore.role))
)

function isActive(path: string): boolean {
  // 前缀匹配，覆盖详情下钻（/admin/work-orders/:id、/admin/residents/:id、/admin/leases/:id）
  if (route.path === path || route.path.startsWith(`${path}/`)) return true
  // 社区详情挂在 /admin/communities/:id（复数段），与菜单路径 /admin/community 不同段，单独归入点亮
  if (path === '/admin/community') return route.path.startsWith('/admin/communities/')
  // 合并项：旧一级路径与未挂在 Hub 路径下的下钻详情（租约/房源/看房预约/协议模板）一并点亮
  const item = menuItems.find((menu) => menu.path === path)
  if (item?.activeMatch?.some((prefix) => route.path === prefix || route.path.startsWith(`${prefix}/`))) {
    return true
  }
  return false
}
</script>

<template>
  <aside class="app-sidebar">
    <nav class="app-sidebar-nav" aria-label="管理端菜单">
      <router-link
        v-for="item in visibleItems"
        :key="item.path"
        :to="item.path"
        class="app-sidebar-item"
        :class="{ active: isActive(item.path) }"
      >
        <span class="app-sidebar-item-icon" aria-hidden="true">
          <!-- 线性图标：单色 currentColor，视觉粗细一致（stroke-width 1.7） -->
          <svg v-if="item.icon === 'dashboard'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3.5" y="3.5" width="7.2" height="7.2" rx="1.6" />
            <rect x="13.3" y="3.5" width="7.2" height="7.2" rx="1.6" />
            <rect x="3.5" y="13.3" width="7.2" height="7.2" rx="1.6" />
            <rect x="13.3" y="13.3" width="7.2" height="7.2" rx="1.6" />
          </svg>
          <svg v-else-if="item.icon === 'community'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <rect x="8.75" y="3" width="6.5" height="5" rx="1.2" />
            <rect x="2.5" y="16" width="6.5" height="5" rx="1.2" />
            <rect x="15" y="16" width="6.5" height="5" rx="1.2" />
            <path d="M12 8v3.5M5.75 16v-2a2 2 0 0 1 2-2h8.5a2 2 0 0 1 2 2v2" />
          </svg>
          <svg v-else-if="item.icon === 'workorder'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <rect x="5.5" y="4.5" width="13" height="16.5" rx="2" />
            <rect x="9" y="2.5" width="6" height="3.5" rx="1.2" />
            <path d="M9 11h6M9 15h4" />
          </svg>
          <svg v-else-if="item.icon === 'schedule'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3.5" y="5" width="17" height="15.5" rx="2" />
            <path d="M3.5 10h17M8 3.2v3.6M16 3.2v3.6M8.6 14h2.2M13.4 14h2.2M8.6 17.2h2.2" />
          </svg>
          <svg v-else-if="item.icon === 'staff'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="9.4" cy="8.2" r="3.3" />
            <path d="M3.4 19.6a6 6 0 0 1 12 0" />
            <path d="M16.4 5.6a3.2 3.2 0 0 1 0 6.2M18.2 19.6a5.6 5.6 0 0 0-1.8-4.1" />
          </svg>
          <svg v-else-if="item.icon === 'residents'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="8" r="3.6" />
            <path d="M5.4 20a6.6 6.6 0 0 1 13.2 0" />
          </svg>
          <svg v-else-if="item.icon === 'lease'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 10.4 12 4l8 6.4V19a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19Z" />
            <path d="M9.6 20.4v-5.6h4.8v5.6" />
          </svg>
          <svg v-else-if="item.icon === 'notice'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 10.2v3.6a1 1 0 0 0 1 1h2.2L14 19V5L7.2 9.2H5a1 1 0 0 0-1 1Z" />
            <path d="M17.4 9.4a4.2 4.2 0 0 1 0 5.2M20 7.2a7.4 7.4 0 0 1 0 9.6" />
          </svg>
          <svg v-else-if="item.icon === 'feedback'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20 13.6a1.6 1.6 0 0 1-1.6 1.6H8.4L4 19.2V5.6A1.6 1.6 0 0 1 5.6 4h12.8A1.6 1.6 0 0 1 20 5.6Z" />
            <path d="M8.4 10.6h.01M12 10.6h.01M15.6 10.6h.01" />
          </svg>
          <svg v-else-if="item.icon === 'reservation'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="5.5" width="16" height="15" rx="2" />
            <path d="M4 10.2h16M8.5 3.2v4M15.5 3.2v4" />
          </svg>
          <svg v-else-if="item.icon === 'evaluation'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="m12 3.8 2.5 5 5.6.8-4 4 .9 5.6-5-2.7-5 2.7.9-5.6-4-4 5.6-.8Z" />
          </svg>
          <svg v-else-if="item.icon === 'notification'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M17.8 9.4a5.8 5.8 0 0 0-11.6 0c0 6.4-2.4 8-2.4 8h16.4s-2.4-1.6-2.4-8Z" />
            <path d="M10.4 20.4a1.8 1.8 0 0 0 3.2 0" />
          </svg>
          <svg v-else-if="item.icon === 'chat'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 11.5a8.4 7.5 0 0 1-8.4 7.5c-1.1 0-2.2-.2-3.1-.5L4 20l1.6-4.1A7.3 7.3 0 0 1 4.2 11.5 8.4 7.5 0 0 1 12.6 4 8.4 7.5 0 0 1 21 11.5Z" />
          </svg>
          <svg v-else-if="item.icon === 'system'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3.2" />
            <path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.4 5.4l1.9 1.9M16.7 16.7l1.9 1.9M18.6 5.4l-1.9 1.9M7.3 16.7l-1.9 1.9" />
          </svg>
        </span>
        <span class="app-sidebar-item-label">{{ item.title }}</span><span v-if="pending(item.path)>0" class="todo-dot" :title="`${pending(item.path)}项待处理`" :aria-label="`${pending(item.path)}项待处理`" />
      </router-link>
    </nav>
  </aside>
</template>

<style scoped>
.todo-dot{width:8px;height:8px;flex-shrink:0;border-radius:50%;background:#ff5964;margin-left:auto}
.app-sidebar {
  width: var(--admin-sidebar-width);
  flex-shrink: 0;
  background: var(--admin-sidebar-bg);
  /* 固定于视口：长页面滚动时侧栏常驻可点，不再随内容滚走 */
  position: sticky;
  top: 60px;
  height: calc(100vh - 60px);
  overflow-y: auto;
  padding: var(--spacing-lg) var(--spacing-sm);
}

.app-sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.app-sidebar-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  color: var(--admin-sidebar-text);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
  transition: opacity 0.15s ease, background-color 0.15s ease, color 0.15s ease;
}

.app-sidebar-item:hover {
  opacity: 0.8;
}

.app-sidebar-item.active {
  background: var(--admin-sidebar-active);
  color: var(--admin-sidebar-text-active);
  font-weight: var(--font-weight-medium);
}

.app-sidebar-item-icon {
  display: inline-flex;
  flex-shrink: 0;
}

.app-sidebar-item-icon svg {
  display: block;
}
</style>
