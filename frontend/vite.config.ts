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
    // sockjs-client 为 CommonJS 包引用 Node 的 global（接口设计 9.11.1.6 SockJS 通道）
    define: {
      global: 'globalThis'
    },
    build: {
      // 保留路由懒加载；按实际入口拆重依赖，不让任一图表拖入全部图表模块。
      rolldownOptions: {
        output: {
          strictExecutionOrder: true,
          codeSplitting: {
            includeDependenciesRecursively: true,
            groups: [
              { name: 'vue-runtime', test: /node_modules[\\/](?:@vue|vue|pinia|vue-router)[\\/]/, priority: 30 },
              { name: 'element-plus', test: /node_modules[\\/](?:element-plus|@element-plus)[\\/]/, priority: 20, maxSize: 500_000, entriesAware: true },
              { name: 'charts', test: /node_modules[\\/](?:echarts|zrender)[\\/]/, priority: 10, maxSize: 500_000, entriesAware: true }
            ]
          }
        }
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
