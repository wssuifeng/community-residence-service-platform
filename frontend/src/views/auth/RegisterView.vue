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
  } catch (error) {
    // 注册失败（用户名占用/注册开关关闭等）：提示用户，停留在注册页
    ElMessage.error(error instanceof Error ? error.message : '注册失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="register-page">
    <div class="register-brand">
      <img
        class="register-brand-image"
        src="/images/guest-hero.png"
        alt="白天的社区花园"
      />
      <div class="register-brand-copy">
        <h1>社区居住服务</h1>
      </div>
    </div>

    <div class="register-panel">
      <div class="register-card">
        <h1 class="register-title">注册居民账号</h1>

        <form class="register-form" @submit.prevent="handleRegister">
          <label class="register-field">
            <span>用户名</span>
            <el-input v-model="form.username" class="register-input" placeholder="4~20 位字母/数字/下划线" autocomplete="username" />
          </label>
          <label class="register-field">
            <span>密码</span>
            <el-input v-model="form.password" class="register-input" type="password" placeholder="8~20 位" autocomplete="new-password" show-password />
          </label>
          <label class="register-field">
            <span>确认密码</span>
            <el-input v-model="form.confirmPassword" class="register-input" type="password" placeholder="再次输入密码" autocomplete="new-password" show-password />
          </label>
          <label class="register-field">
            <span>真实姓名</span>
            <el-input v-model="form.realName" class="register-input" placeholder="请输入真实姓名" />
          </label>
          <label class="register-field">
            <span>手机号</span>
            <el-input v-model="form.phone" class="register-input" placeholder="请输入手机号" />
          </label>
          <label class="register-field">
            <span>邮箱（选填）</span>
            <el-input v-model="form.email" class="register-input" placeholder="请输入邮箱" type="email" />
          </label>
          <label class="register-field register-field--full">
            <span>身份证号（选填）</span>
            <el-input v-model="form.idCardNumber" class="register-input" placeholder="请输入身份证号" />
          </label>

          <el-button type="primary" native-type="submit" class="register-submit register-field--full" :loading="loading">
            注 册
          </el-button>
        </form>

        <p class="register-login">
          已有账号？
          <router-link to="/auth/login">返回登录</router-link>
        </p>
      </div>
    </div>
  </main>
</template>

<style scoped>
.register-page {
  display: grid;
  grid-template-columns: 45fr 55fr;
  min-height: 100dvh;
  background-color: #fff;
}

.register-brand {
  position: relative;
  overflow: hidden;
}

/* 底部暗纱：保证左下白字叠在摄影图上可读 */
.register-brand::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to top, rgba(20, 24, 40, 0.45), transparent 45%);
}

/* 品牌图绝对定位填充：不参与栅格行高计算，避免撑高整页 */
.register-brand-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
}

.register-brand-copy {
  position: absolute;
  left: var(--spacing-xxl);
  bottom: var(--spacing-xxl);
  z-index: 1;
  color: #fff;
  text-shadow: 0 2px 12px rgba(31, 41, 55, 0.45);
}

.register-brand-copy h1 {
  font-size: var(--font-size-hero);
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.04em;
}

.register-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xl) var(--spacing-lg);
  background-color: var(--color-bg);
}

.register-card {
  width: 100%;
  max-width: 640px;
  padding: var(--spacing-xxl);
  background-color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card-float);
}

.register-title {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  text-align: center;
}

.register-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: var(--spacing-lg);
  row-gap: var(--spacing-md);
  margin-top: var(--spacing-xl);
}

.register-field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.register-field > span {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 身份证号与注册按钮通栏占满两列 */
.register-field--full {
  grid-column: 1 / -1;
}

.register-input {
  /* 输入框通高 48px（= spacing-xxl），对齐设计稿的大输入框比例 */
  --el-input-height: var(--spacing-xxl);
}

.register-input :deep(.el-input__wrapper) {
  padding: 0 var(--spacing-md);
  border-radius: var(--radius-md);
  box-shadow: 0 0 0 1px var(--color-border) inset;
  transition: box-shadow 0.2s ease;
}

.register-input :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--color-text-disabled) inset;
}

.register-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}

/* EP 未做全局主题覆盖，此处把主按钮染回品牌蓝 */
.register-submit {
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

.register-login {
  margin-top: var(--spacing-xl);
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

/* 平板竖屏以下收起品牌栏、表单落单列 */
@media (max-width: 1023px) {
  .register-page {
    grid-template-columns: 1fr;
  }

  .register-brand {
    min-height: 180px;
  }

  .register-brand-copy {
    left: var(--spacing-lg);
    bottom: var(--spacing-md);
  }

  .register-brand-copy h1 {
    font-size: var(--font-size-xxl);
  }

  .register-form {
    grid-template-columns: 1fr;
  }
}
</style>
