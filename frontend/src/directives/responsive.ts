import type { Directive, DirectiveBinding } from 'vue'
import { isDesktop, isTablet, isMobile } from '@/utils/responsive'

type ResponsiveValue = 'desktop' | 'tablet' | 'mobile' | 'desktop-tablet'

function checkVisibility(value: ResponsiveValue): boolean {
  switch (value) {
    case 'desktop':
      return isDesktop()
    case 'tablet':
      return isTablet()
    case 'mobile':
      return isMobile()
    case 'desktop-tablet':
      return isDesktop() || isTablet()
    default:
      return true
  }
}

interface ResponsiveElement extends HTMLElement {
  _handleResize?: () => void
}

/** v-responsive 指令（响应式布局设计规范.md §5.2）：按端隐藏元素 */
export const responsive: Directive = {
  mounted(el: ResponsiveElement, binding: DirectiveBinding<ResponsiveValue>) {
    const apply = () => {
      el.style.display = checkVisibility(binding.value) ? '' : 'none'
    }
    apply()
    window.addEventListener('resize', apply)
    el._handleResize = apply
  },
  unmounted(el: ResponsiveElement) {
    if (el._handleResize) {
      window.removeEventListener('resize', el._handleResize)
      delete el._handleResize
    }
  }
}

export default responsive
