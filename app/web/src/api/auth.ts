import type { ApiResult } from '@/types/api'
import type { CurrentUserResult, LoginPayload, LoginResult } from '@/types/auth'
import { http, unwrap } from './http'

/**
 * 认证接口。
 *
 * 路径集中在 `AUTH_ENDPOINTS`：接入真实后端时只改这一处，页面和 store 不用动。
 */
export const AUTH_ENDPOINTS = {
  login: '/api/v1/auth/login',
  me: '/api/v1/auth/me',
  logout: '/api/v1/auth/logout',
} as const

export async function login(payload: LoginPayload): Promise<LoginResult> {
  const response = await http.post<ApiResult<LoginResult>>(AUTH_ENDPOINTS.login, payload)
  return unwrap(response.data)
}

export async function fetchCurrentUser(): Promise<CurrentUserResult> {
  const response = await http.get<ApiResult<CurrentUserResult>>(AUTH_ENDPOINTS.me)
  return unwrap(response.data)
}

/**
 * 通知服务端注销。
 *
 * 这里**不**吞掉错误：错误策略属于调用方（`stores/auth.ts` 的 logout 会兜底并保证清空本地会话）。
 * 在 API 层悄悄 catch，会让「网络真的坏了」和「注销成功」在调用方看来完全一样。
 */
export async function logout(): Promise<void> {
  await http.post<ApiResult<null>>(AUTH_ENDPOINTS.logout)
}
