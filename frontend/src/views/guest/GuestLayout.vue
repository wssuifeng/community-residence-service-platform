<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppLogo from '@/components/common/AppLogo.vue'

/** 游客端布局：顶部导航 + 单栏内容区 + 深色页脚（UI设计.md §2，未登录态） */
const navItems = [
  { label: '首页', to: '/guest/home' },
  { label: '房源', to: '/guest/housings' },
  { label: '公告', to: '/guest/notices' }
]

/* 页脚快速链接：服务类入口游客需先登录，统一指向登录页 */
const quickLinks = [
  { label: '首页', to: '/guest/home' },
  { label: '房源', to: '/guest/housings' },
  { label: '社区公告', to: '/guest/notices' },
  { label: '在线报修', to: '/auth/login' },
  { label: '意见反馈', to: '/auth/login' },
  { label: '资源预约', to: '/auth/login' }
]

/* 沉浸式导航仅首页生效：Hero 底边滚过导航高度线（60px）即恢复白底。
   用 IntersectionObserver 实测 Hero 位置，不依赖任何硬编码高度公式 */
const route = useRoute()
const isHome = computed(() => route.name === 'GuestHome')
const isScrolled = ref(false)

let heroObserver: IntersectionObserver | null = null

function observeHero(): void {
  heroObserver?.disconnect()
  heroObserver = null
  const hero = isHome.value ? document.querySelector('.guest-main .hero') : null
  if (!hero) {
    isScrolled.value = false
    return
  }
  heroObserver = new IntersectionObserver(
    ([entry]) => {
      isScrolled.value = !entry.isIntersecting
    },
    /* rootMargin 顶部内缩 60px（导航高度）：Hero 底边越过该线即判定滚过 */
    { rootMargin: '-60px 0px 0px 0px' }
  )
  heroObserver.observe(hero)
}

/* 路由切回首页时 Hero 重新挂载，需等 DOM 更新后重挂观察 */
watch(isHome, () => {
  void nextTick(observeHero)
})

onMounted(() => {
  void nextTick(observeHero)
})

onUnmounted(() => {
  heroObserver?.disconnect()
})
</script>

<template>
  <div class="guest-layout" :class="{ 'is-home': isHome, 'is-immersive': isHome && !isScrolled }">
    <AppHeader :items="navItems" show-register />
    <main class="guest-main">
      <router-view />
    </main>

    <!-- 深色页脚：品牌 + 快速链接 + 联系方式（游客端全页面共享） -->
    <footer class="guest-footer">
      <div class="guest-footer-inner">
        <div class="footer-brand">
          <h2 class="footer-brand-name">
            <AppLogo :size="22" />
            <span>社区居住服务</span>
          </h2>
          <p class="footer-brand-desc">
            致力于为社区居民提供便捷、高效、贴心的社区服务，打造更美好的社区生活。
          </p>
        </div>

        <nav class="footer-links" aria-label="快速链接">
          <h3 class="footer-heading">快速链接</h3>
          <ul class="footer-links-grid">
            <li v-for="link in quickLinks" :key="link.label">
              <router-link :to="link.to" class="footer-link">{{ link.label }}</router-link>
            </li>
          </ul>
        </nav>

        <div class="footer-contact">
          <h3 class="footer-heading">联系我们</h3>
          <p class="footer-contact-item">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
            </svg>
            <span>400-123-4567（工作日 9:00 - 18:00）</span>
          </p>
          <p class="footer-contact-item">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="2" y="4" width="20" height="16" rx="2" />
              <polyline points="22,6 12,13 2,6" />
            </svg>
            <span>service@community.local</span>
          </p>
          <p class="footer-contact-item">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            <span>社区服务中心一层 · 物业办公室</span>
          </p>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.guest-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.guest-main {
  flex: 1;
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-lg) var(--spacing-md) var(--spacing-xxl);
  width: 100%;
}

/* 首页导航 fixed 脱离文档流（Hero 顶到视口顶部），滚动后白底 + 阴影 */
.guest-layout.is-home :deep(.app-header) {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  box-shadow: var(--shadow-sm);
  transition: background 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

/* 首屏未滚动：透明压 Hero，Logo/导航/登录/注册全部反白 */
.guest-layout.is-immersive :deep(.app-header) {
  background: transparent;
  border-bottom-color: transparent;
  box-shadow: none;
}

.guest-layout.is-immersive :deep(.app-header-logo) {
  color: #fff;
}

.guest-layout.is-immersive :deep(.app-header-nav-item) {
  color: rgba(255, 255, 255, 0.85);
}

.guest-layout.is-immersive :deep(.app-header-nav-item:hover),
.guest-layout.is-immersive :deep(.app-header-nav-item.active) {
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
}

.guest-layout.is-immersive :deep(.app-header-login) {
  color: #fff;
}

.guest-layout.is-immersive :deep(.app-header-register) {
  border-color: rgba(255, 255, 255, 0.8);
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
}

.guest-layout.is-immersive :deep(.app-header-register:hover) {
  background: rgba(255, 255, 255, 0.24);
}

/* 深色页脚：三栏（品牌 / 快速链接 / 联系方式），窄屏降单列 */
.guest-footer {
  background: var(--color-bg-dark);
  color: var(--color-text-on-dark);
}

.guest-footer-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-xxl) var(--spacing-md);
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: var(--spacing-xl);
}

.footer-brand-name {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-on-dark-strong);
}

.footer-brand-desc {
  margin-top: var(--spacing-md);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  max-width: 22em;
}

.footer-heading {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-on-dark-strong);
  margin-bottom: var(--spacing-md);
}

.footer-links-grid {
  display: grid;
  grid-template-columns: repeat(3, auto);
  gap: var(--spacing-sm) var(--spacing-lg);
  justify-content: start;
}

.footer-link {
  font-size: var(--font-size-sm);
  color: var(--color-text-on-dark);
  transition: color 0.15s ease;
}

.footer-link:hover {
  color: #fff;
}

.footer-contact-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
  margin-bottom: var(--spacing-sm);
}

.footer-contact-item svg {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

@media (max-width: 768px) {
  .guest-footer-inner {
    grid-template-columns: 1fr;
  }
}
</style>
