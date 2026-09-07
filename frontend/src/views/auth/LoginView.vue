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
        src="/images/login-illustration.png"
        alt="社区生活插画"
      />
      <div class="login-brand-copy">
        <h1>社区居住服务</h1>
        <p>房屋、工单、公告、预约，一个入口打理社区生活</p>
      </div>
    </div>

    <div class="login-panel">
      <div class="login-card">
        <h2 class="login-title">登录</h2>

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
          <label class="login-field">
            <span>用户名</span>
            <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
          </label>
          <label class="login-field">
            <span>密码</span>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
              show-password
            />
          </label>
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
  grid-template-columns: 1.1fr 1fr;
  min-height: 100dvh;
  background-color: #fff;
}

.login-brand {
  position: relative;
  overflow: hidden;
  background: linear-gradient(160deg, var(--color-primary-bg) 0%, #f6f9ff 55%, #eef3ff 100%);
}

.login-brand-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
}

.login-brand-copy {
  position: absolute;
  left: var(--spacing-xl);
  bottom: var(--spacing-xl);
  color: #fff;
  text-shadow: 0 2px 12px rgba(31, 41, 55, 0.45);
}

.login-brand-copy h1 {
  font-size: var(--font-size-xxl);
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
}

.login-card {
  width: 100%;
  max-width: 400px;
}

.login-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.login-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: var(--spacing-lg) 0 var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.login-tabs button {
  padding: var(--spacing-sm) 0;
  border: none;
  background-color: #fff;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.login-tabs button.active {
  background-color: var(--color-primary);
  color: #fff;
  font-weight: var(--font-weight-medium);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  margin-top: var(--spacing-md);
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.login-submit {
  width: 100%;
  margin-top: var(--spacing-sm);
}

.login-register {
  margin-top: var(--spacing-lg);
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
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
}
</style>
