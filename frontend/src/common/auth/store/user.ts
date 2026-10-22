import { defineStore } from 'pinia'
import { ref, computed, onScopeDispose } from 'vue'
import type { AuthUser, PersistedSession } from '@/common/auth/types/user'
import type { Role } from '@/common/web/types/api'
import { loadSession, saveSession, clearSession } from '@/common/auth/utils/auth'
import { useNotificationStore } from '@/common/messaging/store/notification'

export const useUserStore = defineStore('user', () => {
  const session = ref<PersistedSession | null>(loadSession())
  // 续期仅同步会话，不重新初始化通知/群聊以免中断当前操作。
  const syncSession = () => { session.value = loadSession() }
  window.addEventListener('crsp-session-updated', syncSession)
  onScopeDispose(() => window.removeEventListener('crsp-session-updated', syncSession))

  const token = computed(() => session.value?.token ?? '')
  const user = computed<AuthUser | null>(() => session.value?.user ?? null)
  const role = computed<Role | null>(() => session.value?.user.role ?? null)
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
