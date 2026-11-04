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
 * - 请求侧：把 store 里的 token 注入 `Authorization`；
 * - 响应侧：受保护接口的 401 表示会话过期 → 清空本地会话并跳登录页，带上 `redirect`；
 *   登录接口自身的 401 是「账号密码错误」，必须排除，否则用户只会看到一个不断刷新的登录页。
 */
export function installAuthInterceptors(router: Router, pinia: Pinia) {
  http.interceptors.request.use((config) => {
    const { token } = useAuthStore(pinia)
    if (token)
      config.headers.Authorization = `Bearer ${token}`
    return config
  })

  http.interceptors.response.use(
    response => response,
    async (error: AxiosError<ApiResult<unknown>>) => {
      const url = error.config?.url ?? ''
      if (error.response?.status === 401 && !url.endsWith('/auth/login')) {
        useAuthStore(pinia).clear()
        const current = router.currentRoute.value
        if (current.path !== '/login')
          await router.replace({ path: '/login', query: { redirect: current.fullPath } })
      }
      return Promise.reject(toApiError(error))
    },
  )
}
