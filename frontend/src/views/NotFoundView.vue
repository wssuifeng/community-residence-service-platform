<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppLogo from '@/components/common/AppLogo.vue'

const router = useRouter()

function goNotices(): void {
  router.push('/guest/notices')
}
</script>

<template>
  <main class="error-page">
    <header class="error-nav">
      <router-link class="error-brand" to="/guest/home">
        <AppLogo class="error-brand-logo" :size="22" />
        <span>社区居住服务</span>
      </router-link>
      <nav class="error-nav-links">
        <router-link to="/guest/home">首页</router-link>
        <router-link to="/auth/login">登录</router-link>
      </nav>
    </header>

    <section class="error-body">
      <img class="error-illustration" src="/images/error-404.png" alt="路标与放大镜插画" />
      <h1 class="error-code">404</h1>
      <h2 class="error-title">页面走丢了</h2>
      <p class="error-desc">你访问的页面不存在或已被移动</p>
      <div class="error-actions">
        <el-button type="primary" class="error-btn error-btn--primary" @click="router.push('/')">
          返回首页
        </el-button>
        <el-button class="error-btn error-btn--ghost" @click="goNotices">查看社区公告</el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.error-page {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  background-color: var(--color-bg);
}

/* 页面内联精简导航条（裸页不引 GuestLayout） */
.error-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-md) var(--spacing-xxl);
  background-color: #fff;
  border-bottom: 1px solid var(--color-border);
}

.error-brand {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  color: var(--color-text-primary);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.error-brand-logo {
  color: var(--color-primary);
}

.error-nav-links {
  display: flex;
  gap: var(--spacing-xl);
}

.error-nav-links a {
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
}

.error-nav-links a:hover {
  color: var(--color-primary);
}

.error-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xxl) var(--spacing-md);
  text-align: center;
}

/* 插画边缘已做 alpha 羽化（素材处理），直接铺放在暖灰底上 */
.error-illustration {
  width: 340px;
  height: auto;
}

.error-code {
  margin-top: var(--spacing-xl);
  font-size: var(--font-size-display);
  line-height: 1;
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.error-title {
  margin-top: var(--spacing-lg);
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.error-desc {
  margin-top: var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.error-actions {
  margin-top: var(--spacing-xl);
  display: flex;
  gap: var(--spacing-md);
}

/* EP 未做全局主题覆盖：主按钮染回品牌蓝，次按钮保持白底灰边 */
.error-btn {
  height: auto;
  margin-left: 0;
  padding: var(--spacing-md) var(--spacing-xxl);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
}

.error-btn--primary {
  --el-button-bg-color: var(--color-primary);
  --el-button-border-color: var(--color-primary);
  --el-button-hover-bg-color: var(--color-primary-light);
  --el-button-hover-border-color: var(--color-primary-light);
  --el-button-active-bg-color: var(--color-primary-dark);
  --el-button-active-border-color: var(--color-primary-dark);
  --el-button-text-color: #fff;
  --el-button-hover-text-color: #fff;
  --el-button-active-text-color: #fff;
}

.error-btn--ghost {
  --el-button-bg-color: #fff;
  --el-button-border-color: var(--color-border);
  --el-button-text-color: var(--color-text-primary);
  --el-button-hover-bg-color: var(--color-bg-hover);
  --el-button-hover-border-color: var(--color-primary);
  --el-button-hover-text-color: var(--color-primary);
  --el-button-active-bg-color: var(--color-bg-hover);
  --el-button-active-border-color: var(--color-primary-dark);
  --el-button-active-text-color: var(--color-primary-dark);
}
</style>
