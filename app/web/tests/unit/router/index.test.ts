import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchCurrentUser } from '@/api/auth'
import { router } from '@/router'
import { safeRedirect } from '@/router/safe-redirect'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  logout: vi.fn(),
  startLogin: vi.fn(),
}))

const mockedFetchCurrentUser = vi.mocked(fetchCurrentUser)

const profile = {
  user: {
    id: '1',
    username: '1',
    displayName: '管理员',
    avatarUrl: null,
    permissions: ['user:read'],
  },
}

/** 未登录时 /api/me 返回 401。 */
function unauthorized() {
  return { isAxiosError: true, response: { status: 401, data: undefined }, config: {} }
}

describe('safeRedirect', () => {
  it('只接受站内绝对路径', () => {
    expect(safeRedirect('/workbench?tab=1')).toBe('/workbench?tab=1')
    expect(safeRedirect('//evil.com')).toBe('/workbench')
    expect(safeRedirect('https://evil.com')).toBe('/workbench')
    expect(safeRedirect(undefined)).toBe('/workbench')
    expect(safeRedirect(['/workbench'])).toBe('/workbench')
    expect(safeRedirect('relative', '/login')).toBe('/login')
  })
})

describe('路由守卫', () => {
  beforeEach(async () => {
    // 每个用例换一个全新的 pinia：store 有 initialized 缓存，复用会让上一个用例的状态串味
    setActivePinia(createPinia())
    mockedFetchCurrentUser.mockReset()
    await router.replace('/login')
  })

  it('未登录访问受保护页面时跳到登录页并带上 redirect', async () => {
    mockedFetchCurrentUser.mockRejectedValue(unauthorized())

    await router.push('/workbench')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/workbench')
  })

  it('已登录用户可以进入工作台', async () => {
    mockedFetchCurrentUser.mockResolvedValue(profile)

    await router.push('/workbench')

    expect(router.currentRoute.value.name).toBe('workbench')
  })

  it('已登录用户访问 /home 也放行', async () => {
    mockedFetchCurrentUser.mockResolvedValue(profile)

    await router.push('/home')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('根路径重定向到工作台', async () => {
    mockedFetchCurrentUser.mockResolvedValue(profile)

    await router.push('/')

    expect(router.currentRoute.value.path).toBe('/workbench')
  })
})
