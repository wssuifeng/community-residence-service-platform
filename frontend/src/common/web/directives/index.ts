import type { Directive } from 'vue'
import permission from '@/common/web/directives/permission'
import responsive from '@/common/web/directives/responsive'

/** 全局自定义指令注册表 */
export function setupDirectives(app: { directive: (name: string, directive: Directive) => void }): void {
  app.directive('permission', permission)
  app.directive('responsive', responsive)
}
