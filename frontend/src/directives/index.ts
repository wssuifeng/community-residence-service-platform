import type { Directive } from 'vue'
import permission from './permission'
import responsive from './responsive'

/** 全局自定义指令注册表 */
export function setupDirectives(app: { directive: (name: string, directive: Directive) => void }): void {
  app.directive('permission', permission)
  app.directive('responsive', responsive)
}
