/// <reference types="vite/client" />

/** Vite 环境变量类型（.env.development / .env.production） */
interface ImportMetaEnv {
  /** 应用标题 */
  readonly VITE_APP_TITLE: string
  /** 后端 API 基础路径（Axios baseURL） */
  readonly VITE_API_BASE_URL: string
  /** WebSocket 地址（STOMP 连接） */
  readonly VITE_WS_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
