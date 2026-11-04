import type { Locator, Page } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * 页面对象（Page Object）——AI 自动化测试的唯一入口。
 *
 * 为什么要这一层：
 * - 改文案、改布局时只有这里要跟着改，用例本身保持稳定；
 * - AI 写新用例时不用猜选择器，直接调用语义化方法，减少「选择器猜错 → 假失败」；
 * - 选择器优先级：`data-testid` > 角色 + 无障碍名称 > 文本，**不要**用 nth-child 结构选择器。
 *
 * 约定：对外暴露 getter 与「一步到位」的动作方法，测试里不写等待逻辑。
 */
export class AppPage {
  constructor(protected readonly page: Page) {}

  get brand(): Locator {
    return this.page.locator('header a').first()
  }

  get userMenu(): Locator {
    return this.page.getByTestId('user-menu-trigger')
  }

  get logoutMenuItem(): Locator {
    return this.page.getByText('退出登录', { exact: true })
  }

  get themeToggle(): Locator {
    return this.page.getByTestId('theme-toggle')
  }

  async goto(path = '/') {
    await this.page.goto(path)
  }

  /** 断言当前路径；SPA 跳转不会触发导航事件，因此匹配 URL 而不是等 load。 */
  async expectPath(path: string) {
    const escaped = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    await expect(this.page).toHaveURL(new RegExp(`${escaped}(\\?.*)?$`))
  }
}

export class LoginPage extends AppPage {
  get heading(): Locator {
    return this.page.getByTestId('login-heading')
  }

  /**
   * 用户名输入框。
   *
   * Element Plus 的 `el-input` 会把未识别的属性透传到内部 `<input>` 上，所以 `data-testid`
   * 直接落在 `<input class="el-input__inner">`，**不是**外层包装元素。
   * 这里多写一次 `.locator('input')` 就会匹配到零个元素，表现为「元素一直等不到」的超时，
   * 而错误信息只显示 `waiting for getByTestId(...).locator('input')`，很容易误判成页面没渲染。
   */
  get usernameInput(): Locator {
    return this.page.getByTestId('login-username')
  }

  get passwordInput(): Locator {
    return this.page.getByTestId('login-password')
  }

  get submitButton(): Locator {
    return this.page.getByTestId('login-submit')
  }

  get mockHint(): Locator {
    return this.page.getByTestId('login-mock-hint')
  }

  async open() {
    await this.page.goto('/login')
    await expect(this.heading).toBeVisible()
  }

  /** 只负责提交，落地页由用例断言。 */
  async submit(username = 'admin', password = 'admin123') {
    await this.usernameInput.fill(username)
    await this.passwordInput.fill(password)
    await this.submitButton.click()
  }

  async expectError(message: string | RegExp) {
    await expect(this.page.getByText(message)).toBeVisible()
  }
}

export class HomePage extends AppPage {
  get heading(): Locator {
    return this.page.getByTestId('home-title')
  }

  get username(): Locator {
    return this.page.getByTestId('home-username')
  }

  get clock(): Locator {
    return this.page.getByTestId('home-clock')
  }

  async open() {
    await this.page.goto('/home')
    await expect(this.heading).toBeVisible()
  }

  async logout() {
    await this.userMenu.click()
    await this.logoutMenuItem.click()
    await expect(this.page.getByTestId('login-heading')).toBeVisible()
  }
}
