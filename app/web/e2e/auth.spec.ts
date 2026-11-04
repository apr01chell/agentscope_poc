import { expect, test } from '@playwright/test'
import { HomePage, LoginPage } from './pages'

/**
 * 认证冒烟测试。
 *
 * 覆盖模板最关键的 4 条路径：未登录被拦截、登录成功进入首页、错误凭据提示、登出后会话失效。
 * AI 改完登录相关代码后先跑这一条：`pnpm test:e2e e2e/auth.spec.ts`。
 */
test.describe('认证流程', () => {
  test('未登录访问首页会被重定向到登录页并带 redirect', async ({ page }) => {
    const login = new LoginPage(page)
    await page.goto('/home')

    await expect(login.heading).toBeVisible()
    await expect(page).toHaveURL(/\/login\?redirect=/)
  })

  test('登录页展示 Mock 账号提示', async ({ page }) => {
    const login = new LoginPage(page)
    await login.open()

    await expect(login.usernameInput).toBeVisible()
    await expect(login.passwordInput).toBeVisible()
    await expect(login.mockHint).toContainText('admin123')
  })

  test('使用 admin / admin123 登录后进入首页', async ({ page }) => {
    const login = new LoginPage(page)
    const home = new HomePage(page)

    await login.open()
    await login.submit()
    await home.expectPath('/home')
    await expect(home.heading).toContainText('你好')
    await expect(home.username).toHaveText('admin')
    await expect(home.clock).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
  })

  test('错误密码提示后端文案并停留在登录页', async ({ page }) => {
    const login = new LoginPage(page)

    await login.open()
    await login.submit('admin', 'wrong-password')

    await login.expectError('用户名或密码错误')
    await login.expectPath('/login')
  })

  test('空表单提交出现必填校验', async ({ page }) => {
    const login = new LoginPage(page)

    await login.open()
    await login.submitButton.click()

    await login.expectError('请输入用户名')
    await login.expectPath('/login')
  })

  test('登录后通过用户菜单退出，并清除本地会话', async ({ page }) => {
    const login = new LoginPage(page)
    const home = new HomePage(page)

    await login.open()
    await login.submit()
    await home.expectPath('/home')
    await home.logout()

    await login.expectPath('/login')
    // 再访问受保护页面仍应被拦截，说明本地会话真的被清掉了。
    await page.goto('/home')
    await login.expectPath('/login')
  })

  test('刷新页面后保持登录状态（localStorage 持久化）', async ({ page }) => {
    const login = new LoginPage(page)
    const home = new HomePage(page)

    await login.open()
    await login.submit()
    await home.expectPath('/home')

    await page.reload()

    await expect(home.heading).toContainText('你好')
    await home.expectPath('/home')
  })

  test('未知路径展示 404 页', async ({ page }) => {
    await page.goto('/no-such-page')

    await expect(page.getByTestId('not-found-heading')).toBeVisible()
    await expect(page.getByTestId('not-found-heading')).toHaveText('页面不存在')
  })
})
