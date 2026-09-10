import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { getToken } from './auth'

/**
 * WebSocket 通知连接管理（接口设计.md 9.11.1.6）：
 * SockJS + STOMP，CONNECT 帧 JWT 认证，断线指数退避重连；
 * 认证失败静默停止，推送缺口由 store/notification.ts 轮询补拉兜底
 */

type NotificationHandler = (payload: unknown) => void

let client: Client | null = null
let handler: NotificationHandler | null = null
let reconnectDelay = 2000
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

/** 连接并订阅用户通知队列（重复调用幂等） */
export function connectWebSocket(onNotification: NotificationHandler): void {
  handler = onNotification
  if (client?.active) return

  const token = getToken()
  if (!token) return

  client = new Client({
    webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 0,
    onConnect: () => {
      reconnectDelay = 2000
      client?.subscribe('/user/queue/notifications', (frame) => {
        try {
          handler?.(JSON.parse(frame.body))
        } catch {
          /* 载荷解析失败丢弃，轮询兜底 */
        }
      })
    },
    onWebSocketClose: () => {
      scheduleReconnect()
    },
    onStompError: () => {
      /* 认证失败等不可恢复错误：静默停止重连，由轮询兜底 */
      client?.deactivate()
      client = null
    }
  })
  client.activate()
}

function scheduleReconnect(): void {
  if (!client || reconnectTimer !== null) return
  const delay = reconnectDelay
  reconnectDelay = Math.min(reconnectDelay * 2, 30000)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    client?.deactivate()
    client = null
    if (handler) connectWebSocket(handler)
  }, delay)
}

/** 断开连接（登出时调用） */
export function disconnectWebSocket(): void {
  handler = null
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  client?.deactivate()
  client = null
  reconnectDelay = 2000
}
