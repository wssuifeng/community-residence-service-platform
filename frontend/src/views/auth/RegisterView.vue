<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { residentRegister } from '@/api/auth'

/** 注册页（UI设计.md §3.2）：居民自助注册（可全局关闭，5201 由后端校验） */
const router = useRouter()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  realName: '',
  phone: '',
  email: '',
  idCardNumber: ''
})

async function handleRegister(): Promise<void> {
  // 参数校验与接口设计.md 9.2.1.1 的后端校验对齐（前端先行提示）
  if (!/^[a-zA-Z0-9_]{4,20}$/.test(form.username)) {
    ElMessage.warning('用户名须为 4~20 位字母/数字/下划线')
    return
  }
  if (form.password.length < 8 || form.password.length > 20) {
    ElMessage.warning('密码长度须为 8~20 位')
    return
  }
  if (form.password !== form.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  if (!form.realName.trim()) {
    ElMessage.warning('请输入真实姓名')
    return
  }
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.warning('手机号格式错误')
    return
  }
  if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    ElMessage.warning('邮箱格式错误')
    return
  }
  if (form.idCardNumber && !/^\d{17}[\dXx]$/.test(form.idCardNumber)) {
    ElMessage.warning('身份证号格式错误')
    return
  }

  loading.value = true
  try {
    await residentRegister({
      username: form.username,
      password: form.password,
      realName: form.realName,
      phone: form.phone,
      email: form.email || undefined,
      idCardNumber: form.idCardNumber || undefined
    })
    ElMessage.success('注册成功，请登录')
    router.push('/auth/login')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="register-page">
    <div class="register-card">
      <h1 class="register-title">注册居民账号</h1>

      <form class="register-form" @submit.prevent="handleRegister">
        <label class="register-field">
          <span>用户名 *</span>
          <el-input v-model="form.username" placeholder="4~20 位字母/数字/下划线" autocomplete="username" />
        </label>
        <label class="register-field">
          <span>密码 *</span>
          <el-input v-model="form.password" type="password" placeholder="8~20 位" autocomplete="new-password" show-password />
        </label>
        <label class="register-field">
          <span>确认密码 *</span>
          <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" autocomplete="new-password" show-password />
        </label>
        <label class="register-field">
          <span>真实姓名 *</span>
          <el-input v-model="form.realName" placeholder="请输入真实姓名" />
        </label>
        <label class="register-field">
          <span>手机号 *</span>
          <el-input v-model="form.phone" placeholder="请输入手机号" />
        </label>
        <label class="register-field">
          <span>邮箱</span>
          <el-input v-model="form.email" placeholder="选填" type="email" />
        </label>
        <label class="register-field">
          <span>身份证号</span>
          <el-input v-model="form.idCardNumber" placeholder="选填" />
        </label>

        <el-button type="primary" native-type="submit" class="register-submit" :loading="loading">
          注 册
        </el-button>
      </form>

      <p class="register-login">
        已有账号？
        <router-link to="/auth/login">返回登录</router-link>
      </p>
    </div>
  </main>
</template>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--color-primary-bg), var(--color-bg));
  padding: var(--spacing-lg) var(--spacing-md);
}

.register-card {
  width: 440px;
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: var(--spacing-xl) var(--spacing-lg);
}

.register-title {
  font-size: var(--font-size-lg);
  text-align: center;
  margin-bottom: var(--spacing-md);
}

.register-form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  margin-top: var(--spacing-md);
}

.register-field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.register-submit {
  width: 100%;
  margin-top: var(--spacing-sm);
}

.register-login {
  margin-top: var(--spacing-md);
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}
</style>
