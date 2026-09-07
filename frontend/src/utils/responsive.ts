export const breakpoints = {
  xs: 0,
  sm: 576,
  md: 768,
  lg: 1024,
  xl: 1200,
  xxl: 1600
} as const

export type Breakpoint = keyof typeof breakpoints

/** 当前断点（响应式布局设计规范.md §2.3） */
export function getCurrentBreakpoint(): Breakpoint {
  const width = window.innerWidth
  if (width >= breakpoints.xxl) return 'xxl'
  if (width >= breakpoints.xl) return 'xl'
  if (width >= breakpoints.lg) return 'lg'
  if (width >= breakpoints.md) return 'md'
  if (width >= breakpoints.sm) return 'sm'
  return 'xs'
}

/** 桌面端 ≥1200px（主要适配） */
export function isDesktop(): boolean {
  return window.innerWidth >= breakpoints.xl
}

/** 平板端 768~1199px（次要适配） */
export function isTablet(): boolean {
  const width = window.innerWidth
  return width >= breakpoints.md && width < breakpoints.xl
}

/** 移动端 <768px（不适配，仅提示） */
export function isMobile(): boolean {
  return window.innerWidth < breakpoints.md
}
