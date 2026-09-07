<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()

function backHome(): void {
  const role = userStore.role
  if (role === 'RESIDENT') router.push('/resident/home')
  else if (role === 'STAFF') router.push('/staff/dashboard')
  else if (role === 'ADMIN' || role === 'SUPER_ADMIN') router.push('/admin/statistics/dashboard')
  else router.push('/guest/home')
}
</script>

<template>
  <main class="error-page">
    <h1>403</h1>
    <p>无权访问该页面</p>
    <el-button type="primary" @click="backHome">返回首页</el-button>
  </main>
</template>

<style scoped>
.error-page {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
}

.error-page h1 {
  font-size: var(--font-size-xxl);
  color: var(--color-text-disabled);
}
</style>
