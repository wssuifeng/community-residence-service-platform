import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd())

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      proxy: {
        // API 代理：浏览器请求同源 /api，由 dev server 转发到后端
        '/api': {
          target: env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
          changeOrigin: true
        },
        // WebSocket 代理（STOMP 通知 + 反馈会话）
        '/ws': {
          target: env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
          ws: true
        },
        // 上传文件静态访问代理（后端 /uploads/** 托管本地磁盘文件）
        '/uploads': {
          target: env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  }
})
