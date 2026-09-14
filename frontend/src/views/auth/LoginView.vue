<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { residentLogin, adminLogin } from '@/api/auth'
import { useUserStore } from '@/store/user'
import type { LoginResult } from '@/types/user'

/** 登录页（UI设计.md §3.2）：居民登录 / 管理员登录切换 */
type LoginTab = 'resident' | 'admin'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeTab = ref<LoginTab>('resident')
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function handleLogin(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    // 居民与管理员登录接口不同（接口设计.md 9.2.1.2 / 9.10.1.1）
    const result: LoginResult =
      activeTab.value === 'resident'
        ? await residentLogin({ username: form.username, password: form.password })
        : await adminLogin({ username: form.username, password: form.password })

    userStore.setSession({
      token: result.token,
      user: result.user,
      expiresAt: Date.now() + result.expiresIn * 1000
    })
    ElMessage.success('登录成功')

    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    if (redirect) {
      router.push(redirect)
      return
    }
    // 无回跳地址时按角色进入各自首页
    switch (result.user.role) {
      case 'RESIDENT':
        router.push('/resident/home')
        break
      case 'STAFF':
        router.push('/staff/dashboard')
        break
      case 'ADMIN':
      case 'SUPER_ADMIN':
        router.push('/admin/statistics/dashboard')
        break
      default:
        router.push('/guest/home')
    }
  } catch (error) {
    // 登录失败（凭据错误/账号冻结等）：提示用户，不中断停留在登录页
    ElMessage.error(error instanceof Error ? error.message : '登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-brand">
      <img
        class="login-brand-image"
        src="/images/login-brand-panel.png"
        alt="暮色下的社区花园"
      />
      <div class="login-brand-copy">
        <h1>社区居住服务</h1>
        <p>房屋、工单、公告、预约，一个入口打理社区生活</p>
      </div>
    </div>

    <div class="login-panel">
      <div class="login-card">
        <h2 class="login-title">欢迎回来</h2>
        <p class="login-subtitle">登录你的账号</p>

        <div class="login-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'resident'"
            :class="{ active: activeTab === 'resident' }"
            @click="activeTab = 'resident'"
          >
            居民登录
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'admin'"
            :class="{ active: activeTab === 'admin' }"
            @click="activeTab = 'admin'"
          >
            管理员 / 服务人员
          </button>
        </div>

        <form class="login-form" @submit.prevent="handleLogin">
          <el-input
            v-model="form.username"
            class="login-input"
            placeholder="请输入用户名"
            autocomplete="username"
          >
            <template #prefix>
              <svg
                class="login-input-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <circle cx="12" cy="8" r="4" />
                <path d="M4.5 20c1.4-3.6 4.2-5.5 7.5-5.5s6.1 1.9 7.5 5.5" />
              </svg>
            </template>
          </el-input>
          <el-input
            v-model="form.password"
            class="login-input"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
          >
            <template #prefix>
              <svg
                class="login-input-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="5" y="11" width="14" height="9" rx="2" />
                <path d="M8 11V8a4 4 0 0 1 8 0v3" />
              </svg>
            </template>
          </el-input>
          <el-button type="primary" native-type="submit" class="login-submit" :loading="loading">
            登 录
          </el-button>
        </form>

        <p class="login-register">
          还没有账号？
          <router-link to="/auth/register">注册居民账号</router-link>
          <span class="login-divider">·</span>
          <router-link to="/guest/home">先逛逛</router-link>
        </p>
      </div>
    </div>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: 55fr 45fr;
  min-height: 100dvh;
  background-color: #fff;
}

.login-brand {
  position: relative;
  overflow: hidden;
}

/* 底部暗纱：保证左下白字叠在摄影图上可读 */
.login-brand::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to top, rgba(20, 24, 40, 0.45), transparent 45%);
}

/* 品牌图绝对定位填充：竖向摄影图若参与栅格行高计算会撑高整页 */
.login-brand-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
}

.login-brand-copy {
  position: absolute;
  left: var(--spacing-xxl);
  bottom: var(--spacing-xxl);
  z-index: 1;
  color: #fff;
  text-shadow: 0 2px 12px rgba(31, 41, 55, 0.45);
}

.login-brand-copy h1 {
  font-size: var(--font-size-hero);
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.04em;
}

.login-brand-copy p {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-md);
}

.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xl) var(--spacing-lg);
  background-color: var(--color-bg);
}

.login-card {
  width: 100%;
  max-width: 440px;
  padding: var(--spacing-xxl);
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card-float);
  text-align: center;
}

.login-title {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.login-subtitle {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.login-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-sm);
  margin: var(--spacing-xl) 0 var(--spacing-lg);
}

.login-tabs button {
  padding: var(--spacing-md) 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: #fff;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
}

.login-tabs button.active {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
  font-weight: var(--font-weight-medium);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.login-input {
  /* 输入框通高 48px（= spacing-xxl），对齐设计稿的大输入框比例 */
  --el-input-height: var(--spacing-xxl);
}

.login-input-icon {
  width: 18px;
  height: 18px;
  color: var(--color-text-disabled);
}

.login-input :deep(.el-input__wrapper) {
  padding: 0 var(--spacing-md);
  border-radius: var(--radius-md);
  box-shadow: 0 0 0 1px var(--color-border) inset;
  transition: box-shadow 0.2s ease;
}

.login-input :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--color-text-disabled) inset;
}

.login-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}

/* EP 未做全局主题覆盖，此处把主按钮染回品牌蓝 */
.login-submit {
  --el-button-bg-color: var(--color-primary);
  --el-button-border-color: var(--color-primary);
  --el-button-hover-bg-color: var(--color-primary-light);
  --el-button-hover-border-color: var(--color-primary-light);
  --el-button-active-bg-color: var(--color-primary-dark);
  --el-button-active-border-color: var(--color-primary-dark);
  --el-button-text-color: #fff;
  --el-button-hover-text-color: #fff;
  --el-button-active-text-color: #fff;
  width: 100%;
  height: auto;
  margin-top: var(--spacing-sm);
  padding: var(--spacing-md) 0;
  border-radius: var(--radius-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  letter-spacing: 0.3em;
}

.login-register {
  margin-top: var(--spacing-xl);
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.login-divider {
  margin: 0 var(--spacing-sm);
  color: var(--color-text-disabled);
}

/* 平板竖屏以下收起品牌栏 */
@media (max-width: 1023px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-brand {
    min-height: 180px;
  }

  .login-brand-copy {
    left: var(--spacing-lg);
    bottom: var(--spacing-md);
  }

  .login-brand-copy h1 {
    font-size: var(--font-size-xxl);
  }
}
</style>
