# 前端基础模板（Vue 3 + Vite）

公司内部新前端的起步模板：登录 + 首页两屏、鉴权链路、请求封装、主题与设计 token、AI 可跑的端到端测试。

**用脚手架创建新项目，不要直接 clone 本仓库**：

```bash
pnpm create @icell/vue3-web my-app
```

## 技术栈

| 能力 | 选型 |
| --- | --- |
| 构建 | Vite 8 + `@vitejs/plugin-vue` |
| 框架 | Vue 3.5（`<script setup lang="ts">`） |
| 状态 | Pinia（setup store） |
| 组合式工具 | VueUse（`useStorage` / `useDark` / `useTitle` / `useNow` …） |
| 路由 | Vue Router 5（`meta.requiresAuth` / `meta.title`） |
| 请求 | Axios（统一实例 + 鉴权注入 + 信封解包 + 错误归一化） |
| 组件库 | Element Plus（主题色映射到设计 token） |
| 样式 | UnoCSS `presetWind4`（Tailwind CSS v4 语义） |
| 规范 | `@antfu/eslint-config` + `vue-tsc` |
| 测试 | Vitest（单测）+ Playwright（E2E，AI 自动化验收入口） |

## 创建新项目（推荐）

不要直接 clone 这个仓库——那会把模板的 git 历史带到新项目里。用脚手架：

```bash
pnpm create @icell/vue3-web my-app
```

它会自动：取最新模板版本 → 浅克隆 → **删除模板 `.git` 并重新 init** → 改写 `package.json` → 生成 `.env.local`。

脚手架是独立仓库 [`@icell/create-vue3-web`](https://git.5ireport.com/frontend/create-vue3-web)，用法与实现说明见该仓库的 README。

## 本仓库自身开发

```bash
pnpm install                 # 安装依赖
cp .env.example .env.local   # 按需修改
pnpm dev                     # http://localhost:5173
```

默认使用**内置认证 Mock**，无需后端即可完整体验：

- 账号 `admin` / 密码 `admin123`
- 关闭 Mock：在 `.env.local` 设置 `VITE_USE_MOCK=false`，请求会走 `/api`（开发期由 Vite 代理到 `VITE_PROXY_TARGET`，默认 `http://localhost:8080`）

## 常用脚本

| 命令 | 说明 |
| --- | --- |
| `pnpm dev` | 开发服务器（端口 5173，`strictPort`） |
| `pnpm build` | 类型检查 + 生产构建 |
| `pnpm preview` | 预览构建产物 |
| `pnpm typecheck` | 仅 `vue-tsc -b` |
| `pnpm lint` / `pnpm lint:fix` | ESLint |
| `pnpm test` / `pnpm test:watch` | Vitest |
| `pnpm test:e2e` | Playwright（自动 build + preview）；首次需 `pnpm test:e2e:install` |
| `pnpm test:e2e:chrome` | 同上，但复用系统 Chrome（不便下载浏览器时用） |
| `pnpm ai:check` | 类型 + lint + 单测 + E2E 全链路，AI 改完后先跑它 |

## 版本约定

模板仓库用 `vX.Y.Z` 打 tag，脚手架只认这个格式（`v1.0.0`、`v1.2.3`），预发布 tag（`v1.0.0-beta.1`）会被忽略。

新项目会在 `package.json` 里记录来源：

```json
{
  "template": {
    "name": "vue3-web-template",
    "version": "v1.0.0",
    "repo": "https://git.5ireport.com/frontend/vue3-web-template"
  }
}
```

想知道模板有没有更新，去 Gogs 看 tag 即可——**模板不提供自动同步**，因为同步会把模板提交混进业务历史，
且冲突需要懂 git 的人处理。模板只做「一次性骨架」，易变的东西（依赖版本）通过 `pnpm` 升级。

## 目录说明

```
src/            # 只放会进产物的代码，不含测试
  api/          # http 实例、鉴权拦截器（interceptors.ts）、auth.ts 接口
  components/   # 通用组件（ThemeToggle）
  layouts/      # DefaultLayout：顶栏 + 内容 + 页脚
  mock/         # 认证 Mock（axios 适配器，和真实网络走同一条代码路径）
  modules/      # 业务模块（新业务放这里，按领域拆分）
  router/       # 路由表、守卫、safe-redirect
  stores/       # Pinia store（auth）
  styles/       # theme.css 设计 token / element.css 主题适配 / index.css 入口
  types/        # ApiResult、领域类型、router meta 增强
  views/        # LoginView / HomeView / NotFoundView
tests/          # 单元测试（Vitest + jsdom）
  setup.ts      # 全局准备
  unit/         # 目录镜像 src/：api、stores、router、views、mock
e2e/            # 端到端测试（Playwright）
  pages/        # 页面对象（AI 自动化测试的唯一入口）
  *.spec.ts     # 用例
```

测试与源码分离而不是放在旁边：`src/` 的语义是「会被打包的代码」，`tests/unit` 与 `e2e/`
对称，「这条断言写哪里」没有歧义。测试统一用 `@/` 别名引用源码。

## 新项目接入真实后端

1. `.env.local` 设 `VITE_USE_MOCK=false`、`VITE_API_BASE_URL`（同源留空）、`VITE_API_TIMEOUT`。
2. 按后端实际字段调整 `src/types/auth.ts` 与 `src/api/auth.ts` 的路径。
3. 后端响应若不是 `{ success, message, data }`，只改 `src/api/http.ts` 的 `unwrap` 与 `toApiError`。
4. 删掉 `src/mock/` 与 `VITE_USE_MOCK` 相关分支后跑 `pnpm ai:check` 收尾。

## 约定

见 [AGENTS.md](./AGENTS.md)：请求与状态边界、样式与可访问性、测试要求。
