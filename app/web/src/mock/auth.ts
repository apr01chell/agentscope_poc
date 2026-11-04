import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { UserProfile } from '@/types/auth'
import axios from 'axios'

/**
 * 登录 Mock 后端。
 *
 * 为什么模板自带 Mock：新建项目时后端通常还不存在，若登录必须连真实接口，每个新项目和每个
 * AI 自动化测试都要先搭一套后端才能跑起来。Mock **只覆盖认证**，由 `VITE_USE_MOCK` 控制
 * （`.env.example` 默认开启）；接入真实接口时关掉它即可，页面代码不用改。
 *
 * 实现方式是替换 axios 适配器，而不是在每个 api 函数里写 if：请求照常经过请求拦截器，
 * 响应照常走 `unwrap` 与错误归一化，所以 Mock 与真实网络走的是**同一条代码路径**。
 */
interface MockUser extends UserProfile {
  password: string
}

export const mockUsers: MockUser[] = [
  {
    id: 1,
    username: 'admin',
    password: 'admin123',
    displayName: '管理员',
    avatarUrl: null,
    permissions: ['USER_READ'],
  },
]

const TOKEN_PREFIX = 'mock-token:'

function delay(ms = 200) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function toProfile({ password: _password, ...profile }: MockUser): UserProfile {
  return profile
}

function findUserByToken(authorization: string | undefined): MockUser | undefined {
  const token = authorization?.replace(/^Bearer\s+/i, '')
  if (!token?.startsWith(TOKEN_PREFIX))
    return undefined
  const username = token.slice(TOKEN_PREFIX.length)
  return mockUsers.find(item => item.username === username)
}

export interface MockRequest {
  url: string
  method: string
  body: unknown
  headers: Record<string, string>
}

export interface MockResponse {
  status: number
  body: unknown
}

/**
 * 纯函数形式的 Mock 路由表：单测可以直接断言每个分支，不需要浏览器。
 *
 * 返回 HTTP 状态码 + 后端同款信封；非 2xx 由 axios 的 `validateStatus` 转成异常，
 * 从而和真实后端一样进入响应错误拦截器。
 */
export function resolveMockRequest({ url, method, body, headers }: MockRequest): MockResponse {
  const path = url.split('?')[0]
  const verb = method.toUpperCase()

  if (path.endsWith('/auth/login') && verb === 'POST') {
    const payload = (body ?? {}) as Partial<Record<'username' | 'password', string>>
    const found = mockUsers.find(item => item.username === payload.username && item.password === payload.password)
    if (!found)
      return { status: 401, body: { success: false, message: '用户名或密码错误', data: null } }
    return {
      status: 200,
      body: {
        success: true,
        message: null,
        data: { token: `${TOKEN_PREFIX}${found.username}`, user: toProfile(found) },
      },
    }
  }

  if (path.endsWith('/auth/me') && verb === 'GET') {
    const found = findUserByToken(headers.Authorization)
    if (!found)
      return { status: 401, body: { success: false, message: '登录状态已失效', data: null } }
    return { status: 200, body: { success: true, message: null, data: { user: toProfile(found) } } }
  }

  if (path.endsWith('/auth/logout') && verb === 'POST')
    return { status: 200, body: { success: true, message: null, data: null } }

  return { status: 404, body: { success: false, message: `Mock 未实现：${verb} ${path}`, data: null } }
}

/**
 * axios 适配器。
 *
 * 签名必须是 `AxiosAdapter`（入参 `InternalAxiosRequestConfig`，出参 `AxiosResponse`），
 * 否则 `axios.create({ adapter })` 过不了类型检查——手写一个宽松签名的适配器看起来能用，
 * 但会在升级 axios 时以「类型不兼容」的形式爆炸。
 */
/**
 * 只 Mock 这些路径，其余一律透传给真实适配器。
 *
 * 「未知路径返回 404」看着安全，实际会把「Mock 没登记」和「后端真的没有这个接口」混成一件事：
 * 健康检查这类必须打到真实后端的请求会被 Mock 悄悄吞掉，让人误以为后端已经通了。
 */
const MOCKED_PATH_SUFFIXES = ['/auth/login', '/auth/me', '/auth/logout'] as const

function isMockedPath(url: string | undefined): boolean {
  const path = (url ?? '').split('?')[0]
  return MOCKED_PATH_SUFFIXES.some(suffix => path.endsWith(suffix))
}

export function createMockAdapter(): AxiosAdapter {
  // 真实适配器在浏览器里是 xhr/fetch，交给 axios 自己挑，不硬编码。
  const realAdapter = axios.getAdapter(axios.defaults.adapter)

  return async (config: InternalAxiosRequestConfig) => {
    if (!isMockedPath(config.url))
      return realAdapter(config)

    await delay()

    // AxiosHeaders 用 `get` 取值（大小写不敏感）；直接下标访问拿不到拦截器写入的 Authorization。
    const authorization = config.headers?.get?.('Authorization')

    let parsedBody: unknown = config.data
    if (typeof config.data === 'string')
      parsedBody = JSON.parse(config.data)

    const { status, body } = resolveMockRequest({
      url: config.url ?? '',
      method: config.method ?? 'get',
      body: parsedBody,
      headers: { Authorization: typeof authorization === 'string' ? authorization : '' },
    })

    return {
      data: body,
      status,
      statusText: String(status),
      headers: {},
      config,
    } satisfies AxiosResponse
  }
}
