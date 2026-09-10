import SockJS from 'sockjs-client'
import { Client, type StompSubscription } from '@stomp/stompjs'
import { getToken } from './auth'

/**
 * WebSocket 连接管理（接口设计.md 9.11.1.6 通知 / 9.6.3.3 反馈会话）：
 * 统一 /ws 端点 SockJS + STOMP，CONNECT 帧 JWT 认证，断线指数退避重连；
 * 多目的地订阅——连接未就绪时登记排队，连接/重连成功后统一补订阅；
 * 认证失败静默停止，推送缺口由各业务轮询兜底（通知 30s / 反馈会话 5s）
 */

type MessageHandler = (payload: unknown) => void

/** 一条订阅登记：目的地 + 回调 + 当前连接上的 STOMP 订阅句柄（重连后重建） */
interface SubscriptionEntry {
  destination: string
  handler: MessageHandler
  stompSubscription: StompSubscription | null
}

const NOTIFICATION_DESTINATION = '/user/queue/notifications'

let client: Client | null = null
let entries: SubscriptionEntry[] = []
let reconnectDelay = 2000
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

/** 连接并订阅用户通知队列（登录后调用；通知队列为常驻订阅，重复调用幂等） */
export function connectWebSocket(onNotification: MessageHandler): void {
  const existing = entries.find((entry) => entry.destination === NOTIFICATION_DESTINATION)
  if (existing) existing.handler = onNotification
  else {
    entries.push({
      destination: NOTIFICATION_DESTINATION,
      handler: onNotification,
      stompSubscription: null
    })
  }
  connect()
}

/** 订阅业务主题（反馈会话 /topic/feedback/{id} 等）；返回退订函数 */
export function subscribe(destination: string, handler: MessageHandler): () => void {
  const entry: SubscriptionEntry = { destination, handler, stompSubscription: null }
  entries.push(entry)
  if (client?.connected) entry.stompSubscription = doSubscribe(entry)
  else connect()
  return () => {
    entry.stompSubscription?.unsubscribe()
    entries = entries.filter((item) => item !== entry)
  }
}

/** 连接是否就绪（页面据此在实时推送与轮询兜底间调整频率） */
export function isWsConnected(): boolean {
  return client?.connected === true
}

function connect(): void {
  if (client) return
  const token = getToken()
  if (!token) return

  client = new Client({
    webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 0,
    onConnect: () => {
      reconnectDelay = 2000
      for (const entry of entries) {
        entry.stompSubscription = doSubscribe(entry)
      }
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

function doSubscribe(entry: SubscriptionEntry): StompSubscription {
  return client!.subscribe(entry.destination, (frame) => {
    try {
      entry.handler(JSON.parse(frame.body))
    } catch {
      /* 载荷解析失败丢弃，轮询兜底 */
    }
  })
}

function scheduleReconnect(): void {
  /* 已无任何订阅则不重连（登出后残余的 close 事件） */
  if (!client || reconnectTimer !== null || entries.length === 0) return
  const delay = reconnectDelay
  reconnectDelay = Math.min(reconnectDelay * 2, 30000)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    client?.deactivate()
    client = null
    connect()
  }, delay)
}

/** 断开连接并清空全部订阅（登出时调用） */
export function disconnectWebSocket(): void {
  entries = []
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  client?.deactivate()
  client = null
  reconnectDelay = 2000
}
