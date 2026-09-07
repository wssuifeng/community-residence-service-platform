import type { PersistedSession } from '@/types/user'

const SESSION_KEY = 'crsp_session'

/**
 * 会话存储：sessionStorage 承载临时会话（AGENTS.md 禁止项：
 * 敏感信息不走 localStorage；令牌过期由后端黑名单校验兜底）
 */
export function saveSession(session: PersistedSession): void {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function loadSession(): PersistedSession | null {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    const session = JSON.parse(raw) as PersistedSession
    // 本地过期兜底：过期即视为未登录，不等后端 401
    if (Date.now() >= session.expiresAt) {
      clearSession()
      return null
    }
    return session
  } catch {
    clearSession()
    return null
  }
}

export function getToken(): string | null {
  return loadSession()?.token ?? null
}

export function clearSession(): void {
  sessionStorage.removeItem(SESSION_KEY)
}
