import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { login as loginRequest } from '@/api/auth'
import { createApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

const mockedLogin = vi.mocked(loginRequest)

const user = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  avatarUrl: null,
  permissions: ['USER_READ'],
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/home', component: { template: '<div>home</div>' } },
    ],
  })
}

async function mountLogin(initialPath = '/login') {
  const router = createTestRouter()
  await router.push(initialPath)
  await router.isReady()

  const wrapper = mount(LoginView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
  })

  return { wrapper, router }
}

type LoginWrapper = Awaited<ReturnType<typeof mountLogin>>['wrapper']

/**
 * 提交表单。
 *
 * 直接触发 `form` 的 submit 事件，而不是点击按钮：jsdom 不会因为点击 `type=submit` 的按钮
 * 就派发表单提交，用按钮点击写的测试会在「什么都没发生」的情况下假绿。
 */
async function submitForm(
  wrapper: LoginWrapper,
  { username = 'admin', password = 'admin123' } = {},
) {
  await wrapper.find('input[autocomplete="username"]').setValue(username)
  await wrapper.find('input[autocomplete="current-password"]').setValue(password)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('loginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockedLogin.mockReset()
    // ElMessage 是普通函数，必须用 spy 才能断言；`restoreMocks: true` 会在每个用例后还原。
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: () => {} }) as never)
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('渲染登录表单与 Mock 账号提示', async () => {
    const { wrapper } = await mountLogin()

    expect(wrapper.find('[data-testid="login-heading"]').text()).toBe('登录')
    expect(wrapper.find('[data-testid="login-mock-hint"]').text()).toContain('admin123')
  })

  it('用户名或密码为空时拦截提交，不发请求', async () => {
    const { wrapper } = await mountLogin()

    await submitForm(wrapper, { username: '', password: '' })

    expect(mockedLogin).not.toHaveBeenCalled()
  })

  it('提交按钮是表单的原生 submit，回车与点击走同一条路径', async () => {
    const { wrapper } = await mountLogin()

    expect(wrapper.find('[data-testid="login-submit"]').attributes('type')).toBe('submit')
  })

  it('登录成功后进入 redirect 指向的站内页面', async () => {
    mockedLogin.mockResolvedValue({ token: 'token-1', user })
    const { wrapper, router } = await mountLogin('/login?redirect=/home')

    await submitForm(wrapper)

    expect(mockedLogin).toHaveBeenCalledWith({ username: 'admin', password: 'admin123' })
    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(router.currentRoute.value.path).toBe('/home')
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('外站 redirect 被忽略，登录后回落到首页', async () => {
    mockedLogin.mockResolvedValue({ token: 'token-1', user })
    const { wrapper, router } = await mountLogin('/login?redirect=//evil.example.com')

    await submitForm(wrapper)

    expect(router.currentRoute.value.path).toBe('/home')
  })

  it('登录失败时提示后端文案并停留在登录页', async () => {
    mockedLogin.mockRejectedValue(createApiError('用户名或密码错误', 401))
    const { wrapper, router } = await mountLogin()

    await submitForm(wrapper, { password: 'bad' })

    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(router.currentRoute.value.path).toBe('/login')
    expect(ElMessage.error).toHaveBeenCalledWith('用户名或密码错误')
  })
})
