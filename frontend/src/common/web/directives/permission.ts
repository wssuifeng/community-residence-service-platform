import type { Directive, DirectiveBinding } from 'vue'
import { useUserStore } from '@/common/auth/store/user'
import { hasRole } from '@/common/routing/utils/permission'
import type { Role } from '@/common/web/types/api'

/**
 * v-permission 按钮级账号角色权限指令（UI设计.md §10.2）：
 * 无权限的按钮直接从 DOM 移除（不显示禁用态，§6.2.2）
 * 用法：v-permission="['SUPER_ADMIN']" 或单角色简写 v-permission="'ADMIN'"
 */
export const permission: Directive = {
  mounted(el: HTMLElement, binding: DirectiveBinding<Role | Role[]>) {
    const allowed = Array.isArray(binding.value) ? binding.value : [binding.value]
    const userStore = useUserStore()
    if (!hasRole(userStore.role, allowed)) {
      el.parentNode?.removeChild(el)
    }
  }
}

export default permission
