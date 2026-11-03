import { expect, test } from '@playwright/test'
import { HomePage, LoginPage } from './pages'

/**
 * 主页冒烟测试：确认布局、技术栈清单与主题切换在当前构建产物里可用。
 */
test.describe('主页', () => {
  test.beforeEach(async ({ page }) => {
    const login = new LoginPage(page)
    await login.open()
    await login.submit()
    await new HomePage(page).expectPath('/home')
  })

  test('展示当前账号与技术栈清单', async ({ page }) => {
    const home = new HomePage(page)

    await expect(home.username).toHaveText('admin')
    await expect(page.getByRole('heading', { name: '技术栈清单' })).toBeVisible()
    await expect(page.getByText('UnoCSS presetWind4')).toBeVisible()
    await expect(page.getByText('Playwright')).toBeVisible()
  })

  test('顶栏包含品牌、主题切换与用户菜单', async ({ page }) => {
    const home = new HomePage(page)

    await expect(home.brand).toContainText('前端基础模板')
    await expect(home.themeToggle).toBeVisible()
    await expect(home.userMenu).toBeVisible()
  })

  test('主题切换改变 aria-pressed 并在刷新后保持', async ({ page }) => {
    const home = new HomePage(page)

    const before = await home.themeToggle.getAttribute('aria-pressed')
    await home.themeToggle.click()
    const after = await home.themeToggle.getAttribute('aria-pressed')
    expect(after).not.toBe(before)

    await page.reload()
    await expect(home.themeToggle).toHaveAttribute('aria-pressed', after ?? '')
  })
})
