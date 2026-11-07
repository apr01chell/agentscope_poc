import type { ApiResult } from '@/types/api'
import type { CurrentUserResult } from '@/types/auth'
import { http, unwrap } from './http'

/**
 * 认证接口。
 *
 * 工作台不再自己收账号密码：登录是跳用户中心（OIDC 授权码 + PKCE），
 * 会话由服务端 session 维护，前端只负责"问一次我是谁"。
 */
/**
 * 后端 origin。
 *
 * 开发时前端在 vite（5173）、后端在 8080，登录必须由后端发起（session 要落在后端 origin）；
 * 生产由 nginx 同源，留空即可。
 */
const SERVER_ORIGIN = import.meta.env.VITE_SERVER_ORIGIN ?? 'http://localhost:8080'

export const AUTH_ENDPOINTS = {
  /** Spring Security 的 OAuth2 登录入口，浏览器直接跳转。 */
  login: `${SERVER_ORIGIN}/oauth2/authorization/workbench`,
  me: '/api/me',
  logout: `${SERVER_ORIGIN}/logout`,
} as const

interface MePayload {
  sub: string
  name: string
  tenant: string
  roles: string[]
  perms: string[]
}

export async function fetchCurrentUser(): Promise<CurrentUserResult> {
  const response = await http.get<ApiResult<MePayload>>(AUTH_ENDPOINTS.me)
  const me = unwrap(response.data)
  return {
    user: {
      id: me.sub,
      username: me.sub,
      displayName: me.name,
      avatarUrl: null,
      permissions: me.perms ?? [],
    },
  }
}

/** 跳转到用户中心完成登录；成功后由服务端 302 回到站点根路径。 */
export function startLogin(): void {
  window.location.assign(AUTH_ENDPOINTS.login)
}

/**
 * 通知服务端注销。
 *
 * 这里不吞错误：错误策略属于调用方（store 的 logout 会兜底并保证清空本地状态）。
 */
export async function logout(): Promise<void> {
  await http.post<ApiResult<null>>(AUTH_ENDPOINTS.logout)
}
