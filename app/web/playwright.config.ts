import process from 'node:process'
import { defineConfig, devices } from '@playwright/test'

const PORT = 4173
/**
 * 显式绑定 127.0.0.1。
 *
 * `vite preview` 默认只监听 `localhost`，而在这台机器上 `localhost` 解析到 IPv6 `::1`；
 * Playwright 的 `webServer.url` 用 `127.0.0.1` 去探活时连不上，于是会一直等到 180s 超时
 * 并报「Timed out waiting from config.webServer」——看起来像测试卡住，实际是地址族不匹配。
 */
const HOST = '127.0.0.1'
const BASE_URL = `http://${HOST}:${PORT}`

/**
 * AI 自动化验收入口：`pnpm test:e2e`。
 *
 * - 先 `build` 再 `preview`：测的是真正会发布的产物，而不是开发服务器；
 * - 本地反复跑时复用已启动的服务，CI 上总是重新构建；
 * - 失败用例保留 trace / 截图到 `test-results/`，HTML 报告在 `playwright-report/`，
 *   两者都可以直接交给 AI 分析。
 *
 * 浏览器从哪来（新手最容易卡住的一步）：
 * - 默认用 Playwright 自带的 Chromium，需要先跑 `pnpm test:e2e:install` 下载；
 * - 设 `PW_USE_SYSTEM_CHROME=1` 则复用系统已安装的 Google Chrome（`channel: 'chrome'`），
 *   适合内网环境不方便下载浏览器，或 CI 镜像里已经装了 Chrome 的情况。
 */
const useSystemChrome = process.env.PW_USE_SYSTEM_CHROME === '1'

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['list'],
    ['html', { open: 'never' }],
  ],
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL: BASE_URL,
    // trace 已经包含每一步的 DOM 快照、网络与 console，AI 分析失败原因足够用。
    // 不默认录像：视频需要额外的 ffmpeg 二进制，缺了它会让**所有**用例在 newPage 阶段失败，
    // 而报错信息看起来像「浏览器没装」，非常误导。需要录像时临时加 --video=on。
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...(useSystemChrome ? { channel: 'chrome' } : {}),
      },
    },
  ],
  webServer: {
    command: `pnpm build && pnpm preview --host ${HOST} --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
})
