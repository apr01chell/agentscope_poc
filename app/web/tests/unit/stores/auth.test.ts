import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCurrentUser, logout as logoutRequest } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  logout: vi.fn(),
  startLogin: vi.fn(),
}))

const profile = {
  user: {
    id: '42',
    username: '42',
    displayName: '管理员',
    avatarUrl: null,
    permissions: ['user:read'],
  },
}

function axiosError(status: number) {
  return { isAxiosError: true, response: { status, data: undefined }, config: {} }
}

describe('auth store（session 模型）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(fetchCurrentUser).mockReset()
    vi.mocked(logoutRequest).mockReset()
  })

  it('拿到用户后进入 authenticated', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(profile)
    const auth = useAuthStore()

    expect(await auth.initialize()).toBe('authenticated')
    expect(auth.displayName).toBe('管理员')
    expect(auth.can('user:read')).toBe(true)
    expect(auth.can('client:write')).toBe(false)
  })

  it('401 视为未登录', async () => {
    vi.mocked(fetchCurrentUser).mockRejectedValue(axiosError(401))
    const auth = useAuthStore()

    expect(await auth.initialize()).toBe('unauthenticated')
    expect(auth.isAuthenticated).toBe(false)
  })

  it('网络异常标记 unavailable，而不是把人踢回登录页', async () => {
    vi.mocked(fetchCurrentUser).mockRejectedValue(new Error('Network Error'))
    const auth = useAuthStore()

    expect(await auth.initialize()).toBe('unavailable')
  })

  it('初始化只问一次服务端', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(profile)
    const auth = useAuthStore()

    await auth.initialize()
    await auth.initialize()

    expect(fetchCurrentUser).toHaveBeenCalledTimes(1)
  })

  it('登出即使服务端失败也清空本地状态', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(profile)
    vi.mocked(logoutRequest).mockRejectedValue(new Error('boom'))
    const auth = useAuthStore()
    await auth.initialize()

    const result = await auth.logout()

    expect(result.remoteFailed).toBe(true)
    expect(auth.isAuthenticated).toBe(false)
  })
})
