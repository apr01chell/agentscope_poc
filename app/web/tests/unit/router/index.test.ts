import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCurrentUser } from '@/api/auth'
import { createApiError } from '@/api/http'
import { router } from '@/router'
import { safeRedirect } from '@/router/safe-redirect'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

const mockedFetchCurrentUser = vi.mocked(fetchCurrentUser)

const user = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  avatarUrl: null,
  permissions: ['USER_READ'],
}

describe('safeRedirect', () => {
  it('只接受站内绝对路径', () => {
    expect(safeRedirect('/home?tab=1')).toBe('/home?tab=1')
    expect(safeRedirect('//evil.com')).toBe('/home')
    expect(safeRedirect('https://evil.com')).toBe('/home')
    expect(safeRedirect(undefined)).toBe('/home')
    expect(safeRedirect(['/home'])).toBe('/home')
    expect(safeRedirect('relative', '/login')).toBe('/login')
  })
})

describe('路由守卫', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    mockedFetchCurrentUser.mockReset()
    // 先清会话再回登录页：反过来会在「上一个用例留下已登录状态」时被守卫送回首页，
    // 后续的 push('/home') 就变成一次同址导航，断言随之失真。
    useAuthStore().clear()
    await router.replace('/login')
  })

  it('未登录访问受保护页面时跳到登录页并带上 redirect', async () => {
    await router.push('/home')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/home')
  })

  it('已登录用户可以进入受保护页面', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    mockedFetchCurrentUser.mockResolvedValue({ user })

    await router.push('/home')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('本地会话已失效（401）时清空并回到登录页', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    auth.initialized = false
    mockedFetchCurrentUser.mockRejectedValue(createApiError('登录状态已失效', 401))

    await router.push('/home')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(useAuthStore().token).toBeNull()
  })

  it('后端不可达时放行（提示交给页面），不在登录页与业务页之间弹跳', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)
    auth.initialized = false
    mockedFetchCurrentUser.mockRejectedValue(createApiError('网络异常，请检查网络连接'))

    await router.push('/home')

    expect(router.currentRoute.value.name).toBe('home')
    expect(useAuthStore().status).toBe('unavailable')
  })

  it('已登录用户访问登录页时直接进入目标页', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)

    await router.push('/login?redirect=/home')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('已登录用户带外站 redirect 时回落到首页', async () => {
    const auth = useAuthStore()
    auth.setSession('token-1', user)

    await router.push('/login?redirect=//evil.com')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('未知路径命中 404 页且不需要登录', async () => {
    await router.push('/not-exist')

    expect(router.currentRoute.value.name).toBe('not-found')
    expect(useAuthStore().token).toBeNull()
  })
})
