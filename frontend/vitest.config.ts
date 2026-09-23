import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * 组件测试配置（vitest + @vue/test-utils + jsdom）。
 *
 * 与 vite.config.ts 的关系：测试不需要 dev 代理与 loadEnv，故独立成文件而不用 mergeConfig
 * （生产构建配置一字未动）；此处只复刻测试必需的 alias `@` 与 `define.global`
 * （后者是 sockjs-client 依赖 Node global 的既有口径，见 vite.config.ts 注释）。
 *
 * pool 用 threads + 单线程：本机沙箱下 fork/子进程的管道 stdio 会被拒绝（spawn EPERM），
 * 线程池不创建子进程，是最稳妥的选择。
 */
export default defineConfig({
  /* transformAssetUrls: false —— 页面模板里有 `src="/images/*.png"` 这类根相对静态资源，
     Vite 的 asset 转换在 vitest(jsdom) 下会把它折成 file:///images/... 并抛
     「The argument 'filename' must be ...」；public/ 资源本就按原样 URL 引用，
     测试环境关掉该转换即可（**仅测试配置**，vite.config.ts 未动） */
  plugins: [vue({ template: { transformAssetUrls: false } })],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  define: {
    global: 'globalThis'
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.ts'],
    /* 公共前置：注册 main.ts 同款自定义指令（见 src/test/setup.ts） */
    setupFiles: ['src/test/setup.ts'],
    /* 组件测试挂载真实 Element Plus 组件，弹窗/抽屉内容可能异步分层渲染 */
    testTimeout: 20000,
    pool: 'threads',
    maxWorkers: 1,
    /* 单文件顺序执行：避免 Element Plus 弹窗在并行 worker 里互相干扰 */
    fileParallelism: false
  }
})
