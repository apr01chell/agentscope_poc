import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn().mockRejectedValue({ isAxiosError: true, response: { status: 401 } }),
  logout: vi.fn(),
  startLogin: vi.fn(),
}))

vi.mock('@/api/health', () => ({
  fetchHealth: vi.fn().mockResolvedValue({ status: 'UP', service: 'server', version: '0.1.0' }),
}))

function mountLogin() {
  return mount(LoginView, { global: { plugins: [ElementPlus] } })
}

describe('loginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('不再收集账号密码，只提供统一账号登录入口', () => {
    const wrapper = mountLogin()

    expect(wrapper.find('[data-testid="login-heading"]').text()).toBe('登录')
    expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true)
    expect(wrapper.findAll('input[type="password"]')).toHaveLength(0)
  })

  it('点击登录跳转用户中心', async () => {
    const auth = useAuthStore()
    const spy = vi.spyOn(auth, 'login').mockImplementation(() => undefined)
    const wrapper = mountLogin()

    await wrapper.find('[data-testid="login-submit"]').trigger('click')

    expect(spy).toHaveBeenCalled()
  })
})
