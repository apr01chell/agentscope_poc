import { describe, expect, it } from 'vitest'
import { mockUsers, resolveMockRequest } from '@/mock/auth'

const baseHeaders = { 'Content-Type': 'application/json' }

describe('resolveMockRequest', () => {
  it('正确的账号密码返回 token 与不含密码的用户信息', () => {
    const response = resolveMockRequest({
      url: '/api/v1/auth/login',
      method: 'POST',
      body: { username: 'admin', password: 'admin123' },
      headers: baseHeaders,
    })

    expect(response.status).toBe(200)
    const body = response.body as { success: boolean, data: { token: string, user: Record<string, unknown> } }
    expect(body.success).toBe(true)
    expect(body.data.token).toBe('mock-token:admin')
    expect(body.data.user.username).toBe('admin')
    expect(body.data.user).not.toHaveProperty('password')
  })

  it('密码错误返回 401 与后端同款信封', () => {
    const response = resolveMockRequest({
      url: '/api/v1/auth/login',
      method: 'POST',
      body: { username: 'admin', password: 'wrong' },
      headers: baseHeaders,
    })

    expect(response.status).toBe(401)
    expect(response.body).toMatchObject({ success: false, message: '用户名或密码错误' })
  })

  it('/auth/me 用 Bearer 令牌识别会话', () => {
    const ok = resolveMockRequest({
      url: '/api/v1/auth/me',
      method: 'GET',
      body: undefined,
      headers: { Authorization: 'Bearer mock-token:admin' },
    })
    const expired = resolveMockRequest({
      url: '/api/v1/auth/me',
      method: 'GET',
      body: undefined,
      headers: { Authorization: 'Bearer unknown' },
    })

    expect(ok.status).toBe(200)
    expect((ok.body as { data: { user: { username: string } } }).data.user.username).toBe('admin')
    expect(expired.status).toBe(401)
  })

  it('未实现的接口返回 404，便于尽早发现漏掉的 Mock', () => {
    expect(resolveMockRequest({ url: '/api/v1/unknown', method: 'GET', body: undefined, headers: {} }).status)
      .toBe(404)
  })

  it('mock 账号清单可用于文档与测试', () => {
    expect(mockUsers.map(item => item.username)).toContain('admin')
  })
})
