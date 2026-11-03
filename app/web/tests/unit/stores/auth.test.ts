import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCurrentUser, login as loginRequest, logout as logoutRequest } from '@/api/auth'
import { createApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

const mockedFetchCurrentUser = vi.mocked(fetchCurrentUser)
const mockedLogin = vi.mocked(loginRequest)
const mockedLogout = vi.mocked(logoutRequest)

const user = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  avatarUrl: null,
  permissions: ['USER_READ'],
}

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockedFetchCurrentUser.mockReset()
    mockedLogin.mockReset()
    mockedLogout.mockReset().mockResolvedValue(undefined)
  })

  it('没有本地会话时 initialize 直接判定为未登录且不发请求', async () => {
    const auth = useAuthStore()

    await expect(auth.initialize()).resolves.toBe('unauthenticated')
    expect(auth.isAuthenticated).toBe(false)
    expect(mockedFetchCurrentUser).not.toHaveBeenCalled()
  })

  it('initialize 成功后写入最新的用户信息', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    auth.initialized = false
    mockedFetchCurrentUser.mockResolvedValue({ user: { ...user, displayName: '新的名字' } })

    await expect(auth.initialize()).resolves.toBe('authenticated')
    expect(auth.displayName).toBe('新的名字')
    expect(auth.can('USER_READ')).toBe(true)
    expect(auth.can('USER_WRITE')).toBe(false)
  })

  it('initialize 遇到 401 时清空会话', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    auth.initialized = false
    mockedFetchCurrentUser.mockRejectedValue(createApiError('登录状态已失效', 401))

    await expect(auth.initialize()).resolves.toBe('unauthenticated')
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
  })

  it('网络异常标记为 unavailable 并保留本地会话，允许重试', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    auth.initialized = false
    mockedFetchCurrentUser.mockRejectedValueOnce(createApiError('网络异常，请检查网络连接'))
    mockedFetchCurrentUser.mockResolvedValueOnce({ user })

    await expect(auth.initialize()).resolves.toBe('unavailable')
    expect(auth.token).toBe('token-1')

    await expect(auth.retryInitialize()).resolves.toBe('authenticated')
  })

  it('并发调用 initialize 只发一次请求', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    auth.initialized = false
    mockedFetchCurrentUser.mockResolvedValue({ user })

    await Promise.all([auth.initialize(), auth.initialize(), auth.initialize()])

    expect(mockedFetchCurrentUser).toHaveBeenCalledTimes(1)
  })

  it('login 成功后把会话持久化到 localStorage（VueUse useLocalStorage）', async () => {
    mockedLogin.mockResolvedValue({ token: 'token-9', user })
    const auth = useAuthStore()

    await auth.login('admin', 'admin123')

    expect(mockedLogin).toHaveBeenCalledWith({ username: 'admin', password: 'admin123' })
    expect(auth.isAuthenticated).toBe(true)
    expect(JSON.parse(localStorage.getItem('vue3-web-template.auth') ?? '{}').token).toBe('token-9')
  })

  it('login 失败时抛出错误且不改变状态', async () => {
    mockedLogin.mockRejectedValue(createApiError('用户名或密码错误', 401))
    const auth = useAuthStore()

    await expect(auth.login('admin', 'bad')).rejects.toThrow('用户名或密码错误')
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.token).toBeNull()
  })

  it('登出失败也要清空本地会话，并通过返回值告知服务端未确认', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    mockedLogout.mockRejectedValue(createApiError('服务不可用', 503))

    await expect(auth.logout()).resolves.toEqual({ remoteFailed: true })

    expect(auth.token).toBeNull()
    expect(auth.status).toBe('unauthenticated')
  })

  it('登出成功时 remoteFailed 为 false', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)

    await expect(auth.logout()).resolves.toEqual({ remoteFailed: false })
    expect(auth.token).toBeNull()
  })

  it('本地已有持久化会话时初始状态为 unknown（待校验）', () => {
    localStorage.setItem('vue3-web-template.auth', JSON.stringify({ token: 'token-1', user }))

    const auth = useAuthStore()

    expect(auth.status).toBe('unknown')
    expect(auth.token).toBe('token-1')
  })
})
