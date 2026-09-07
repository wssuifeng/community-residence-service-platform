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
    <div class="login-card">
      <h1 class="login-title">社区居住服务管理系统</h1>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="居民登录" name="resident" />
        <el-tab-pane label="管理员登录" name="admin" />
      </el-tabs>

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
      </p>
    </div>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--color-primary-bg), var(--color-bg));
  padding: var(--spacing-md);
}

.login-card {
  width: 400px;
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: var(--spacing-xl) var(--spacing-lg);
}

.login-title {
  font-size: var(--font-size-lg);
  text-align: center;
  margin-bottom: var(--spacing-md);
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
  margin-top: var(--spacing-md);
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}
</style>
