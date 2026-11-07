import process from 'node:process'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 控制台与 auth 同源：开发时靠 vite 代理，生产由 nginx 反代。
 * 浏览器看到的始终是同一个 origin，session cookie 才能正常携带。
 */
const AUTH_TARGET = process.env.VITE_AUTH_TARGET ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': new URL('./src', import.meta.url).pathname },
  },
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/auth': { target: AUTH_TARGET, changeOrigin: false },
      '/oauth2': { target: AUTH_TARGET, changeOrigin: false },
      '/userinfo': { target: AUTH_TARGET, changeOrigin: false },
      '/.well-known': { target: AUTH_TARGET, changeOrigin: false },
      // 注意：不要代理 /login。GET /login 是 SPA 的路由（登录表单），
      // 只有 POST /auth/login 才该到 auth —— 这样也不必按 HTTP 方法做分流。
    },
  },
  test: {
    environment: 'jsdom',
    dir: './tests/unit',
    include: ['**/*.test.ts'],
  },
})
