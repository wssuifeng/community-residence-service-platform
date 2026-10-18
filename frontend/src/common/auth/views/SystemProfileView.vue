<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/common/auth/store/user'
import { systemRoleLabels } from '@/common/auth/types/auth'
import type { SystemRole } from '@/common/auth/types/auth'

const userStore = useUserStore()
const roleLabel = computed(() => systemRoleLabels[userStore.role as SystemRole] ?? '')
</script>

<template>
  <section class="account-page" aria-labelledby="account-title">
    <h1 id="account-title">个人中心</h1>
    <div class="account-card">
      <div class="account-identity">
        <span class="account-avatar" aria-hidden="true">{{ userStore.user?.realName?.slice(0, 1) }}</span>
        <div>
          <h2>{{ userStore.user?.realName }}</h2>
          <p>{{ roleLabel }}</p>
        </div>
      </div>
      <dl class="account-details">
        <div><dt>用户名</dt><dd>{{ userStore.user?.username }}</dd></div>
        <div><dt>姓名</dt><dd>{{ userStore.user?.realName }}</dd></div>
        <div><dt>身份</dt><dd>{{ roleLabel }}</dd></div>
      </dl>
    </div>
  </section>
</template>

<style scoped>
.account-page { max-width: 760px; }
h1 { margin: 0 0 var(--spacing-lg); font-size: var(--font-size-xl); }
.account-card { padding: var(--spacing-xl); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-bg-card, #fff); }
.account-identity { display: flex; align-items: center; gap: var(--spacing-md); padding-bottom: var(--spacing-lg); border-bottom: 1px solid var(--color-border); }
.account-avatar { display: flex; align-items: center; justify-content: center; width: 52px; height: 52px; flex-shrink: 0; border-radius: 50%; background: var(--color-primary); color: #fff; font-size: var(--font-size-xl); }
h2 { margin: 0; font-size: var(--font-size-lg); overflow-wrap: anywhere; }
p { margin: var(--spacing-xs) 0 0; color: var(--color-text-secondary); }
.account-details { display: grid; gap: var(--spacing-lg); margin: var(--spacing-lg) 0 0; }
.account-details div { display: grid; grid-template-columns: 88px minmax(0, 1fr); gap: var(--spacing-md); }
dt { color: var(--color-text-secondary); }
dd { margin: 0; overflow-wrap: anywhere; }
</style>
