import type { AxiosError } from 'axios'
import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'
import type { ApiResult } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { http, toApiError } from './http'

/**
 * 鉴权拦截器。
 *
 * 单独放在这个文件，是为了避免 `http.ts` 反过来导入 store 造成的循环依赖
 * （`http → stores/auth → api/auth → http`）。这里可以自由依赖两侧。
 *
 * 与模板原来的 token 模型不同：登录态由服务端 session cookie 承载，
 * 所以请求侧**不再注入 Authorization**——浏览器自动带 cookie，
 * 前端也就没有令牌可被 XSS 读到。
 *
 * 响应侧的 401 表示会话失效：同步本地状态并跳登录页，带上 `redirect`。
 */
export function installAuthInterceptors(router: Router, pinia: Pinia) {
  http.interceptors.response.use(
    response => response,
    async (error: AxiosError<ApiResult<unknown>>) => {
      if (error.response?.status === 401) {
        useAuthStore(pinia).markUnauthenticated()
        const current = router.currentRoute.value
        if (current.path !== '/login')
          await router.replace({ path: '/login', query: { redirect: current.fullPath } })
      }
      return Promise.reject(toApiError(error))
    },
  )
}
