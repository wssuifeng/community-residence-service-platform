<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'

/**
 * 管理端左侧边栏（UI设计.md §2.3）：按 C1~C12 模块组织菜单；
 * 超管独占菜单按角色过滤（UI设计.md §10.1）
 */
const route = useRoute()
const userStore = useUserStore()

interface MenuItem {
  path: string
  title: string
  roles?: string[]
}

interface MenuGroup {
  title: string
  items: MenuItem[]
}

const menuGroups: MenuGroup[] = [
  {
    title: '运营统计',
    items: [
      { path: '/admin/statistics/dashboard', title: '运营看板' },
      { path: '/admin/statistics/work-orders', title: '工单统计' },
      { path: '/admin/statistics/residents', title: '居民统计' },
      { path: '/admin/statistics/resources', title: '资源统计' },
      { path: '/admin/statistics/evaluations', title: '评价统计' }
    ]
  },
  {
    title: '社区管理',
    items: [
      { path: '/admin/communities', title: '社区列表' },
      { path: '/admin/buildings', title: '楼栋管理' },
      { path: '/admin/units', title: '单元管理' },
      { path: '/admin/houses', title: '房屋管理' },
      { path: '/admin/resources', title: '公共资源' }
    ]
  },
  {
    title: '居民管理',
    items: [
      { path: '/admin/residents', title: '居民列表' },
      { path: '/admin/residence-applications', title: '入住申请' },
      { path: '/admin/residence-relations', title: '居住关系' },
      { path: '/admin/configs', title: '全局配置', roles: ['SUPER_ADMIN'] }
    ]
  },
  {
    title: '租住管理',
    items: [
      { path: '/admin/leases', title: '租住记录' },
      { path: '/admin/leases/expiring', title: '即将到期' }
    ]
  },
  {
    title: '服务管理',
    items: [
      { path: '/admin/service-categories', title: '服务类别' },
      { path: '/admin/work-orders', title: '工单列表' },
      { path: '/admin/evaluations', title: '评价列表' }
    ]
  },
  {
    title: '公告与反馈',
    items: [
      { path: '/admin/notices', title: '公告列表' },
      { path: '/admin/feedbacks', title: '反馈列表' }
    ]
  },
  {
    title: '预约管理',
    items: [
      { path: '/admin/resource-reservations', title: '资源预约' },
      { path: '/admin/violations', title: '违约记录' },
      { path: '/admin/viewing-appointments', title: '看房预约' }
    ]
  },
  {
    title: '房源管理',
    items: [
      { path: '/admin/housings', title: '房源列表' }
    ]
  },
  {
    title: '系统管理',
    items: [
      { path: '/admin/sys-users', title: '系统用户', roles: ['SUPER_ADMIN'] },
      { path: '/admin/operation-logs', title: '操作日志' },
      { path: '/admin/notifications', title: '通知列表' }
    ]
  }
]

/** 角色过滤后的菜单（超管独占项对 ADMIN 隐藏，UI设计.md §10.1） */
const visibleGroups = computed(() =>
  menuGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.roles || item.roles.includes(userStore.role))
    }))
    .filter((group) => group.items.length > 0)
)

function isActive(path: string): boolean {
  // 精确匹配优先，避免「租住记录」与「即将到期」互相点亮
  return route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<template>
  <aside class="app-sidebar">
    <nav aria-label="管理端菜单">
      <div v-for="group in visibleGroups" :key="group.title" class="app-sidebar-group">
        <div class="app-sidebar-group-title">{{ group.title }}</div>
        <router-link
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="app-sidebar-item"
          :class="{ active: isActive(item.path) }"
        >
          {{ item.title }}
        </router-link>
      </div>
    </nav>
  </aside>
</template>

<style scoped>
.app-sidebar {
  width: 220px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid var(--color-border);
  overflow-y: auto;
  padding: var(--spacing-md) 0;
}

.app-sidebar-group {
  margin-bottom: var(--spacing-md);
}

.app-sidebar-group-title {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  padding: var(--spacing-xs) var(--spacing-lg);
  margin-bottom: var(--spacing-xs);
}

.app-sidebar-item {
  display: block;
  color: var(--color-text-secondary);
  padding: var(--spacing-sm) var(--spacing-lg);
  border-left: 3px solid transparent;
}

.app-sidebar-item:hover {
  color: var(--color-primary);
  background: var(--color-bg-hover);
}

.app-sidebar-item.active {
  color: var(--color-primary);
  background: var(--color-primary-bg);
  border-left-color: var(--color-primary);
}
</style>
