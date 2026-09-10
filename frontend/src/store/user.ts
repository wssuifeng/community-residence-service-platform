import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AuthUser, PersistedSession } from '@/types/user'
import type { Role } from '@/types/api'
import { loadSession, saveSession, clearSession } from '@/utils/auth'
import { useNotificationStore } from './notification'

export const useUserStore = defineStore('user', () => {
  const session = ref<PersistedSession | null>(loadSession())

  const token = computed(() => session.value?.token ?? '')
  const user = computed<AuthUser | null>(() => session.value?.user ?? null)
  const role = computed<Role>(() => session.value?.user.role ?? 'GUEST')
  const isLoggedIn = computed(() => session.value !== null)

  function setSession(next: PersistedSession): void {
    session.value = next
    saveSession(next)
    useNotificationStore().init()
  }

  function logout(): void {
    useNotificationStore().cleanup()
    session.value = null
    clearSession()
  }

  return { token, user, role, isLoggedIn, setSession, logout }
})
