import process from 'node:process'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'
import { defineConfig } from 'vitest/config'

/** 后端地址：开发时由 Vite 代理 `/api`，前端代码里只写相对路径。 */
const API_TARGET = process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), UnoCSS()],
  resolve: {
    // 与 tsconfig.app.json 的 `paths` 保持一致，否则类型能解析、运行时会 404。
    alias: {
      '@': new URL('./src', import.meta.url).pathname,
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
        secure: false,
      },
    },
  },
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 1200,
  },
  test: {
    environment: 'jsdom',
    // 单元测试与源码分离：src/ 只放会被打进产物的代码，测试统一在 tests/unit 下。
    // `dir` 比宽泛的 exclude 更快，也让「哪些文件是测试」有唯一答案。
    dir: './tests/unit',
    include: ['**/*.test.ts'],
    setupFiles: ['./tests/setup.ts'],
    restoreMocks: true,
    clearMocks: true,
  },
})
