import { ref, onMounted, onUnmounted } from 'vue'
import { getCurrentBreakpoint, isDesktop, isTablet, isMobile } from '@/utils/responsive'

/** 响应式断点 Composable（响应式布局设计规范.md §5.1，resize 时同步更新） */
export function useResponsive() {
  const breakpoint = ref(getCurrentBreakpoint())
  const desktop = ref(isDesktop())
  const tablet = ref(isTablet())
  const mobile = ref(isMobile())

  const updateBreakpoint = () => {
    breakpoint.value = getCurrentBreakpoint()
    desktop.value = isDesktop()
    tablet.value = isTablet()
    mobile.value = isMobile()
  }

  onMounted(() => {
    window.addEventListener('resize', updateBreakpoint)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', updateBreakpoint)
  })

  return {
    breakpoint,
    isDesktop: desktop,
    isTablet: tablet,
    isMobile: mobile
  }
}
