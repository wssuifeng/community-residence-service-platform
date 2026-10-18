import { ElDialog } from 'element-plus'

/**
 * Element Plus 组件全局默认收口。
 *
 * 出处：`docs/30_系统设计/组件库设计规范.md` §6.3.2 —— **蒙层点击不关闭弹窗**
 * （长表单误关会丢输入）。规范要求在实现层统一收口，而不是逐处给 `<el-dialog>`
 * 硬补 `:close-on-click-modal="false"`（全仓 44 处、仅 9 处显式声明，散补必然漏）。
 *
 * 做法：把 `ElDialog` 的 `closeOnClickModal` 默认值改为 `false`。Element Plus 解析
 * 未显式传参的 prop 时读取该默认值，因此对所有既有与后续 `<el-dialog>` 一次性生效；
 * 显式传参（含已有的 9 处 `:close-on-click-modal="false"`）不受影响，需要蒙层关闭的
 * 场景仍可显式写 `:close-on-click-modal="true"` 覆盖。
 *
 * 调用点：`src/main.ts`（生产）与 `src/test/setup.ts`（组件测试），保证测试环境与实际
 * 运行环境取到同一套默认值——否则该默认值无法被任何用例覆盖到。
 */
export function applyElementPlusDefaults(): void {
  const closeOnClickModal = (
    ElDialog.props as unknown as Record<string, { default?: unknown } | undefined>
  )?.closeOnClickModal
  if (closeOnClickModal && closeOnClickModal.default !== false) {
    closeOnClickModal.default = false
  }
}
