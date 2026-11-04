import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApiError, http, isApiError, toApiError, unwrap } from '@/api/http'
import { installAuthInterceptors } from '@/api/interceptors'
import { useAuthStore } from '@/stores/auth'

const user = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  avatarUrl: null,
  permissions: ['USER_READ'],
}

describe('toApiError', () => {
  it('保留后端信封里的 message、状态码与业务码', () => {
    const result = toApiError({
      isAxiosError: true,
      response: {
        status: 400,
        data: { success: false, message: '用户名已被占用', code: 'USERNAME_TAKEN', data: null },
      },
      config: { url: '/api/v1/users' },
    })

    expect(result).toMatchObject({ message: '用户名已被占用', status: 400, code: 'USERNAME_TAKEN' })
  })

  it('没有响应体时给出带状态码的兜底文案', () => {
    const result = toApiError({
      isAxiosError: true,
      response: { status: 502, data: undefined },
      config: { url: '/api/v1/health' },
    })

    expect(result.status).toBe(502)
    expect(result.message).toContain('502')
  })

  it('把超时与断网翻译成可读文案', () => {
    expect(toApiError({ isAxiosError: true, code: 'ECONNABORTED', config: {} }).message)
      .toBe('请求超时，请稍后重试')
    expect(toApiError({ isAxiosError: true, config: {} }).message)
      .toBe('网络异常，请检查网络连接')
  })

  it('对普通 Error 与未知值都能收敛', () => {
    expect(toApiError(new Error('boom'))).toMatchObject({ message: 'boom', status: 0 })
    expect(toApiError(null).message).toBe('未知错误')
  })

  it('幂等：已归一化的 ApiError 原样返回，status 不退化', () => {
    const original = createApiError('登录状态已失效', 401)

    const again = toApiError(original)

    expect(again).toBe(original)
    expect(again.status).toBe(401)
  })
})

describe('isApiError', () => {
  it('只认自己造的 ApiError', () => {
    expect(isApiError(createApiError('x', 500))).toBe(true)
    expect(isApiError(new Error('x'))).toBe(false)
    expect(isApiError(null)).toBe(false)
  })
})

describe('unwrap', () => {
  it('success 为 true 时返回 data', () => {
    expect(unwrap({ success: true, message: null, data: { id: 1 } })).toEqual({ id: 1 })
  })

  it('success 为 false 时抛出后端 message', () => {
    expect(() => unwrap({ success: false, message: '会话已过期', data: null })).toThrow('会话已过期')
  })

  it('success 为 true 但 data 为空时也视为错误', () => {
    expect(() => unwrap({ success: true, message: null, data: null })).toThrow('服务端返回了空数据')
  })
})

describe('createApiError', () => {
  it('带上 status 与 code 方便调用方分支处理', () => {
    expect(createApiError('拒绝访问', 403, 'FORBIDDEN')).toMatchObject({
      message: '拒绝访问',
      status: 403,
      code: 'FORBIDDEN',
    })
  })
})

describe('鉴权拦截器', () => {
  const router = {
    currentRoute: { value: { path: '/home', fullPath: '/home?tab=1' } },
    replace: vi.fn().mockResolvedValue(undefined),
  }

  let pinia: Pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    http.interceptors.request.clear()
    http.interceptors.response.clear()
    router.replace.mockClear()
    installAuthInterceptors(router as unknown as Router, pinia)
  })

  afterEach(() => {
    http.interceptors.request.clear()
    http.interceptors.response.clear()
  })

  /**
   * 取出请求拦截器。
   *
   * 这里断言成 `unknown` 再转成测试用的宽松签名：axios 的 handler 参数是
   * `InternalAxiosRequestConfig`，而测试只关心 `headers`。直接双重断言是为了说明
   * 「这是测试里的类型妥协」，而不是把类型系统糊过去。
   */
  function requestFulfilled() {
    const handler = http.interceptors.request.handlers?.[0]?.fulfilled
    if (!handler)
      throw new Error('请求拦截器未安装')
    return handler as unknown as (config: { headers: Record<string, string> }) => { headers: Record<string, string> }
  }

  function responseRejected() {
    const handler = http.interceptors.response.handlers?.[0]?.rejected
    if (!handler)
      throw new Error('响应拦截器未安装')
    return handler as (error: unknown) => Promise<never>
  }

  it('登录后把 token 注入 Authorization', () => {
    useAuthStore().setSession('token-123', user)

    const config = requestFulfilled()({ headers: {} })

    expect(config.headers.Authorization).toBe('Bearer token-123')
  })

  it('未登录时不注入空的 Authorization', () => {
    const config = requestFulfilled()({ headers: {} })

    expect(config.headers.Authorization).toBeUndefined()
  })

  it('受保护接口 401 时清空会话并带着 redirect 跳登录页', async () => {
    useAuthStore().setSession('expired', user)
    const error = { isAxiosError: true, response: { status: 401 }, config: { url: '/api/v1/auth/me' } }

    await expect(responseRejected()(error)).rejects.toMatchObject({ status: 401 })
    expect(useAuthStore().token).toBeNull()
    expect(router.replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/home?tab=1' } })
  })

  it('登录接口自身的 401 不跳转（那是密码错误，不是会话过期）', async () => {
    const error = { isAxiosError: true, response: { status: 401 }, config: { url: '/api/v1/auth/login' } }

    await expect(responseRejected()(error)).rejects.toMatchObject({ status: 401 })
    expect(router.replace).not.toHaveBeenCalled()
  })
})
