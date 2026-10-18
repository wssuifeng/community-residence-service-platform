import { config } from '@vue/test-utils'
import permission from '@/common/web/directives/permission'
import responsive from '@/common/web/directives/responsive'
import { applyElementPlusDefaults } from '@/common/web/utils/elementPlusDefaults'

/**
 * 组件测试全局前置：注册 main.ts 里 setupDirectives 的同款自定义指令。
 * 管理端页面普遍使用 v-permission，不注册会在每个用例刷 "Failed to resolve directive"
 * 警告并淹没真实断言（浏览器环境由 src/main.ts 注册，测试环境在此补齐）。
 */
config.global.directives = { permission, responsive }

/* 组件库默认值同样按 main.ts 收口（如弹窗蒙层点击不关闭），否则测试环境取到的是
   Element Plus 原始默认值，这类全局默认就无法被任何用例覆盖到 */
applyElementPlusDefaults()
